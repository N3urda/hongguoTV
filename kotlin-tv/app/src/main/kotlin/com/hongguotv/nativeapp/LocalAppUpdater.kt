// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executors

/** Fork-specific LAN update entry. Upstream online updater remains unmodified and is never created. */
internal class LocalAppUpdater(private val activity: Activity, private val idle: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val apk = File(activity.cacheDir, "local-update/package.apk")
    private var active = false
    private var dialog: AlertDialog? = null
    val showing get() = dialog?.isShowing == true
    private var grant = ""
    private var grantUntil = 0L
    private var asking = false
    private var owner = ""
    private var task = ""
    private var expected = 0L
    private var offset = 0L
    private var touched = 0L
    private var state = "idle"
    private var message = "请选择 APK 上传并安装"
    private var target = 0L

    init { LocalUpdateCleanup.clean(activity) }
    @Synchronized fun resume() { active = true }
    @Synchronized fun pause() { active = false; grant = ""; dialog?.dismiss(); dialog = null; asking = false }
    fun interrupt() = Unit
    fun destroy() { pause(); worker.shutdown() }

    @Synchronized fun request(scope: String, body: JSONObject, policy: ChildModeStore): JSONObject {
        val now = System.currentTimeMillis()
        if (state == "uploading" && now - touched > 10 * 60 * 1000L) reset()
        val action = body.getString("action")
        if (action == "updateStatus") return snapshot()
        check(active) { "请保持电视应用在前台" }
        check(!policy.broken) { "请先修复家长配置" }
        if (action == "updateAuthorize") {
            if (policy.configured) {
                check(policy.authorized(body.optString("parentToken"))) { "请先在家长设置中验证密码" }
            } else if (grant != scope || now >= grantUntil) {
                if (!asking) {
                    asking = true
                    main.post {
                        synchronized(this) {
                            if (!active) { asking = false; return@post }
                            dialog = AlertDialog.Builder(activity).setTitle("允许手机上传更新？")
                                .setMessage("仅允许安装与本应用签名一致的新版本，本次授权有效期为 10 分钟。")
                                .setPositiveButton("允许") { _, _ -> synchronized(this) { grant = scope; grantUntil = System.currentTimeMillis() + 600000 } }
                                .setNegativeButton("取消", null).create().also {
                                    it.setOnDismissListener { synchronized(this) { asking = false; dialog = null } }
                                    it.show()
                                }
                        }
                    }
                }
                return snapshot().put("authorized", false)
            }
            return snapshot().put("authorized", true)
        }
        check(if (policy.configured) policy.authorized(body.optString("parentToken")) else grant == scope && now < grantUntil) { "更新授权已失效，请重新验证" }
        if (policy.configured) policy.touch(body.optString("parentToken"))
        when (action) {
            "updateBegin" -> {
                check(state !in setOf("uploading", "checking", "installing")) { "已有更新任务，请先取消或等待完成" }
                val size = body.getLong("size")
                require(size in 1..200L * 1024 * 1024) { "安装包大小需在 200 MB 以内" }
                apk.parentFile!!.mkdirs()
                check(apk.parentFile!!.usableSpace > size * 2 + 16 * 1024 * 1024) { "电视存储空间不足" }
                apk.delete(); apk.createNewFile()
                expected = size; offset = 0; target = 0; owner = scope; task = UUID.randomUUID().toString()
                state = "uploading"; message = "正在上传"; touched = now
            }
            "updateChunk" -> {
                own(scope, body); check(state == "uploading")
                val start = body.getLong("offset")
                val bytes = Base64.decode(body.getString("data"), Base64.NO_WRAP)
                require(bytes.size in 1..32768 && start >= 0 && start <= expected - bytes.size) { "分块无效" }
                RandomAccessFile(apk, "rw").use { file ->
                    file.seek(start)
                    if (start < offset) {
                        require(start + bytes.size <= offset)
                        val previous = ByteArray(bytes.size); file.readFully(previous)
                        require(previous.contentEquals(bytes)) { "重复分块内容不一致" }
                    } else {
                        require(start == offset) { "上传顺序不一致，请重试" }
                        file.write(bytes); offset += bytes.size
                    }
                }
                touched = now
            }
            "updateCancel" -> { own(scope, body); check(state == "uploading") { "安装已开始，不能取消上传" }; reset() }
            "updateFinish" -> {
                own(scope, body)
                if (state == "checking" || state == "installing") return snapshot()
                check(state == "uploading" && offset == expected && apk.length() == expected) { "安装包尚未上传完整" }
                state = "checking"; message = "正在校验安装包"
                worker.execute {
                    try {
                        val version = validate()
                        synchronized(this) {
                            check(active) { "电视已离开应用，请重新上传" }
                            check(if (policy.configured) policy.authorized(body.optString("parentToken")) else grant == scope && System.currentTimeMillis() < grantUntil) { "更新授权已失效" }
                            activity.getSharedPreferences("update_install_session", 0).edit().remove("result_text").commit()
                            target = version
                            activity.getSharedPreferences("local_update", 0).edit().putLong("target", version).putLong("created", System.currentTimeMillis()).commit()
                            state = "installing"; message = "已发起系统安装，请按电视系统提示确认"
                        }
                        UpdateInstallSession.submit(activity, apk)
                    } catch (e: Exception) {
                        synchronized(this) { apk.delete(); state = "failed"; message = e.message ?: "安装未完成，请重新上传" }
                    }
                }
            }
            else -> error("未知更新操作")
        }
        return snapshot()
    }

    private fun own(scope: String, body: JSONObject) { check(owner == scope && task == body.optString("task")) { "更新任务已失效" } }
    private fun reset() { apk.delete(); task = ""; owner = ""; state = "idle"; offset = 0; message = "请选择 APK 上传并安装" }
    private fun snapshot(): JSONObject {
        if (state == "installing" && activity.getSharedPreferences("update_install_session", 0).contains("result_text")) {
            state = "failed"; message = "系统安装未完成，请重新上传"; apk.delete()
        }
        return JSONObject().put("state", state).put("message", message).put("task", task).put("offset", offset)
            .put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE).put("target", target)
    }

    @Suppress("DEPRECATION")
    private fun validate(): Long {
        val pm = activity.packageManager
        val flags = UpdateApkSignatures.flags()
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: error("无法识别 APK")
        val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        check(candidate.packageName == activity.packageName) { "安装包包名不一致" }
        check(version > BuildConfig.VERSION_CODE) { "只能安装更高版本" }
        if (Build.VERSION.SDK_INT >= 24) check((candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "安装包不支持当前 Android 版本" }
        // API 23 has no public minSdkVersion archive accessor; the system session enforces it.
        UpdateApkSignatures.verify(pm.getPackageInfo(activity.packageName, flags), candidate)
        return version
    }
}

internal object LocalUpdateCleanup {
    fun clean(context: Context, completed: Boolean = false) {
        val prefs = context.getSharedPreferences("local_update", 0)
        val target = prefs.getLong("target", 0)
        val file = File(context.cacheDir, "local-update/package.apk")
        if (completed || target > 0 && BuildConfig.VERSION_CODE >= target ||
            file.exists() && System.currentTimeMillis() - file.lastModified() > 24 * 60 * 60 * 1000L) {
            if (!file.exists() || file.delete()) prefs.edit().clear().apply()
        }
    }
}

class LocalUpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) LocalUpdateCleanup.clean(context)
    }
}
