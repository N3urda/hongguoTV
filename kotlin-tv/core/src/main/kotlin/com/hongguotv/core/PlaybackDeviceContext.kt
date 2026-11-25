// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.UUID
import org.json.JSONObject

/** A separate slot from the historical aid=1967 player device. Never included in library exports. */
interface PlaybackDeviceStore {
    fun load(): String?
    fun save(value: String)
}

internal object PlaybackDeviceDefaults {
    val values = VendorConstants.device.filterKeys { it !in setOf("device_id", "iid") } + mapOf(
        "version_code" to "74532", "manifest_version_code" to "74532",
        "update_version_code" to "74532", "version_name" to "7.4.5.32", "pv_player" to "74532",
    )

    fun userAgent(device: Map<String, String>) =
        "com.phoenix.read/${device.getValue("version_code")} (Linux; U; Android ${device.getValue("os_version")}; zh_CN; ${device.getValue("device_type")})"
}

/** Local seed is committed before registration; a network failure must not generate another seed. */
internal class PlaybackDeviceContext(
    val values: Map<String, String>,
    val registered: DeviceRegister.Registered? = null,
) {
    fun toJson(): String = JSONObject().put("schema", 1).put("values", JSONObject(values)).apply {
        registered?.let {
            put("registered", JSONObject().put("device_id", it.deviceId).put("install_id", it.installId)
                .put("device_token", it.deviceToken).put("klink_egdi", it.klinkEgdi)
                .put("cookies", JSONObject(it.cookies)))
        }
    }.toString()

    override fun toString() = "PlaybackDeviceContext([redacted])"

    companion object {
        fun fresh(device: Map<String, String>, now: Long) = PlaybackDeviceContext(
            PlaybackDeviceDefaults.values + device.filterKeys { it !in setOf("device_id", "iid") } + mapOf(
                "cdid" to UUID.randomUUID().toString(),
                "openudid" to UUID.randomUUID().toString().replace("-", "").take(16),
                "clientudid" to UUID.randomUUID().toString(), "apk_first_install_time" to now.toString(),
            )
        )

        fun parse(raw: String): PlaybackDeviceContext = try {
            val json = JSONObject(raw)
            require(json.getInt("schema") == 1)
            val fields = json.getJSONObject("values")
            val values = fields.keys().asSequence().associateWith { fields.getString(it) }
            require(listOf("cdid", "openudid", "clientudid", "version_code", "version_name", "device_type", "os_version").all { !values[it].isNullOrBlank() })
            val registered = json.optJSONObject("registered")?.let { r ->
                require(listOf("device_id", "install_id").all { r.getString(it).matches(Regex("[1-9][0-9]{0,29}")) })
                val cookies = r.optJSONObject("cookies") ?: JSONObject()
                DeviceRegister.Registered(r.getString("device_id"), r.getString("install_id"),
                    r.optString("device_token"), cookies = cookies.keys().asSequence().associateWith { cookies.getString(it) },
                    klinkEgdi = r.optString("klink_egdi"))
            }
            PlaybackDeviceContext(values, registered)
        } catch (_: Exception) {
            // Do not silently replace an existing installation when storage is damaged or from a future version.
            throw IOException("播放设备数据无法读取，请检查应用数据")
        }
    }
}
