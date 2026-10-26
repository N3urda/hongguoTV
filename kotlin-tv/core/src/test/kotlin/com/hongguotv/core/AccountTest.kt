// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 账号凭据解析（cookieStore.xml 序列化流 / 粘贴 Cookie 串）的对账，移植自 HGTV 1.0.8。 */
class AccountTest {
    /** 手工构造一段 Java 序列化流（TC_STRING 串列表），模拟 cookieStore.xml 里的十六进制值。 */
    private fun hex(vararg fields: String): String {
        val out=StringBuilder("aced0005")
        fields.forEach { field ->
            val bytes=field.toByteArray(Charsets.US_ASCII)
            out.append("74")
            out.append("%04x".format(bytes.size))
            bytes.forEach { out.append("%02x".format(it.toInt() and 255)) }
        }
        return out.toString()
    }

    private fun xml(host: String,name: String,value: String) =
        """<string name="http://$host/|$name">$value</string>"""

    @Test
    fun cookieStoreXmlYieldsHeaderForDefaultHost() {
        val sid=hex("sessionid","cf98cf3caabbccdd0011223344556677","snssdk.com","/")
        val guard=hex("sid_guard","cf98cf3caabbccdd0011223344556677%7C1700000000%7C7776000%7Cxx")
        val uid=hex("uid_tt","12345")
        val xml=xml("snssdk.com","sessionid",sid)+xml("fqnovel.com","sessionid",sid)+xml("snssdk.com","sid_guard",guard)+xml("snssdk.com","uid_tt",uid)
        val cookie=AccountCredentialsParser.parse(xml)
        assertEquals("cf98cf3caabbccdd0011223344556677",cookie["sessionid"])
        assertEquals("12345",cookie["uid_tt"])
        // 只取 snssdk.com 那份：fqnovel.com 的同名字段不进结果。
        assertEquals(3,cookie.size)
        val credentials=AccountCredentialsParser.parseCredentials(xml)
        assertTrue(credentials.cookie.startsWith("sessionid=cf98cf3c"))
        assertTrue(credentials.cookie.contains("uid_tt=12345"))
        // 过期 = 签发 + max-age。
        assertEquals(1700000000L+7776000L,credentials.expiresAt)
    }

    @Test
    fun pastedCookieHeaderRoundTrips() {
        val text="sessionid=abc123; uid_tt=42; bogus_key=1; sid_guard=s%7C1700000000%7C7776000%7Cx"
        val credentials=AccountCredentialsParser.parseHeader(text)
        // 只有 COOKIE_KEYS 白名单里的字段进 Cookie 头。
        assertTrue(credentials.cookie.contains("sessionid=abc123"))
        assertTrue(credentials.cookie.contains("uid_tt=42"))
        assertEquals(1700000000L+7776000L,credentials.expiresAt)
    }

    @Test
    fun sessionIdExtractionHandlesAllUserInputs() {
        val value="cf98cf3caabbccdd0011223344556677"
        assertEquals(value,AccountCredentialsParser.sessionIdOf(value))
        assertEquals(value,AccountCredentialsParser.sessionIdOf("sessionid=$value"))
        assertEquals(value,AccountCredentialsParser.sessionIdOf("sessionid=$value; uid_tt=1"))
        // 用户从文件里搜到整行 / 十六进制流直接粘进来：按字节扫出真值，不当成 sessionid。
        assertEquals(value,AccountCredentialsParser.sessionIdOf(xml("snssdk.com","sessionid",hex("sessionid",value))))
        assertEquals(value,AccountCredentialsParser.sessionIdOf(hex("sessionid",value)))
        assertEquals("",AccountCredentialsParser.sessionIdOf("   "))
    }

    @Test
    fun deviceProfileParsesKnownKeysAndIgnoresGarbage() {
        val profile="""{"device_id":"123","install_id":"456","cdid":"abc","junk":"x"}"""
        val device=AccountCredentialsParser.parseDeviceProfile(profile)
        assertEquals("123",device["device_id"])
        assertEquals("456",device["iid"])
        assertEquals("abc",device["cdid"])
        assertTrue("junk" !in device)
        assertEquals(emptyMap<String,String>(),AccountCredentialsParser.parseDeviceProfile("not json"))
    }

    @Test
    fun malformedHexFailsClosed() {
        assertEquals(emptyMap<String,String>(),AccountCredentialsParser.parse(xml("snssdk.com","sessionid","zzz")))
        assertEquals(emptyMap<String,String>(),AccountCredentialsParser.parse(xml("snssdk.com","sessionid","abc")))
        assertEquals(emptyMap<String,String>(),AccountCredentialsParser.parse(xml("snssdk.com","sessionid","")))
    }
}
