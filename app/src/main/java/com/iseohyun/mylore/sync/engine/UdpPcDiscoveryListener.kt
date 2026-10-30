package com.iseohyun.mylore.sync.engine

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class UdpPcSignal(
    @SerializedName("event") val event: String = "",
    @SerializedName("pc_name") val pcName: String = "",
    @SerializedName("port") val port: Int = 18443
)

class UdpPcDiscoveryListener {

    private var socket: DatagramSocket? = null
    @Volatile private var isListening = false

    suspend fun listenForPcOnlineSignal(onPcOnlineSignal: (pcName: String, port: Int) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                if (socket == null || socket?.isClosed == true) {
                    socket = DatagramSocket(18444, InetAddress.getByName("0.0.0.0")).apply {
                        reuseAddress = true
                        soTimeout = 5000 // 5-second socket timeout
                    }
                }
                isListening = true
                val buffer = ByteArray(2048)

                while (isListening && socket?.isClosed == false) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)

                        val rawMessage = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        if (rawMessage.contains("MYLORE_PC_ONLINE")) {
                            val signal = Gson().fromJson(rawMessage, UdpPcSignal::class.java)
                            if (signal.event == "MYLORE_PC_ONLINE") {
                                Log.d("UdpListener", "Received PC Online Broadcast from ${signal.pcName}")
                                onPcOnlineSignal(signal.pcName, signal.port)
                            }
                        }
                    } catch (_: Exception) {
                        // Socket timeout or loop iteration, continue
                    }
                }
            } catch (e: Exception) {
                Log.e("UdpListener", "Error setting up UDP socket listener on port 18444", e)
            } finally {
                stopListening()
            }
        }
    }

    fun stopListening() {
        isListening = false
        try {
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Log.e("UdpListener", "Error closing UDP socket", e)
        }
    }
}
