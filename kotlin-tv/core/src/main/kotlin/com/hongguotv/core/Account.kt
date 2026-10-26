// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

/**
 * 导入的账号凭据（移植自 pan8664716/HGTV 1.0.8）。
 *
 * [cookie] 可直接当 `Cookie:` 头用的整段串（`sessionid=…; uid_tt=…`）。
 * 本地保存、不进备份：登录态是敏感凭据，不应随「备份二维码」流到别的设备。
 */
data class AccountCredentials(
    val cookie: String,
    val deviceId: String = "",
    val installId: String = "",
    val cdid: String = "",
    val openudid: String = "",
    /** `sid_guard` 里的过期时间（秒）；解析不出来时为 0。 */
    val expiresAt: Long = 0,
    /** 真机设备档案（`device_profile.json` 的字段 → 值）；缺哪项回落内置档案。 */
    val device: Map<String, String> = emptyMap()
) {
    val configured: Boolean get() = cookie.isNotBlank()

    /** 剩余天数；未知时为 null。 */
    fun remainingDays(now: Long = System.currentTimeMillis()): Double? =
        if (expiresAt <= 0) null else (expiresAt * 1000 - now) / 86_400_000.0
}

/**
 * `cookieStore.xml` / 粘贴 Cookie → [AccountCredentials]。
 *
 * `cookieStore.xml` 是 App 自己写的 `SerializableHttpCookie` 序列化结果：每个
 * `<string name="http://<host>/|cookieName">` 的值是一段 Java 序列化流（十六进制），
 * 里面的字符串按 `TC_STRING(0x74) + u2 长度 + UTF-8` 排列，形如 `[name, value, domain, path]`。
 *
 * 同一份登录态在文件里有十几个 host 的副本（内容一致），取 `snssdk.com`（App 主域）。
 * 只做「取需要的字段」这一件事，不做网络校验。
 */
object AccountCredentialsParser {
    /** 组装 `Cookie:` 头时要带的字段（顺序固定，便于比对）。 */
    val COOKIE_KEYS = listOf("sessionid", "sessionid_ss", "sid_tt", "uid_tt", "uid_tt_ss", "d_ticket",
        "sid_guard", "session_tlb_tag", "store-region", "store-region-src", "install_id", "n_mh", "odin_tt", "ttreq")

    /** 默认取哪个 host 下的 cookie（文件里每个 host 都有一份同样的值）。 */
    const val DEFAULT_HOST = "snssdk.com"

