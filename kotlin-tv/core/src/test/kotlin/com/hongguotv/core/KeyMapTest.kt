package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class KeyMapTest {
    @Test fun factoryBindingsMatchThePreCustomizationBehaviour() {
        // 出厂绑定必须与引入本功能之前的播放界面逐键一致，否则老用户的手感被悄悄改掉。
        assertEquals(PlayerKeyAction.SETTINGS, KeyMap.DEFAULT.action(PlayerKey.UP))
        assertEquals(PlayerKeyAction.SETTINGS, KeyMap.DEFAULT.action(PlayerKey.MENU))
        assertEquals(PlayerKeyAction.EPISODES, KeyMap.DEFAULT.action(PlayerKey.DOWN))
        assertEquals(PlayerKeyAction.SEEK_BACK, KeyMap.DEFAULT.action(PlayerKey.LEFT))
        assertEquals(PlayerKeyAction.SEEK_FORWARD, KeyMap.DEFAULT.action(PlayerKey.RIGHT))
        assertEquals(PlayerKeyAction.PLAY_PAUSE, KeyMap.DEFAULT.action(PlayerKey.OK))
        assertTrue(KeyMap.DEFAULT.isDefault)
        assertEquals(0, KeyMap.DEFAULT.customized)
    }

    @Test fun defaultMapEncodesToEmptyString() {
        // 出厂状态磁盘上就是一个空串：旧版本读到空串时行为与以前完全一致。
        assertEquals("", KeyMap.DEFAULT.encode())
        assertEquals("", KeyMap.decode("").encode())
        assertEquals("", KeyMap.decode(null).encode())
        assertEquals("", KeyMap.decode("   ").encode())
    }

    @Test fun encodeWritesOnlyChangedKeys() {
        val map = KeyMap.DEFAULT.customize(PlayerKey.DOWN, PlayerKeyAction.NEXT)
            .customize(PlayerKey.LEFT, PlayerKeyAction.NONE)
        assertEquals("down:next,left:none", map.encode())
        assertEquals(2, map.customized)
        assertFalse(map.isDefault)
    }

    @Test fun sameBindingsAlwaysEncodeToTheSameString() {
        // 顺序固定按 PlayerKey.entries，与传入顺序无关——备份比对与测试断言都靠这一点。
        val a = KeyMap.DEFAULT.customize(PlayerKey.UP, PlayerKeyAction.NEXT)
            .customize(PlayerKey.RIGHT, PlayerKeyAction.PREVIOUS)
        val b = KeyMap.DEFAULT.customize(PlayerKey.RIGHT, PlayerKeyAction.PREVIOUS)
            .customize(PlayerKey.UP, PlayerKeyAction.NEXT)
        assertEquals(a.encode(), b.encode())
    }

    @Test fun customizeTouchesOnlyTheRequestedKey() {
        val map = KeyMap.DEFAULT.customize(PlayerKey.MENU, PlayerKeyAction.HUD)
        assertEquals(PlayerKeyAction.HUD, map.action(PlayerKey.MENU))
        assertEquals(PlayerKeyAction.SETTINGS, map.action(PlayerKey.UP))
        assertEquals(PlayerKeyAction.EPISODES, map.action(PlayerKey.DOWN))
        assertEquals(1, map.customized)
    }

    @Test fun changingAKeyBackToFactoryReadsAsDefault() {
        // customized 按「当前动作与出厂是否一致」现算，而不是数表里有几条。
        val map = KeyMap.DEFAULT.customize(PlayerKey.UP, PlayerKeyAction.HUD)
            .customize(PlayerKey.UP, PlayerKeyAction.SETTINGS)
        assertTrue(map.isDefault)
        assertEquals(0, map.customized)
        // 磁盘上那一条虽然还在，编码时应当被剔掉。
        assertEquals("", map.encode())
    }

    @Test fun decodeSurvivesUnknownAndMalformedEntries() {
        // 备份文件是用户手里的东西：坏值只丢自己那一条，绝不让整份恢复失败。
        val map = KeyMap.decode("up:episodes,bogus:next,down:,left:nope,:next,right:previous,right:next")
        assertEquals(PlayerKeyAction.EPISODES, map.action(PlayerKey.UP))
        // 重复段按后一条生效（linkedMap 后写覆盖前写）。
        assertEquals(PlayerKeyAction.NEXT, map.action(PlayerKey.RIGHT))
        // down / left 保持出厂：空动作名与不认识的动作名都整条丢弃，不影响其余键。
        assertEquals(PlayerKeyAction.EPISODES, map.action(PlayerKey.DOWN))
        assertEquals(PlayerKeyAction.SEEK_BACK, map.action(PlayerKey.LEFT))
    }

    @Test fun decodeRejectsOverlongPayload() {
        val junk = (1..KeyMap.MAX_ENCODED + 50).joinToString("") { "x" }
        assertEquals("", KeyMap.decode(junk).encode())
    }

    @Test fun roundTripPreservesEveryBinding() {
        val map = KeyMap.DEFAULT.customize(PlayerKey.UP, PlayerKeyAction.NEXT)
            .customize(PlayerKey.DOWN, PlayerKeyAction.PLAY_PAUSE)
            .customize(PlayerKey.LEFT, PlayerKeyAction.PREVIOUS)
            .customize(PlayerKey.RIGHT, PlayerKeyAction.HUD)
            .customize(PlayerKey.MENU, PlayerKeyAction.SEEK_BACK)
            .customize(PlayerKey.OK, PlayerKeyAction.NONE)
        val restored = KeyMap.decode(map.encode())
        assertEquals(map.encode(), restored.encode())
        PlayerKey.entries.forEach { key -> assertEquals(map.action(key), restored.action(key)) }
        assertEquals(6, restored.customized)
    }
}
