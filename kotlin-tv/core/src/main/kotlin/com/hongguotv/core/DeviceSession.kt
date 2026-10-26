// SPDX-License-Identifier: GPL-3.0-only
// Holds the fqnovel device identity obtained from the device_register handshake and
// exposes it as a Cookie string. fqnovel's risk control correlates the signed
// device_id with the install_id/device_id/device_token sent as cookies; without them
// a device is only allowed a single "first request" grace play, then flagged (110001).
// Keeping the trusted, server-issued identity here lets an OkHttp interceptor attach it
// to every fqnovel request so a registered device survives repeated playback.
package com.hongguotv.core

object DeviceSession {
    @Volatile var installId: String? = null
    @Volatile var deviceId: String? = null
    @Volatile var deviceToken: String? = null

    fun set(installId: String, deviceId: String, deviceToken: String?) {
        this.installId = installId
        this.deviceId = deviceId
        this.deviceToken = deviceToken
    }

    fun clear() {
        installId = null
        deviceId = null
        deviceToken = null
    }

    fun isEmpty(): Boolean = installId.isNullOrEmpty() || deviceId.isNullOrEmpty()

    // Cookie sent on fqnovel API/player requests. Order matches the real client.
    fun cookie(): String {
        val parts = mutableListOf<String>()
        installId?.let { parts += "install_id=$it" }
        deviceId?.let { parts += "device_id=$it" }
        deviceToken?.let { parts += "device_token=$it" }
        return parts.joinToString("; ")
    }
}
