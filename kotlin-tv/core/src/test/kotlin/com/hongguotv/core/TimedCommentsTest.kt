// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TimedCommentsTest {
    @Test
    fun deterministicCommentSignature() {
        val h =
            CommentSignature()
                .headers("aid=8662&device_id=123", 1700000000123, ByteArray(11) { it.toByte() })
        assertEquals("840420e100007c44bd0bfe74163888622f1454edf858fca6234c", h["X-Gorgon"])
        assertEquals("AgMEBVbVr6bCGXEl3RbL1aAkpFGUXoV+4yYWolKDMC0DpDXW", h["X-Ladon"])
        assertEquals(
            "PMxPj+JImxTKEfQuAsVmWpqWW55ysbz50VgZsw7nAusOgw1l8frdM9uBXSga5sP42uqiFTi9IcF4fLQ1tKcCzvCOSimC41NEVEjNJLzfEjRxKwyqbtbQ2qLvZl/PhMRPjEMMqLnDX064CGeH7Rt9JbxGmLTNLw4v83XXXrMDozK17tDXIJ3nJPCCh70TOfOE//cWmfoxWrWZ68qMlErAsRCe",
            h["X-Argus"],
        )
    }

    @Test
    fun registeredContextAndContinuationAreReusedWithoutAccountCredentials() {
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            DeviceRegister.Registered("333", "444", cookies = mapOf("ttreq" to "anonymous-test"))
        }
        val cursors = mutableListOf<String>()
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("333", request.url.queryParameter("device_id"))
            assertEquals("444", request.url.queryParameter("iid"))
            assertEquals("74332", request.url.queryParameter("version_code"))
            assertEquals("7.4.3.32", request.url.queryParameter("version_name"))
            assertTrue(request.header("User-Agent")!!.contains("74332"))
            assertEquals("ttreq=anonymous-test", request.header("Cookie"))
            assertFalse(request.header("X-Argus").isNullOrBlank())
            val buffer = okio.Buffer()
            request.body!!.writeTo(buffer)
            val encoded = buffer.readByteArray()
            val digest = java.security.MessageDigest.getInstance("MD5").digest(encoded)
                .joinToString("") { "%02X".format(it.toInt() and 255) }
            assertEquals(digest, request.header("x-ss-stub"))
            val sent = JSONObject(String(encoded, Charsets.UTF_8))
            cursors += sent.getString("cursor")
            val start = sent.getJSONObject("business_param").getLong("start_offset_time")
            val result = JSONObject().put("code", 0).put("data", JSONObject()
                .put("extra", JSONObject().put("next_query_danmaku_list_time", start + 30000))
                .put("common_list_info", JSONObject().put("cursor", "next-window").put("has_more", true))
                .put("data_list", JSONArray()))
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(result.toString().toResponseBody()).build()
        }.build()
        val timed = TimedComments(http, session)
        val first = timed.read("123456", "234567", 0, 90000)
        assertEquals("next-window", first.getString("nextCursor"))
        assertTrue(first.getBoolean("hasMore"))
        timed.read("123456", "234567", 30000, 90000, first.getString("nextCursor"))
        timed.read("123456", "234567", 30000, 90000, first.getString("nextCursor"))
        assertEquals(listOf("", "next-window"), cursors)
        timed.clearCache()
        timed.read("123456", "234567", 30000, 90000, "next-window")
        assertEquals(listOf("", "next-window", "next-window"), cursors)
        assertEquals(1, registrations)
    }

    @Test
    fun registrationFailureDoesNotSilentlyFallBackToSparsePlaceholderIdentity() {
        var requests = 0
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { requests++; error("不应发出旧身份请求") }.build()
        val session = PlaybackDeviceSession { null }
        assertThrows(PlaybackDeviceRegistrationException::class.java) {
            TimedComments(http, session).read("123456", "234567", 0, 90000)
        }
        assertEquals(0, requests)
    }

    @Test
    fun filtersPromptsOtherEpisodesDuplicatesAndInvalidTimes() {
        fun row(id: String, time: Long, status: Int = 1, episode: String = "234567") =
            JSONObject()
                .put(
                    "comment",
                    JSONObject()
                        .put("comment_id", id)
                        .put(
                            "common",
                            JSONObject()
                                .put("group_id", episode)
                                .put("status", status)
                                .put("content", JSONObject().put("text", "文本")),
                        )
                        .put("expand", JSONObject().put("offset_time", time)),
                )
        val data =
            JSONObject()
                .put("extra", JSONObject().put("next_query_danmaku_list_time", 30000))
                .put(
                    "data_list",
                    JSONArray(
                        listOf(
                            row("valid", 1000),
                            row("valid", 1000),
                            row("prompt", 0, 0),
                            row("other", 2000, episode = "345678"),
                            row("late", 30000),
                            row("negative", -1),
                        )
                    ),
                )
        val result = JSONObject().put("code", 0).put("data", data)
        val parsed = TimedComments.parse(result, "123456", "234567", 0, 90000)
        assertEquals(1, parsed.getJSONArray("items").length())
        assertEquals(30000, parsed.getInt("nextMS"))
        data.getJSONObject("extra").put("next_query_danmaku_list_time", 0)
        try {
            TimedComments.parse(result, "123456", "234567", 0, 90000)
            fail("non-advancing window accepted")
        } catch (_: IllegalArgumentException) {}
    }
}
