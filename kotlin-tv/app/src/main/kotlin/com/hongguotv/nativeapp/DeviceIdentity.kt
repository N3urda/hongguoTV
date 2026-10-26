// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Log
import com.hongguotv.core.DeviceRegistrar
import com.hongguotv.core.DeviceSession
import com.hongguotv.core.applyDeviceIdentity
import okhttp3.OkHttpClient
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * fqnovel 设备身份与注册握手。
 *
 * 根因：fqnovel 对设备做风控——一个设备身份只有一次"首请求宽限"，之后被标记
 * （播放返回 Code=110001 / 搜索空响应）。光有签名算法不够，必须先把设备注册成
 * 服务端信任的身份（device_register 握手），并把下发的 install_id / device_id /
 * device_token 作为 Cookie 随每个请求发送，注册设备才能稳定存活。
 *
 * 做法：
 *  - 已持久化可信身份 → 直接应用并注入 DeviceSession（Cookie 来源）。
 *  - 未持久化 → 先用本地随机身份（保证首启立即可用，享一次宽限），后台向 fqnovel
 *    发起 device_register 拿到服务端信任身份，持久化并升级 DeviceSession。
 *  同一安装内身份恒定（SharedPreferences），卸载重装即换新。
 */
internal object DeviceIdentity {
    private const val PREFS = "hg_device"
    private const val KEY_IID = "iid"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_TOKEN = "device_token"

    fun ensure(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iid = prefs.getString(KEY_IID, null)
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        val token = prefs.getString(KEY_DEVICE_TOKEN, null)
        if (iid != null && deviceId != null) {
            apply(iid, deviceId, token)
            Log.i("HongguoTV", "DeviceIdentity: reuse persisted iid=$iid device_id=$deviceId tokenLen=${token?.length ?: 0}")
            return
        }
        // 首次启动：先用本地随机身份占位，再后台注册升级为可信身份。
        val rnd = Random()
        val localIid = randomId(rnd)
        val localDeviceId = randomId(rnd)
        prefs.edit().putString(KEY_IID, localIid).putString(KEY_DEVICE_ID, localDeviceId).apply()
        apply(localIid, localDeviceId, null)
        Log.i("HongguoTV", "DeviceIdentity: fresh local iid=$localIid device_id=$localDeviceId; registering...")
        Thread({
            try {
                val reg = DeviceRegistrar.register(OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS).build())
                val rid = reg.installId
                val rdid = reg.deviceId
                if (rid != null && rdid != null) {
                    prefs.edit().putString(KEY_IID, rid)
                        .putString(KEY_DEVICE_ID, rdid)
                        .putString(KEY_DEVICE_TOKEN, reg.deviceToken ?: "").apply()
                    apply(rid, rdid, reg.deviceToken)
                    Log.i("HongguoTV", "DeviceIdentity: registered trusted iid=$rid device_id=$rdid tokenLen=${reg.deviceToken?.length ?: 0}")
                } else {
                    Log.w("HongguoTV", "DeviceIdentity: register returned no ids (${reg.raw.take(120)}); keep local identity")
                }
            } catch (e: Exception) {
                Log.w("HongguoTV", "DeviceIdentity: register failed ${e.javaClass.simpleName}: ${e.message}; keep local identity")
            }
        }, "hg-register").start()
    }

    private fun randomId(rnd: Random): String {
        val sb = StringBuilder()
        sb.append((1 + rnd.nextInt(9)).toString())      // 首位 1-9，避免前导零
        repeat(15) { sb.append(rnd.nextInt(10)) }        // 共 16 位，与既有格式一致
        return sb.toString()
    }

    private fun apply(iid: String, deviceId: String, token: String?) {
        applyDeviceIdentity(iid, deviceId)
        DeviceSession.set(iid, deviceId, token)
    }
}
