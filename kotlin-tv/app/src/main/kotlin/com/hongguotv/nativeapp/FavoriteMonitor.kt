// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.ContentRepository
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Serial, foreground-only checks. Library access and callbacks stay on the main thread. */
class FavoriteMonitor(private val library: Library,source: ContentRepository,private val changed: ()->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private val http=source.http.newBuilder().callTimeout(12,TimeUnit.SECONDS).build()
    private val repository=ContentRepository(http)
    private var epoch=0
    var running=false; private set
    var status="进入收藏可手动检查更新"; private set
    fun check(force: Boolean=false) {
        if(running) return
        val now=System.currentTimeMillis()
        val items=library.favorites().filter { force || library.favoriteUpdate(it.id)?.let { now-it.checkedAt !in 0 until 3_600_000L } != false }
        if(items.isEmpty()) { status=if(library.favorites().isEmpty()) "收藏后可检查新集" else "最近一小时已检查，可手动刷新"; changed(); return }
        val ticket=++epoch; running=true; var completed=0; var failures=0; var consecutiveFailures=0
        fun next() {
            if(ticket!=epoch) return
            if(completed==items.size || consecutiveFailures>=3) {
                running=false; status="已检查 $completed/${items.size} 部"+(if(failures>0) " · $failures 部失败，稍后可重试" else " · 检查完成"); changed(); return
            }
            val item=items[completed]; val started=System.currentTimeMillis()
            status="检查更新 ${completed+1}/${items.size}…"; changed()
            worker.execute {
                val result=runCatching { repository.detail(item.id).episodes.size }
                main.post {
                    if(ticket!=epoch) return@post
                    result.onSuccess { count -> library.observeFavorite(item.id,count,started); consecutiveFailures=0 }
                        .onFailure { failures++; consecutiveFailures++ }
                    completed++; next()
                }
            }
        }
        next()
    }
    fun stop() { epoch++; running=false }
    fun destroy() { stop(); worker.shutdownNow() }
}
