// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlayerModelProtocolTest {
    private val model = """{"video_list":[{"main_url":"https://example.test/video","video_meta":{"definition":"720p","codec_type":"h264","vwidth":720,"vheight":1280},"encrypt_info":{"encrypt":false}}]}"""
    private fun response(request: Request, body: String, status: Int = 200, backoff: Boolean = false) =
        Response.Builder().request(request).protocol(Protocol.HTTP_2).code(status).message("fixture")
            .apply { if (backoff) header("Retry-After", "120") }.body(body.toResponseBody()).build()

    @Test fun singleModelSupportsObjectJsonBase64AndFlatShapesWithoutLegacyRequests() {
        for (shape in 0..3) {
            var calls = 0
            val objectModel = JSONObject(model)
            val data = if (shape == 3) objectModel else JSONObject().put("video_model", when (shape) {
                0 -> objectModel
                1 -> model
                else -> java.util.Base64.getEncoder().encodeToString(model.toByteArray())
            })
            val http = OkHttpClient.Builder().cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("sessionid").value("test-account-only").domain(url.host).build())
            }).addInterceptor { chain ->
                calls++
                val request = chain.request()
                assertEquals("/novel/player/video_model/v1/", request.url.encodedPath)
                assertEquals("73532", request.url.queryParameter("version_code"))
                assertNull(request.header("Cookie"))
                assertNull(request.header("X-Medusa"))
                response(request, JSONObject().put("code", 0).put("data", data).toString())
            }.build()
            val repo = ContentRepository(http, playbackSession = PlaybackDeviceSession { DeviceRegister.Registered("333", "444", cookies = mapOf("ttreq" to "test")) })
            assertEquals(720, repo.streamLadder("123").renditions.single().quality)
            assertEquals(1, calls)
        }
    }

    @Test fun explicitRejectionAndRetryAfterDoNotFallThroughToOtherHostsOrRotate() {
        for (case in listOf("403", "429", "business", "backoff")) {
            var calls = 0
            var registrations = 0
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                response(chain.request(), if (case == "business") """{"code":110001}""" else "",
                    case.toIntOrNull() ?: 200, case == "backoff")
            }.build()
            val repo = ContentRepository(http, playbackSession = PlaybackDeviceSession {
                registrations++; DeviceRegister.Registered("333", "444")
            })
            assertThrows(IOException::class.java) { repo.streamLadder("123") }
            assertEquals(case, 1, calls)
            assertEquals(case, 1, registrations)
        }
    }
}
