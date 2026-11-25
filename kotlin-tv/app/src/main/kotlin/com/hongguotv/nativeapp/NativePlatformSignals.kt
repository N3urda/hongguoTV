// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import com.hongguotv.core.AppSigningException
import java.io.File
import java.security.Security
import org.json.JSONObject

/** Host capabilities from d3/j3/n1. Never manufacture an advertising ID or device certificate. */
internal class NativePlatformSignals(
    private val context: Context,
    private val decode: (String, IntArray) -> String,
) {
    private var certificateStatus: String? = null
    private var certificateChecked = false
    private fun d(key: String, vararg bytes: Int) = decode(key, bytes)

    fun accepts(operation: Int) = operation in setOf(
        0x10007, 0x10000001, 0x10000006, 0x1000000a, 0x1000000b,
        0x1000000c, 0x1000000d, 0x10000010, 0x10000011, 0x10000012,
    )

    @Suppress("DEPRECATION")
    fun value(operation: Int): Any? = when (operation) {
        0x10000001 -> Point().also { point ->
            try { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(point) }
            catch (_: Exception) { }
        }.let { "${it.x}*${it.y}" }
        0x1000000d -> try {
            Settings.Secure.getString(context.contentResolver, "default_input_method")
                ?.takeIf { it.isNotEmpty() }?.substringBefore('/') ?: "null_"
        } catch (_: Exception) { "null_" }
        0x10000006 -> cpuInformation()
        0x1000000a -> null // Explicit default branch in the reviewed d3 callback switch.
        0x10007 -> optionalHostCapability()
        0x1000000b, 0x1000000c, 0x10000010, 0x10000011 -> {
            checkCertificateCapability()
            // Advertising-ID collectors are not bundled. Null means not collected, not a fake ID.
            null
        }
        0x10000012 -> { checkCertificateCapability(); certificateStatus }
        else -> throw AppSigningException("未知原生设备回调")
    }

    private fun cpuInformation(): String {
        // z/y collect CPU metadata from the actual kernel files; no model/clock values are invented.
        val values = linkedMapOf<String, String>()
        try {
            File(d("66e5e2",104,36,4,78,89,106,54,7,33,108,41,50,25)).bufferedReader().useLines { lines ->
                lines.take(4096).forEach { line ->
                    val pair = line.split(Regex(d("93d691",114)), limit = 2)
                    if (pair.size == 2 && pair[0].trim() !in values) values[pair[0].trim()] = pair[1].trim()
                }
            }
        } catch (_: Exception) { }
        fun firstLine(path: String): String = try {
            val file = File(path)
            if (!file.exists()) d("793d42",118)
            else file.bufferedReader().use { it.readLine()?.trim() } ?: d("67d25d",119)
        } catch (_: Exception) { d("67d25d",119) }
        val count = try {
            val pattern = Regex(d("196df1",35,43,80,43,9,107,107,37))
            File(d("300fe3",109,33,90,1,21,32,53,7,104,53,39,33,12,1,67,55,36,20,108,121,33,34,86,93))
                .listFiles { entry -> pattern.matches(entry.name) }?.size ?: -1
        } catch (_: Exception) { -1 }
        return JSONObject()
            .put(d("fd88bc",116,105,89,73), count)
            .put(d("27b3e3",51,54), values[d("a73bfd",64,39,79,21,92,96,113,25,112)].orEmpty())
            .put(d("7317c1",46,38), values[d("f44583",95,55,85,69,16,37,119,16)].orEmpty())
            .put(d("698cad",33,47), values[d("111be7",6,54,67,2,79,50,55,3)].orEmpty())
            .put(d("2c0bf0",46,96,91), firstLine(d("14eb50",111,37,15,5,69,35,55,3,61,49,37,37,89,5,19,52,38,16,57,125,35,38,3,89,9,55,39,69,123,49,48,35,16,4,15,54,125,22,36,39,41,56,16,25,53,42,51,13,11,52,50,51,7)))
            .put(d("b70159",126,60,77), firstLine(d("e881a1",59,41,82,86,17,34,99,15,96,98,113,41,4,86,71,53,114,28,100,46,119,42,94,10,93,54,115,73,38,98,100,47,77,87,91,55,41,26,121,116,125,52,77,74,97,43,111,23,86,103,102,63,90)))
            .toString()
    }

    private fun optionalHostCapability(): String? = try {
        val type = Class.forName(d("9e61fc",43,104,72,11,74,103,116,69,105,101,58,104,76,65,23,97,61,71,41,112,61,104,81,68,23,86,30,106,98,117,63,104,87,78,109,117,61,103,104,111,60,98,93,81,105,102,53,82,110,101,45,117,100,65,88,100,46,65,117))
        val method = type.getDeclaredMethod(d("6f8e5b",42,101,89,26,43,102,27,66,126,0,52,97,89)).apply { isAccessible = true }
        if (method.invoke(type.getDeclaredConstructor().newInstance()) == true)
            d("89ef4d",39,62,1,39,24,118,41) else null
    } catch (_: Exception) { null }

    @Synchronized private fun checkCertificateCapability() {
        if (certificateChecked) return
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val providerDevice = manufacturer.equals(d("d4fb9b",93,3,52,33,35,92), true) ||
            brand.equals(d("926bc7",0,5,100,33,121,9), true) ||
            manufacturer.equals(d("a24d85",88,31,105,63,53), true) ||
            brand.equals(d("492d4b",13,20,111,63,57), true) || hasProviderRom()
        certificateStatus = if (providerDevice) {
            // i0.g installs the optional vendor provider before examining Security.getProviders().
            try {
                Class.forName(d("42383f",36,62,68,94,3,120,51,93,113,109,38,37,82,69,24,104,121,24,103,113,54,36,79,94,9,63,4,28,118,109,55,27,69,85,63,101,56,1,103,88,55,63,86,69,8,116,37))
                    .getMethod(d("96265e",33,58,82,86,11,126,54)).apply { isAccessible = true }.invoke(null)
            } catch (_: Exception) { }
            val prefix = d("c66d58",65,59,81,21,24,4,101,14,84,32,125,38,64)
            if (Security.getProviders().orEmpty().any { it.name.startsWith(prefix) }) unsupportedCertificate()
            // w4.e: queried provider is actually absent, not a substituted certificate.
            d("1815ed",46,53,125,81,72,124,36,16,100,96,50)
        } else {
            val target = ComponentName(
                d("8f9dc0",42,107,71,94,72,34,53,68,109,58,61,42,89,31,72,34,41,9,123,59,61,97,88,3,89,53,45,66,122),
                d("2723b9",32,58,76,9,73,43,63,21,102,109,55,123,82,72,73,43,35,88,112,108,55,48,83,84,88,60,39,19,113,45,16,58,85,66,79,29,52,4,117,106,32,48),
            )
            val intent = Intent(d("3da5ca",33,105,31,15,72,115,62,70,53,107,54,40,1,78,72,115,34,11,35,106,54,99,0,82,89,100,38,64,34,43,11,85,29,85,89,100,3,64,34,115,43,101,23))
                .setPackage(target.packageName).setComponent(target)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            // An explicit bind avoids treating Android package-visibility filtering as absence.
            val bound = try { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            catch (_: Exception) { unsupportedCertificate() }
            if (bound) {
                try { context.unbindService(connection) } finally { unsupportedCertificate() }
            }
            // w4.g is the official result when this exact service cannot be bound.
            d("a01a55",126,61,125,23,3,44,102,20,114)
        }
        certificateChecked = true
    }

    private fun hasProviderRom(): Boolean = try {
        val properties = Class.forName("android.os.SystemProperties")
        val value = properties.getMethod("get", String::class.java, String::class.java).invoke(null,
            d("99a783",58,52,92,65,18,45,54,28,126,113,45,41,1,74,8,42,116,29,61,114,33), "") as String
        value.isNotEmpty() && !value.contains(d("692eec",50,53,74,31,85,99,59))
    } catch (_: Exception) { false }

    private fun unsupportedCertificate(): Nothing = throw AppSigningException("此设备提供尚未接入的原生设备证明，请使用更新的安装包")
}
