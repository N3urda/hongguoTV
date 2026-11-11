// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * App passport 短信登录（`/passport/mobile/` 三个端点）：网页端已要求滑块，这条通道仍未要求；
 * 用例只验证请求构造、响应解析与错误分类，真实验证码与 sessionid 需实机验收。
 */
class PhoneLoginTest {

    private fun client(
        cookies: Map<String, String> = emptyMap(),
        respond: (Request) -> Pair<Int, String>,
    ) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val (code, body) = respond(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("fixture")
                    .apply { cookies.forEach { (k, v) -> addHeader("Set-Cookie", "$k=$v; Path=/") } }
                    .body(body.toResponseBody())
                    .build()
            }
            .build()

    private fun deviceRegister(request: Request) =
        request.url.encodedPath == "/service/2/device_register/"

    private val registered = 200 to """{"device_id":"333","install_id":"444"}"""

    @Test
    fun sendReportsMaskedNumberRetryAndTicket() {
        val login = PhoneLogin(
            client { request ->
                if (deviceRegister(request)) registered
                else 200 to """{"message":"success","data":{"mobile":"138******00","retry_time":60,"mobile_ticket":"tk"}}"""
            }
        )
        val sent = login.send("13800000000")
        assertEquals("138******00", sent.maskedMobile)
        assertEquals(60, sent.retrySeconds)
        assertEquals("tk", login.ticket)
        // 官方 App 的登录直接用设备表里的固定号，不注册新设备。
        assertEquals("1905892595378490", login.deviceId)
        assertEquals("1905892595382586", login.installId)
    }

    @Test
    fun sliderChallengeIsReportedAsUnsupported() {
        val login = PhoneLogin(
            client { request ->
                if (deviceRegister(request)) registered
                else 200 to """{"message":"success","data":{"verify_center_decision_conf":"{}"}}"""
            }
        )
        try {
            login.send("13800000000")
            fail("expected slider failure")
        } catch (failure: PhoneLogin.Failure) {
            assertTrue(failure.message.orEmpty().contains("滑块"))
        }
    }

    @Test
    fun verifyReturnsCredentialsBoundToTheRegisteredDevice() {
        val calls = AtomicInteger()
        val login = PhoneLogin(
            client(
                respond = { request ->
                    when {
                        deviceRegister(request) -> registered
                        request.url.encodedPath.contains("/send_code/") ->
                            200 to """{"message":"success","data":{"mobile":"138******00","mobile_ticket":"tk"}}"""
                        else -> {
                            calls.incrementAndGet()
                            200 to """{"message":"success","data":{}}"""
                        }
                    }
                },
                cookies = mapOf("sessionid" to "abc"),
            )
        )
        login.send("13800000000")
        val credentials = login.verify("123456")
        assertEquals("sessionid=abc", credentials.cookie)
        assertEquals("1905892595378490", credentials.deviceId)
        assertEquals("1905892595382586", credentials.installId)
        assertEquals("1905892595378490", credentials.device["device_id"])
        assertEquals(1, calls.get())
    }

    /** `error_code 1` 表示端点不适用（validate_code 对未登录会话固定回它），应继续尝试后续端点。 */
    @Test
    fun sessionExpiredErrorKeepsTryingOtherEndpoints() {
        val endpoints = mutableListOf<String>()
        val login = PhoneLogin(
            client { request ->
                when {
                    deviceRegister(request) -> registered
                    request.url.encodedPath.contains("/send_code/") ->
                        200 to """{"message":"success","data":{"mobile":"138******00","mobile_ticket":"tk"}}"""
                    else -> {
                        endpoints += request.url.encodedPath
                        200 to """{"message":"error","data":{"error_code":1,"description":"会话过期，请重新登录"}}"""
                    }
                }
            }
        )
        login.send("13800000000")
        try {
            login.verify("123456")
            fail("expected rejected session")
        } catch (failure: PhoneLogin.Failure) {
            assertEquals(1, failure.errorCode)
            assertFalse(failure.canRetryCode)
        }
        // 五条端点都试过，且不会把会话标记成不可再用（用户可换验证码重试）。
        assertEquals(5, endpoints.size)
        try {
            login.verify("123456")
            fail("expected another rejection")
        } catch (failure: PhoneLogin.Failure) {
            assertFalse(failure.message.orEmpty().contains("已失效"))
        }
    }

    /** 1203 是「错误次数过多或验证码过期」，本次会话作废，不再继续提交。 */
    @Test
    fun tooManyAttemptsExpiresTheSession() {
        val login = PhoneLogin(
            client { request ->
                when {
                    deviceRegister(request) -> registered
                    request.url.encodedPath.contains("/send_code/") ->
                        200 to """{"message":"success","data":{"mobile":"138******00","mobile_ticket":"tk"}}"""
                    else ->
                        200 to """{"message":"error","data":{"error_code":1203,"description":"错误次数过多或验证码过期，请稍后重试"}}"""
                }
            }
        )
        login.send("13800000000")
        try {
            login.verify("123456")
            fail("expected exhausted session")
        } catch (failure: PhoneLogin.Failure) {
            assertEquals(1203, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("错误次数过多"))
        }
        try {
            login.verify("123456")
            fail("expected expired session")
        } catch (failure: PhoneLogin.Failure) {
            assertTrue(failure.message.orEmpty().contains("已失效"))
        }
    }

    /** sms_login 成功时直接基于它建立会话，不应再打 validate_code。 */
    @Test
    fun smsLoginEstablishesSessionWithoutTouchingValidateCode() {
        val paths = mutableListOf<String>()
        val login = PhoneLogin(
            client(
                cookies = mapOf("sessionid" to "abc"),
                respond = { request ->
                    when {
                        deviceRegister(request) -> registered
                        request.url.encodedPath.contains("/send_code/") ->
                            200 to """{"message":"success","data":{"mobile":"138******00","mobile_ticket":"tk"}}"""
                        else -> {
                            paths += request.url.encodedPath
                            200 to """{"message":"success","data":{}}"""
                        }
                    }
                },
            )
        )
        login.send("13800000000")
        login.verify("123456")
        assertEquals(listOf("/passport/mobile/sms_login/"), paths)
    }

    @Test
    fun formatAcceptsMainlandAndOverseasNumbers() {
        assertEquals("13800138000", PhoneLogin.format("86", "138 0013 8000"))
        assertEquals("+886 912345678", PhoneLogin.format("886", "0912345678"))
        try {
            PhoneLogin.format("86", "12345")
            fail("expected invalid mainland number")
        } catch (problem: IllegalArgumentException) {
            assertTrue(problem.message.orEmpty().contains("11 位"))
        }
    }
}
