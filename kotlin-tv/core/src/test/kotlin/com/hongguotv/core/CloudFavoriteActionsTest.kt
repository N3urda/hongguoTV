package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudFavoriteActionsTest {
    @Test
    fun videoFavoriteWritesUseCorrectProtocolAndCurrentAccountExactlyOnce() {
        for (add in listOf(true, false)) {
            var calls = 0
            val http =
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        calls++
                        val request = chain.request()
                        assertEquals("POST", request.method)
                        assertEquals(
                            "/reading/bookapi/bookshelf/video/update/v1/",
                            request.url.encodedPath,
                        )
                        assertEquals("test-only", request.header("Cookie"))
                        assertEquals("123", request.url.queryParameter("device_id"))
                        val buffer = okio.Buffer()
                        request.body!!.writeTo(buffer)
                        val json = JSONObject(buffer.readUtf8())
                        assertFalse(json.getBoolean("is_cancelled"))
                        val row = json.getJSONArray("update_bookshelf_video_list").getJSONObject(0)
                        assertEquals("456", row.getString("book_id"))
                        assertEquals(2, row.getInt("book_type"))
                        assertEquals(if (add) 0 else 1, row.getInt("video_shelf_operate_type"))
                        assertTrue(row.getLong("modify_time") > 0)
                        if (add) {
                            assertTrue(json.has("shark_extra"))
                            assertFalse(row.has("group_name"))
                        } else {
                            assertEquals("", row.getString("group_name"))
                            assertFalse(json.has("shark_extra"))
                        }
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("""{"code":0}""".toResponseBody())
                            .build()
                    }
                    .build()
            CloudAccountClient(http)
                .setFavorite(AccountCredentials("test-only", "123", "789"), "456", add)
            assertEquals(1, calls)
        }
    }

    @Test
    fun rejectedWriteNeverRetriesAndInvalidIdsNeverReachNetwork() {
        var calls = 0
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    calls++
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"code":101144}""".toResponseBody())
                        .build()
                }
                .build()
        val client = CloudAccountClient(http)
        val account = AccountCredentials("test-only")
        assertThrows(IllegalArgumentException::class.java) {
            client.setFavorite(account, "reservation:123", true)
        }
        assertEquals(0, calls)
        assertThrows(IOException::class.java) { client.setFavorite(account, "123", true) }
        assertEquals(1, calls)
    }
}
