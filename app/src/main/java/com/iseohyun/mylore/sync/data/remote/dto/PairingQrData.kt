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
    @SerializedName("ssid") val ssid: String? = null,
    @SerializedName("wifi_pw") val wifiPw: String? = null,
    @SerializedName("pw") val pw: String? = null,
    @SerializedName("password") val password: String? = null,
    @SerializedName("wifi_auth") val wifiAuth: String? = null,
    @SerializedName("auth") val auth: String? = null,
    @SerializedName("ssids") val ssids: List<String> = emptyList(),
    @SerializedName("created_at") val createdAt: Long = 0L
) {
    val targetSsid: String
        get() = ssid?.ifBlank { null } ?: ssids.firstOrNull() ?: ""

    val targetWifiPw: String
        get() = wifiPw?.ifBlank { null } ?: pw?.ifBlank { null } ?: password ?: ""

    val targetWifiAuth: String
        get() = wifiAuth?.ifBlank { null } ?: auth?.ifBlank { null } ?: "WPA2"

    companion object {
        fun parse(rawString: String): PairingQrData? {
            return try {
                val trimmed = rawString.trim()
                // myloresync://pair?data=<base64> 딥링크 접두사 처리
                val payloadString = if (trimmed.contains("data=")) {
                    trimmed.substringAfter("data=").substringBefore("&")
                } else {
                    trimmed
                }

                val jsonString = if (payloadString.startsWith("{")) {
                    payloadString
                } else {
                    String(Base64.decode(payloadString, Base64.DEFAULT), Charsets.UTF_8)
                }
                Gson().fromJson(jsonString, PairingQrData::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
