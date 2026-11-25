// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import com.hongguotv.core.AppSigningException
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import ms.bd.c.k3
import org.json.JSONArray

/** Reviewed SDK ABI; configuration and binary are local build inputs, never downloaded at runtime. */
internal class NativeSdkEngine(private val context: Context) : NativeSigningEngine {
    private var managerHandle = -1L
    @Volatile private var signingHandle = -1L
    @Volatile private var dynamicPolicyHandle = -1L
    @Volatile private var signingCallbackRegistered = false
    @Volatile internal var unsupportedCallbackCode = 0
        private set
    internal val unsupportedCallbackCodes = java.util.concurrent.ConcurrentSkipListSet<Int>()
    @Volatile internal var phase = "initialization"
        private set
    private var appId = ""
    private var signatureCalls = 0
    private var sampledSigningStack: Throwable? = null
    private val batteryHistory = ArrayList<Int>()
    private val policy by lazy { NativeRequestPolicy(context, ::call) { key, bytes -> decode(key, *bytes) } }
    private val reportTransport by lazy { NativeSdkTransport { key, bytes -> decode(key, *bytes) } }
    private val platform by lazy { NativePlatformSignals(context) { key, bytes -> decode(key, *bytes) } }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var fetchedDeviceId: String? = null
    private var cachedVersionName: String? = null
    @Volatile private var contextInitializationMicros = 0L
    @Volatile private var configurationInitializationMicros = 0L
    @Volatile private var initializationClockOffset = 0L
    internal fun diagnosticReportCounts() = reportTransport.diagnosticCounts()

