// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import com.hongguotv.core.AppUpdateRoute
import com.hongguotv.core.AppUpdateRepository
import com.hongguotv.core.UpdateFiles
import java.io.File

/** Real-network updater acceptance. Downloads and verifies the public APK without opening an installer. */
object UpdateDownloadAcceptance {
    fun run(
        instrumentation: Instrumentation,
        route: AppUpdateRoute,
        finish: (Int, Bundle) -> Unit,
    ) {
        val result = Bundle()
        val repository = AppUpdateRepository(Build.VERSION.SDK_INT) { route }
        try {
            val update = repository.latest()
            val directory =
                instrumentation.targetContext.getExternalFilesDir("update-acceptance")
                    ?: throw IllegalStateException("外部验收目录不可用")
            val apk = File(directory, update.fileName).apply { delete() }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "stream",
                        "route=${route.name}; version=${update.versionName}; expected=${update.size} bytes\n",
                    )
                },
            )
            repository.download(update, apk) { percent ->
                if (percent % 10 == 0)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply { putString("stream", "route=${route.name}; download=$percent%\n") },
                    )
            }
            check(UpdateFiles.verify(apk, update)) { "下载完成后摘要校验失败" }
            val actualRoute = repository.lastSuccessfulRoute ?: route
            val evidence =
                "PASS: requested=${route.name}; route=${actualRoute.name}; version=${update.versionName}; " +
                    "versionCode=${update.versionCode}; bytes=${apk.length()}; sha256=${update.sha256}; path=${apk.absolutePath}"
            result.putString("stream", evidence)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", evidence + "\n") })
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            val failure = "FAIL: route=${route.name}; ${error.javaClass.simpleName}:${error.message}"
            result.putString("failure", failure)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", failure + "\n") })
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            repository.close()
        }
    }
}
