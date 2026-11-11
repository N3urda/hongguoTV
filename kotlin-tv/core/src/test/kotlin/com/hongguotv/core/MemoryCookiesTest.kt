package com.hongguotv.core

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class MemoryCookiesTest {
    private fun cookie(
        name: String,
        value: String,
        host: String,
        expiresAt: Long = System.currentTimeMillis() + 60_000,
    ) = Cookie.Builder().name(name).value(value).domain(host).path("/").expiresAt(expiresAt).build()

    @Test
    fun savedCookiesAreLoadedForLaterRequestsOnTheSameHost() {
        val jar = MemoryCookies()
        val url = "https://hongguoduanju.com/rank/hot-drama".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("ttwid", "abc", "hongguoduanju.com")))
        val loaded = jar.loadForRequest("https://hongguoduanju.com/rank/hot-real-drama".toHttpUrl())
        assertEquals(listOf("ttwid" to "abc"), loaded.map { it.name to it.value })
    }

    @Test
    fun cookiesAreIsolatedPerHost() {
        val jar = MemoryCookies()
        jar.saveFromResponse(
            "https://hongguoduanju.com/rank/hot-drama".toHttpUrl(),
            listOf(cookie("ttwid", "site", "hongguoduanju.com")),
        )
        assertTrue(jar.loadForRequest("https://api5-normal-sinfonlinea.fqnovel.com/x".toHttpUrl()).isEmpty())
    }

    @Test
    fun expiredCookiesAreNotSent() {
        val jar = MemoryCookies()
        val url = "https://hongguoduanju.com/".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(cookie("ttwid", "old", "hongguoduanju.com", expiresAt = System.currentTimeMillis() - 1)),
        )
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun resavingTheSameNameReplacesTheOldValue() {
        val jar = MemoryCookies()
        val url = "https://hongguoduanju.com/".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("ttwid", "first", "hongguoduanju.com")))
        jar.saveFromResponse(url, listOf(cookie("ttwid", "second", "hongguoduanju.com")))
        val loaded = jar.loadForRequest(url)
        assertEquals(1, loaded.size)
        assertEquals("second", loaded.single().value)
    }
}
