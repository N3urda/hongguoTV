package com.hongguotv.core

import java.io.IOException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudAccountTest {
    private val credentials =
        AccountCredentials("sessionid=test-only", "3012345678901234567", "3012345678901234568")

    private fun http(respond: (Request) -> String) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(respond(chain.request()).toResponseBody())
                    .build()
            }
            .build()

    @Test
    fun accountSessionRoundTripsWithoutExposingCredentialsInToString() {
        assertEquals(credentials, AccountCredentials.fromJson(credentials.toJson()))
        assertFalse(credentials.toString().contains("test-only"))
    }

    @Test
    fun historyKeepsExactPositionAndNormalizesTimestamp() {
        val rows =
            JSONArray(
                """[{"series_id":"123","vid":"456","vid_index":3,"current_play_time":43210,"current_total_time":90000,"last_watch_time":1792500000}]"""
            )
        val result = CloudAccountClient.parseProgress(rows).single()
        assertEquals(2, result.episodeIndex)
        assertEquals(43210L, result.position)
        assertEquals(90000L, result.duration)
        assertEquals(1792500000000L, result.updatedAt)
    }

    @Test
    fun accountProfileRejectsAnonymousFallbackInsteadOfPretendingLoginSucceeded() {
        val client =
            CloudAccountClient(
                http { """{"code":0,"data":{"user_name":"用户12345678","has_set_username":false}}""" }
            )
        assertThrows(IOException::class.java) { client.profile(credentials) }
    }

    @Test
    fun emptyResponseIsAnAccountError() {
        val client = CloudAccountClient(http { "" })
        val failure = assertThrows(IOException::class.java) { client.profile(credentials) }
        assertTrue(failure.message.orEmpty().contains("空响应"))
    }

    @Test
    fun historyEmptyResponsesIdentifyTheFailingStageWithoutLeakingIdentity() {
        for (failureStage in listOf("read_progress", "multi-detail")) {
            val client = CloudAccountClient(http { request ->
                if (request.url.encodedPath.contains(failureStage)) ""
                else """{"code":0,"video_data":[{"series_id":"123","vid":"456","vid_index":2}]}"""
            })
            val error = assertThrows(IOException::class.java) { client.history(credentials) }
            val message = error.message.orEmpty()
            assertTrue(message.contains(if (failureStage == "read_progress") "云端历史列表" else "云端剧集资料"))
            assertTrue(message.contains("HTTP 200/0B/h1"))
            assertFalse(message.contains(credentials.cookie))
            assertFalse(message.contains(credentials.deviceId))
            assertFalse(message.contains("https://"))
        }
    }

    @Test
    fun historyLoadsMetadataWithoutLosingPlaybackPosition() {
        val client =
            CloudAccountClient(
                http { request ->
                    assertEquals("sessionid=test-only", request.header("Cookie"))
                    assertEquals(credentials.deviceId, request.url.queryParameter("device_id"))
                    if (request.url.encodedPath.contains("read_progress"))
                        """{"code":0,"video_data":[{"series_id":"123","vid":"456","vid_index":2,"current_play_time":12000}]}"""
                    else """{"code":0,"data":[{"book_id":"123","book_name":"云端测试剧"}]}"""
                }
            )
        val row = client.history(credentials).single()
        assertEquals("云端测试剧", row.series.title)
        assertEquals(12000L, row.position)
    }

    @Test
    fun uploadingProgressUsesCurrentSessionAndOneBasedEpisodeIndex() {
        var calls = 0
        val client =
            CloudAccountClient(
                http { request ->
                    calls++
                    assertEquals("api5-normal-sinfonlinea.fqnovel.com", request.url.host)
                    assertEquals("sessionid=test-only", request.header("Cookie"))
                    assertEquals(credentials.deviceId, request.url.queryParameter("device_id"))
                    assertEquals(
                        "/reading/bookapi/read_progress/upload/v1/",
                        request.url.encodedPath,
                    )
                    val buffer = okio.Buffer()
                    request.body!!.writeTo(buffer)
                    val body = JSONObject(buffer.readUtf8())
                    val book = body.getJSONArray("books").getJSONObject(0)
                    assertEquals(1, body.getInt("write_mode"))
                    assertEquals(3, book.getInt("vid_index"))
                    assertEquals(43210L, book.getLong("current_play_time"))
                    assertEquals("456", book.getString("item_id"))
                    """{"code":0}"""
                }
            )
        client.upload(
            credentials,
            CloudProgress(Series("123", "测试"), "456", 2, 43210, 90000, 1792500000000),
            8,
        )
        assertEquals(1, calls)
    }

    @Test
    fun rejectedUploadNeverReportsSuccess() {
        val client = CloudAccountClient(http { """{"code":100103}""" })
        assertThrows(IOException::class.java) {
            client.upload(credentials, CloudProgress(Series("123", "测试"), "456", 0, 0, 0, 1), 1)
        }
    }

    @Test
    fun phoneLoginStopsOnIncorrectVerificationCodeWithoutRetryingOtherEndpoints() {
        var calls = 0
        val login =
            PhoneLogin(
                http {
                    calls++
                    """{"message":"error","data":{"error_code":1102,"description":"验证码错误"}}"""
                }
            )
        login.deviceId = credentials.deviceId
        login.installId = credentials.installId
        login.mobile = "13800138000"
        assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertEquals(1, calls)
        assertTrue(login.trace.isEmpty())
    }

    /**
     * `error_code 1` 是端点不适用（validate_code 对未登录会话固定回它），要继续试后续端点，
     * 且不能把会话标记成不可再用——否则用户换一个验证码也没机会。
     */
    @Test
    fun sessionExpiredErrorIsTreatedAsEndpointMismatch() {
        var calls = 0
        val login =
            PhoneLogin(
                http {
                    calls++
                    """{"message":"error","data":{"error_code":1,"description":"会话过期，请重新登录"}}"""
                }
            )
        login.deviceId = credentials.deviceId
        login.installId = credentials.installId
        login.cookies["temporary"] = "test-session"
        login.ticket = "test-ticket"
        val first = assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertEquals(1, first.errorCode)
        assertFalse(first.canRetryCode)
        assertEquals(5, calls)
        assertEquals("test-ticket", login.ticket)
        assertTrue(login.cookies.isNotEmpty())
    }

    /** 会话有效但业务账号未建立时给出可操作的提示，而不是笼统的“重新登录”。 */
    @Test
    fun sessionWithoutBusinessAccountReportsProvisioningHint() {
        val client = CloudAccountClient(http { """{"code":0,"data":{"biz_user_id":"","user_name":""}}""" })
        try {
            client.profile(credentials)
            fail("expected provisioning failure")
        } catch (problem: AccountNotProvisionedException) {
            assertTrue(problem.message.orEmpty().contains("官方红果 App"))
        }
    }
}
