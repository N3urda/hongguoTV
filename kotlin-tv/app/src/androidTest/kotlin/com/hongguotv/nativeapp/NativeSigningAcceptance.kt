// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Instrumentation
import android.os.Bundle
import com.hongguotv.core.AppSigningContext
import com.hongguotv.core.AppSigningInput

/** Explicit synthetic JNI smoke test. It never reads or logs an account, URL or signature value. */
internal object NativeSigningAcceptance {
    fun sharedEngine(): NativeSdkEngine? = runCatching {
        val runtime = NativePlaybackSigner::class.java.getDeclaredField("shared").apply { isAccessible = true }.get(null)
        NativeSigningRuntime::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(runtime) as NativeSdkEngine
    }.getOrNull()

    fun compare(instrumentation: Instrumentation, settle: Boolean = false) {
        check(instrumentation.targetContext.packageName.endsWith(".debug"))
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val results = mutableListOf<String>()
        var successful = true
        var sameDeviceVerified = false
        try {
            instrumentation.runOnMainSync { check(!(field("cloudAccount").get(activity) as AccountController).signedIn) }
            val contexts = field("playbackContexts").get(activity) as com.hongguotv.core.PlaybackContextProvider
            val repositoryInActivity = field("repository").get(activity) as com.hongguotv.core.ContentRepository
            contexts.devices.javaClass.declaredMethods.first { it.name.startsWith("profile") && it.parameterTypes.size == 1 }
                .apply { isAccessible = true }.invoke(contexts.devices, repositoryInActivity.http)
            val existingMethod = contexts.devices.javaClass.declaredMethods.first { it.name.startsWith("existing") && it.parameterTypes.isEmpty() }
                .apply { isAccessible = true }
            val existing = existingMethod.invoke(contexts.devices) ?: error("Persistent device required")
            @Suppress("UNCHECKED_CAST")
            val values = existing.javaClass.getDeclaredField("values").apply { isAccessible = true }.get(existing) as Map<String, String>
            if (settle) {
                contexts.requestSigner.sign(AppSigningInput(
                    "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/?aid=8662",
                    "GET", emptyMap(), byteArrayOf(), AppSigningContext(values)))
                // Keep this isolated process alive for the SDK's own async report, without retrying a model.
                Thread.sleep(20_000)
                sharedEngine()?.let { results += "sdk-report=${it.diagnosticReportCounts()}; unsupported=${it.unsupportedCallbackCodes}" }
            }
            for ((label, backend) in listOf("kotlin" to com.hongguotv.core.KotlinAppRequestSigner(), "native" to contexts.requestSigner)) {
                var signedRequests = 0
                val checkedSigner = object : com.hongguotv.core.AppRequestSigner {
                    override val backend = backend.backend
                    override fun sign(input: AppSigningInput): com.hongguotv.core.AppSigningResult {
                        check(input.context.deviceId == values.getValue("device_id") && input.context.installId == values.getValue("iid"))
                        signedRequests++
                        val result = backend.sign(input)
                        val f16Length = NativeEnvelopeProbe.f16Length(result.headers)
                        if (label == "kotlin") check(f16Length == 25) { "Envelope diagnostic self-check failed" }
                        results += "$label envelope-f16-length=${f16Length ?: "absent"}"
                        return result
                    }
                    override fun observeResponse(url: String, status: Int, headers: Map<String, List<String>>) = backend.observeResponse(url, status, headers)
                }
                try {
                    val provider = com.hongguotv.core.PlaybackContextProvider(contexts.devices, requestSigner = checkedSigner)
                    val repository = com.hongguotv.core.ContentRepository(playbackSession = contexts.devices, playbackContexts = provider)
                    val ladder = repository.streamLadderWithCompatibility("7704857946630409240")
                    results += "$label renditions=${ladder.renditions.size}; signed=$signedRequests"
                } catch (error: Throwable) {
                    successful = false
                    results += "$label failed=${error.javaClass.simpleName}; signed=$signedRequests; detail=${diagnosticDetail(error).orEmpty()}"
                }
                check(signedRequests > 0)
                check(existingMethod.invoke(contexts.devices) == existing)
            }
            sameDeviceVerified = true
            sharedEngine()?.let { results += "sdk-report=${it.diagnosticReportCounts()}; unsupported=${it.unsupportedCallbackCodes}" }
        } catch (error: Throwable) {
            successful = false
            results += "compare failed=${error.javaClass.simpleName}"
        } finally { instrumentation.runOnMainSync { activity.finish() } }
        instrumentation.finish(if (successful) 0 else 1, Bundle().apply { putString("stream", results.joinToString("\n") + "\nsame-persistent-device=$sameDeviceVerified\n") })
    }

    fun run(instrumentation: Instrumentation, captureSynthetic: Boolean = false) {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug")) { "Use the isolated debug package" }
        val engine = NativeSdkEngine(context)
        val result = Bundle()
        var route = "playback"
        try {
            val signer = NativePlaybackSigner(NativeSigningRuntime(engine,
                context.getSharedPreferences("native_signing_probe", 0)))
            val input = AppSigningInput(
                "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/?aid=8662&_rticket=1",
                "GET", mapOf("Cookie" to listOf("sessionid=synthetic-only"),
                    "X-Tt-Token" to listOf("synthetic-only")), byteArrayOf(),
                AppSigningContext(mapOf("device_id" to "123456789", "iid" to "987654321")),
            )
            val signed = signer.sign(input)
            check(signed.headers.size == 3)
            if (captureSynthetic) {
                val capture = org.json.JSONObject().put("synthetic", true).put("url", input.url)
                    .put("method", input.method).put("bodyHex", "")
                    .put("inputHeaders", org.json.JSONObject(input.headers))
                    .put("device", org.json.JSONObject(input.context.device))
                    .put("outputHeaders", org.json.JSONObject(signed.headers))
                context.openFileOutput("native-signing-synthetic.json", android.content.Context.MODE_PRIVATE).use {
                    it.write(capture.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val accountPaths = listOf(
                "sms-send" to "https://security.snssdk.com/passport/mobile/send_code/v1/?aid=8662",
                "sms-login" to "https://security.snssdk.com/passport/mobile/sms_login/?aid=8662",
                "account" to "https://reading.snssdk.com/reading/user/info/v1/?aid=8662",
            )
            accountPaths.forEach { (label, url) ->
                route = label
                check(signer.sign(AppSigningInput(url, "POST", input.headers, byteArrayOf(), input.context))
                    .headers.size == 3)
            }
            result.putString("stream", "PASS native signing; headers=3; synthetic=true; routes=playback,sms-send,sms-login,account\n")
            instrumentation.finish(0, result)
        } catch (_: Throwable) {
            result.putString("stream", "FAIL native signing; route=$route; phase=${engine.phase}; unsupported_callbacks=${engine.unsupportedCallbackCodes}\n")
            instrumentation.finish(1, result)
        }
    }
}
