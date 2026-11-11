package com.hongguotv.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SignerTest {
    @Test
    fun playbackRequestTimeChangesWithoutChangingProcessContext() {
        val signer = Signer({ 1000L })
        val method = Signer::class.java.getDeclaredMethod("deviceProto", Map::class.java).apply { isAccessible = true }
        val before = method.invoke(signer, null) as ByteArray
        val first = signer.signPlayback("https://example.test/model", VendorConstants.device, now = 2000L)
        val second = signer.signPlayback("https://example.test/model", VendorConstants.device, now = 9000L)
        assertEquals("2", first.headers["x-khronos"])
        assertEquals("9", second.headers["x-khronos"])
        assertArrayEquals(before, method.invoke(signer, null) as ByteArray)
    }

    @Test
    fun matchesPinnedJavaScriptOracleAcrossTimestampVariants() {
        val rows = JSONArray(javaClass.getResource("/signing.json")!!.readText())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val ts = row.getInt("ts")
            val url = row.getString("url")
            val query = url.substringAfter('?')
            val body = if (row.isNull("body")) null else row.getString("body").toByteArray()
            var seed = 17L
            val signer =
                Signer(
                    { ts.toLong() * 1000 },
                    processNonce = row.getString("nonce"),
                    random = {
                        seed = (seed * 1664525 + 1013904223) and 0xffffffffL
                        seed / 4294967296.0
                    },
                )
            assertEquals("branch $i", 0, signer.branch(query, body, ts))
            assertEquals(
                "hash $i",
                row.getString("hash"),
                signer
                    .hashF13(
                        sm3(query.toByteArray()),
                        body?.let { digest("MD5", it) } ?: ByteArray(16),
                        ts,
                    )
                    .hex(),
            )
            assertEquals(
                "gorgon $i",
                row.getString("gorgon"),
                signer.gorgon(query, body, ts, 42131),
            )
            assertEquals("helios $i", row.getString("helios"), signer.helios(ts))
            assertEquals("medusa $i", row.getString("medusa"), signer.medusa(url, body, ts))
        }
    }

    @Test
    fun playbackSignatureBindsFinalQueryBodyAndRegisteredProfile() {
        val now = 1793880000000L
        val profile = VendorConstants.device + mapOf("device_id" to "333", "iid" to "444",
            "version_name" to "7.3.9.32", "version_code" to "73932")
        for (body in listOf(null, "{\"video_id\":\"123\"}".toByteArray())) {
            val signer = Signer({ now }, profile, random = { 0.5 })
            val signed = signer.signPlayback("https://example.test/model", profile + ("video_id" to "123"), body)
            val url = signed.url.toHttpUrl()
            val query = url.encodedQuery!!
            val ts = signed.headers.getValue("x-khronos").toInt()
            assertNull(url.queryParameter("ts"))
            assertEquals("123", url.queryParameter("video_id"))
            assertEquals(url.queryParameter("_rticket"), signed.headers["x-ss-req-ticket"])
            assertEquals(0, signer.branch(query, body, ts))
            assertEquals(signer.gorgon(query, body, ts, 32768), signed.headers["x-gorgon"])
            assertEquals(signer.medusa(signed.url, body, ts), signed.headers["x-medusa"])
            val wrongProfile = Signer({ now }, random = { 0.5 })
            assertNotEquals(wrongProfile.medusa(signed.url, body, ts), signed.headers["x-medusa"])
            if (body != null)
                assertEquals(digest("MD5", body).hex().uppercase(), signed.headers["x-ss-stub"])
        }
    }


    // LCG 复现 signing-oracle 的 Math.random 序列（seed=17），保证受控变量下的可重复输出。
    private fun seeded(): () -> Double {
        var seed = 17L
        return {
            seed = (seed * 1664525 + 1013904223) and 0xffffffffL
            seed / 4294967296.0
        }
    }

    private fun playbackIdentity() =
        VendorConstants.device +
            mapOf(
                "device_id" to "7300000000000000001",
                "iid" to "7300000000000000002",
                "cdid" to "test-cdid",
            )

    private fun playbackSigner(ts: Int) = Signer({ ts.toLong() * 1000 }, random = seeded())

    @Test
    fun playbackSigningSwitchesToOfficialPlayerHeaderProfile() {
        val ts = 1760000000
        val identity = playbackIdentity()
        val body = """{"biz_param":{}}""".toByteArray()
        val request =
            playbackSigner(ts)
                .sign(
                    "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/",
                    identity,
                    body,
                    identity = identity,
                    playback = true,
                )
        // 官方播放器头集合：签名族齐备，播放器不发的头一律不携带。
        assertEquals(VendorConstants.PLAYER_UA, request.headers["User-Agent"])
        assertEquals("2", request.headers["sdk-version"])
        assertEquals("0", request.headers["x-xs-from-web"])
        assertEquals(ts.toString(), request.headers["x-khronos"])
        assertEquals("application/json; charset=UTF-8", request.headers["Content-Type"])
        assertEquals("cn", request.headers["x-tt-store-region"])
        assertEquals("did", request.headers["x-tt-store-region-src"])
        assertNull(request.headers["x-tt-request-tag"])
        assertNull(request.headers["passport-sdk-version"])
        assertNull(request.headers["x-ss-stub"])
        assertNull(request.headers["x-vc-bdturing-sdk-version"])
        // 注册身份进 query，签名 query 携带 ts。
        val query = request.url.substringAfter('?')
        assertTrue(query.contains("device_id=7300000000000000001"))
        assertTrue(query.contains("iid=7300000000000000002"))
        assertTrue(query.contains("cdid=test-cdid"))
        assertTrue(query.contains("ts=$ts"))
        assertTrue(query.contains("_rticket="))
        assertEquals(request.body, body)
    }

    @Test
    fun defaultSigningKeepsSearchHeaderProfile() {
        val ts = 1760000000
        val request = playbackSigner(ts).sign("https://example.com/api", VendorConstants.device)
        assertEquals(VendorConstants.APP_UA, request.headers["User-Agent"])
        assertEquals("t=0;n=0", request.headers["x-tt-request-tag"])
        assertEquals("50561", request.headers["passport-sdk-version"])
        assertEquals(null, request.headers["x-tt-store-region"])
        assertEquals(null, request.headers["x-tt-store-region-src"])
        assertEquals(null, request.headers["x-ss-stub"])
        assertTrue(request.url.substringAfter('?').contains("ts=$ts"))
    }

    @Test
    fun medusaEmbedsProvidedPlaybackIdentity() {
        val url = "https://api5-normal-sinfonlineb.fqnovel.com/x/?a=1"
        val body = "payload".toByteArray()
        // medusa 内嵌 hashF13 与播放请求同约束（branch 0）：选一个满足 branch==0 的 ts。
        val probe = Signer({ 0L })
        val ts = (1760000000..1760000040).first { probe.branch("a=1", body, it) == 0 }
        val plain = playbackSigner(ts).medusa(url, body, ts)
        val stamped =
            playbackSigner(ts).medusa(url, body, ts, playbackIdentity())
        // 相同随机序列下，medusa 明文里 device_proto/message 的 device_id 改为注册身份，密文必变。
        assertNotEquals(plain, stamped)
    }
}
