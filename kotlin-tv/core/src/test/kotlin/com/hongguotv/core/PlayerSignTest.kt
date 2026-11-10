// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/** Golden-vector regression against the independent reference implementation
 * bundled with fqapp (github.com/ch6vip/fqapp rust/testdata/vectors.json). */
class PlayerSignTest {
    private fun u64(s: String) = s.toULong().toLong()

    @Test fun sm3MatchesGoVectors() {
        assertEquals("1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b", sm3(ByteArray(0)).hex())
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", sm3("abc".toByteArray()).hex())
        assertEquals("106e34a2b8c7bb13156cfdd0d91379dcc47543dcf9787c68ae5eb582620ae6e8", sm3(ByteArray(16)).hex())
        assertEquals("0c105d5a46a65fdf0a0938283db2517ea87f176de84786f443cb78802aaa03de", sm3(ByteArray(100) { 'a'.code.toByte() }).hex())
        assertEquals("81c65adb29f5b7912512e81103813432471e6f72311425a111741459d03eee5f", sm3("aid=1967&device_id=123&iid=456".toByteArray()).hex())
    }

    @Test fun simonMatchesGoVectors() {
        val k = longArrayOf(u64("1111111111111111111"), u64("2222222222222222222"), u64("3333333333333333333"), u64("4444444444444444444"))
        val ct = Simon.encrypt(u64("1234567890123456789"), u64("9876543210987654321"), k)
        assertEquals(u64("6071150433487837324"), ct[0])
        assertEquals(u64("2027321285990159935"), ct[1])
        val key = Simon.keyExpansion(k)
        assertEquals(u64("13679004903447286117"), key[68])
        assertEquals(u64("6988159389011166349"), key[69])
        assertEquals(u64("12845848347567849394"), key[70])
        assertEquals(u64("14469362449063813004"), key[71])
        val back = Simon.decrypt(ct[0], ct[1], k)
        assertEquals(u64("1234567890123456789"), back[0])
        assertEquals(u64("9876543210987654321"), back[1])
    }

    @Test fun ladonMatchesGoVector() {
        val got = Ladon.encrypt(1_700_000_000, 1_611_921_764, 1967, hex("01020304"))
        assertEquals("AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH", got)
    }

    @Test fun argusHashesMatchGoVectors() {
        assertEquals("106e34a2b8c7", Argus.bodyHash("").hex())
        assertEquals("106e34a2b8c7", Argus.queryHash("").hex())
        assertEquals("925b8d86b20f", Argus.queryHash("aid=1967&device_id=123").hex())
    }

    @Test fun argusEncPbMatchesGoVector() {
        val input = hex("1122334411223344112233441122334411223344112233441122334411223344")
        assertEquals("44332211443322110000000000000000000000000000000000000000000000004433221144332211",
            Argus.encryptEncPb(input, input.size + 8).hex())
    }

    @Test fun legacyBeanAndEncryptionMatchGoVectors() {
        val bean = ProtoBuf()
        bean.putVarint(1, 1_077_940_818)
        bean.putVarint(2, 2)
        bean.putVarint(3, 305_419_896)
        bean.putUtf8(4, "1967")
        bean.putUtf8(5, "test_device_id")
        bean.putUtf8(6, "1611921764")
        bean.putUtf8(7, "")
        bean.putUtf8(8, "")
        bean.putVarint(9, 0)
        bean.putUtf8(10, "")
        bean.putUtf8(11, "")
        bean.putVarint(12, 1_700_000_000 shl 1)
        bean.putBytes(13, Argus.bodyHash(""))
        bean.putBytes(14, Argus.queryHash("aid=1967&device_id=test_device_id&iid=456"))
        val sub15 = ProtoBuf()
        sub15.putVarint(1, 1); sub15.putVarint(2, 1); sub15.putVarint(3, 1); sub15.putVarint(7, 0xC792ECCC)
        bean.putProtobuf(15, sub15)
        bean.putUtf8(16, "")
        bean.putUtf8(20, "none")
        bean.putVarint(21, 738)
        val sub23 = ProtoBuf()
        sub23.putUtf8(1, "NX551J"); sub23.putVarint(2, 8196); sub23.putVarint(4, 0x80E0D800L)
        bean.putProtobuf(23, sub23)
        bean.putVarint(25, 2)
        assertEquals("08d2a4808204100218f8acd191012204313936372a0e746573745f6465766963655f6964320a313631313932313736343a004200480052005a006080c49fd50c6a06106e34a2b8c77206ac5095fc674e7a0c08011001180138ccd9cbbc0c820100a201046e6f6e65a801e205ba01110a064e583535314a1084402080b0838708c80102", bean.toBuf().hex())
        assertEquals("CenCagJvoxTUWE5EKHSREum5XvTccRUiQ6zMVQ+ukI60LEAcPfL4G+G7JQ+PQUf1N/GhJZfgDbrDQz6bB5hu2WHD6Jhk+ZJa5OVOITddXAmdw03KsxjyGgg59eQAw/3C77PsT/y36u1YWD9aqegf30VVgrG4doDhNoPZoaTbx4qAzXrwY/gAsrW3zXEgEP2RgepRfPugDCNZPoqFu76/8g==",
            Argus.encryptLegacy(bean.toBuf()))
    }

