// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// Android 应用更新流程：检查版本、下载 APK，并通过系统安装器请求安装。

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateRepository
import com.hongguotv.core.UpdateFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground-only updater: no background service, and no networking or prompts during playback. */
class AppUpdater(private val activity: Activity, private val idle: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = AppUpdateRepository()
    private val preferences = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    private val apk = File(activity.cacheDir, "updates/update.apk")
    private var task: Future<*>? = null
    private var generation = 0
    private var active = false
    private var busy = false
    private var available: AppUpdate? = null
    private var ready = false
    private var permissionRequested = false
    private var dialog: AlertDialog? = null
    val showing
        get() = dialog?.isShowing == true

    private var statusView: TextView? = null
    private var anchor: View? = null
    private var status = "当前版本 ${BuildConfig.VERSION_NAME}"
    private var retryAfter = 0L
    private var promptedCode = 0L
    private val auto
        get() = preferences.getBoolean("automatic", true)

    private val poll =
        object : Runnable {
            override fun run() {
                if (!active) return
                if (idle() && dialog == null && !busy && auto) {
                    val now = System.currentTimeMillis()
                    val last = preferences.getLong("last_attempt", 0)
                    val update = available
                    when {
                        ready &&
                            update != null &&
                            promptedCode != update.versionCode &&
                            !deferred(update) -> {
                            promptedCode = update.versionCode
                            show(activity.currentFocus)
                        }
                        update != null && !ready && now >= retryAfter -> download(false)
                        now < last || now - last >= 6 * 60 * 60 * 1000L -> check(false)
                    }
                }
                main.postDelayed(this, 15000)
            }
        }

    fun resume() {
        active = true
        main.removeCallbacks(poll)
        main.postDelayed(poll, 8000)
        val sessionState = activity.getSharedPreferences("update_install_session", 0)
        sessionState.getString("result_text", null)?.let {
            sessionState.edit().remove("result_text").apply()
            updateStatus(it)
            show(anchor)
        }
        if (permissionRequested) {
            permissionRequested = false
            if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls())
                install()
            else {
                status = "安装权限未开启，可在这里再次安装"
                show(anchor)
            }
        }
    }

    fun pause() {
        active = false
        main.removeCallbacks(poll)
        interrupt()
        dialog?.dismiss()
    }

    fun interrupt() {
        generation++
        task?.cancel(true)
        task = null
        repository.cancel()
        busy = false
    }

    fun destroy() {
        pause()
        worker.shutdownNow()
        // TLS socket close may send close_notify; it must not run on Android's main thread.
        Thread({ repository.close() }, "hongguotv-update-cleanup").start()
    }

    private fun updateStatus(text: String) {
        status = text
        statusView?.text = text
    }

    private fun deferred(update: AppUpdate) =
        preferences.getLong("deferred_code", 0) == update.versionCode &&
            preferences.getLong("deferred_until", 0) > System.currentTimeMillis()

    private fun renderActions() {
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text =
                when {
                    busy -> "处理中…"
                    ready -> "安装新版"
                    available != null -> "下载新版"
                    else -> "检查更新"
                }
            // Keep D-pad focus stable while the request runs; action methods reject duplicates.
            isEnabled = true
        }
    }

    fun show(from: View?) {
        if (!active) return
        anchor = from
        dialog?.dismiss()
        val dp = activity.resources.displayMetrics.density
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), 0)
            }
        statusView =
            TextView(activity).apply {
                text = status
                textSize = 17f
                setTextColor(TvStyle.text)
            }
        body.addView(statusView)
        body.addView(
            CheckBox(activity).apply {
                text = "自动检查并下载更新"
                isChecked = auto
                setOnCheckedChangeListener { _, enabled ->
                    preferences.edit().putBoolean("automatic", enabled).apply()
                    if (!enabled && busy) {
                        interrupt()
                        updateStatus("自动更新已关闭，可手动检查")
                        renderActions()
                    }
                }
            }
        )
        body.addView(
            TextView(activity).apply {
                text = "只在应用前台浏览时检查，播放中不打扰。下载后需确认系统安装，收藏与观看记录保留。"
                textSize = 13f
                setTextColor(TvStyle.muted)
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            }
        )
        val next =
            AlertDialog.Builder(activity)
                .setTitle("版本与更新 · ${BuildConfig.VERSION_NAME}")
                .setView(ScrollView(activity).apply { addView(body) })
                .setNegativeButton("稍后", null)
                .setPositiveButton("检查更新", null)
                .create()
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                statusView = null
            }
            if (from?.isAttachedToWindow == true) from.requestFocus()
        }
        next.show()
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            when {
                ready -> install()
                available != null -> download(true)
                else -> check(true)
            }
        }
        next.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            available?.let {
                preferences
                    .edit()
                    .putLong("deferred_code", it.versionCode)
                    .putLong("deferred_until", System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
                    .apply()
            }
            next.dismiss()
        }
        renderActions()
        next.getButton(AlertDialog.BUTTON_POSITIVE).post {
            if (dialog === next) next.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
        }
    }

    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        busy = true
        renderActions()
        val ticket = ++generation
        task =
            worker.submit {
                try {
                    val result = action()
                    main.post {
                        if (active && generation == ticket) {
                            busy = false
                            done(result)
                            renderActions()
                        }
                    }
                } catch (e: Exception) {
                    main.post {
                        if (active && generation == ticket) {
                            busy = false
                            ready = false
                            retryAfter = System.currentTimeMillis() + 30 * 60 * 1000L
                            updateStatus(
                                if (e is IOException) e.message ?: "更新失败，请重试" else "更新校验失败，请重试"
                            )
                            renderActions()
                        }
                    }
                }
            }
    }

    private fun check(manual: Boolean) {
        if (busy || !active || !idle()) return
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        updateStatus("正在检查新版…")
        work({ repository.latest() }) { update ->
            available =
                update.takeIf {
                    it.newerThan(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT)
                }
            ready = false
            if (available == null) {
                updateStatus(
                    if (
                        update.versionCode > BuildConfig.VERSION_CODE &&
                            update.minSdk > Build.VERSION.SDK_INT
                    )
                        "新版需要 Android API ${update.minSdk}，当前电视继续使用本版"
                    else "已是最新版本 ${BuildConfig.VERSION_NAME}"
                )
                worker.execute { apk.delete() }
            } else {
                updateStatus(
                    "发现 ${update.versionName} · ${"%.1f".format(update.size/1048576.0)} MB\n${update.notes}"
                )
                if (manual || auto) download(manual)
            }
        }
    }

    private fun download(manual: Boolean) {
        val update = available ?: return
        if (busy || !active || !idle()) return
        updateStatus("正在下载 ${update.versionName}…")
        val ticket = generation + 1
        work({
            repository.download(update, apk) { percent ->
                main.post {
                    if (generation == ticket && active)
                        updateStatus("正在下载 ${update.versionName} · $percent%")
                }
            }
            validateApk(update)
        }) {
            ready = true
            updateStatus("${update.versionName} 已下载并校验\n${update.notes}\n\n确认后由系统覆盖安装，保留本机记录。")
            if (idle() && dialog == null && (manual || !deferred(update))) {
                promptedCode = update.versionCode
                show(activity.currentFocus)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(update: AppUpdate) {
        if (!UpdateFiles.verify(apk, update)) throw IOException("安装包校验未通过，请重新下载")
        val pm = activity.packageManager
        val flags = UpdateApkSignatures.flags()
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("安装包无法识别")
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val version =
            if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode
            else candidate.versionCode.toLong()
        // ApplicationInfo.minSdkVersion is unavailable on API 23. The system installer
        // still enforces the APK's declared minimum there; retain all hash/signature checks.
        if (
            candidate.packageName != activity.packageName ||
                version != update.versionCode ||
                candidate.versionName != update.versionName ||
                version <= BuildConfig.VERSION_CODE ||
                (Build.VERSION.SDK_INT >= 24 &&
                    candidate.applicationInfo?.minSdkVersion != update.minSdk)
        )
            throw IOException("新版包名或版本不匹配，已阻止安装")
        UpdateApkSignatures.verify(installed, candidate)
    }

    private var legacyApk: File? = null
    private var installerFailure = ""

    /** 仅 Android 6 准备旧式入口；外部目录不可用时仍保留内容授权入口。 */
    private fun prepareLegacyApk(update: AppUpdate) {
        legacyApk = null
        if (Build.VERSION.SDK_INT >= 24) return
        legacyApk = runCatching {
            val directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("外部目录不可用")
            stageLegacyApk(apk, directory) { UpdateFiles.verify(it, update) }
        }.onFailure {
            android.util.Log.w("HongguoTV", "Legacy update copy failed: ${installerError(it)}")
        }.getOrNull()
    }

    /** 依次尝试安装入口；查询结果不能作为是否允许启动的依据。 */
    private fun launchInstaller(uri: Uri): Boolean {
        val fileUri = if (Build.VERSION.SDK_INT < 24) legacyApk?.let(Uri::fromFile) else null
        val sources = installerIntents(uri, fileUri)
        val failures = mutableMapOf<String, Int>()
        sources.forEachIndexed { index, candidate ->
            candidate.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            candidate.clipData = ClipData.newRawUri("更新安装包", uri)
            val result = runCatching { activity.startActivity(candidate) }
            if (result.isSuccess) return true
            val reason = installerError(result.exceptionOrNull()!!)
            failures[reason] = (failures[reason] ?: 0) + 1
            // 只记录入口序号、传递方式和异常类别，不输出文件地址或异常正文。
            android.util.Log.w("HongguoTV", "Update installer entry=${index + 1} scheme=${candidate.data?.scheme} failure=$reason")
        }
        installerFailure = "已尝试 ${sources.size} 个入口：" +
            failures.entries.joinToString("，") { "${it.key} ${it.value}" }
        return false
    }

    companion object {
        /** 常见电视/手机安装器的组件名；隐式匹配失败时按组件直达。 */
        private val INSTALLER_COMPONENTS =
            listOf(
                // Android 7+ 的公开入口是 InstallStart，PackageInstallerActivity 多为内部页面，
                // 只在少数厂商 ROM 上可直接拉起，放在后面兜底。
                "com.android.packageinstaller" to "com.android.packageinstaller.InstallStart",
                "com.google.android.packageinstaller" to "com.android.packageinstaller.InstallStart",
                "com.miui.packageinstaller" to "com.miui.packageinstaller.PackageInstallerActivity",
                "com.android.packageinstaller" to "com.android.packageinstaller.PackageInstallerActivity",
                "com.google.android.packageinstaller" to "com.android.packageinstaller.PackageInstallerActivity",
            )

        /** 先尝试内容授权入口，再尝试 Android 6 外部副本的旧式文件入口。 */
        internal fun installerIntents(uri: Uri, fileUri: Uri?): List<Intent> {
            val type = "application/vnd.android.package-archive"
            val sources = mutableListOf<Intent>()
            val uris = listOfNotNull(uri, fileUri?.takeIf { Build.VERSION.SDK_INT < 24 })
            for (source in uris) {
                if (Build.VERSION.SDK_INT < 26)
                    sources += Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(source, type)
                sources += Intent(Intent.ACTION_VIEW).setDataAndType(source, type)
                for ((pkg, cls) in INSTALLER_COMPONENTS) {
                    sources += Intent(Intent.ACTION_VIEW).setClassName(pkg, cls).setDataAndType(source, type)
                    if (Build.VERSION.SDK_INT < 26)
                        sources += Intent(Intent.ACTION_INSTALL_PACKAGE).setClassName(pkg, cls).setDataAndType(source, type)
                }
            }
            return sources
        }

        /** 不放宽私有目录权限；复制完成并校验后才暴露旧式文件路径。 */
        internal fun stageLegacyApk(source: File, directory: File, verify: (File) -> Boolean): File {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法准备外部安装目录")
            val target = File(directory, "guoying-tv-update.apk")
            val temporary = File(directory, "guoying-tv-update.apk.part")
            try {
                source.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                if (!verify(temporary)) throw IOException("外部安装副本校验失败")
                if (!temporary.renameTo(target)) throw IOException("无法保存外部安装副本")
                return target
            } finally {
                temporary.delete()
            }
        }

        internal fun installerError(error: Throwable): String = when (error) {
            is android.content.ActivityNotFoundException -> "入口不存在"
            is SecurityException -> "系统拒绝"
            else -> "调用失败"
        }
    }

    private fun install() {
        val update = available ?: return
        if (busy || !active || !ready) return
        work({
            validateApk(update)
            prepareLegacyApk(update)
        }) {
            try {
                // Android 8+ 未授权时先尝试打开授权页；但很多电视没有这个页面，
                // 打不开时不能直接放弃——继续走安装入口，由系统安装器自己提示未知来源。
                if (
                    Build.VERSION.SDK_INT >= 26 &&
                        !activity.packageManager.canRequestPackageInstalls()
                ) {
                    val opened =
                        runCatching {
                                activity.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${activity.packageName}"),
                                    )
                                )
                                true
                            }
                            .getOrDefault(false)
                    if (opened) {
                        permissionRequested = true
                        updateStatus("已打开安装权限设置，允许后返回这里再次点安装新版")
                        return@work
                    }
                }
                run {
                    val uri =
                        FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                    // 为 Android 6/7 增加标准安装 action；厂商 ROM 是否提供对应入口仍需实机验证。
                    if (!launchInstaller(uri)) {
                        updateStatus("正在准备系统兼容安装…")
                        work({ runCatching { UpdateInstallSession.submit(activity, apk) } }) { result ->
                            if (result.isSuccess) {
                                updateStatus("已提交系统安装会话，请在系统确认页继续安装")
                            } else {
                                android.util.Log.w("HongguoTV", "Update session failed: ${installerError(result.exceptionOrNull()!!)}")
                                val openedSettings =
                                    runCatching {
                                            activity.startActivity(
                                                Intent(Settings.ACTION_SECURITY_SETTINGS)
                                            )
                                            true
                                        }
                                        .getOrDefault(false)
                                if (openedSettings) {
                                    // 这里只打开设置，不能视为已经授权，也不在返回时自动重试，
                                    // 否则安装入口始终缺失的 ROM 会反复跳回设置。
                                    updateStatus(
                                        "已打开系统安全设置，请查找并允许「未知来源」，返回后再次点安装新版"
                                    )
                                } else {
                                    updateStatus(
                                        "无法打开系统安装器（$installerFailure）。" +
                                            (if (legacyApk != null) "安装副本位于 Android/data/${activity.packageName}/files/Download/guoying-tv-update.apk，可尝试用文件管理器打开。"
                                            else "请从正式发布页下载并用系统文件管理器安装新版。")
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                permissionRequested = false
                android.util.Log.w("HongguoTV", "Update installer setup failed: ${installerError(error)}")
                updateStatus("安装入口准备失败（${installerError(error)}），请通过系统文件管理器安装新版")
            }
        }
    }
}