    /** 从 `cookieStore.xml` 里取某个 host 下所有 cookie 的 `name → value`。 */
    fun parse(xml: String, host: String = DEFAULT_HOST): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val pattern = Regex("""<string name="(https?://[^"|]+)/\|([^"]+)">([^<]*)</string>""")
        pattern.findAll(xml).forEach { match ->
            val site = match.groupValues[1].removePrefix("http://").removePrefix("https://").trimEnd('/')
            if (site != host) return@forEach
            val name = match.groupValues[2]
            val value = cookieValue(match.groupValues[3], name) ?: return@forEach
            result[name] = value
        }
        return result
    }

    /**
     * 从一段十六进制序列化流里取 [name] 后面的那个字符串。
     *
     * 手工按字节扫 `0x74 + u2 长度`，不依赖任何 Java 序列化实现：
     * 值里可能带 `%7C`（`sid_guard`）等转义，但长度前缀是字节数，因此不会错位。
     */
    private fun cookieValue(hex: String, name: String): String? {
        val bytes = hexToBytes(hex) ?: return null
        val fields = mutableListOf<String>()
        var index = 0
        while (index < bytes.size - 2) {
            if (bytes[index] == 0x74.toByte()) {
                val length = ((bytes[index + 1].toInt() and 255) shl 8) or (bytes[index + 2].toInt() and 255)
                if (length in 1..2048 && index + 3 + length <= bytes.size) {
                    val chunk = bytes.copyOfRange(index + 3, index + 3 + length)
                    if (chunk.all { it.toInt() and 255 in 0x20..0x7E }) {
                        fields += String(chunk, Charsets.US_ASCII)
                        index += 3 + length
                        continue
                    }
                }
            }
            index++
        }
        val at = fields.indexOf(name)
        return if (at >= 0 && at + 1 < fields.size) fields[at + 1] else null
    }

    private fun hexToBytes(value: String): ByteArray? {
        if (value.isEmpty() || value.length % 2 != 0 || value.any { it !in "0123456789abcdefABCDEF" }) return null
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** 取 `sid_guard` 的过期时间：`sessionid|签发时间戳|max-age|人类可读`，**过期 = 签发 + max-age**。 */
    fun expiryOf(cookie: Map<String, String>): Long {
        val guard = cookie["sid_guard"]?.replace("%7C", "|") ?: return 0
        val parts = guard.split('|')
        if (parts.size < 3) return 0
        val issued = parts[1].trim().toLongOrNull() ?: return 0
        val maxAge = parts[2].trim().toLongOrNull() ?: return 0
        return issued + maxAge
    }

    /** 组装可直接发出去的 `Cookie:` 头。 */
    fun headerOf(cookie: Map<String, String>): String =
        COOKIE_KEYS.mapNotNull { name -> cookie[name]?.let { "$name=$it" } }.joinToString("; ")

    /** 一步到位：`cookieStore.xml` → [AccountCredentials]。 */
    fun parseCredentials(xml: String, device: Map<String, String> = emptyMap()): AccountCredentials {
        val cookie = parse(xml)
        return AccountCredentials(
            cookie = headerOf(cookie),
            deviceId = device["device_id"].orEmpty(),
            installId = device["install_id"].orEmpty(),
            cdid = device["cdid"].orEmpty(),
            openudid = device["openudid"].orEmpty(),
            expiresAt = expiryOf(cookie),
            device = device.filterValues { it.isNotBlank() }
        )
    }

    /**
     * 从 `device_profile.json` 里取设备档案；解析失败就当没有，签名回落内置档案。
     */
    fun parseDeviceProfile(text: String): Map<String, String> {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        return buildMap {
            for (key in DEVICE_KEYS) {
                val value = if (key == "iid") json.optString("install_id") else json.optString(key)
                if (value.isNotBlank()) put(key, value)
            }
        }
    }

    /** 签名与 query 会用到的设备字段（`device_profile.json` 里的键名）。 */
    val DEVICE_KEYS = listOf("device_id", "iid", "cdid", "openudid", "device_type", "device_brand",
        "os_version", "os_api", "resolution", "dpi", "klink_egdi", "rom_version")

    /** `sessionid` 的形状：32 位十六进制。 */
    fun looksLikeSessionId(value: String) = value.matches(Regex("[0-9a-fA-F]{32}"))

    /**
     * 从用户粘贴的**任意一段文本**里取出 `sessionid` 的值。
     *
     * 认这几种输入（都是用户手上真实会出现的东西）：
     * 1. 纯值 —— `cf98cf3c…`；
     * 2. `sessionid=值`；
     * 3. 整段 Cookie —— `sessionid=…; uid_tt=…; …`；
     * 4. `cookieStore.xml` 里的**整行**；
     * 5. 那一行的**值本身** —— 十六进制序列化流。
     *
     * 第 4、5 两种最容易踩：文件里搜 `sessionid` 看到的是一长串十六进制，直接粘进来
     * 会变成非法 sessionid（平台不报错，只把请求降级成匿名设备），因此按字节扫一遍。
     */
    fun sessionIdOf(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        // 4) 整行 <string>：取出引号里那段十六进制，再按下面第 5 条解。
        Regex("""<string name="[^"]*\|sessionid">([^<]*)</string>""").find(text)?.let { match ->
            val inner = sessionIdOf(match.groupValues[1])
            if (inner.isNotEmpty()) return inner
        }
        // 5) 十六进制序列化流：按 TC_STRING 扫出字段，取 `sessionid` 后面那一个。
        if (text.length > 64 && text.length % 2 == 0 && text.all { it in "0123456789abcdefABCDEF" }) {
            cookieValue(text, "sessionid")?.let { return it }
        }
        // 2)/3) k=v 串：从中挑出 sessionid 那一段（没有就返回空，由调用方报错）。
        if (text.contains('=')) {
            return text.split(';').mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null else part.substring(0, at).trim() to part.substring(at + 1).trim()
            }.firstOrNull { it.first == "sessionid" }?.second.orEmpty()
        }
        // 1) 纯值。
        return text
    }

    /** 直接粘整段 Cookie 时的入口：`key=value; key=value` → [AccountCredentials]。 */
    fun parseHeader(text: String): AccountCredentials {
        val map = text.split(';').mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null else part.substring(0, at).trim() to part.substring(at + 1).trim()
        }.toMap()
        return AccountCredentials(headerOf(map), expiresAt = expiryOf(map))
    }
}
