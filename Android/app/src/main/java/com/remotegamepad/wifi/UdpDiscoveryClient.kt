package com.remotegamepad.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * UDP broadcast client for discovering RemoteGamepad servers on the local LAN.
 *
 * Protocol:
 *   Sends:  "REMOTE_GAMEPAD_DISCOVER" to broadcast:26761
 *   Expects: "REMOTE_GAMEPAD_SERVER|{serverName}|{inputPort}"
 *
 * The broadcast address is determined from the active Wi-Fi network interface,
 * so it works on any subnet without hardcoded IPs.
 */
class UdpDiscoveryClient(private val context: Context) {

    companion object {
        private const val TAG = "UdpDiscoveryClient"
        const val DISCOVER_REQUEST = "REMOTE_GAMEPAD_DISCOVER"
        const val DISCOVER_RESPONSE_PREFIX = "REMOTE_GAMEPAD_SERVER"
        const val DISCOVERY_PORT = 26761
        const val RECEIVE_TIMEOUT_MS = 3000
    }

    data class ServerInfo(
        val name: String,
        val ip: String,
        val inputPort: Int
    )

    /**
     * Broadcast a discovery request on the LAN and collect responses.
     * Runs on the calling thread (must not be main thread).
     *
     * Sends to ALL known broadcast addresses to handle hotspot scenarios
     * where ConnectivityManager.activeNetwork may report the wrong interface.
     *
     * @param timeoutMs How long to listen for responses after sending the broadcast.
     * @return List of unique servers that responded.
     */
    fun discover(timeoutMs: Int = RECEIVE_TIMEOUT_MS): List<ServerInfo> {
        val broadcastAddrs = getAllBroadcastAddresses()

        if (broadcastAddrs.isEmpty()) {
            Log.w(TAG, "Could not determine any broadcast addresses")
            return emptyList()
        }

        Log.d(TAG, "Broadcasting discovery to ${broadcastAddrs.size} address(es): ${broadcastAddrs.joinToString { it.hostAddress ?: "?" }}")

        val servers = mutableListOf<ServerInfo>()
        var socket: DatagramSocket? = null

        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs
            }

            val requestBytes = DISCOVER_REQUEST.toByteArray(Charsets.UTF_8)

            // Send discovery broadcast to ALL interfaces
            for (broadcastAddr in broadcastAddrs) {
                try {
                    val requestPacket = DatagramPacket(
                        requestBytes,
                        requestBytes.size,
                        broadcastAddr,
                        DISCOVERY_PORT
                    )
                    socket.send(requestPacket)
                    Log.d(TAG, "Sent DISCOVER to ${broadcastAddr.hostAddress}:$DISCOVERY_PORT")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send to ${broadcastAddr.hostAddress}: ${e.message}")
                }
            }

            // Collect responses until timeout
            val buffer = ByteArray(1024)
            val deadline = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < deadline) {
                try {
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(responsePacket)

                    val message = String(responsePacket.data, 0, responsePacket.length, Charsets.UTF_8)
                    val senderIp = responsePacket.address.hostAddress ?: continue

                    Log.d(TAG, "Discovery response from $senderIp: $message")

                    parseResponse(message, senderIp)?.let { server ->
                        if (servers.none { it.ip == server.ip }) {
                            servers.add(server)
                            Log.d(TAG, "Found server: ${server.name} at ${server.ip}:${server.inputPort}")
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery error", e)
        } finally {
            socket?.close()
        }

        Log.d(TAG, "Discovery complete: ${servers.size} server(s) found")
        return servers
    }

    private fun parseResponse(message: String, senderIp: String): ServerInfo? {
        if (!message.startsWith(DISCOVER_RESPONSE_PREFIX)) return null

        val parts = message.split("|")
        if (parts.size != 3) return null

        val serverName = parts[1].ifEmpty { return null }
        val inputPort = parts[2].toIntOrNull() ?: return null

        return ServerInfo(
            name = serverName,
            ip = senderIp,
            inputPort = inputPort
        )
    }

    /**
     * Collect broadcast addresses from ALL active network interfaces.
     * This handles the hotspot scenario where ConnectivityManager.activeNetwork
     * may report the Wi-Fi client (internet) instead of the hotspot AP interface.
     * By sending to all broadcasts, we ensure the discovery packet reaches
     * the server regardless of which interface it's on.
     */
    private fun getAllBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        val seen = mutableSetOf<String>()

        // Source 1: ConnectivityManager for all networks (not just active)
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // Check active network
            cm.activeNetwork?.let { network ->
                val capabilities = cm.getNetworkCapabilities(network) ?: return@let
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    val linkProps = cm.getLinkProperties(network) ?: return@let
                    for (linkAddr in linkProps.linkAddresses) {
                        val addr = linkAddr.address
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val bcast = computeBroadcast(addr, linkAddr.prefixLength)
                            val key = bcast.hostAddress ?: continue
                            if (seen.add(key)) addresses.add(bcast)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ConnectivityManager lookup failed", e)
        }

        // Source 2: Enumerate ALL network interfaces (catches hotspot AP, VPN, etc.)
        try {
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (iface.isLoopback || !iface.isUp) continue
                for (interfaceAddr in Collections.list(iface.inetAddresses)) {
                    if (interfaceAddr is java.net.Inet4Address && !interfaceAddr.isLoopbackAddress) {
                        val rawAddr = interfaceAddr.address.clone()
                        // Assume /24 subnet for interface enumeration fallback
                        rawAddr[3] = (-1).toByte()
                        val bcast = InetAddress.getByAddress(rawAddr)
                        val key = bcast.hostAddress ?: continue
                        if (seen.add(key)) addresses.add(bcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Interface enumeration failed", e)
        }

        // Always include 255.255.255.255 as a last-resort catch-all
        if (seen.add("255.255.255.255")) {
            try { addresses.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        }

        Log.d(TAG, "Found ${addresses.size} broadcast address(es): ${addresses.map { it.hostAddress }}")
        return addresses
    }

    /**
     * Compute the broadcast address from an IPv4 address and prefix length.
     */
    private fun computeBroadcast(address: java.net.Inet4Address, prefixLength: Int): InetAddress {
        val addr = address.address
        val mask = Int.MAX_VALUE shl (32 - prefixLength)
        val maskBytes = byteArrayOf(
            ((mask shr 24) and 0xFF).toByte(),
            ((mask shr 16) and 0xFF).toByte(),
            ((mask shr 8) and 0xFF).toByte(),
            (mask and 0xFF).toByte()
        )
        val broadcast = ByteArray(4)
        for (i in 0..3) {
            broadcast[i] = (addr[i].toInt() or (maskBytes[i].toInt().inv() and 0xFF)).toByte()
        }
        return InetAddress.getByAddress(broadcast)
    }
}
