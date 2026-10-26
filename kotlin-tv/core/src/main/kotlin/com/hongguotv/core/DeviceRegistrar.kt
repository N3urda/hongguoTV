// SPDX-License-Identifier: GPL-3.0-only
// Device-registration handshake for fqnovel (the missing piece that makes a device trusted).
// Spec ported from prpr2cy/fqnovel-unidbg DeviceRegisterClientService / DeviceGeneratorService.
// Uses the existing Kotlin Signer (X-Gorgon/X-Argus family) — no unidbg needed.
package com.hongguotv.core

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import java.util.zip.GZIPInputStream

object DeviceRegistrar {
    private const val REGISTER_URL = "https://log5-applog.fqnovel.com/service/2/device_register/"
    private val rnd = Random()

    data class Result(val raw: String, val deviceId: String?, val installId: String?, val deviceToken: String?)

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
    private fun randHex(n: Int): String {
        val c = "0123456789abcdef"
        return (1..n).joinToString("") { c[rnd.nextInt(c.length)].toString() }
    }
    private fun randLongId(): String {
        // 16-digit decimal id, first digit 1-9 (matches fqnovel device_id/install_id shape).
        // Must NOT use java.util.Random.nextLong(a,b) — that two-arg overload doesn't exist on Random.
        val first = (rnd.nextInt(9) + 1).toString()
        val rest = (1..15).joinToString("") { rnd.nextInt(10).toString() }
        return first + rest
    }
    private fun ipv6(): String = (0..7).joinToString(":") { "%04X".format(rnd.nextInt(0x10000)) }

    fun register(http: OkHttpClient): Result {
        val androidId = randHex(16)
        val openudid = md5(androidId) + md5(md5(androidId)).substring(0, 8)
        val deviceId = randLongId()
        val installId = randLongId()
        val header = JSONObject().apply {
            put("display_name", "番茄小说"); put("aid", 1967); put("channel", "googleplay")
            put("package", "com.dragon.read.oversea.gp"); put("app_version", "6.8.1.32")
            put("version_code", 68132); put("update_version_code", 68132); put("manifest_version_code", 68132)
            put("app_version_minor", "6.8.1.32"); put("sdk_version", "3.7.0-rc.25-fanqie-xiaoshuo-opt")
            put("sdk_target_version", 29); put("git_hash", "5b6a0d3"); put("sdk_flavor", "china")
            put("guest_mode", 0); put("is_system_app", 0)
            put("os", "Android"); put("os_version", "16"); put("os_api", 36)
            put("device_model", "25053RT47C"); put("device_brand", "Redmi"); put("device_manufacturer", "Redmi")
            put("cpu_abi", "arm64-v8a"); put("release_build", "16_20171120")
            put("density_dpi", 520); put("display_density", "xxhdpi"); put("resolution", "1280x2772")
            put("language", "zh"); put("timezone", 8); put("access", "wifi")
            put("rom", "V433IR release-keys"); put("rom_version", "V433IR release-keys")
            put("cdid", UUID.randomUUID().toString()); put("sig_hash", randHex(32))
            put("openudid", openudid); put("clientudid", UUID.randomUUID().toString())
            put("ipv6_list", JSONArray().apply { put(JSONObject().apply { put("type", "client_anpi"); put("value", ipv6()) }) })
            put("region", "CN"); put("tz_name", "Asia/Shanghai"); put("tz_offset", 28800)
            put("sim_serial_number", JSONArray()); put("oaid_may_support", false)
            put("req_id", UUID.randomUUID().toString()); put("device_platform", "android")
            put("custom", JSONObject().apply { put("host_bit", 64); put("account_region", "cn"); put("dragon_device_type", "phone") })
        }
        val payload = JSONObject().apply {
            put("magic_tag", "ss_app_log"); put("header", header); put("_gen_time", System.currentTimeMillis())
        }
        val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
        val rticket = System.currentTimeMillis()
        val queryValues = linkedMapOf(
            "aid" to "1967", "version_code" to "68132", "channel" to "googleplay",
            "package" to "com.dragon.read.oversea.gp", "_rticket" to rticket.toString(),
            "use_store_region_cookie" to "1", "okhttp_version" to "4.2.137.76-fanqie"
        )
        val signed = Signer().sign(REGISTER_URL, queryValues, bodyBytes)
        val ua = "com.dragon.read.oversea.gp/68132 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3;tt-ok/3.12.13.4-tiktok)"
        val cookie = "store-region=cn-zj; store-region-src=did; install_id=$installId"
        val reqBuilder = Request.Builder().url(signed.url)
            .post(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        signed.headers.forEach { (k, v) -> reqBuilder.header(k, v) }
        reqBuilder.header("Accept", "application/json")
        reqBuilder.header("Accept-Encoding", "gzip")
        reqBuilder.header("log-encode-type", "gzip")
        reqBuilder.header("x-vc-bdturing-sdk-version", "3.7.2.cn")
        reqBuilder.header("User-Agent", ua)
        reqBuilder.header("Cookie", cookie)
        val raw = try {
            respUse(http, reqBuilder.build())
        } catch (e: Exception) { "EXCEPTION ${e.javaClass.simpleName}: ${e.message}" }
        val (dId, iId, token) = parseIds(raw)
        return Result(raw, dId, iId, token)
    }

    private fun respUse(http: OkHttpClient, req: Request): String {
        val resp = http.newCall(req).execute()
        try {
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            return if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).readText()
            } else String(bytes, Charsets.UTF_8)
        } finally {
            resp.close()
        }
    }

    private fun parseIds(raw: String): Triple<String?, String?, String?> {
        try {
            val o = JSONObject(raw)
            val d = o.optString("device_id").ifEmpty { o.optJSONObject("data")?.optString("device_id").orEmpty() }
            val i = o.optString("install_id").ifEmpty { o.optJSONObject("data")?.optString("install_id").orEmpty() }
            val t = o.optString("device_token").ifEmpty { o.optJSONObject("data")?.optString("device_token").orEmpty() }
            return Triple(d.ifEmpty { null }, i.ifEmpty { null }, t.ifEmpty { null })
        } catch (e: Exception) { return Triple(null, null, null) }
    }
}