    @Test fun argus551MatchesGoVectors() {
        val golden = listOf(
            "aid=1967&device_id=dev_aaaaaaaa&iid=111" to (1_700_000_000L to "8oHyrVYEy1xDCDlx64ko40qC6JC9VYY/ZkjJzEl50iSFDw8Yfph1hupLxfKrA9GswSEz0Yi4goA7OQ4u+H8ECdOaQDL4GduQ3LULSq9Q4iSEGbS3L6G24GXChtnmPr6n46M2PLlKpXvF7uE0lhH4yD+X69FpdM41gQwx5Y5U3NdwXFaxlqYFszyp7dlUE6BEBWqwDsiAH8Jk9TGKr/jfHt4fVmMc9zRyNRdGeV0x6aP//x7aV+8Po8a/9KK3UNG5KEpmIgzRT6NUttkBP9gRNUh3"),
            "aid=1967&device_id=dev_bbbbbbbbbbbbbbbb&iid=222" to (1_700_000_001L to "8oGWgilKQHD4eCdd0VB0vBSbTAkv/3xWE2YWJkxic35CgXkxrUlMTz05HJAak5b5Xx9yxohnoAAcEX//MI9t7XBBtnPwOTaJzkJgBLaYOOotfI7YuqhsEbE5BB363bhKz/Qw/+MI5gH6pgdAXfsRj68u7Qwu3gqCY1NYstqNZFFVrph7dWtXDBYM1aoVEyfkYD2haoaOr9D/gl1zdzAAzbW7we6lr9/zFApbuNsbr2dagdUrQc51QNN0HBOtIkpVBVunDwXM8qR271Ei+Pa4EgKy"),
            "aid=1967&device_id=X&iid=333" to (1_234_567_890L to "8oEOCRTb0LQf+M+MsliYu4M9kBzAlzXvFgL/Ub198hyoBGxYfNXLAENmH+K/10JQ+Rbhm2zRxNtNv5wVd885xcb6C6F10zp/9jVeGHGz8plz8W3UXHHNrFKjJptjQElGDbyaVLSKt56+HfwO7ENYMfjVv/5PNi1upVciiHqyOPDDAtwi/fZkwBoU1kC7RolMOuedn4lCg/s0CjnNvxFT0QpF/K0yoW3Zpu30tkadIET7WbZ3+7KT3c1yAA6RKWI4mmw="),
            "aid=1967&device_id=test_device_id&iid=456&version_name=6.5.1.32" to (1_700_000_000L to "8oHu8LnZm0OtJJIaSr1427D+yeH70h5aZTVjoSW7MfFi/hk1LhjTqsEEWYpwcHfK4/2SXuJxTtopRcPeHzVpeFpUD4B4yxEG7REejpCZ1OqeAaLsw/lMebwUjUHrUjnAZCKq0QyimnwdiTiYIsH3GfiK/xlPcFcARqwX7RkI1qnnWUgiJVDEacSm8yhBat6MvDVOa7+U2MgcLu7Q26pFzX8VX5F+ryP76ETs1nRGLmlhHkwyiKb6CETstvGQnJgocgZBNNvlHD2/P+4LWjFMvhNK"),
        )
        for ((query, pair) in golden) {
            val (ts, want) = pair
            val field3 = Argus.recover551Field3(want)
            assertNotNull("recover field3 for $query", field3)
            val got = Argus.sign(query, "", ts, 1967, 1_611_921_764, queryParam(query, "device_id").orEmpty(), "", field3!!)
            assertEquals("argus551 query=$query ts=$ts", want, got)
        }
    }

    @Test fun playerHeadersCarryArgusAndLadon() {
        val query = "aid=8662&device_id=1234567890&iid=99"
        val body = "{\"x\":1}".toByteArray()
        val headers = PlayerSigner.headers(query, body, 1_700_000_000)
        assertEquals("1700000000", headers["x-khronos"])
        assertEquals("1700000000000", headers["x-ss-req-ticket"])
        assertEquals(digest("MD5", body).hex(), headers["x-ss-stub"])
        assertNotNull(headers["x-ladon"])
        val argus = headers["x-argus"]!!
        val bean = Argus.decrypt551(argus)
        assertNotNull("x-argus decodes back to a bean", bean)
        assertNotNull(Argus.recover551Field3(argus))
    }
}
