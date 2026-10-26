// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.KeyEvent
import android.view.View
import com.hongguotv.core.KeyMap
import com.hongguotv.core.PlayerKey
import com.hongguotv.core.PlayerKeyAction

// 播放界面按键自定义：设置页那一栏的文案 / 选择弹窗，以及播放时的按键分发。
//
// 三件事分得很清楚，改的时候别混：
// * 有哪些键、每个键能绑哪些动作、出厂绑定是什么 → core/KeyMap.kt（纯 Kotlin，可测）；
// * 设置页长什么样、点一格怎么改 → 本文件的 keyRowLabel / showKeyActionPicker；
// * 播放时按键怎么落地 → 本文件的 playerKeyAction / dispatchPlayerKey，
//   入口在 MainActivity.dispatchKeyEvent 的播放分支。
//
// 这些成员是 MainActivity 的扩展（同包可见），因此它们用到的 hud / panel / showPanel 等
// 在 MainActivity 里必须是 internal——private 会让这里编译不过。

/**
 * 设置页里一个键那一格的文案：`上键  ·  播放设置`。
 *
 * 与相邻的「默认清晰度  ·  1080P」同一套写法（两个空格 + 间隔号 + 当前值），
 * 用户扫一眼就知道每一格现在是什么。
 */
internal fun MainActivity.keyRowLabel(key: PlayerKey) = "${key.label}  ·  ${library.keyMap.action(key).label}"

/**
 * 「恢复默认按键」那一行的文案。
 *
 * 默认状态下也要写出来（而不是留空或灰掉）：这一行同时是「当前是不是默认」的读数，
 * 用户改过几项之后想确认自己有没有改回去，看这一行就够了。
 */
internal fun MainActivity.restoreKeysLabel(): String =
    if (library.keyMap.isDefault) "恢复默认按键  ·  当前为默认" else "恢复默认按键  ·  已改 ${library.keyMap.customized} 项"

/**
 * 选一个键要绑哪个动作。
 *
 * 用全站统一的 [TvTools.choose] 列表弹窗；每一项的说明取自 [PlayerKeyAction.hint]
 * （「档位名 · 做什么」）——「控制条」和「播放设置」光看名字分不出差别，说明文字才说得清。
 * 标题里带上当前动作，用户不必猜现在绑的是什么。选完立刻落盘并回调一次，由设置页刷新那一格。
 */
internal fun MainActivity.showKeyActionPicker(key: PlayerKey, anchor: View, onChanged: () -> Unit = {}) {
    val options = PlayerKeyAction.entries
    tvTools.choose("${key.label}的动作 · 当前 ${library.keyMap.action(key).label}",
        options.map { it.hint }, anchor, { index ->
            library.keyMap = library.keyMap.customize(key, options[index])
            onChanged()
        })
}

/**
 * 播放界面里这个键当前绑定的动作；不是那六个可自定义的键就返回 null。
 *
 * 返回键与媒体键（播放 / 暂停 / 上一集 / 下一集 / 快进 / 快退）**不在自定义范围内**：
 * 前者任何绑定下都必须是「收起浮层 / 退出播放 / 回主界面」的出口，后者是遥控器 / 电视
 * 自己的媒体键，语义由系统约定，不该由本应用改写。因此这里只认这六个。
 */
internal fun MainActivity.playerKeyAction(keyCode: Int): PlayerKeyAction? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> library.keyMap.action(PlayerKey.UP)
    KeyEvent.KEYCODE_DPAD_DOWN -> library.keyMap.action(PlayerKey.DOWN)
    KeyEvent.KEYCODE_DPAD_LEFT -> library.keyMap.action(PlayerKey.LEFT)
    KeyEvent.KEYCODE_DPAD_RIGHT -> library.keyMap.action(PlayerKey.RIGHT)
    KeyEvent.KEYCODE_MENU -> library.keyMap.action(PlayerKey.MENU)
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> library.keyMap.action(PlayerKey.OK)
    else -> null
}

/**
 * 把「这一下按键」交给当前绑定去做；返回 true 表示已经消化掉。
 *
 * 只处理按下（[KeyEvent.ACTION_DOWN]），且除快进 / 快退外都只在**第一次按下**（`repeatCount==0`）
 * 触发一次：长按方向键时系统会连发 DOWN，连开好几次菜单 / 反复切集都不是用户想要的。
 * 快进 / 快退保留连按（`repeatCount` 直接交给 [seek]）——那里连按本来就是加速。
 *
 * 「无」这一档什么都不做但仍然吃掉这一下：播放界面上没有可聚焦的按钮
 * （控制条上的图标与状态项都不抢焦点），把键放给焦点系统也只会在原地打转。
 * 返回键不在这里，用户任何时候都退得出播放界面。
 */
internal fun MainActivity.dispatchPlayerKey(action: PlayerKeyAction, event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN) when (action) {
        PlayerKeyAction.SETTINGS -> if (event.repeatCount == 0) showPanel()
        PlayerKeyAction.EPISODES -> if (event.repeatCount == 0) showEpisodePanel(hud)
        PlayerKeyAction.HUD -> if (event.repeatCount == 0) {
            if (hud.visibility == View.VISIBLE) hidePlaybackOverlay() else showHud()
        }
        PlayerKeyAction.PLAY_PAUSE -> if (event.repeatCount == 0) togglePlayback()
        PlayerKeyAction.SEEK_BACK -> seek(-1, event.repeatCount)
        PlayerKeyAction.SEEK_FORWARD -> seek(1, event.repeatCount)
        PlayerKeyAction.PREVIOUS -> if (event.repeatCount == 0) skipEpisode(-1)
        PlayerKeyAction.NEXT -> if (event.repeatCount == 0) skipEpisode(1)
        PlayerKeyAction.NONE -> Unit
    }
    return true
}
