package com.halo.network

import org.json.JSONObject

data class QrPairingData(
    val version: Int,
    val deviceName: String,
    val ip: String,
    val port: Int
) {
    fun toJsonString(): String {
        val json = JSONObject()
        json.put("version", version)
        json.put("deviceName", deviceName)
        json.put("ip", ip)
        json.put("port", port)
        return json.toString()
    }

    companion object {
        fun fromJsonString(jsonString: String): QrPairingData? {
            return try {
                val json = JSONObject(jsonString)
                val version = json.getInt("version")
                val deviceName = json.getString("deviceName")
                val ip = json.getString("ip")
                val port = json.getInt("port")
                
                if (version != 1) return null
                if (port !in 1..65535) return null
                
                QrPairingData(version, deviceName, ip, port)
            } catch (e: Exception) {
                null
            }
        }
    }
}
