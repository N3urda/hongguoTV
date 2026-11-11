package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WebLoginTest {
    private fun http(respond: (Request, Response.Builder) -> String) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val response =
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                response.body(respond(request, response).toResponseBody()).build()
            }
            .build()

    private fun qr(response: Response.Builder): String {
        response.addHeader("Set-Cookie", "passport_csrf_token=test-csrf; Path=/; Secure; HttpOnly")
        return """{"message":"success","data":{"token":"test-token","qrcode":"aW1hZ2U=","expire_time":180}}"""
    }

    @Test
    fun smsUsesOfficialWebProtocolAndSameCsrfSession() {
        var calls = 0
        val login =
            WebLogin(
                http { request, response ->
                    calls++
                    assertEquals("hongguoduanju.com", request.url.host)
                    assertEquals("513755", request.url.queryParameter("aid"))
                    when (request.url.encodedPath) {
                        "/passport/web/get_qrcode/" -> qr(response)
                        "/passport/web/send_code/" -> {
                            assertEquals("test-csrf", request.header("x-tt-passport-csrf-token"))
                            assertTrue(
                                request
                                    .header("Cookie")
                                    .orEmpty()
                                    .contains("passport_csrf_token=test-csrf")
                            )
                            val body = request.body as FormBody
                            val values =
                                (0 until body.size).associate { body.name(it) to body.value(it) }
                            assertEquals(WebLogin.mix("+86 13800138000"), values["mobile"])
                            assertEquals(WebLogin.mix("24"), values["type"])
                            """{"message":"error","data":{"error_code":1105,"verify_center_decision_conf":"challenge"}}"""
                        }
                        else -> error("Unexpected endpoint")
                    }
                }
            )
        val failure = assertThrows(PhoneLogin.Failure::class.java) { login.send("13800138000") }
        assertEquals(1105, failure.errorCode)
        assertEquals(2, calls)
        assertTrue(failure.message.orEmpty().contains("扫码"))
    }

    @Test
    fun qrConfirmationCompletesOfficialRedirectBeforeAccountCheck() {
        val paths = mutableListOf<String>()
        val login =
            WebLogin(
                http { request, response ->
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/passport/web/get_qrcode/" -> qr(response)
                        "/passport/web/check_qrconnect/" ->
                            """{"message":"success","data":{"status":"3","redirect_url":"/auth/complete"}}"""
                        "/auth/complete" -> {
                            response.addHeader(
                                "Set-Cookie",
                                "sessionid=test-only; Path=/; Secure; HttpOnly",
                            )
                            "ok"
                        }
                        "/passport/account/info/v2/" ->
                            """{"message":"success","data":{"user_id":"test-user"}}"""
                        else -> error("Unexpected endpoint")
                    }
                }
            )
        assertArrayEquals("image".toByteArray(), login.qr().image)
        assertTrue(login.poll())
        assertTrue(login.credentials().cookie.contains("sessionid=test-only"))
        assertEquals(
            listOf(
                "/passport/web/get_qrcode/",
                "/passport/web/check_qrconnect/",
                "/auth/complete",
                "/passport/account/info/v2/",
            ),
            paths,
        )
    }

    @Test
    fun confirmationDoesNotForwardSessionToOtherSites() {
        val login =
            WebLogin(
                http { request, response ->
                    if (request.url.encodedPath.endsWith("get_qrcode/")) qr(response)
                    else
                        """{"message":"success","data":{"status":"confirmed","redirect_url":"https://example.com/login"}}"""
                }
            )
        login.qr()
        assertThrows(java.io.IOException::class.java) { login.poll() }
    }
}
