// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 账户界面与状态协调：连接登录、云端记录及页面刷新回调。

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.hongguotv.core.*
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials stay in an Android Keystore encrypted partition, outside library exports. */
private class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_account_secure", Context.MODE_PRIVATE)
    private val alias = "hongguotv.cloud.account.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun read(): AccountCredentials? {
        val raw = prefs.getString("session", null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return AccountCredentials.fromJson(
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        )
    }

    fun save(credentials: AccountCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val raw = cipher.iv + cipher.doFinal(credentials.toJson().toByteArray())
        check(
            prefs.edit().putString("session", Base64.encodeToString(raw, Base64.NO_WRAP)).commit()
        ) {
            "保存登录态失败"
        }
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "清除登录态失败" }
    }
}

internal data class FavoriteState(
    val rows: List<CloudFavorite> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val message: String = "尚未读取账户收藏",
)

internal data class ReservationState(
    val rows: List<CloudReservation> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val message: String = "尚未读取预约记录",
)

/**
 * Cloud history is kept separate from guest records; only explicit playback creates local history.
 */
class AccountController(private val activity: Activity, private val play: (CloudProgress) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val http =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val client = CloudAccountClient(http)
    // passport 短信登录沿用默认重定向策略（与网页登录的 no-redirect 客户端分开）。
    private val phoneHttp =
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    private val store = AccountStore(activity)
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var accountRestored = false
    private var accountRestoreFailed = false
    private var credentials: AccountCredentials? = null
    private var profile: CloudProfile? = null
    private var history = emptyList<CloudProgress>()
    private var historyLoaded = false
    private val acceptedReservations = mutableSetOf<String>()
    private var favorites = FavoriteState()
    private val unconfirmedFavorites = mutableSetOf<String>()
    private var reservations = emptyMap<Boolean, ReservationState>()
    private var status = "未登录"
    private var busy = false
    private var lastUpload = 0L
    private var lastEpisode = ""
    private var pending: Pair<CloudProgress, Int>? = null
    private var lastProgress: Pair<CloudProgress, Int>? = null
    private var draining = false
    private var dialog: AlertDialog? = null
    var onHistoryChanged: (() -> Unit)? = null

    internal fun recommendationCredentials() = credentials?.takeIf { profile != null }

    internal val recommendationLoading
        get() = !accountRestored || (credentials != null && profile == null && busy)

    // 未确认身份时不能让临时 guest 清空已保存的账号推荐游标。
    internal fun recommendationStartupCache(): FeedStartupCache? =
        if (accountRestored && !accountRestoreFailed && (credentials == null || profile != null))
            FeedStartupCache(activity) else null

    internal val recommendationIdentity
        get() = "$generation:${profile?.id.orEmpty()}:$recommendationLoading:${accountRestored && !accountRestoreFailed}"

    val signedIn
        get() = credentials != null

    val accountLabel
        get() = profile?.nickname ?: if (signedIn) "红果账号" else "未登录"

    val historyStatus
        get() = status

    val historyLoading
        get() = busy

    val historyReady
        get() = signedIn && historyLoaded

    val historyScope
        get() = generation

    fun historyRows(): List<CloudProgress> = if (signedIn) history.toList() else emptyList()

    fun refreshHistory() {
        refresh(false)
    }

    internal fun reservationState(online: Boolean): ReservationState =
        if (signedIn) reservations[online] ?: ReservationState()
        else ReservationState(message = "未登录")

    fun reservationLabel(work: RelatedWork): String =
        when {
            !signedIn -> "登录后预约"
            reservationState(false).rows.any {
                it.series.id == work.series.id || it.series.id == "reservation:${work.series.id}"
            } -> "已预约"
            work.series.id in acceptedReservations -> "刷新预约状态"
            else -> "预约"
        }

    fun reserve(work: RelatedWork, done: (String) -> Unit) {
        val account =
            credentials
                ?: run {
                    show()
                    return
                }
        if (busy) {
            toast("账号操作进行中，请稍候")
            return
        }
        if (reservationLabel(work) == "已预约") {
            done("该作品已在当前账号的预约列表中")
            return
        }
        if (work.series.id in acceptedReservations) {
            refreshReservations()
            done("正在刷新预约列表，请稍后核对；未显示前暂不能确认预约生效。")
            return
        }
        task(
            { client.reserve(account, work) },
            {
                acceptedReservations += work.series.id
                changed()
                done("红果已接受预约请求，尚未在账号列表确认。请刷新预约状态，或在手机红果核对是否新增。")
            },
            { done("预约未完成：${safeError(it)}") },
        )
    }

    internal fun favoriteState(): FavoriteState =
        if (signedIn) favorites else FavoriteState(message = "未登录")

    fun accountFavorite(id: String) = signedIn && favorites.rows.any { it.series.id == id }

    fun favoriteNeedsRefresh(id: String) = id in unconfirmedFavorites

    fun favoriteActionLabel(id: String): String =
        when {
            !signedIn -> "登录后加入账户收藏"
            favorites.loading -> "账户收藏处理中…"
            favoriteNeedsRefresh(id) -> "刷新账户收藏状态"
            accountFavorite(id) -> "取消账户收藏"
            else -> "加入账户收藏"
        }

    fun changeFavorite(series: Series, add: Boolean, done: (String) -> Unit) {
        val account =
            credentials
                ?: run {
                    show()
                    return
                }
        if (closed || favorites.loading) {
            done("账户收藏正在处理，请稍候")
            return
        }
        if (favoriteNeedsRefresh(series.id)) {
            refreshFavorites()
            done("上次操作尚未确认，已刷新账户收藏，请核对后再操作。")
            return
        }
        val token = generation
        favorites = favorites.copy(loading = true, message = "正在核对并更新账户收藏…")
        changed()
        worker.execute {
            var attempted = false
            val result = runCatching {
                check(!closed && token == generation)
                val before = client.favorites(account)
                if (before.any { it.series.id == series.id } == add) before to false
                else {
                    check(!closed && token == generation)
                    attempted = true
                    client.setFavorite(account, series.id, add)
                    check(!closed && token == generation)
                    client.favorites(account) to true
                }
            }
            main.post {
                if (closed || token != generation) return@post
                val message =
                    result.fold(
                        { (rows, wrote) ->
                            val confirmed = rows.any { it.series.id == series.id } == add
                            if (confirmed) unconfirmedFavorites.remove(series.id)
                            else unconfirmedFavorites.add(series.id)
                            favorites =
                                FavoriteState(
                                    rows,
                                    loaded = true,
                                    message =
                                        if (confirmed) "已核对账户收藏，共读取 ${rows.size} 部"
                                        else "请求已提交，列表尚未确认，请刷新或在手机核对",
                                )
                            if (!confirmed) "请求已提交，但账户列表尚未确认生效。请刷新或在手机红果核对，不要连续重复提交。"
                            else if (wrote) if (add) "已加入账户收藏，并已在账户列表确认" else "已取消账户收藏，并已从本次读取的列表移除"
                            else if (add) "这部剧已在账户收藏中，无需重复添加" else "本次读取的账户列表中已没有这部剧，无需重复取消"
                        },
                        { error ->
                            if (attempted) unconfirmedFavorites.add(series.id)
                            favorites =
                                favorites.copy(
                                    loading = false,
                                    message =
                                        if (attempted) "收藏操作未确认，请刷新或在手机核对"
                                        else "读取失败：${safeError(error)}",
                                )
                            if (attempted) "收藏操作未确认：${safeError(error)}。请先刷新或在手机核对。"
                            else "未修改账户收藏：${safeError(error)}"
                        },
                    )
                changed()
                done(message)
            }
        }
    }

    fun refreshFavorites() {
        val account = credentials ?: return
        if (closed || favorites.loading) return
        val token = generation
        favorites = favorites.copy(loading = true, message = "正在读取账户收藏…")
        changed()
        worker.execute {
            if (closed || token != generation) return@execute
            val result = runCatching { client.favorites(account) }
            main.post {
                if (closed || token != generation) return@post
                favorites =
                    result.fold(
                        {
                            unconfirmedFavorites.clear()
                            FavoriteState(it, loaded = true, message = "已读取 ${it.size} 部账户收藏")
                        },
                        { favorites.copy(loading = false, message = "读取失败：${safeError(it)}") },
                    )
                changed()
            }
        }
    }

    fun refreshReservations() {
        val account = credentials ?: return
        if (closed || reservations.values.any { it.loading }) return
        val token = generation
        reservations =
            listOf(true, false).associateWith {
                reservationState(it).copy(loading = true, message = "正在读取预约记录…")
            }
        changed()
        // Independent results: failure in one category must not masquerade as an empty list.
        worker.execute {
            for (online in listOf(true, false)) {
                if (closed || token != generation) return@execute
                val result = runCatching { client.reservations(account, online) }
                main.post {
                    if (closed || token != generation) return@post
                    val before = reservationState(online)
                    reservations =
                        reservations +
                            (online to
                                result.fold(
                                    {
                                        ReservationState(
                                            it,
                                            loaded = true,
                                            message = "已读取 ${it.size} 部预约记录",
                                        )
                                    },
                                    {
                                        before.copy(
                                            loading = false,
                                            message = "读取失败：${safeError(it)}",
                                        )
                                    },
                                ))
                    changed()
                }
            }
        }
    }

    private fun changed() {
        onHistoryChanged?.invoke()
    }

    val showing
        get() = dialog?.isShowing == true

    init {
        val token = generation
        worker.execute {
            val saved = runCatching { store.read() }
            main.post {
                if (closed || token != generation) return@post
                accountRestored = true
                accountRestoreFailed = saved.isFailure
                credentials = saved.getOrNull()
                status =
                    if (credentials != null) "登录态已恢复，打开账号页面刷新云端记录"
                    else if (saved.isFailure) "登录态无法读取，请重新登录" else "未登录"
                if (credentials != null) refresh(false) else changed()
            }
        }
    }

    private fun toast(value: String) {
        if (!closed) Toast.makeText(activity, value, Toast.LENGTH_LONG).show()
    }

    private fun safeError(error: Throwable) =
        when (error) {
            is java.net.SocketTimeoutException -> "账号接口连接超时，请稍后重试"
            is java.net.UnknownHostException -> "无法连接账号接口，请检查网络"
            else ->
                error.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(150).ifBlank {
                    "账号操作失败"
                }
        }

    fun progress(id: String): CloudProgress? =
        if (credentials != null) history.firstOrNull { it.series.id == id } else null

    private fun showDialog(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = builder.create()
        dialog?.show()
    }

    fun show() {
        if (closed) return
        if (busy) {
            toast("账号操作进行中，请稍候")
            return
        }
        val signed = credentials != null
        val choices =
            if (signed) arrayOf("云端继续观看（最近 50 部）", "刷新账号和云端记录", "退出红果账号")
            else arrayOf("使用红果 App 扫码登录", "手机号 + 验证码登录")
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle(profile?.let { "红果账号 · ${it.nickname}" } ?: "红果账号（测试功能）")
                .setItems(choices) { _, index ->
                    if (!signed) {
                        if (index == 0) qrLogin() else phone()
                    } else
                        when (index) {
                            0 -> showHistory()
                            1 -> refresh(true)
                            2 -> logout()
                        }
                }
                .setNeutralButton("同步状态") { _, _ ->
                    showDialog(
                        AlertDialog.Builder(activity)
                            .setTitle("账号同步状态")
                            .setMessage(
                                "$status\n\n登录后，电视播放的进度会自动上传。手机记录可从「云端继续观看」打开。平台接口可能变化，首次使用请对照手机核对集数和时间。\n本机历史与云端记录分别保留；退出登录会停止上传。"
                            )
                            .setPositiveButton("关闭", null)
                    )
                }
                .setNegativeButton("关闭", null)
        )
    }

    private fun input(
        title: String,
        numeric: Boolean,
        error: String? = null,
        submit: (String) -> Unit,
    ) {
        val field =
            EditText(activity).apply {
                isSingleLine = true
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_PHONE
            }
        val builder =
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认", null)
        if (error != null) builder.setMessage(error)
        showDialog(builder)
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val value = field.text.toString().trim()
            if (value.isBlank()) return@setOnClickListener
            dialog?.dismiss()
            submit(value)
        }
    }

    /**
     * 手机号与验证码放在同一个对话框里：号码输入、「获取验证码」（发送后按钮倒数读秒）、
     * 验证码输入与登录按钮，流程与手机 App 一致，避免电视上两次弹窗来回切换。
     */
    private fun phone() {
        val context = activity
        val density = context.resources.displayMetrics.density
        fun px(value: Int) = (value * density).toInt()

        var country = "86"
        val countryButton =
            Button(context).apply {
                text = "+86 ▾"
                isAllCaps = false
            }
        val mobile =
            EditText(context).apply {
                hint = "手机号（大陆 11 位，境外不填前导 0）"
                inputType = InputType.TYPE_CLASS_PHONE
                isSingleLine = true
            }
        val mobileRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    countryButton,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    mobile,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        val code =
            EditText(context).apply {
                hint = "短信验证码"
                inputType = InputType.TYPE_CLASS_NUMBER
                isSingleLine = true
            }
        val send =
            Button(context).apply {
                text = "获取验证码"
                isAllCaps = false
            }
        val hint =
            TextView(context).apply {
                text = "先获取验证码，再输入短信里的数字"
                textSize = 13f
                setPadding(0, px(8), 0, 0)
            }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    code,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    send,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(24), px(12), px(24), 0)
                addView(mobileRow)
                addView(row)
                addView(hint)
            }

        var login: PhoneLogin? = null
        var countdown: Runnable? = null
        fun stopCountdown() {
            countdown?.let(main::removeCallbacks)
            countdown = null
        }
        fun startCountdown(seconds: Int) {
            stopCountdown()
            var left = seconds.coerceIn(1, 600)
            val tick =
                object : Runnable {
                    override fun run() {
                        if (dialog?.isShowing != true) return
                        send.isEnabled = left <= 0
                        send.text = if (left > 0) "${left}s 后重发" else "获取验证码"
                        if (left > 0) {
                            left--
                            main.postDelayed(this, 1000)
                        }
                    }
                }
            countdown = tick
            send.isEnabled = false
            send.text = "${left}s 后重发"
            left--
            main.postDelayed(tick, 1000)
        }

        dialog?.dismiss()
        dialog =
            AlertDialog.Builder(context)
                .setTitle("手机号 + 验证码登录")
                .setView(column)
                .setPositiveButton("登录", null)
                .setNegativeButton("取消", null)
                .setOnDismissListener { stopCountdown() }
                .create()
                .also { it.show() }

        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val current = login
            val value = code.text.toString().trim()
            if (current == null) {
                hint.text = "请先点「获取验证码」"
                return@setOnClickListener
            }
            if (!value.matches(Regex("[0-9]{4,8}"))) {
                hint.text = "请输入 4—8 位验证码"
                return@setOnClickListener
            }
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            hint.text = "正在登录…"
            task(
                { validatePhoneAccount(current.verify(value)).also { store.save(it.first) } },
                { (next, account) ->
                    dialog?.dismiss()
                    acceptAccount(next, account)
                },
                { failure ->
                    dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                    hint.text = safeError(failure)
                    if (failure is PhoneLogin.Failure && !failure.canRetryCode)
                        phoneLoginFailure(failure)
                },
            )
        }

        countryButton.setOnClickListener {
            pickCountryCode(context, country) { picked ->
                country = picked
                countryButton.text = "+$picked ▾"
            }
        }

        send.setOnClickListener {
            val typed = mobile.text.toString().trim()
            val number =
                runCatching {
                        // 直接粘贴完整国际号码时按 +区号 解析，否则用下拉选中的区号拼接。
                        if (typed.startsWith("+")) PhoneLogin.parse(typed)
                        else PhoneLogin.format(country, typed)
                    }
                    .getOrElse {
                        hint.text = safeError(it)
                        return@setOnClickListener
                    }
            val fresh = PhoneLogin(phoneHttp)
            stopCountdown()
            send.isEnabled = false
            send.text = "发送中…"
            hint.text = "正在发送验证码…"
            task(
                { fresh.send(number) },
                { sent ->
                    login = fresh
                    hint.text = "验证码已发送至 ${sent.maskedMobile}"
                    startCountdown(sent.retrySeconds)
                },
                { failure ->
                    send.isEnabled = true
                    send.text = "获取验证码"
                    hint.text = safeError(failure)
                },
            )
        }
    }

    private data class CountryCode(val code: String, val name: String)

    /** 常用国家/地区区号；电视上先选再输号码，避免记格式。 */
    private val countryCodes =
        listOf(
            CountryCode("86", "中国大陆"),
            CountryCode("852", "中国香港"),
            CountryCode("853", "中国澳门"),
            CountryCode("886", "中国台湾"),
            CountryCode("65", "新加坡"),
            CountryCode("60", "马来西亚"),
            CountryCode("62", "印度尼西亚"),
            CountryCode("63", "菲律宾"),
            CountryCode("66", "泰国"),
            CountryCode("84", "越南"),
            CountryCode("855", "柬埔寨"),
            CountryCode("856", "老挝"),
            CountryCode("95", "缅甸"),
            CountryCode("81", "日本"),
            CountryCode("82", "韩国"),
            CountryCode("850", "朝鲜"),
            CountryCode("91", "印度"),
            CountryCode("92", "巴基斯坦"),
            CountryCode("880", "孟加拉国"),
            CountryCode("94", "斯里兰卡"),
            CountryCode("977", "尼泊尔"),
            CountryCode("1", "美国 / 加拿大"),
            CountryCode("52", "墨西哥"),
            CountryCode("55", "巴西"),
            CountryCode("54", "阿根廷"),
            CountryCode("56", "智利"),
            CountryCode("57", "哥伦比亚"),
            CountryCode("51", "秘鲁"),
            CountryCode("44", "英国"),
            CountryCode("33", "法国"),
            CountryCode("49", "德国"),
            CountryCode("39", "意大利"),
            CountryCode("34", "西班牙"),
            CountryCode("31", "荷兰"),
            CountryCode("32", "比利时"),
            CountryCode("41", "瑞士"),
            CountryCode("43", "奥地利"),
            CountryCode("46", "瑞典"),
            CountryCode("47", "挪威"),
            CountryCode("45", "丹麦"),
            CountryCode("358", "芬兰"),
            CountryCode("48", "波兰"),
            CountryCode("420", "捷克"),
            CountryCode("36", "匈牙利"),
            CountryCode("30", "希腊"),
            CountryCode("351", "葡萄牙"),
            CountryCode("353", "爱尔兰"),
            CountryCode("7", "俄罗斯 / 哈萨克斯坦"),
            CountryCode("380", "乌克兰"),
            CountryCode("90", "土耳其"),
            CountryCode("972", "以色列"),
            CountryCode("971", "阿联酋"),
            CountryCode("966", "沙特阿拉伯"),
            CountryCode("20", "埃及"),
            CountryCode("27", "南非"),
            CountryCode("234", "尼日利亚"),
            CountryCode("254", "肯尼亚"),
            CountryCode("61", "澳大利亚"),
            CountryCode("64", "新西兰"),
        )

    /** 区号选择器：支持按住遥控器在列表里翻页，也能在搜索框按名称或区号过滤。 */
    private fun pickCountryCode(
        context: Context,
        current: String,
        onPick: (String) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun px(value: Int) = (value * density).toInt()
        val search =
            EditText(context).apply {
                hint = "输入国家/地区名称或区号筛选"
                isSingleLine = true
            }
        val list = ListView(context)
        fun label(rows: List<CountryCode>) = rows.map { "${it.name}  +${it.code}" }
        var rows = countryCodes.sortedBy { if (it.code == current) "" else it.name }
        list.adapter =
            ArrayAdapter(context, android.R.layout.simple_list_item_1, label(rows))
        var picker: AlertDialog? = null
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(editable: Editable?) {
                    val query = editable?.toString().orEmpty().trim()
                    rows =
                        countryCodes
                            .filter {
                                query.isEmpty() ||
                                    it.name.contains(query) ||
                                    it.code.startsWith(query.removePrefix("+"))
                            }
                            .sortedBy { if (it.code == current) "" else it.name }
                    list.adapter =
                        ArrayAdapter(context, android.R.layout.simple_list_item_1, label(rows))
                }
            }
        )
        list.setOnItemClickListener { _, _, position, _ ->
            rows.getOrNull(position)?.let { onPick(it.code) }
            picker?.dismiss()
        }
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20), px(8), px(20), 0)
                addView(search)
                addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(260)))
            }
        picker =
            AlertDialog.Builder(context)
                .setTitle("选择国家/地区区号（当前 +$current）")
                .setView(column)
                .setNegativeButton("取消", null)
                .create()
        picker.show()
    }

    /** 需要重新发码或改用扫码时才打断用户；可重试的错误留在面板里显示。 */
    private fun phoneLoginFailure(failure: PhoneLogin.Failure) {
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle("登录未完成")
                .setMessage(safeError(failure))
                .setPositiveButton("返回账号页") { _, _ -> show() }
                .setNeutralButton("改用扫码") { _, _ -> qrLogin() }
        )
    }

    /**
     * App 通道在 passport 阶段已把这台设备写进会话，账号请求必须继续用同一 device_id/iid；
     * 再注册一次新设备会让签名与登录会话不匹配。
     */
    private fun validatePhoneAccount(
        credentials: AccountCredentials
    ): Pair<AccountCredentials, CloudProfile> = credentials to client.profile(credentials)

    private fun validateAccount(web: AccountCredentials): Pair<AccountCredentials, CloudProfile> {
        val device = client.registerDevice() ?: throw java.io.IOException("账号设备初始化失败")
        val next = web.copy(deviceId = device.deviceId, installId = device.installId)
        val account = client.profile(next)
        return next to account
    }

    private fun acceptAccount(next: AccountCredentials, account: CloudProfile) {
        accountRestored = true
        accountRestoreFailed = false
        credentials = next
        profile = account
        history = emptyList()
        historyLoaded = false
        favorites = FavoriteState()
        unconfirmedFavorites.clear()
        reservations = emptyMap()
        acceptedReservations.clear()
        generation++
        status = "已登录，正在读取云端记录"
        lastUpload = 0
        lastEpisode = ""
        lastProgress = null
        pending = null
        draining = false
        changed()
        toast("已登录 ${account.nickname}")
        refresh(true)
    }

    private fun qrLogin() {
        val login = WebLogin(http)
        task(
            { login.qr() },
            { qr ->
                val bitmap =
                    android.graphics.BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)
                if (bitmap == null) {
                    toast("登录二维码无法显示，请重试")
                    return@task
                }
                val size = (300 * activity.resources.displayMetrics.density).toInt()
                val image =
                    android.widget.ImageView(activity).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        setBackgroundColor(android.graphics.Color.WHITE)
                        layoutParams =
                            android.widget.LinearLayout.LayoutParams(size, size).apply {
                                gravity = android.view.Gravity.CENTER_HORIZONTAL
                            }
                    }
                val panel =
                    android.widget.LinearLayout(activity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(24, 16, 24, 16)
                        addView(image)
                        addView(
                            android.widget.TextView(activity).apply {
                                text = "用手机红果 App：我的 → 右上角菜单 → 扫一扫\n扫码后请在手机确认登录"
                                textSize = 18f
                                gravity = android.view.Gravity.CENTER
                                setPadding(0, 12, 0, 0)
                            }
                        )
                    }
                showDialog(
                    AlertDialog.Builder(activity)
                        .setTitle("红果官方扫码登录")
                        .setView(panel)
                        .setNegativeButton("取消", null)
                )
                val current = dialog ?: return@task
                val token = generation
                val deadline =
                    android.os.SystemClock.elapsedRealtime() + qr.expiresIn.coerceIn(1, 300) * 1000L
                var cancelled = false
                busy = true
                status = "等待手机确认扫码登录"
                current.setOnDismissListener {
                    cancelled = true
                    login.cancel()
                    if (token == generation) {
                        busy = false
                        status = "已取消扫码登录"
                    }
                }
                // Android dispatches OnDismiss through a Handler. Finish the QR operation now;
                // a delayed dismissal must not keep the save task busy or cancel its successor.
                fun closeQr() {
                    cancelled = true
                    current.setOnDismissListener(null)
                    login.cancel()
                    if (token == generation) busy = false
                    current.dismiss()
                }
                fun poll() {
                    if (cancelled || closed || token != generation) return
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        closeQr()
                        status = "二维码已过期，请重新扫码"
                        showDialog(
                            AlertDialog.Builder(activity)
                                .setTitle("二维码已过期")
                                .setMessage("请刷新二维码，再用手机红果 App 扫码确认。")
                                .setPositiveButton("刷新二维码") { _, _ -> qrLogin() }
                                .setNegativeButton("取消", null)
                        )
                        return
                    }
                    worker.execute {
                        val result = runCatching {
                            if (login.poll()) validateAccount(login.credentials()) else null
                        }
                        main.post {
                            if (cancelled || closed || token != generation) return@post
                            result
                                .onSuccess { account ->
                                    if (account != null) {
                                        closeQr()
                                        status = "扫码已确认，正在保存登录状态"
                                        task(
                                            {
                                                store.save(account.first)
                                                account
                                            },
                                            { acceptAccount(it.first, it.second) },
                                            { failure ->
                                                showDialog(
                                                    AlertDialog.Builder(activity)
                                                        .setTitle("登录状态保存失败")
                                                        .setMessage(safeError(failure))
                                                        .setPositiveButton("重新扫码") { _, _ ->
                                                            qrLogin()
                                                        }
                                                        .setNegativeButton("返回账号页") { _, _ ->
                                                            show()
                                                        }
                                                )
                                            },
                                        )
                                    } else main.postDelayed({ poll() }, 2000)
                                }
                                .onFailure {
                                    closeQr()
                                    status = safeError(it)
                                    showDialog(
                                        AlertDialog.Builder(activity)
                                            .setTitle("扫码登录未完成")
                                            .setMessage(status)
                                            .setPositiveButton("重新扫码") { _, _ -> qrLogin() }
                                            .setNegativeButton("返回账号页") { _, _ -> show() }
                                    )
                                }
                        }
                    }
                }
                main.postDelayed({ poll() }, 1500)
            },
        )
    }

    private fun <T> task(
        action: () -> T,
        success: (T) -> Unit,
        failure: (Throwable) -> Unit = { toast(safeError(it)) },
    ) {
        if (busy || closed) return
        busy = true
        val token = generation
        worker.execute {
            if (closed || token != generation) return@execute
            val result = runCatching(action)
            main.post {
                if (closed || token != generation) return@post
                busy = false
                result.onSuccess(success).onFailure {
                    status = safeError(it)
                    failure(it)
                }
            }
        }
    }

    private fun refresh(showResult: Boolean) {
        val account = credentials ?: return
        if (busy || closed) return
        status = "正在读取账号观看历史…"
        task(
            { client.profile(account) to client.history(account) },
            { (user, rows) ->
                profile = user
                historyLoaded = true
                history =
                    rows.distinctBy { it.series.id }.sortedByDescending { it.updatedAt }.take(50)
                status = "已读取 ${rows.size} 部云端观看记录"
                changed()
                if (showResult) {
                    toast(status)
                    show()
                }
            },
            {
                changed()
                toast(safeError(it))
            },
        )
        changed()
    }

    private fun showHistory() {
        if (history.isEmpty()) {
            toast("暂无已读取的云端记录，请先刷新")
            return
        }
        val snapshot = history
        showDialog(
            AlertDialog.Builder(activity)
                .setTitle("云端继续观看")
                .setItems(
                    snapshot
                        .map {
                            "${it.series.title} · 第 ${it.episodeIndex+1} 集 · ${it.position/1000} 秒"
                        }
                        .toTypedArray()
                ) { _, index ->
                    play(snapshot[index])
                }
                .setNegativeButton("关闭", null)
        )
    }

    private fun logout() {
        accountRestored = true
        accountRestoreFailed = false
        generation++
        busy = false
        credentials = null
        profile = null
        history = emptyList()
        historyLoaded = false
        favorites = FavoriteState()
        unconfirmedFavorites.clear()
        reservations = emptyMap()
        acceptedReservations.clear()
        pending = null
        lastProgress = null
        draining = false
        http.dispatcher.cancelAll()
        status = "已退出账号，自动同步已停止"
        changed()
        worker.execute {
            val result = runCatching { store.clear() }
            main.post { toast(if (result.isSuccess) status else "清除登录态失败，请重试退出") }
        }
    }

    fun upload(progress: CloudProgress, episodes: Int, force: Boolean) {
        val account = credentials?.takeIf { profile != null } ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && progress.episodeId == lastEpisode && now - lastUpload < 30000) return
        lastUpload = now
        lastEpisode = progress.episodeId
        history =
            (listOf(progress.copy(episodeCount = episodes)) +
                    history.filterNot { it.series.id == progress.series.id })
                .take(50)
        changed()
        pending = progress to episodes
        lastProgress = pending
        if (draining) return
        draining = true
        drain(account, generation)
    }

    private fun drain(account: AccountCredentials, token: Int) {
        val next = pending
        if (closed || token != generation) return
        if (next == null) {
            draining = false
            return
        }
        pending = null
        worker.execute {
            val result = runCatching {
                if (!closed && token == generation) client.upload(account, next.first, next.second)
            }
            main.post {
                if (closed) return@post
                if (token == generation)
                    status =
                        if (result.isSuccess) "播放进度已上传（请在手机端核对续播）"
                        else "进度上传失败：${safeError(result.exceptionOrNull()!!)}"
                drain(account, token)
            }
        }
    }

    fun destroy() {
        val account = credentials
        val final = lastProgress
        onHistoryChanged = null
        closed = true
        generation++
        pending = null
        dialog?.dismiss()
        http.dispatcher.cancelAll()
        worker.execute {
            if (account != null && final != null)
                runCatching { client.upload(account, final.first, final.second) }
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
        worker.shutdown()
    }
}