    override fun initialize() {
        if (!BuildConfig.NATIVE_SIGNING_SDK_AVAILABLE || Build.VERSION.SDK_INT < 23 ||
            !Process.is64Bit() || "arm64-v8a" !in Build.SUPPORTED_ABIS) {
            throw AppSigningException("此设备或安装包不支持原生签名，请安装 ARM64 原生签名包")
        }
        verifyLibrary()
        val config = context.assets.open("native-signing/sdk-metadata.json")
            .bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) }
        if (config.length() != 17 || config.optString(14) != "0" || listOf(6, 7, 8, 9, 13).any { config.optString(it).isNotEmpty() } ||
            config.optJSONArray(15)?.length() != 0 || config.optJSONArray(16)?.length() != 0) {
            throw AppSigningException("原生签名配置包含不支持的设备数据")
        }
        appId = config.getString(0)
        if (appId != "8662" || config.getString(12) != "5") throw AppSigningException("原生签名配置与当前支持模式不匹配")
        k3.setCallback(::callback)
        phase = "library-load"
        val contextStartedAt = System.nanoTime() / 1000
        System.loadLibrary("metasec_ml")
        phase = "context"
        initializationClockOffset = System.currentTimeMillis() - contextStartedAt
        call(0x1000003, value = context)
        contextInitializationMicros = System.nanoTime() / 1000 - contextStartedAt
        // Same library-derived version as the reviewed SDK's ms.bd.c.s4 initializer.
        val sdkVersion = call(0x1000001, text = "73dd8b", value = byteArrayOf(
            48, 97, 67, 94, 86, 37, 122, 66, 102, 122, 118, 101, 90, 18, 18, 114, 50, 27, 45,
        )) as? String ?: throw AppSigningException("无法读取原生签名组件版本")
        config.put(4, sdkVersion)
        phase = "configuration"
        val configurationStartedAt = System.nanoTime() / 1000
        val configured = call(0x4000001, text = config.toString())
        configurationInitializationMicros = System.nanoTime() / 1000 - configurationStartedAt
        if (configured != true) {
            throw AppSigningException("原生签名初始化被拒绝，请检查安装包")
        }
        phase = "manager"
        managerHandle = (call(0x4000002) as? Number)?.toLong() ?: -1L
        if (managerHandle == -1L) {
            phase = "manager-unavailable"
            throw AppSigningException("原生签名初始化未完成，请重启应用")
        }
        if (!signingCallbackRegistered || signingHandle == -1L) {
            phase = "signing-callback-unavailable"
            throw AppSigningException("原生签名初始化未完成，请重启应用")
        }
    }

    override fun bind(deviceId: String, installId: String) {
        phase = "device-binding"
        if (fetchedDeviceId != null && fetchedDeviceId != deviceId) {
            throw AppSigningException("登记设备已变更，请重启应用以重新绑定原生签名")
        }
        if (fetchedDeviceId == null) {
            // g3/y2 bootstrap the already registered AppLog DID before ordinary ID updates.
            val fetchedManager = (call(0x4000002, text = appId) as? Number)?.toLong()?.takeIf { it != -1L }
                ?: throw AppSigningException("原生签名未能绑定登记设备，请稍后重试")
            call(0x200000e, handle = fetchedManager, text = deviceId, value = 0)
            fetchedDeviceId = deviceId
        }
        call(0x2000002, handle = managerHandle, text = deviceId, value = context)
        call(0x2000003, handle = managerHandle, text = installId, value = context)
    }

    override fun report(event: String) {
        phase = "event-report"
        require(event in setOf("first_time", "did-iid-update", "login"))
        call(0x2000001, handle = managerHandle, text = event, value = "")
    }

    override fun sign(url: String, headers: Map<String, List<String>>): Map<String, String> {
        phase = "dynamic-policy"
        policy.beforeRequest(url, dynamicPolicyHandle)
        phase = "signing"
        // NetworkParams passes the first value of each header, including the final Cookie/Token.
        val flattened = headers.flatMap { (name, values) ->
            values.firstOrNull()?.let { listOf(name, it) }.orEmpty()
        }.toTypedArray()
        // NetworkParams samples its real Java call stack every 31st signing call.
        if (sampledSigningStack == null && signatureCalls++ >= 30) {
            signatureCalls = 0
            sampledSigningStack = Throwable()
        }
        val result = call(0x3000001, handle = signingHandle, text = url, value = flattened) as? Array<*>
            ?: throw AppSigningException("原生签名未返回结果，请稍后重试")
        if (result.size % 2 != 0) throw AppSigningException("原生签名结果格式异常，请稍后重试")
        val selected = linkedMapOf<String, String>()
        result.toList().chunked(2).forEach { pair ->
            val name = (pair[0] as? String)?.lowercase()
            val value = pair[1] as? String
            if (name in REQUIRED_HEADERS) {
                if (value == null || selected.put(name!!, value) != null) {
                    throw AppSigningException("原生签名结果无效，请稍后重试")
                }
            }
        }
        return selected
    }

    override fun observeResponse(url: String, status: Int, headers: Map<String, List<String>>) {
        phase = "response-policy"
        policy.observeResponse(url, headers, managerHandle)
    }

    private fun call(operation: Int, argument: Int = 0, handle: Long = 0L, text: String? = null, value: Any? = null): Any? {
        val result = k3.a(operation, argument, handle, text, value)
        if (unsupportedCallbackCode != 0) throw AppSigningException("原生签名需要尚未支持的设备能力，请使用更新的安装包")
        return result
    }

    @Suppress("DEPRECATION")
    private fun callback(operation: Int, argument: Int, handle: Long, text: String?, value: Any?): Any? = try {
        when (operation) {
            0x2000001 -> { signingHandle = handle; signingCallbackRegistered = true; true }
            // l2 installs a dynamic request/response policy hook. Its gate is checked before signing.
            0x2000002 -> { dynamicPolicyHandle = handle; true }
            // MSC.GetABSwitch/GetDelayTime defaults in the reviewed SDK (without remote overrides).
            0x1000000e -> 2L
            0x1000000f -> 0L
            // The official optional logger returns null when no log proxy is installed.
            0x1000041 -> null
            0x1000001 -> context.applicationInfo.sourceDir ?: context.applicationInfo.publicSourceDir
            0x10001 -> context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            0x10002 -> context.packageName
            0x10008 -> try {
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString(decode("c9f35a", 65,8,42,113,47,68,83,49,24,77,77,21,52,106,47)).orEmpty()
            } catch (_: Exception) { "" }
            // This adapter does not include SDKMonitor: report unavailable/null, never fabricated success.
            // This is an explicit capability boundary, not the official app's full telemetry implementation.
            0x20001, 0x20002 -> null
            0x30001 -> reportTransport.post(requireNotNull(text), value as Array<*>)
            0x30004 -> {
                if (Build.VERSION.SDK_INT < 23) true else {
                    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val network = manager?.activeNetwork
                    network == null || manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                }
            }
            0x30005 -> { observeNetwork(); null }
            0x10003 -> {
                val child = k3.a(0x1000001, 0, 0L, "afc0d5", byteArrayOf(62, 105, 3, 64, 90, 54, 99)) as String
                val directory = File(context.filesDir, child)
                check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
                check(directory.isDirectory || directory.mkdirs())
                directory.absolutePath
            }
            0x1000002 -> context.packageManager.getPackageInfo(
                text?.takeIf { it.isNotEmpty() } ?: context.packageName, PackageManager.GET_SIGNATURES,
            ).signatures?.firstOrNull()?.toByteArray()
            0x1000010 -> context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            0x1000011 -> context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
                .also { cachedVersionName = it }
            0x1000012 -> {
                if (cachedVersionName.isNullOrEmpty()) {
                    cachedVersionName = try {
                        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
                            ?.getString(decode("4226ac",22,3,126,116,123,70,4,58,76,72,26,30,96,111,123))
                    } catch (_: Exception) { null }
                }
                cachedVersionName
            }
            0x1000005 -> context.resources.displayMetrics.densityDpi.toString()
            0x1000009 -> java.util.TimeZone.getDefault().let {
                it.id + decode("1dd463", 108) + (it.rawOffset / 60 / 60 / 1000)
            }
            0x100000a -> context.resources.configuration.locale.let {
                it.language + decode("ecb2af", 75) + it.country
            }
            0x1000017 -> batteryPercent().toString()
            0x1000018 -> {
                val extras = value as? Array<*>
                if (text != null && extras != null && extras.size % 2 == 0) {
                    val intent = android.content.Intent(text).setPackage(context.packageName)
                    extras.toList().chunked(2).forEach { intent.putExtra(it[0] as String, it[1] as String) }
                    context.sendBroadcast(intent)
                }
                null
            }
            0x100001a -> context.resources.configuration.locale.let {
                it.language + decode("985c81", 23) + it.country
            }
            // The host does not request ACCESS_WIFI_STATE: b3.E's unavailable collection result.
            0x100001c -> "[]"
            0x1000039 -> try {
                val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
                if (metadata == null) "" else metadata.get(decode("379a58", 23,5,110,52,62,10,15,32,77,3,17,28,101,59,53,12,31,50,77)).toString()
            } catch (_: Exception) { "" }
            0x100003a -> contextInitializationMicros.toString()
            0x100003b -> configurationInitializationMicros.toString()
            0x100003c -> initializationClockOffset.toString()
            0x100002c -> context.registerReceiver(null, IntentFilter(decode("d5eb0d",116,57,18,4,0,122,99,90,61,60,97,50,24,2,65,114,100,0,61,61,123,121,52,55,59,71,66,38,13,13,86,31,55,56,40,86,67)))
                ?.getIntExtra(decode("da3404",125,102,65,76,27,43), -1) ?: -1
            0x1000022 -> context.getSharedPreferences(requireNotNull(text), Context.MODE_PRIVATE)
                .getString(value as String, null)
            0x1000023 -> {
                val delimiter = k3.a(0x1000001, 0, 0L, "ed955b", byteArrayOf(72, 122)) as String
                val parts = requireNotNull(text).split(Regex(delimiter))
                check(parts.size == 2)
                check(context.getSharedPreferences(parts[0], Context.MODE_PRIVATE).edit()
                    .putString(parts[1], value as String?).commit())
                null
            }
            0x1000025 -> UUID.randomUUID().toString()
            0x100001e -> { recordBatteryState(); null }
            0x1000026 -> sampledSigningStack?.stackTrace?.contentToString().orEmpty()
            0x1000027 -> sampledSigningStack?.stackTrace?.takeIf { it.size >= 4 }?.let { stack ->
                ArrayList(stack.slice(1..3).flatMap { element ->
                    Class.forName(element.className).declaredMethods.filter { it.name == element.methodName }
                })
            }
            else -> if (platform.accepts(operation)) platform.value(operation) else { unsupported(operation); null }
        }
    } catch (_: Throwable) {
        unsupported(operation)
        null
    }

    private fun unsupported(operation: Int) {
        unsupportedCallbackCodes.add(operation)
        if (unsupportedCallbackCode == 0) unsupportedCallbackCode = operation
    }

    private fun decode(key: String, vararg bytes: Int): String =
        k3.a(0x1000001, 0, 0L, key, bytes.map { it.toByte() }.toByteArray()) as String

    private fun recordBatteryState() {
        // b3.C/g4 record the actual battery status and level, retaining at most 20 samples.
        val status = try {
            val action = decode("d5f8e0", 116,57,17,94,85,46,99,90,62,102,97,50,27,88,20,38,100,0,62,103,123,121,55,109,110,19,66,38,14,87,86,31,52,98,125,2,67)
            val battery = context.registerReceiver(null, IntentFilter(action))
            val plugged = battery?.getIntExtra(decode("4acffc", 53,111,5,21,94,113,51), -1) ?: -1
            val level = battery?.getIntExtra(decode("cc72c1", 126,100,82,67,80), -1) ?: -1
            val scale = battery?.getIntExtra(decode("39f053", 49,56,20,72,15), -1) ?: -1
            val percent = if (battery == null) -1 else Math.round((level.toFloat() / scale) * 100.0f * 10.0f) / 10
            plugged * 10000 + percent
        } catch (_: Exception) { -10001 }
        synchronized(batteryHistory) {
            batteryHistory.add(status)
            if (batteryHistory.size > 20) batteryHistory.subList(0, batteryHistory.size - 10).clear()
        }
    }

    private fun batteryPercent(): Int = try {
        val intent = context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) -1 else Math.round((intent.getIntExtra("level", -1).toFloat() / intent.getIntExtra("scale", -1)) * 100.0f * 10.0f) / 10
    } catch (_: Exception) { -1 }

    private fun observeNetwork() {
        if (networkCallback != null) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { k3.a(0x9000001, 1, 0L, null, null) }
            override fun onLost(network: Network) { k3.a(0x9000001, 0, 0L, null, null) }
        }
        manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        networkCallback = callback
    }

    private fun verifyLibrary() {
        val extracted = File(context.applicationInfo.nativeLibraryDir, "libmetasec_ml.so")
        val bytes = if (extracted.isFile) extracted.readBytes() else {
            (listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty())
                .firstNotNullOfOrNull { path ->
                    ZipFile(path).use { archive ->
                        archive.getEntry("lib/arm64-v8a/libmetasec_ml.so")?.let { entry ->
                            archive.getInputStream(entry).use { it.readBytes() }
                        }
                    }
                } ?: throw AppSigningException("安装包缺少原生签名组件")
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != SDK_SHA256) throw AppSigningException("原生签名组件校验失败，请重新安装")
    }

    companion object {
        const val SDK_SHA256 = "c6831ab265a0cb76dacfa3b7041e9ea0f706490106a74e12f39058671cc14fe3"
        private val REQUIRED_HEADERS = setOf("x-khronos", "x-helios", "x-medusa")
    }
}
