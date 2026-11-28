// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.*
import com.hongguotv.core.Series
import java.util.concurrent.Executors

/** Small remote-first parent gate. No network or playback ownership. */
internal class ChildModeUi(private val activity: Activity, private val store: ChildModeStore) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var dialog: Dialog? = null
    private var token = ""
    @Volatile private var generation = 0
    private var busy = false
    private var lockInput: ((Boolean) -> Unit)? = null
    private var resetInput: (() -> Unit)? = null
    val showing get() = dialog?.isShowing == true
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun label(text: String) = TextView(activity).apply {
        this.text = text; textSize = 18f; setTextColor(TvStyle.text); setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private fun button(text: String, action: () -> Unit) = Button(activity).apply {
        this.text = text; isFocusable = true; minHeight = dp(48)
        setTextColor(TvStyle.text)
        setOnFocusChangeListener { _, focused -> background = TvStyle.card(activity, focused) }
        background = TvStyle.card(activity, false)
        setOnClickListener { action() }
    }
    private fun panel(title: String): LinearLayout {
        dialog?.dismiss()
        busy = false
        lockInput = null
        resetInput = null
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(TvStyle.background)
            addView(label(title))
        }
        val scroll = ScrollView(activity).apply { addView(body) }
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(scroll)
            setOnDismissListener { generation++; busy = false; lockInput = null; resetInput = null }
            show()
            window?.setLayout(minOf(dp(620), activity.resources.displayMetrics.widthPixels - dp(40)),
                (activity.resources.displayMetrics.heightPixels * .85f).toInt())
        }
        return body
    }
    private fun <T> run(action: () -> T, discard: (T) -> Unit = {}, success: (T) -> Unit) {
        if (busy) return
        busy = true
        lockInput?.invoke(true)
        val ticket = generation
        worker.execute {
            val result = runCatching(action)
            main.post {
                if (ticket != generation || activity.isFinishing || activity.isDestroyed) {
                    result.getOrNull()?.let(discard)
                    return@post
                }
                busy = false
                lockInput?.invoke(false)
                result.fold(success) { error ->
                    resetInput?.invoke()
                    Toast.makeText(activity, error.message ?: "操作失败，请重试", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun input(title: String, recovery: Boolean = false, done: (String) -> Unit) {
        val body = panel(title)
        val value = StringBuilder()
        val display = label("").apply { contentDescription = "已输入 0 位" }
        val status = label("")
        body.addView(display)
        val chars = if (recovery) "1234567890ABCDEF" else "1234567890"
        val limit = if (recovery) 32 else 6
        fun refresh() {
            display.text = if (recovery) value.toString().chunked(4).joinToString("-") else "●".repeat(value.length)
            display.contentDescription = "已输入 ${value.length} 位"
        }
        fun append(char: Char) { if (!busy && value.length < limit) { value.append(char); refresh() } }
        fun delete() { if (!busy && value.isNotEmpty()) { value.deleteCharAt(value.lastIndex); refresh() } }
        val grid = GridLayout(activity).apply { columnCount = if (recovery) 4 else 3 }
        val inputs = mutableListOf<View>()
        fun cell(view: View) = grid.addView(view, GridLayout.LayoutParams().apply { width = dp(92); height = dp(48) })
        chars.forEach { digit ->
            if (!recovery && digit == '0') cell(View(activity))
            val key = button(digit.toString()) { append(digit) }
            inputs += key
            cell(key)
        }
        val erase = ImageButton(activity).apply {
            contentDescription = "删除一位"
            setImageDrawable(TvGlyph(15, dp(28), TvStyle.text))
            background = TvStyle.card(activity, false)
            isFocusable = true
            setOnFocusChangeListener { _, focus -> background = TvStyle.card(activity, focus) }
            setOnClickListener { delete() }
        }
        inputs += erase
        cell(erase)
        body.addView(grid)
        val confirm = button("确认") {
            if (!busy) {
                if (value.length == limit) done(value.toString())
                else Toast.makeText(activity, if (recovery) "请输入完整恢复码" else "请输入 6 位数字密码", Toast.LENGTH_SHORT).show()
            }
        }
        inputs += confirm
        body.addView(status)
        body.addView(confirm)
        body.addView(button("取消") { close() }.apply { tag = "parent-cancel" })
        lockInput = { locked ->
            inputs.forEach { it.isEnabled = !locked; it.alpha = if (locked) .45f else 1f }
            status.text = if (locked) "正在验证，请稍候…" else ""
        }
        resetInput = { value.clear(); refresh(); inputs.first().requestFocus() }
        dialog?.setOnKeyListener { _, key, event ->
            val digit = when (key) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0'.code + key - KeyEvent.KEYCODE_0).toChar()
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> ('0'.code + key - KeyEvent.KEYCODE_NUMPAD_0).toChar()
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_F -> if (recovery) ('A'.code + key - KeyEvent.KEYCODE_A).toChar() else null
                else -> null
            }
            when {
                digit != null -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) append(digit)
                    true
                }
                key == KeyEvent.KEYCODE_DEL || key == KeyEvent.KEYCODE_FORWARD_DEL -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) delete()
                    true
                }
                busy && dialog?.currentFocus?.tag != "parent-cancel" && key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) -> true
                else -> false
            }
        }
        inputs.first().requestFocus()
    }
    private fun newPin(title: String, done: (String, String) -> Unit) {
        input(title) { pin -> input("再次输入新密码") { confirm -> done(pin, confirm) } }
    }
    private fun recoveryNotice(code: String) {
        val body = panel("请保存家长恢复码")
        body.addView(label(code))
        body.addView(label("恢复码只展示一次。忘记密码时可用它重设密码；请勿交给孩子。"))
        body.addView(button("已保存，返回家长入口") { show() }.apply { requestFocus() })
    }
    fun show() {
        if (store.broken) {
            panel("儿童模式配置损坏").addView(label("已限制播放。请保留应用数据并联系维护者，不能自动解除儿童模式。"))
            return
        }
        if (!store.configured) {
            newPin("设置 6 位家长密码") { pin, confirm -> run({ store.setup(pin, confirm) }, success = ::recoveryNotice) }
            return
        }
        val body = panel("家长入口")
        body.addView(button("管理儿童片单") { authenticate { manage() } }.apply { requestFocus() })
        body.addView(button(if (store.enabled) "退出儿童模式" else "开启儿童模式") {
            if (store.enabled) input("退出儿童模式：输入家长密码") { pin -> run({ store.disable(pin) }) { close() } }
            else authenticate {
                run({ store.enable(token) }) { close() }
            }
        })
        body.addView(button("修改家长密码") {
            input("输入原家长密码") { old -> newPin("输入新家长密码") { pin, confirm ->
                run({ store.changePin(old, pin, confirm) }, success = ::recoveryNotice)
            } }
        })
        body.addView(button("忘记密码：使用恢复码") {
            input("输入保存的恢复码", recovery = true) { code -> newPin("设置新家长密码") { pin, confirm ->
                run({ store.recover(code, pin, confirm) }, success = ::recoveryNotice)
            } }
        })
        body.addView(button("返回") { close() })
    }
    private fun authenticate(done: () -> Unit) {
        input("输入家长密码") { pin -> run({ store.unlock(pin) }, discard = { store.lock(it) }) { token = it; done() } }
    }
    fun add(series: Series) {
        if (!store.configured) { show(); return }
        authenticate {
            val body = panel("加入儿童片单：${series.title}")
            body.addView(label("允许该作品全部集数，包括后续更新。"))
            body.addView(button("确认加入") { run({ store.add(token, series) }) {
                close(); Toast.makeText(activity, "已加入儿童片单", Toast.LENGTH_SHORT).show()
            } }.apply { requestFocus() })
            body.addView(button("取消") { close() })
        }
    }
    private fun manage() {
        if (!store.authorized(token)) { show(); return }
        val body = panel("儿童片单 · ${store.entries().size} 部")
        body.addView(label("新增作品：使用手机控制台，或在普通模式作品详情中添加。移除正在播放的作品会停止播放。"))
        com.hongguotv.core.SeriesDisplayOrder.byName(store.entries()).forEach { series ->
            body.addView(button("移除 · ${series.title}") {
                val confirm = panel("移除《${series.title}》？")
                confirm.addView(button("确认移除") { run({ store.remove(token, series.id) }) { manage() } }.apply { requestFocus() })
                confirm.addView(button("取消") { manage() })
            })
        }
        body.addView(button("返回") { show() }.apply { if (store.entries().isEmpty()) requestFocus() })
    }
    fun close() {
        generation++
        store.lock(token); token = ""
        dialog?.dismiss(); dialog = null
    }
    fun destroy() { close(); worker.shutdownNow() }
}
