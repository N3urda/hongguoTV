// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import com.hongguotv.core.MemoryCookies
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

object DesktopNetwork {
    // Follow Android's direct content transport by default. An explicit opt-in supports
    // networks that require the OS proxy without editing the user's global configuration.
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).cookieJar(MemoryCookies())
        .apply { if (System.getenv("HONGGUO_USE_SYSTEM_PROXY") != "1") proxy(Proxy.NO_PROXY) }
        .build()
}
