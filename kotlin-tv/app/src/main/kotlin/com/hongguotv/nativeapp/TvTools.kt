// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.BackupData
import com.hongguotv.core.EpisodeTarget
import com.hongguotv.core.LibraryTransferServer
import com.hongguotv.core.PhoneSearchServer
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Native modal controls kept separate from catalog/player navigation. */
class TvTools(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog? = null
    val showing: Boolean
        get() = dialog?.isShowing == true

    private var phone: java.io.Closeable? = null

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 17f) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

    private fun column() =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }

    private fun open(next: AlertDialog, anchor: View?) {
        val previous = dialog
        dialog = null
        phone?.close()
        phone = null
        previous?.dismiss()
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) {
                phone?.close()
                phone = null
                dialog = null
                if (anchor?.isAttachedToWindow == true) anchor.requestFocus()
            }
        }
        next.show()
        next.window?.setBackgroundDrawable(TvVisual.panel(activity, modal = true))
        next.window?.setDimAmount(.6f)
    }

    fun close() {
        dialog?.dismiss()
        phone?.close()
        phone = null
    }

    fun destroy() {
        close()
        worker.shutdownNow()
    }

    fun info(title: String, message: String, anchor: View?) =
        open(
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .create(),
            anchor,
        )

    fun confirm(title: String, message: String, anchor: View?, action: () -> Unit) {
        val next =
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认") { _, _ -> action() }
                .create()
        open(next, anchor)
        next.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    fun choose(
        title: String,
        labels: List<String>,
        anchor: View?,
        onChoice: (Int) -> Unit,
        extra: String? = null,
        compact: Boolean = false,
        selected: Int = -1,
        onExtra: () -> Unit = {},
    ) {
        require(labels.isNotEmpty())
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = TvVisual.panel(activity, modal = true)
        }
        content.addView(label(title, 21f).apply {
            setTextColor(TvStyle.text)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), dp(14))
        })
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
        }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll)
        val choices = mutableListOf<View>()
        lateinit var next: AlertDialog
        fun choice(value: String, checked: Boolean, action: () -> Unit) {
            val row = LinearLayout(activity).apply {
                id = View.generateViewId()
                tag = "choice-${choices.size}"
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                minimumHeight = dp(48)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                contentDescription = value + if (checked) "，当前已选" else ""
            }
            val name = label(value, 16f).apply { setPadding(0, 0, dp(8), 0) }
            val marker = label(if (checked) "✓" else "", 18f).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0, 0, 0, 0)
            }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(marker, LinearLayout.LayoutParams(dp(26), -2))
            fun paint(focused: Boolean) {
                row.background = TvVisual.row(activity, focused, checked)
                name.setTextColor(TvVisual.primary)
                marker.setTextColor(TvVisual.accent)
            }
            paint(false)
            row.setOnFocusChangeListener { _, focused -> paint(focused) }
            row.setOnClickListener { next.dismiss(); action() }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            choices += row
        }
        labels.forEachIndexed { index, value -> choice(value, index == selected) { onChoice(index) } }
        if (extra != null) choice(extra, false, onExtra)
        choices.forEachIndexed { index, view ->
            view.nextFocusUpId = choices[maxOf(0, index - 1)].id
            view.nextFocusDownId = choices[minOf(choices.lastIndex, index + 1)].id
            view.nextFocusLeftId = view.id
            view.nextFocusRightId = view.id
        }
        content.addView(label("确定选择 · 返回取消", 12f).apply {
            setTextColor(TvStyle.muted)
            setPadding(dp(8), dp(8), dp(8), 0)
        })
        next = AlertDialog.Builder(activity).create()
        next.setView(content, 0, 0, 0, 0)
        open(next, anchor)
        next.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        next.window?.setDimAmount(.6f)
        val width = minOf(dp(if (compact) 440 else 480), (activity.resources.displayMetrics.widthPixels * .82f).toInt())
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = minOf(content.measuredHeight, (activity.resources.displayMetrics.heightPixels * .84f).toInt())
        scroll.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        next.window?.setLayout(width, height)
        val target = choices[selected.takeIf { it in labels.indices } ?: 0]
        target.requestFocus()
        target.post { if (next.isShowing) scroll.requestChildFocus(list, target) }
    }

    fun chooseGrid(
        title: String,
        labels: List<String>,
        selected: Int,
        anchor: View?,
        onChoice: (Int) -> Unit,
    ) {
        val content = column()
        val scroll = android.widget.ScrollView(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        content.addView(scroll)
        val choices = mutableListOf<android.widget.TextView>()
        lateinit var next: AlertDialog
        labels.chunked(4).forEachIndexed { rowIndex, values ->
            val row = LinearLayout(activity)
            values.forEachIndexed { col, value ->
                val index = rowIndex * 4 + col
                val choice =
                    android.widget.TextView(activity).apply {
                        id = View.generateViewId()
                        text = value
                        textSize = 15f
                        gravity = android.view.Gravity.CENTER
                        isFocusable = true
                        isClickable = true
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        contentDescription = value
                        fun paint(focused: Boolean) {
                            setTextColor(if (index == selected) TvStyle.accent else TvStyle.text)
                            background = TvStyle.card(activity, focused, index == selected)
                        }
                        paint(false)
                        setOnFocusChangeListener { _, focused -> paint(focused) }
                        setOnClickListener {
                            next.dismiss()
                            onChoice(index)
                        }
                    }
                row.addView(
                    choice,
                    LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    },
                )
                choices += choice
            }
            repeat(4 - values.size) {
                row.addView(View(activity), LinearLayout.LayoutParams(0, dp(46), 1f))
            }
            list.addView(row)
        }
        choices.forEachIndexed { i, v ->
            v.nextFocusLeftId = if (i % 4 == 0) v.id else choices[i - 1].id
            v.nextFocusRightId =
                if (i % 4 == 3 || i == choices.lastIndex) v.id else choices[i + 1].id
            v.nextFocusUpId = choices.getOrNull(i - 4)?.id ?: v.id
            v.nextFocusDownId =
                choices.getOrNull(i + 4)?.id
                    ?: if (i / 4 < choices.lastIndex / 4) choices.last().id else v.id
        }
        next =
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(content)
                .setNegativeButton("返回", null)
                .create()
        open(next, anchor)
        next.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * .85f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        choices.getOrNull(selected.coerceAtLeast(0))?.requestFocus()
    }

    fun episodePicker(count: Int, current: Int, anchor: View?, select: (Int) -> Unit) {
        val content = column()
        var digits = (current + 1).toString()
        var replace = true
        val value = label(digits, 28f).apply { gravity = Gravity.CENTER }
        content.addView(value)
        val error = label("定位到该集，再按确认播放", 14f)
        content.addView(error)
        fun digit(key: String) {
            digits = if (replace) key else (digits + key).take(6)
            replace = false
            value.text = digits
            error.text = "定位到该集，再按确认播放"
        }
        fun erase() {
            digits = if (replace) "" else digits.dropLast(1)
            replace = false
            value.text = digits.ifEmpty { "—" }
        }
        val buttons = mutableListOf<Button>()
        listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("清空", "0", "退格"),
            )
            .forEach { keys ->
                val row = LinearLayout(activity)
                keys.forEach { key ->
                    val button =
                        Button(activity).apply {
                            id = View.generateViewId()
                            text = key
                            minHeight = dp(42)
                            backgroundTintList =
                                android.content.res.ColorStateList(
                                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                                    intArrayOf(TvStyle.accent, TvStyle.surface),
                                )
                            setOnClickListener {
                                when (key) {
                                    "清空" -> {
                                        digits = ""
                                        replace = false
                                        value.text = "—"
                                    }
                                    "退格" -> erase()
                                    else -> digit(key)
                                }
                            }
                        }
                    buttons += button
                    row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
                }
                content.addView(row)
            }
        val next =
            AlertDialog.Builder(activity)
                .setTitle("跳转集数 · 1—$count")
                .setView(ScrollView(activity).apply { addView(content) })
                .setNegativeButton("取消", null)
                .setPositiveButton("定位", null)
                .create()
        next.setOnKeyListener { _, key, event ->
            when {
                key in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    if (event.action == KeyEvent.ACTION_DOWN)
                        digit((key - KeyEvent.KEYCODE_0).toString())
                    true
                }
                key == KeyEvent.KEYCODE_DEL -> {
                    if (event.action == KeyEvent.ACTION_DOWN) erase()
                    true
                }
                else -> false
            }
        }
        open(next, anchor)
        val locate = next.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel = next.getButton(AlertDialog.BUTTON_NEGATIVE)
        buttons.forEachIndexed { i, button ->
            button.nextFocusLeftId = buttons[if (i % 3 == 0) i else i - 1].id
            button.nextFocusRightId = buttons[if (i % 3 == 2) i else i + 1].id
            button.nextFocusUpId = buttons[if (i < 3) i else i - 3].id
            button.nextFocusDownId = if (i < 9) buttons[i + 3].id else locate.id
        }
        locate.nextFocusUpId = buttons[10].id
        cancel.nextFocusUpId = buttons[9].id
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val target = EpisodeTarget.parse(digits, count)
            if (target == null) error.text = "请输入 1—$count 之间的集数"
            else {
                next.dismiss()
                select(target)
            }
        }
        buttons.first().requestFocus()
    }

    private fun qr(url: String) =
        ImageView(activity).apply {
            val bits = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 360, 360)
            val pixels =
                IntArray(360 * 360) { i ->
                    if (bits[i % 360, i / 360]) Color.BLACK else Color.WHITE
                }
            setImageBitmap(Bitmap.createBitmap(pixels, 360, 360, Bitmap.Config.ARGB_8888))
            contentDescription = "二维码"
        }

    fun phoneInput(
        anchor: View?,
        inputTitle: String = "手机输入剧名",
        pageTitle: String = "在电视上搜索",
        pagePrompt: String = "输入剧名，电视会搜索全部短剧和漫剧。",
        inputPlaceholder: String = "输入剧名或关键词",
        extraFieldLabel: String? = null,
        extraFieldPlaceholder: String? = null,
        submit: (String?, String?) -> Unit,
    ) {
        val addresses =
            runCatching {
                    LanInputAddresses.find(
                        activity.getSystemService(ConnectivityManager::class.java)
                    )
                }
                .getOrDefault(emptyList())
        if (addresses.isEmpty()) {
            info("手机输入", "未找到可用的 Wi-Fi 或网线局域网地址。请检查电视网络；手机和电视需要在可互通的局域网内。", anchor)
            return
        }
        showPhoneInput(
            anchor, addresses, 0,
            inputTitle, pageTitle, pagePrompt, inputPlaceholder,
            extraFieldLabel, extraFieldPlaceholder, submit,
        )
    }

    private fun showPhoneInput(
        anchor: View?,
        addresses: List<LanInputAddress>,
        index: Int,
        inputTitle: String,
        pageTitle: String,
        pagePrompt: String,
        inputPlaceholder: String,
        extraFieldLabel: String?,
        extraFieldPlaceholder: String?,
        submit: (String?, String?) -> Unit,
    ) {
        val selected = addresses[index]
        val content = column()
        val status = label("正在开启手机输入…")
        content.addView(status)
        val builder =
            AlertDialog.Builder(activity)
                .setTitle(inputTitle)
                .setView(content)
                .setNegativeButton("关闭", null)
                .setPositiveButton("连接帮助", null)
        if (addresses.size > 1) builder.setNeutralButton("切换地址", null)
        val next = builder.create()
        open(next, anchor)
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val message =
                "请依次检查：\n1. 用手机浏览器打开二维码地址；扫码软件内打不开时，选择在浏览器打开。\n2. 手机不要只用移动数据；手机和电视需处于可互通的局域网。\n3. 访客 Wi-Fi、路由器设备隔离、VPN 可能阻断连接。\n4. 多个网络可尝试切换地址；电脑模拟器的内部地址通常无法被手机访问。\n\n返回时会重新生成二维码，旧地址随窗口关闭失效。"
            val help =
                AlertDialog.Builder(activity)
                    .setTitle("手机连接不上")
                    .setMessage(message)
                    .setPositiveButton("重新生成二维码") { _, _ ->
                        showPhoneInput(
                            anchor, addresses, index,
                            inputTitle, pageTitle, pagePrompt, inputPlaceholder,
                            extraFieldLabel, extraFieldPlaceholder, submit,
                        )
                    }
                    .setNegativeButton("关闭", null)
                    .create()
            open(help, anchor)
        }
        if (addresses.size > 1)
            next.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                choose(
                    "选择电视网络地址",
                    addresses.map { "${it.label} · ${it.address}" },
                    anchor,
                    { chosen ->
                        showPhoneInput(
                            anchor, addresses, chosen,
                            inputTitle, pageTitle, pagePrompt, inputPlaceholder,
                            extraFieldLabel, extraFieldPlaceholder, submit,
                        )
                    },
                )
            }
                worker.execute {
            try {
                val session =
                    PhoneSearchServer(
                        selected.address,
                        { query, extra ->
                            main.post {
                                if (dialog === next) {
                                    next.dismiss()
                                    submit(query, extra)
                                }
                            }
                        },
                        { main.post { if (dialog === next) status.text = "二维码已过期，请关闭后重新打开。" } },
                        onConnected = {
                            main.post { if (dialog === next) status.text = "手机已连接，请在手机上输入并发送。" }
                        },
                        pageTitle = pageTitle,
                        pagePrompt = pagePrompt,
                        inputPlaceholder = inputPlaceholder,
                        extraFieldLabel = extraFieldLabel,
                        extraFieldPlaceholder = extraFieldPlaceholder,
                    )
                main.post {
                    if (dialog !== next) {
                        session.close()
                        return@post
                    }
                    phone = session
                    content.removeAllViews()
                    val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url), LinearLayout.LayoutParams(dp(185), dp(185)))
                    val instructions = column()
                    instructions.addView(
                        label(
                            "${selected.label} · ${selected.address}\n手机扫码后，用浏览器打开并提交。\n需要同一局域网；二维码有效 5 分钟。",
                            16f,
                        )
                    )
                    instructions.addView(label(session.url, 12f))
                    row.addView(instructions, LinearLayout.LayoutParams(0, -2, 1f))
                    content.addView(row)
                    status.text = "等待手机连接…"
                    content.addView(status)
                }
            } catch (_: Exception) {
                main.post { if (dialog === next) status.text = "此地址无法开启手机输入，请关闭重试，或切换其他网络地址。" }
            }
        }
    }

    fun libraryTransfer(backup: String, anchor: View?, restore: (BackupData, Boolean) -> Unit) {
        val connectivity = activity.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        val capabilities = connectivity.getNetworkCapabilities(network)
        val address =
            connectivity
                .getLinkProperties(network)
                ?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        if (
            address == null ||
                capabilities == null ||
                !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        ) {
            info("备份与恢复", "请让电视和手机连接同一局域网后重试。", anchor)
            return
        }
        val content = column()
        val status = label("正在生成二维码…")
        content.addView(status)
        val next =
            AlertDialog.Builder(activity)
                .setTitle("手机备份与恢复")
                .setView(ScrollView(activity).apply { addView(content) })
                .setNegativeButton("关闭", null)
                .create()
        open(next, anchor)
        worker.execute {
            try {
                val session =
                    LibraryTransferServer(
                        address,
                        backup,
                        { data ->
                            main.post {
                                if (dialog !== next) return@post
                                next.dismiss()
                                val preview = column()
                                preview.addView(
                                    label(
                                        "备份含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.searches.size} 条搜索、${data.later.size} 部稍后看、${data.hidden.size} 部已隐藏推荐。\n\n收藏合并，观看位置以较新的记录为准；已有收藏优先保留（最多 500 部），历史保留最近 200 条。",
                                        16f,
                                    )
                                )
                                val settings =
                                    CheckBox(activity).apply {
                                        text = "同时恢复播放设置和内容分类"
                                        isChecked = false
                                    }
                                preview.addView(settings)
                                val confirm =
                                    AlertDialog.Builder(activity)
                                        .setTitle("确认恢复本机记录")
                                        .setView(ScrollView(activity).apply { addView(preview) })
                                        .setNegativeButton("取消", null)
                                        .setPositiveButton("合并恢复") { _, _ ->
                                            restore(data, settings.isChecked)
                                        }
                                        .create()
                                open(confirm, anchor)
                                confirm.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
                            }
                        },
                        {
                            main.post {
                                if (dialog === next) {
                                    content.removeAllViews()
                                    content.addView(label("二维码已过期，请关闭后重新打开。"))
                                }
                            }
                        },
                    )
                main.post {
                    if (dialog !== next) {
                        session.close()
                        return@post
                    }
                    phone = session
                    content.removeAllViews()
                    val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url), LinearLayout.LayoutParams(dp(185), dp(185)))
                    val instructions = column()
                    instructions.addView(
                        label("手机扫码，用浏览器下载备份或选择备份文件恢复。\n仅限同一局域网；关闭即失效，最长 5 分钟。恢复需在电视确认。", 16f)
                    )
                    instructions.addView(label(session.url, 12f))
                    row.addView(instructions, LinearLayout.LayoutParams(0, -2, 1f))
                    content.addView(row)
                }
            } catch (_: Exception) {
                main.post { if (dialog === next) status.text = "无法开启备份传输，请检查网络后重试。" }
            }
        }
    }
}
