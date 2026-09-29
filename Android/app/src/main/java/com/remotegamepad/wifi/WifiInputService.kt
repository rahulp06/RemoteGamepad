package com.remotegamepad.wifi

import android.util.Log
import com.remotegamepad.bluetooth.GamepadState
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends controller state as UDP datagrams at ~60 Hz.
 *
 * Each datagram contains a single JSON payload with the same schema as the
 * Bluetooth INPUT message, but without the "INPUT|" prefix — a datagram
 * doesn't need message-type tagging.
 *
 * No retransmission, ordering, or acknowledgement. A lost packet is superseded
 * by the next one within ~16 ms.
 */
class WifiInputService {

    companion object {
        private const val TAG = "WifiInputService"
        const val SEND_RATE_HZ = 60
        private val SEND_INTERVAL_MS = 1000L / SEND_RATE_HZ
    }

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 26760
    private var sendExecutor: ScheduledExecutorService? = null
    private val _connected = AtomicBoolean(false)

    @Volatile
    private var latestState: GamepadState = GamepadState()

    @Volatile
    private var sendCount: Long = 0

    val isConnected: Boolean get() = _connected.get()

    /**
     * Connect to a server and start sending controller state at 60 Hz.
     *
     * @param ip Server IPv4 address (discovered via UdpDiscoveryClient).
     * @param port UDP input port (default 26760).
     */
    fun connect(ip: String, port: Int = 26760) {
        if (_connected.get()) {
            Log.w(TAG, "Already connected — disconnecting first")
            disconnect()
        }

        // Clean up any stale socket from a previous session
        try { socket?.close() } catch (_: Exception) {}
        socket = null

        try {
            serverAddress = InetAddress.getByName(ip)
            serverPort = port
            socket = DatagramSocket()
            _connected.set(true)

            // Start 60 Hz send loop
            sendExecutor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "WifiInput-Send").apply { isDaemon = true }
            }
            sendExecutor?.scheduleAtFixedRate(
                { sendLatestState() },
                0L,
                SEND_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )

            Log.d(TAG, "Connected to $ip:$port, sending at ${SEND_RATE_HZ}Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            disconnect()
        }
    }

    /**
     * Stop sending and release resources.
     */
    fun disconnect() {
        _connected.set(false)
        sendExecutor?.shutdownNow()
        sendExecutor = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        serverAddress = null
        latestState = GamepadState()
        sendCount = 0
        Log.d(TAG, "Disconnected")
    }

    /**
     * Update the latest controller state. Called from GamepadView listener.
     * The send loop picks this up at the next tick (~16 ms).
     */
    fun updateState(state: GamepadState) {
        latestState = state
    }

    private fun sendLatestState() {
        if (!_connected.get()) return

        val sock = socket ?: return
        val addr = serverAddress ?: return
        val state = latestState

        try {
            val json = stateToJson(state)
            val bytes = json.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(bytes, bytes.size, addr, serverPort)
            sock.send(packet)
            sendCount++

            // Throttled logging
            if (sendCount % 300L == 0L) {
                Log.d(TAG, "Sent $sendCount packets")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
            disconnect()
        }
    }

    /**
     * Serialize GamepadState to JSON (same schema as Bluetooth INPUT payload,
     * but without the "INPUT|" prefix).
     */
    private fun stateToJson(state: GamepadState): String {
        return JSONObject().apply {
            put("lx", state.lx)
            put("ly", state.ly)
            put("rx", state.rx)
            put("ry", state.ry)
            put("lt", state.lt)
            put("rt", state.rt)
            put("a", state.a)
            put("b", state.b)
            put("x", state.x)
            put("y", state.y)
            put("lb", state.lb)
            put("rb", state.rb)
            put("dUp", state.dUp)
            put("dDown", state.dDown)
            put("dLeft", state.dLeft)
            put("dRight", state.dRight)
            put("start", state.start)
            put("select", state.select)
            put("l3", state.l3)
            put("r3", state.r3)
        }.toString()
    }
}
