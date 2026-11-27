// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
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
    private var generation = 0
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
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(TvStyle.background)
            addView(label(title))
        }
        val scroll = ScrollView(activity).apply { addView(body) }
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(scroll)
            setOnDismissListener { generation++ }
            show()
            window?.setLayout(minOf(dp(620), activity.resources.displayMetrics.widthPixels - dp(40)),
                (activity.resources.displayMetrics.heightPixels * .85f).toInt())
        }
        return body
    }
    private fun <T> run(action: () -> T, success: (T) -> Unit) {
        val ticket = generation
        worker.execute {
            val result = runCatching(action)
            main.post {
                if (ticket != generation || activity.isFinishing || activity.isDestroyed) return@post
                result.fold(success) { error ->
                    Toast.makeText(activity, error.message ?: "操作失败，请重试", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun input(title: String, recovery: Boolean = false, done: (String) -> Unit) {
        val body = panel(title)
        val value = StringBuilder()
        val display = label("")
        body.addView(display)
        val chars = if (recovery) "1234567890ABCDEF" else "1234567890"
        fun refresh() { display.text = if (recovery) value.toString().chunked(4).joinToString("-") else "●".repeat(value.length) }
        val grid = GridLayout(activity).apply { columnCount = if (recovery) 4 else 3 }
        chars.forEach { digit -> grid.addView(button(digit.toString()) {
            if (value.length < if (recovery) 32 else 6) { value.append(digit); refresh() }
        }, GridLayout.LayoutParams().apply { width = dp(92); height = dp(48) }) }
        body.addView(grid)
        body.addView(button("删除") { if (value.isNotEmpty()) { value.deleteCharAt(value.lastIndex); refresh() } })
        body.addView(button("确认") {
            if (value.length == if (recovery) 32 else 6) {
                val entered = value.toString(); value.clear(); refresh(); done(entered)
            } else Toast.makeText(activity, if (recovery) "请输入完整恢复码" else "请输入 6 位数字密码", Toast.LENGTH_SHORT).show()
        })
        body.addView(button("取消") { close() })
        dialog?.setOnKeyListener { _, key, event ->
            if (!recovery && key in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9) {
                if (event.action == android.view.KeyEvent.ACTION_UP && value.length < 6) {
                    value.append(('0'.code + key - android.view.KeyEvent.KEYCODE_0).toChar()); refresh()
                }
                true
            } else false
        }
        grid.getChildAt(0).requestFocus()
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
            newPin("设置 6 位家长密码") { pin, confirm -> run({ store.setup(pin, confirm) }, ::recoveryNotice) }
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
                run({ store.changePin(old, pin, confirm) }, ::recoveryNotice)
            } }
        })
        body.addView(button("忘记密码：使用恢复码") {
            input("输入保存的恢复码", recovery = true) { code -> newPin("设置新家长密码") { pin, confirm ->
                run({ store.recover(code, pin, confirm) }, ::recoveryNotice)
            } }
        })
        body.addView(button("返回") { close() })
    }
    private fun authenticate(done: () -> Unit) {
        input("输入家长密码") { pin -> run({ store.unlock(pin) }) { token = it; done() } }
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
        store.entries().forEach { series ->
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
