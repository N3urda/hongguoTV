// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class UpdateInstallSessionTest {
    @Test
    fun sessionSubmissionDoesNotRequireAnExternalInstallerActivity() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val file = File(activity.cacheDir, "session-test.apk").apply { writeText("安装会话测试") }
            val id = UpdateInstallSession.submit(activity, file)
            val installer = activity.packageManager.packageInstaller
            assertNotNull(installer.getSessionInfo(id))
            assertEquals(id, activity.getSharedPreferences("update_install_session", 0).getInt("active", -1))
            val pending = PendingIntent.getActivity(activity, id,
                Intent(activity, UpdateInstallResultActivity::class.java), PendingIntent.FLAG_NO_CREATE)
            assertNotNull(pending)
            assertFalse(shadowOf(pending).isImmutable)
            assertEquals(UpdateInstallResultActivity::class.java.name, shadowOf(pending).savedIntent.component!!.className)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun unreadableApkAbandonsSessionAndClearsPendingState() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            assertThrows(java.io.IOException::class.java) {
                UpdateInstallSession.submit(activity, File(activity.cacheDir, "missing.apk"))
            }
            assertTrue(activity.packageManager.packageInstaller.mySessions.isEmpty())
            assertEquals(-1, activity.getSharedPreferences("update_install_session", 0).getInt("active", -1))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun onlyTrackedSessionCanOpenSystemConfirmation() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("update_install_session", 0).edit().putInt("active", 7).commit()
        val confirmation = Intent("test.SYSTEM_CONFIRM")
        fun result(id: Int) = Intent(app, UpdateInstallResultActivity::class.java)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, confirmation)
        val rejected = Robolectric.buildActivity(UpdateInstallResultActivity::class.java, result(8)).create()
        assertNull(shadowOf(rejected.get()).nextStartedActivity)
        rejected.destroy()
        val accepted = Robolectric.buildActivity(UpdateInstallResultActivity::class.java, result(7)).create()
        assertEquals("test.SYSTEM_CONFIRM", shadowOf(accepted.get()).nextStartedActivity.action)
        accepted.destroy()
        assertFalse(app.packageManager.getActivityInfo(
            android.content.ComponentName(app, UpdateInstallResultActivity::class.java), 0).exported)
    }

    @Test
    fun terminalFailureClearsTrackedSessionWithoutLaunchingConfirmation() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val preferences = app.getSharedPreferences("update_install_session", 0)
        preferences.edit().putInt("active", 7).commit()
        val intent = Intent(app, UpdateInstallResultActivity::class.java)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 7)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_BLOCKED)
        val controller = Robolectric.buildActivity(UpdateInstallResultActivity::class.java, intent).create()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertEquals(-1, preferences.getInt("active", -1))
        controller.destroy()
    }
}
