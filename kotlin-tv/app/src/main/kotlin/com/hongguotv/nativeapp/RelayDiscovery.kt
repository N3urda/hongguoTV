// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** 通过 NSD/mDNS 发现局域网内的果映伴机（伴机侧以 jmdns 注册 _guoying-relay._tcp）。 */
internal object RelayDiscovery {
    const val SERVICE_TYPE = "_guoying-relay._tcp."

    /**
     * 扫描 [timeoutMs] 后回调结果（"ip:port" 列表，可能为空）；返回 false 表示设备不支持 NSD。
     * 必须在主线程调用；API 28 的 resolveService 不可并发，内部按队列串行解析。
     */
    fun discover(context: Context, timeoutMs: Long = 5_000, onResult: (List<String>) -> Unit): Boolean {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return false
        val main = Handler(Looper.getMainLooper())
        val found: MutableSet<String> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
        val finished = AtomicBoolean(false)
        val resolving = AtomicBoolean(false)
        val queue: Queue<NsdServiceInfo> = ConcurrentLinkedQueue()

        fun resolveNext() {
            if (finished.get() || resolving.getAndSet(true)) return
            val info = queue.poll() ?: return
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolving.set(false)
                        resolveNext()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        info.host?.hostAddress?.takeIf { it.isNotBlank() }?.let { address ->
                            found += "$address:${info.port}"
                        }
                        resolving.set(false)
                        resolveNext()
                    }
                },
            )
        }

        val discovery =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    queue.add(serviceInfo)
                    resolveNext()
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    if (finished.compareAndSet(false, true)) main.post { onResult(emptyList()) }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

        val started =
            runCatching {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
                true
            }.getOrDefault(false)
        if (!started) return false

        main.postDelayed(
            {
                if (finished.compareAndSet(false, true)) {
                    runCatching { nsd.stopServiceDiscovery(discovery) }
                    onResult(found.toList().sorted())
                }
            },
            timeoutMs,
        )
        return true
    }
}
