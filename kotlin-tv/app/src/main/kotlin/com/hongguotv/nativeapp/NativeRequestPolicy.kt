// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.AppSigningException
import java.net.URI
import java.util.regex.Pattern
import org.json.JSONObject

/** Reviewed l2/w/u protocol. Unsupported body-changing or dynamic-code policies fail closed. */
internal class NativeRequestPolicy(
    private val context: Context,
    private val invoke: (Int, Int, Long, String?, Any?) -> Any?,
    private val decode: (String, IntArray) -> String,
) {
    private val preferences = context.getSharedPreferences("native_signing_policy", Context.MODE_PRIVATE)
    private data class Rule(val pattern: String, val mode: String)
    private var rules: List<Rule>? = null
    private fun d(key: String, vararg bytes: Int) = decode(key, bytes)
    private fun flag(number: Int) = invoke(0x200000d, number, 0L, null, null) as? Int
    private val defaults by lazy {
        listOf(
            Rule(d("1e308f",111,100,65,84,19,114,58,69,45,118,37,117,73,66,30), d("2fe1e0",48,125,24,70)),
            Rule(d("5dc544",107,118,17,82,24,51,57,87,38,42,41,105,18,72,7,38,121,72,61,103,45,106,21,126,8,34,36,65,13,105,43,97,25,79,68), d("d31e48",102,40,76,18)),
            Rule(d("f6976b",56,36,75,80,26,101,106,5,124,40,122,59,72,74,5,112,42,4,101,116,72,56,69,68,0,123,42), d("2a9b23",48,122,68,21)),
        )
    }

    fun beforeRequest(url: String, policyHandle: Long) {
        if (preferences.getBoolean("unsupported_dynamic_policy", false)) unavailable()
        if (policyHandle == -1L) return
        val gate = invoke(0x3000003, 0, policyHandle, null, null) ?: return
        val reportHandle = gate as? Long ?: unavailable()
        val enabled = listOf(flag(3) != 0, flag(2) == 1, flag(1) == 1)
        val current = rules ?: run {
            val stored = preferences.getString("request_rules", null)
            val parsed = stored?.let(::parseRules).orEmpty()
            (if (parsed.isNotEmpty()) parsed else defaults.filterIndexed { index, _ ->
                if (index == 0) flag(3)?.let { it != 0 } == true else enabled[index]
            }).also { rules = it }
        }
        val match = current.firstOrNull { rule ->
            val defaultIndex = defaults.indexOfFirst { it.pattern == rule.pattern }
            (defaultIndex == -1 || enabled[defaultIndex]) && Pattern.compile(rule.pattern).matcher(url).find()
        } ?: return
        when (match.mode) {
            "off" -> Unit
            "async" -> invoke(0x2000001, 0, reportHandle, "report-async_${System.currentTimeMillis() / 1000}", context)
            // Sync changes the final POST body. It must be implemented before hashing/signing, never skipped.
            else -> unavailable()
        }
    }

    fun observeResponse(url: String, headers: Map<String, List<String>>, managerHandle: Long) {
        val normalized = headers.entries.associate { it.key.lowercase() to it.value }
        if (normalized["x-bdms-dy"]?.any { it.isNotEmpty() } == true) {
            if (!preferences.edit().putBoolean("unsupported_dynamic_policy", true).commit()) unavailable()
            unavailable()
        }
        normalized["x-bdms-ctrl"]?.firstOrNull()?.takeIf { flag(4) == 1 }?.let { value ->
            require(value.length <= 64 * 1024)
            val padding = when (4 - value.length % 4) {
                1 -> d("a2114b",45)
                2 -> d("4cb4e7",120,60)
                else -> ""
            }
            val encoded = value.replace(Regex(d("7f0f6c",107)), d("7dd9d7",109))
                .replace(Regex(d("f32615",72)), d("957588",103)) + padding
            val raw = String(decodePayload(encoded), Charsets.UTF_8)
            val updated = parseRules(raw)
            if (!preferences.edit().putString("request_rules", raw).commit()) unavailable()
            rules = updated
        }
        if (normalized["x-bdms-cj"]?.firstOrNull()?.trim()?.toIntOrNull() == 1) {
            val path = URI(url).path.orEmpty()
            listOf("cashloan", "transfer", "redpacket").firstOrNull(path::contains)?.let {
                invoke(0x2000001, 0, managerHandle, "caijing_apl_$it", "")
            }
        }
    }

    private fun parseRules(raw: String): List<Rule> {
        require(raw.length <= 64 * 1024)
        val value = JSONObject(raw)
        val version = value.getString(d("662c86",49,49,83,4,14,46,59))
        val entries = value.getString(d("30d97d",48,39,27,72,27))
        if (version != d("6ea09b",118)) return emptyList()
        val split = entries.split(Regex(d("c8b0d1",78,38)))
        require(split.size <= 100)
        return split.mapNotNull { entry ->
            val parts = entry.split(Regex(d("83e6ff",115)))
            if (parts.size < 2) return@mapNotNull null
            val pattern = String(decodePayload(parts[0]), Charsets.UTF_8)
            require(pattern.length <= 4096)
            Pattern.compile(pattern)
            Rule(pattern, parts[1])
        }
    }

    private fun decodePayload(value: String): ByteArray {
        val alphabet = d("c2d261",83,18,52,98,44,0,71,59,28,72,89,28,58,104,38,22,81,33,6,86,71,6,32,126,48,28,97,17,54,102,119,54,16,78,0,44,107,31,56,108,125,32,6,84,26,50,117,5,34,122,107,42,71,23,91,117,52,70,99,53,42,105,92,9)
        val padding = d("a2114b",45).single()
        require(value.length >= 4 && value.length % 4 == 0 && alphabet.length == 64)
        val trailing = value.takeLast(2).count { it == padding }
        val normalized = value.dropLast(trailing) + alphabet.first().toString().repeat(trailing)
        val output = ByteArray(value.length * 3 / 4)
        normalized.chunked(4).forEachIndexed { index, block ->
            val sextets = block.map { alphabet.indexOf(it).also { number -> require(number >= 0) } }
            output[index * 3] = ((sextets[0] shl 2) or (sextets[1] shr 4)).toByte()
            output[index * 3 + 1] = ((sextets[1] shl 4) or (sextets[2] shr 2)).toByte()
            output[index * 3 + 2] = ((sextets[2] shl 6) or sextets[3]).toByte()
        }
        return output.copyOf(output.size - trailing)
    }

    private fun unavailable(): Nothing = throw AppSigningException("原生签名要求尚未支持的动态请求策略，请使用更新的安装包")
}
