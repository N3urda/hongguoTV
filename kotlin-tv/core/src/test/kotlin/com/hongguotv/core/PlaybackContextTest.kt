package com.hongguotv.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PlaybackContextTest {
    private val model = """{"code":0,"data":{"123":{"video_model":{"video_list":[{"main_url":"https://cdn.example/video.mp4","video_meta":{"codec_type":"h264","definition":"720p"},"encrypt_info":{"encrypt":false}}]}}}}"""
    private val identity = PlaybackDeviceDefaults.values + mapOf("device_id" to "333", "iid" to "444", "cdid" to "installation", "device_type" to "TV-model", "device_brand" to "TV-brand", "os_version" to "11", "os_api" to "30")
    private fun account(token: String = "test-token") = AccountCredentials("sessionid=test-session", "333", "444", identity,
        "app", "8662", token, listOf(AccountCookies.encode(Cookie.Builder().name("sessionid").value("test-session").domain("snssdk.com").path("/").secure().build())))
    private fun http(handle: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { handle(it.request()) }.build()
    private fun response(request: Request, body: String = model, code: Int = 200, token: String? = null, cookie: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody()).apply {
                token?.let { header("X-Tt-Token", it) }; cookie?.let { addHeader("Set-Cookie", it) }
            }.build()
    private fun devices() = PlaybackDeviceSession { DeviceRegister.Registered("999", "888") }

    @Test fun persistedRegistrationAndSeedSurviveProcessRecreation() {
        var raw: String? = null
        val store = object : PlaybackDeviceStore {
            override fun load() = raw
            override fun save(value: String) { raw = value }
        }
        val registrations = AtomicInteger()
        val first = PlaybackDeviceSession(store = store, device = identity) {
            registrations.incrementAndGet()
            val seed = PlaybackDeviceContext.parse(raw!!)
            assertNull(seed.registered)
            assertTrue(seed.values.getValue("clientudid").isNotBlank())
            DeviceRegister.Registered("333", "444", "device-token", cookies = mapOf("ttreq" to "r"), klinkEgdi = "egdi")
        }.profile(OkHttpClient())
        val second = PlaybackDeviceSession(store = store) { fail("must not register again"); null }.profile(OkHttpClient())
        assertEquals(1, registrations.get())
        assertEquals(first.values, second.values)
        assertEquals(first.cookie, second.cookie)
        assertEquals("egdi", second.values["klink_egdi"])
        assertEquals("device-token", PlaybackDeviceContext.parse(raw!!).registered!!.deviceToken)
        assertFalse(second.toString().contains("egdi"))
    }

    @Test fun failedRegistrationRetainsSeedAndStorageErrorsDoNotRotate() {
        var raw: String? = null
        val store = object : PlaybackDeviceStore {
            override fun load() = raw
            override fun save(value: String) { raw = value }
        }
        val first = PlaybackDeviceSession(store = store) { throw IOException("offline") }
        assertThrows(IOException::class.java) { first.profile(OkHttpClient()) }
        val seed = PlaybackDeviceContext.parse(raw!!).values
        val second = PlaybackDeviceSession(store = store) { DeviceRegister.Registered("333", "444") }
        assertEquals(seed.getValue("cdid"), second.profile(OkHttpClient()).values["cdid"])
        raw = "invalid-data"
        assertThrows(IOException::class.java) {
            PlaybackDeviceSession(store = store) { fail("must not replace corrupt state"); null }.profile(OkHttpClient())
        }
        assertEquals("invalid-data", raw)
    }

    @Test fun registrationAndModelUseTheSameInstallationAndHardwareFields() {
        var registration: org.json.JSONObject? = null
        var raw: String? = null
        val store = object : PlaybackDeviceStore {
            override fun load() = raw
            override fun save(value: String) { raw = value }
        }
        val session = PlaybackDeviceSession(store = store, device = identity)
        val provider = PlaybackContextProvider(session)
        val transport = http { request ->
            if (request.url.encodedPath.contains("device_register")) {
                val payload = okio.Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
                val rnd = payload.copyOfRange(6, 38)
                val salt = hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")
                val key = digest("SHA-512", digest("SHA-512", rnd) + salt)
                val plain = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                    init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key.copyOfRange(0, 16), "AES"), javax.crypto.spec.IvParameterSpec(key.copyOfRange(16, 32)))
                    doFinal(payload.copyOfRange(38, payload.size))
                }
                val text = java.util.zip.GZIPInputStream(plain.copyOfRange(64, plain.size).inputStream()).reader().readText()
                registration = org.json.JSONObject(text).getJSONObject("header")
                assertEquals("application/octet-stream;tt-data=a", request.body!!.contentType().toString())
                response(request, """{"device_id":"333","install_id":"444","klink_egdi":"registered-egdi"}""")
            } else {
                val header = registration!!
                for (key in listOf("cdid", "version_code", "device_brand", "os_version", "os_api"))
                    assertEquals(header.get(key).toString(), request.url.queryParameter(key))
                assertEquals(header.getString("device_model"), request.url.queryParameter("device_type"))
                assertEquals("registered-egdi", request.url.queryParameter("klink_egdi"))
                assertEquals(PlaybackDeviceContext.parse(raw!!).values["clientudid"], header.getString("clientudid"))
                response(request)
            }
        }
        ContentRepository(transport, playbackContexts = provider).streamLadderWithCompatibility("123")
    }

    @Test fun registrationFailuresShareBackoffWithoutChangingSeed() {
        var now = 1000L; var calls = 0
        val session = PlaybackDeviceSession(clock = { now }) { calls++; throw IOException("offline") }
        repeat(2) { assertThrows(IOException::class.java) { session.deviceValues(OkHttpClient()) } }
        assertThrows(PlaybackCooldownException::class.java) { session.deviceValues(OkHttpClient()) }
        assertEquals(2, calls)
        now += 30001
        assertThrows(IOException::class.java) { session.deviceValues(OkHttpClient()) }
        assertEquals(3, calls)
    }

    @Test fun restoringAnAccountWithExperimentOffDoesNotCancelGuestPlayback() {
        val provider = PlaybackContextProvider(devices())
        provider.configure(false, null, restored = false)
        val before = provider.snapshot(OkHttpClient(), false)
        assertFalse(provider.configure(false, account()))
        assertSame(before, provider.snapshot(OkHttpClient(), false))
        provider.run(before) { RequestScope.ensureActive() }
    }

    @Test fun loginSourceAndCookieMetadataRoundTripWithoutPromotingLegacyAccounts() {
        val original = account()
        assertEquals(original, AccountCredentials.fromJson(original.toJson()))
        val old = AccountCredentials.fromJson("""{"cookie":"sessionid=old","deviceId":"1","installId":"2"}""")
        assertEquals("legacy", old.source)
        assertTrue(old.scopedCookies.isEmpty())
        assertFalse(original.toString().contains("test-token"))
        val c = Cookie.Builder().name("hostcookie").value("value").hostOnlyDomain("reading.snssdk.com").path("/novel/").secure().expiresAt(123456).build()
        assertEquals(c, AccountCookies.decode(AccountCookies.encode(c)))
    }

    @Test fun optInPhoneLoginRetainsTheRegisteredDeviceAndCapturesIssuedTokenAndCookies() {
        val session = devices()
        var calls = 0
        val transport = http { request ->
            calls++
            assertEquals("security.snssdk.com", request.url.host)
            assertEquals("74532", request.url.queryParameter("version_code"))
            if (request.url.encodedPath.contains("send_code")) response(request,
                """{"message":"success","data":{"mobile_ticket":"ticket"}}""",
                cookie = "passport_csrf_token=csrf; Domain=snssdk.com; Path=/; Secure")
            else {
                assertTrue(request.header("Cookie")!!.contains("passport_csrf_token=csrf"))
                response(request, """{"message":"success","data":{}}""", token = "issued-token",
                    cookie = "sessionid=issued-session; Domain=snssdk.com; Path=/; Secure; HttpOnly")
            }
        }
        val login = PhoneLogin(transport, deviceSession = session)
        login.send("13800000000")
        val credentials = login.verify("123456")
        assertEquals(2, calls)
        assertEquals(session.deviceValues(transport), credentials.device)
        assertEquals("999", credentials.deviceId)
        assertEquals("app", credentials.source)
        assertEquals("8662", credentials.appId)
        assertEquals("issued-token", credentials.token)
        assertTrue(credentials.scopedCookies.mapNotNull(AccountCookies::decode).any { it.name == "sessionid" && it.httpOnly })
    }

    @Test fun accountModelRedirectIsNotFollowed() {
        val provider = PlaybackContextProvider(devices()); provider.configure(true, account())
        var calls = 0
        val transport = http { r ->
            calls++
            response(r, code = 302).newBuilder().header("Location", "https://other.example/collect").build()
        }
        assertThrows(PlaybackSourceException::class.java) {
            ContentRepository(transport, playbackContexts = provider).streamLadderWithCompatibility("123")
        }
        assertEquals(1, calls)
    }

    @Test fun appProfileValidationUsesTheLoginContextAndRetainsResponseRefresh() {
        var updated: AccountCredentials? = null
        val initial = account()
        val provider = PlaybackContextProvider(devices()); provider.configure(true, initial)
        val before = provider.snapshot(OkHttpClient(), false)
        val client = CloudAccountClient(http { request ->
            assertEquals("reading.snssdk.com", request.url.host)
            assertEquals("74532", request.url.queryParameter("version_code"))
            assertEquals("TV-model", request.url.queryParameter("device_type"))
            assertEquals("test-token", request.header("X-Tt-Token"))
            assertTrue(request.header("Cookie")!!.contains("test-session"))
            response(request, """{"code":0,"data":{"biz_user_id":"1","user_name":"fixture"}}""", token = "profile-refreshed-token")
        })
        assertEquals("1", client.profile(initial) { updated = it }.id)
        assertEquals("profile-refreshed-token", updated!!.token)
        provider.refreshAccount(initial, updated!!)
        assertSame(before, provider.snapshot(OkHttpClient(), false))
        assertEquals("profile-refreshed-token", before.accountSession!!.credentials.token)
        provider.run(before) { RequestScope.ensureActive() }
    }

    @Test fun olderResponseCannotOverwriteAnAccountApiRefresh() {
        val initial = account()
        val newest = initial.copy(token = "newest-token")
        val updates = AtomicInteger()
        val provider = PlaybackContextProvider(devices(), onAccountUpdated = { _, _ -> updates.incrementAndGet() })
        provider.configure(true, initial)
        val context = provider.snapshot(OkHttpClient(), false)
        ContentRepository(http { request ->
            assertEquals("test-token", request.header("X-Tt-Token"))
            provider.refreshAccount(initial, newest)
            response(request, token = "older-response-token")
        }, playbackContexts = provider).streamLadderWithCompatibility("123")
        assertSame(context, provider.snapshot(OkHttpClient(), false))
        assertEquals("newest-token", context.accountSession!!.credentials.token)
        assertEquals(0, updates.get())
    }

    @Test fun enabledAccountUsesItsDeviceAndUpdatesTokenForNextRequestAndPersistence() {
        val updates = mutableListOf<AccountCredentials>()
        val provider = PlaybackContextProvider(devices(), onAccountUpdated = { _, next -> updates += next })
        provider.configure(true, account())
        var calls = 0
        val transport = http { r ->
            assertEquals("333", r.url.queryParameter("device_id"))
            assertEquals("444", r.url.queryParameter("iid"))
            assertEquals("TV-model", r.url.queryParameter("device_type"))
            assertEquals("TV-brand", r.url.queryParameter("device_brand"))
            assertEquals("11", r.url.queryParameter("os_version"))
            assertTrue(r.header("User-Agent")!!.contains("Android 11"))
            assertEquals(if (calls++ == 0) "test-token" else "next-token", r.header("X-Tt-Token"))
            assertTrue(r.header("Cookie")!!.contains("sessionid=test-session"))
            response(r, token = "next-token")
        }
        val repository = ContentRepository(transport, playbackContexts = provider)
        repository.streamLadderWithCompatibility("123")
        repository.streamLadderWithCompatibility("123")
        assertEquals(2, calls)
        assertEquals("next-token", updates.single().token)
    }

    @Test fun webAndLegacyAccountsAreNeverSilentlyConvertedToAppSessions() {
        for (source in listOf("web", "legacy")) {
            val provider = PlaybackContextProvider(devices())
            provider.configure(true, account().copy(source = source))
            assertThrows(PlaybackAccountException::class.java) {
                ContentRepository(http { fail("must not send"); response(it) }, playbackContexts = provider).streamLadderWithCompatibility("123")
            }
        }
    }

    @Test fun flagOffUsesPersistentGuestAndDoesNotLeakCredentials() {
        val provider = PlaybackContextProvider(devices())
        provider.configure(false, account())
        ContentRepository(http { r ->
            assertEquals("999", r.url.queryParameter("device_id"))
            assertNull(r.header("Cookie")); assertNull(r.header("X-Tt-Token"))
            response(r)
        }, playbackContexts = provider).streamLadderWithCompatibility("123")
    }

    @Test fun fallbackKeepsAccountButVodNegotiationDoesNotReceiveAnyAccountHeaders() {
        val provider = PlaybackContextProvider(devices()); provider.configure(true, account())
        var calls = 0
        ContentRepository(http { r ->
            calls++
            assertEquals("test-token", r.header("X-Tt-Token"))
            assertEquals("333", r.url.queryParameter("device_id"))
            response(r, if (calls == 1) "" else model)
        }, playbackContexts = provider).streamLadderWithCompatibility("123")
        assertEquals(2, calls)

        val other = PlaybackContextProvider(devices()); other.configure(true, account())
        val unsupported = """{"code":0,"data":{"123":{"video_model":{"video_list":[{"codec_type":"bytevc2","main_url":"https://cdn.example/bvc"}],"fallback_api":{"fallback_api":"https://reading.snssdk.com/vod/codec"}}}}}"""
        var vod = false
        ContentRepository(http { r ->
            if (r.url.encodedPath == "/vod/codec") {
                vod = true
                assertNull(r.header("Cookie")); assertNull(r.header("X-Tt-Token")); assertNull(r.header("X-Medusa"))
                response(r, """{"video_info":{"data":{"video_list":[{"codec_type":"h264","main_url":"https://cdn.example/video.mp4"}]}}}""")
            } else { assertEquals("test-token", r.header("X-Tt-Token")); response(r, unsupported) }
        }, playbackContexts = other).streamLadderWithCompatibility("123")
        assertTrue(vod)
    }

    @Test fun cookieScopeDeletionAndExpiryAreRespected() {
        val now = 10000L
        val original = account("").copy(scopedCookies = listOf(AccountCookies.encode(
            Cookie.Builder().name("sessionid").value("scoped").hostOnlyDomain("reading.snssdk.com").path("/novel/").expiresAt(now + 1000).secure().build())))
        val updates = mutableListOf<AccountCredentials>()
        val session = AppAccountSession(original, { now }) { _, next -> updates += next }
        var calls = 0
        val transport = http { r -> calls++; response(r, cookie = "sessionid=; Max-Age=0; Path=/novel/") }
            .newBuilder().apply { interceptors().add(0, session) }.build()
        transport.newCall(Request.Builder().url("https://reading.snssdk.com/novel/player/video_model/v1/").build()).execute().close()
        assertTrue(updates.single().scopedCookies.isEmpty())
        assertThrows(PlaybackAccountException::class.java) {
            transport.newCall(Request.Builder().url("https://reading.snssdk.com/novel/player/video_model/v1/").build()).execute().close()
        }
        assertEquals(1, calls)
        assertFalse(isAccountModelUrl("https://reading.snssdk.com.attacker.example/novel/player/x".toHttpUrl()))
        assertFalse(isAccountModelUrl("http://reading.snssdk.com/novel/player/x".toHttpUrl()))
    }

    @Test fun unauthorizedSessionIsInvalidatedAndCannotFallBackToGuest() {
        val updated = mutableListOf<AccountCredentials>()
        val provider = PlaybackContextProvider(devices(), onAccountUpdated = { _, next -> updated += next })
        provider.configure(true, account())
        var calls = 0
        val repository = ContentRepository(http { calls++; response(it, code = 401) }, playbackContexts = provider)
        repeat(2) { assertThrows(PlaybackAccountException::class.java) { repository.streamLadderWithCompatibility("123") } }
        assertEquals(1, calls)
        assertEquals("", updated.single().token)
        assertTrue(updated.single().scopedCookies.isEmpty())
    }

    @Test fun logoutCancelsOldRequestAndDiscardsItsTokenAndModel() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val updates = AtomicInteger()
        val provider = PlaybackContextProvider(devices(), onAccountUpdated = { _, _ -> updates.incrementAndGet() })
        provider.configure(true, account())
        val repository = ContentRepository(http { r ->
            started.countDown(); check(release.await(3, TimeUnit.SECONDS)); response(r, token = "stale-token")
        }, playbackContexts = provider)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val job = worker.submit<StreamLadder> { repository.streamLadderWithCompatibility("123") }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            provider.configure(true, null)
            release.countDown()
            assertThrows(java.util.concurrent.ExecutionException::class.java) { job.get(3, TimeUnit.SECONDS) }
            assertEquals(0, updates.get())
            val next = provider.snapshot(OkHttpClient(), false)
            assertEquals("999", next.profile.values["device_id"])
            assertNull(next.accountSession)
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun manualRetriesAndOtherEpisodesShareCooldown() {
        var now = 1000L
        val provider = PlaybackContextProvider(devices(), clock = { now })
        provider.configure(false, null)
        var calls = 0
        val repository = ContentRepository(http { calls++; response(it, "") }, playbackContexts = provider)
        repeat(2) { assertThrows(EmptyPlaybackResponseException::class.java) { repository.streamLadderWithCompatibility("123") } }
        assertEquals(4, calls)
        assertThrows(PlaybackCooldownException::class.java) { repository.streamLadderWithCompatibility("456") }
        assertEquals(4, calls)
        assertTrue(repository.shouldDeferPlaybackPrefetch())
        now += 30001
        assertThrows(EmptyPlaybackResponseException::class.java) { repository.streamLadderWithCompatibility("456") }
        assertEquals(6, calls)
    }

    @Test fun concurrentReadersShareOneModelRequest() {
        val provider = PlaybackContextProvider(devices()); provider.configure(false, null)
        val calls = AtomicInteger(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val transport = http { r -> calls.incrementAndGet(); entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); response(r) }
        val first = ContentRepository(transport, playbackContexts = provider)
        val second = ContentRepository(transport, playbackContexts = provider)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<StreamLadder> { first.streamLadderWithCompatibility("123") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val b = pool.submit<StreamLadder> { second.streamLadderWithCompatibility("123") }
            // Give the second reader a chance to join, without allowing the first network response yet.
            Thread.sleep(100)
            release.countDown()
            assertEquals(a.get(3, TimeUnit.SECONDS).renditions, b.get(3, TimeUnit.SECONDS).renditions)
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
