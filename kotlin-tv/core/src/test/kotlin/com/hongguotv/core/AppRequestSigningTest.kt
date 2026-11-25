package com.hongguotv.core

import java.io.IOException
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AppRequestSigningTest {
    private val device = PlaybackDeviceDefaults.values + mapOf("device_id" to "333", "iid" to "444", "cdid" to "test-install")
    private val model = """{"code":0,"data":{"123":{"video_model":{"video_list":[{"main_url":"https://cdn.example/video.mp4","video_meta":{"codec_type":"h264","definition":"720p"},"encrypt_info":{"encrypt":false}}]}}}}"""
    private fun account() = AccountCredentials("sessionid=private-cookie", "333", "444", device, "app", "8662", "private-token",
        listOf(AccountCookies.encode(Cookie.Builder().name("sessionid").value("private-cookie").domain("snssdk.com").path("/").secure().build())), "login-generation")
    private fun response(r: Request, text: String = model) = Response.Builder().request(r).protocol(Protocol.HTTP_1_1)
        .code(200).message("fixture").body(text.toResponseBody()).build()
    private fun inputHeader(input: AppSigningInput, name: String) = input.headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.singleOrNull()
    private fun signer(action: (AppSigningInput) -> AppSigningResult) = object : AppRequestSigner {
        override val backend = SigningBackend.NATIVE_SDK
        override fun sign(input: AppSigningInput) = action(input)
    }
    private fun signed(input: AppSigningInput) = AppSigningResult(input.url, mapOf("x-khronos" to "1800000000", "x-helios" to "fixture", "x-medusa" to "fixture"))

    @Test fun modelSignerSeesAccountHeadersAndExactCompressedBodyBeforeTransport() {
        var captured: AppSigningInput? = null
        val provider = PlaybackContextProvider(PlaybackDeviceSession { fail("account must not register"); null }, requestSigner = signer {
            assertEquals("private-token", inputHeader(it, "X-Tt-Token"))
            assertEquals("sessionid=private-cookie", inputHeader(it, "Cookie"))
            assertEquals("login-generation", it.context.accountGeneration)
            assertEquals("333", it.context.deviceId)
            assertEquals("gzip", inputHeader(it, "Content-Encoding"))
            assertEquals(digest("MD5", it.body).hex().uppercase(), inputHeader(it, "x-ss-stub"))
            assertNotNull(inputHeader(it, "Content-Type"))
            assertNull(inputHeader(it, "x-medusa"))
            captured = it; signed(it)
        })
        provider.configure(true, account())
        ContentRepository(OkHttpClient.Builder().addInterceptor {
            val r = it.request()
            assertEquals(captured!!.url, r.url.toString())
            assertArrayEquals(captured!!.body, Buffer().apply { r.body!!.writeTo(this) }.readByteArray())
            assertEquals("fixture", r.header("x-medusa"))
            response(r)
        }.build(), playbackContexts = provider).streamLadderWithCompatibility("123")
        assertTrue(provider.diagnosticSummary().contains("mode=account signer=native auth=token+cookie media=model-ready"))
        assertFalse(provider.diagnosticSummary().contains("private"))
    }

    @Test fun tabFallbackUsesSameIdentityAndFreshTokenButDoesNotSignVodOrCdn() {
        val inputs = mutableListOf<AppSigningInput>()
        val responses = mutableListOf<String>()
        val provider = PlaybackContextProvider(PlaybackDeviceSession { null }, requestSigner = object : AppRequestSigner {
            override val backend = SigningBackend.NATIVE_SDK
            override fun sign(input: AppSigningInput): AppSigningResult { inputs += input; return signed(input) }
            override fun observeResponse(url: String, status: Int, headers: Map<String, List<String>>) {
                assertEquals(200, status)
                responses += url
            }
        })
        provider.configure(true, account())
        var calls = 0
        val http = OkHttpClient.Builder().addInterceptor {
            if (++calls == 1) response(it.request(), "").newBuilder().header("X-Tt-Token", "refreshed").build()
            else response(it.request(), """{"code":0,"data":{"video_model_data":{"123":{"video_model":{"video_list":[{"main_url":"https://cdn.example/v.mp4","codec_type":"h264"}]}}}}}""")
        }.build()
        ContentRepository(http, playbackContexts = provider).streamLadderWithCompatibility("123")
        assertEquals(2, inputs.size)
        assertEquals(listOf("POST", "GET"), inputs.map { it.method })
        assertEquals("refreshed", inputHeader(inputs.last(), "X-Tt-Token"))
        assertEquals("login-generation", inputs.last().context.accountGeneration)
        assertTrue(inputs.last().body.isEmpty())
        assertNull(inputHeader(inputs.last(), "x-ss-stub"))
        val context = provider.snapshot(http, false)
        context.transport(OkHttpClient.Builder().addInterceptor {
            assertNull(it.request().header("X-Tt-Token")); assertNull(it.request().header("x-medusa"))
            response(it.request())
        }.build()).newCall(Request.Builder().url("https://cdn.example/v.mp4").build()).execute().close()
        assertEquals(2, inputs.size)
        assertEquals(inputs.map { it.url }, responses)
    }

    @Test fun rejectedSdkResponseClosesBodyAndPreventsAccountRefreshOrModelDelivery() {
        var updated = false
        var closed = false
        val signing = object : AppRequestSigner {
            override val backend = SigningBackend.NATIVE_SDK
            override fun sign(input: AppSigningInput) = signed(input)
            override fun observeResponse(url: String, status: Int, headers: Map<String, List<String>>) {
                assertTrue(headers.keys.any { it.equals("x-bdms-dy", true) })
                throw IllegalStateException("https://private.example token=private")
            }
        }
        val provider = PlaybackContextProvider(PlaybackDeviceSession { null },
            onAccountUpdated = { _, _ -> updated = true }, requestSigner = signing)
        provider.configure(true, account())
        val body = object : okhttp3.ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = 0L
            override fun source() = Buffer()
            override fun close() { closed = true }
        }
        val repository = ContentRepository(OkHttpClient.Builder().addInterceptor {
            response(it.request()).newBuilder().body(body).header("x-bdms-dy", "synthetic-policy")
                .header("X-Tt-Token", "new-token").build()
        }.build(), playbackContexts = provider)
        val error = assertThrows(AppSigningException::class.java) { repository.streamLadderWithCompatibility("123") }
        assertTrue(closed)
        assertFalse(updated)
        assertFalse(error.toString().contains("private"))
        assertNull(error.cause)
        assertEquals("账号已登录，播放尚未验证", provider.accountPlaybackStatus())
    }

    @Test fun failedNativeSigningNeverFallsBackOrReachesTransportAndRedactsProviderErrors() {
        val provider = PlaybackContextProvider(PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }, requestSigner = signer {
            throw IllegalStateException("https://secret.example Cookie: private-token")
        })
        val error = assertThrows(AppSigningException::class.java) {
            ContentRepository(OkHttpClient.Builder().addInterceptor { fail("must not send"); response(it.request()) }.build(),
                playbackContexts = provider).streamLadderWithCompatibility("123")
        }
        assertFalse(error.toString().contains("secret")); assertFalse(error.toString().contains("private"))
        assertNull(error.cause)
        assertTrue(error.suppressed.filterIsInstance<PlaybackDiagnosticException>().single().detail.contains("signer=native"))
    }

    @Test fun signerCannotChangeCredentialDestinationOrReturnExtraHeaders() {
        val request = Request.Builder().url("https://reading.snssdk.com/novel/player/video_model/v1/?_rticket=1800000000000").build()
        for (bad in listOf(
            signer { AppSigningResult("https://collector.example/?_rticket=1800000000000", signed(it).headers) },
            signer { AppSigningResult(it.url, signed(it).headers + ("Cookie" to "injected")) },
            signer { AppSigningResult(it.url, mapOf("x-medusa" to "incomplete")) },
        )) assertThrows(AppSigningException::class.java) { signAppRequest(request, AppSigningContext(device), bad) }
    }

    @Test fun cancellationDuringSigningPreventsWireRequest() {
        val scope = RequestScope()
        val provider = PlaybackContextProvider(PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }, requestSigner = signer {
            scope.cancel(); signed(it)
        })
        assertThrows(IOException::class.java) {
            scope.run {
                ContentRepository(OkHttpClient.Builder().addInterceptor { fail("cancelled signature sent"); response(it.request()) }.build(),
                    playbackContexts = provider).streamLadderWithCompatibility("123")
            }
        }
    }

    @Test fun verifiedPlaybackRequiresModelAndMatchingGenerationFirstFrame() {
        val provider = PlaybackContextProvider(PlaybackDeviceSession { null }, requestSigner = signer(::signed))
        provider.configure(true, account())
        val repository = ContentRepository(OkHttpClient.Builder().addInterceptor { response(it.request()) }.build(), playbackContexts = provider)
        provider.recordFirstFrame("123")
        assertEquals("账号已登录，播放尚未验证", provider.accountPlaybackStatus())
        repository.streamLadderWithCompatibility("123")
        provider.recordFirstFrame("456")
        assertEquals("已取得播放信息，等待实际播放验证", provider.accountPlaybackStatus())
        provider.recordFirstFrame("123")
        assertEquals("本次账号会话已成功播放", provider.accountPlaybackStatus())
        provider.configure(true, account().copy(signingSessionId = "another-login"))
        provider.recordFirstFrame("123")
        assertEquals("账号已登录，播放尚未验证", provider.accountPlaybackStatus())
    }

    @Test fun appLoginAndProfileUseSameSignerAndPersistGenerationAcrossRefresh() {
        val inputs = mutableListOf<AppSigningInput>()
        val responses = mutableListOf<String>()
        val signing = object : AppRequestSigner {
            override val backend = SigningBackend.NATIVE_SDK
            override fun sign(input: AppSigningInput): AppSigningResult { inputs += input; return signed(input) }
            override fun observeResponse(url: String, status: Int, headers: Map<String, List<String>>) {
                assertEquals(200, status)
                responses += url
            }
        }
        val http = OkHttpClient.Builder().addInterceptor {
            val r = it.request()
            when {
                r.url.encodedPath.contains("send_code") -> response(r, """{"message":"success","data":{"mobile_ticket":"ticket"}}""")
                r.url.encodedPath.contains("sms_login") -> response(r, """{"message":"success","data":{}}""")
                    .newBuilder().header("X-Tt-Token", "issued").header("Set-Cookie", "sessionid=issued; Domain=snssdk.com; Path=/; Secure").build()
                else -> response(r, """{"code":0,"data":{"biz_user_id":"1","user_name":"test"}}""")
                    .newBuilder().header("X-Tt-Token", "new-token").build()
            }
        }.build()
        val login = PhoneLogin(http, deviceSession = PlaybackDeviceSession { DeviceRegister.Registered("333", "444") }, requestSigner = signing)
        login.send("13800000000")
        val credentials = login.verify("123456")
        assertTrue(credentials.signingSessionId.isNotBlank())
        assertEquals(credentials, AccountCredentials.fromJson(credentials.toJson()))
        var updated: AccountCredentials? = null
        CloudAccountClient(http, signing).profile(credentials) { updated = it }
        assertEquals(3, inputs.size)
        assertEquals(inputs.map { it.url }, responses)
        assertFalse(inputs[0].context.authenticated); assertFalse(inputs[1].context.authenticated)
        assertEquals(credentials.signingSessionId, inputs[2].context.accountGeneration)
        assertEquals("issued", inputHeader(inputs[2], "X-Tt-Token"))
        assertEquals(credentials.signingSessionId, updated!!.signingSessionId)
        assertEquals("new-token", updated!!.token)
    }
}
