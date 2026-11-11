// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.io.File

/** 将已校验安装包交给系统会话，避免依赖厂商安装器组件名及外部文件读取权限。 */
internal object UpdateInstallSession {
    fun submit(context: Context, apk: File): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, UpdateInstallResultActivity::class.java)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                // 新系统只在应用可见时委托确认页启动能力，不用于后台打扰。
                val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                        else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                }.toBundle() else null
                val pending = PendingIntent.getActivity(context, id, callback, flags, options)
                context.getSharedPreferences("update_install_session", 0).edit().putInt("active", id).commit()
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("安装准备已取消")
                session.commit(pending.intentSender)
            }
            return id
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            context.getSharedPreferences("update_install_session", 0).edit().remove("active").apply()
            throw error
        }
    }
}

/** 不对外导出；只有系统持有的显式回调才能进入安装确认流程。 */
class UpdateInstallResultActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val preferences = getSharedPreferences("update_install_session", 0)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || preferences.getInt("active", -1) != id) {
            finish()
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            val opened = confirmation != null && runCatching { startActivity(confirmation) }.isSuccess
            if (!opened) {
                preferences.edit().remove("active").apply()
                runCatching { packageManager.packageInstaller.abandonSession(id) }
                val message = "系统安装会话无法打开确认页，请继续使用 U 盘安装"
                preferences.edit().putString("result_text", message).apply()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } else {
            preferences.edit().remove("active").apply()
            // 仅记录状态码，不记录可能包含文件路径的系统错误正文。
            android.util.Log.i("HongguoTV", "Update session result status=$status")
            if (status != PackageInstaller.STATUS_SUCCESS) {
                val message = "系统安装未完成（状态 $status），可使用 U 盘安装"
                preferences.edit().putString("result_text", message).apply()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
