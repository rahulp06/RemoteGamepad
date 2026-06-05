package com.example.remotegamepad

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class SocketClient {

    private var socket: DatagramSocket? = null

    private var serverAddress: InetAddress? = null

    private var serverPort = 5000

    fun connect(ip: String, port: Int) {

        Thread {

            try {

                serverAddress = InetAddress.getByName(ip)

                serverPort = port

                socket = DatagramSocket()

                Log.d("SOCKET", "UDP Ready")

            } catch (e: Exception) {

                e.printStackTrace()

            }

        }.start()
    }

    fun send(message: String) {

        try {

            if (socket == null || serverAddress == null)
                return

            Thread {

                try {

                    val data = message.toByteArray()

                    val packet = DatagramPacket(
                        data,
                        data.size,
                        serverAddress,
                        serverPort
                    )

                    socket!!.send(packet)

                } catch (e: Exception) {

                    e.printStackTrace()

                }

            }.start()

        } catch (e: Exception) {

            e.printStackTrace()

        }
    }
}