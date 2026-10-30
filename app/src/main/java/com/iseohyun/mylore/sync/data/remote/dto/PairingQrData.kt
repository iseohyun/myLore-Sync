package com.iseohyun.mylore.sync.data.remote.dto

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class PairingQrData(
    @SerializedName("v") val version: Int = 1,
    @SerializedName("pc_name") val pcName: String = "MYLORE-PC",
    @SerializedName("ips") val ips: List<String> = emptyList(),
    @SerializedName("port") val port: Int = 18443,
    @SerializedName("token") val token: String,
    @SerializedName("ssids") val ssids: List<String> = emptyList(),
    @SerializedName("created_at") val createdAt: Long = 0L
) {
    companion object {
        fun parse(rawString: String): PairingQrData? {
            return try {
                val trimmed = rawString.trim()
                val jsonString = if (trimmed.startsWith("{")) {
                    trimmed
                } else {
                    String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
                }
                Gson().fromJson(jsonString, PairingQrData::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
