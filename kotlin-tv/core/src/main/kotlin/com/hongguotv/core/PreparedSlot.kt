// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable

/** One cancellable speculative result. Claim transfers ownership; stale completions close themselves. */
class PreparedSlot<T: Closeable>(private val clock: ()->Long,private val ttl: Long=120_000) {
    private var revision=0L
    private var key=""
    private var readyAt=0L
    private var value: T?=null
    @Synchronized fun begin(next: String): Long { clear(); key=next; return revision }
    @Synchronized fun complete(ticket: Long,next: String,result: T): Boolean {
        if(ticket!=revision || key!=next || value!=null) { result.close(); return false }
        value=result; readyAt=clock(); return true
    }
    @Synchronized fun take(next: String): T? {
        val result=value?.takeIf { key==next && clock()-readyAt in 0..ttl }
        if(result!=null) value=null
        clear()
        return result
    }
    @Synchronized fun isFresh(next: String): Boolean = value!=null && key==next && clock()-readyAt in 0..ttl
    @Synchronized fun clear() { revision++; value?.close(); value=null; key="" }
    /** 正在预取（未完成）的 key；没有在途预取时为 null。 */
    @Synchronized fun pendingKey(): String? = if(value==null && key.isNotEmpty()) key else null
    /**
     * 等在途预取落地（切换剧集时避免把抓到一半的预取扔掉重来）。
     * 每 50ms 轮询一次；key 被别的预取顶掉或超时即放弃。只能在后台线程调用。
     */
    fun await(next: String,timeoutMs: Long): T? {
        val deadline=System.nanoTime()+timeoutMs*1_000_000
        while(System.nanoTime()<deadline) {
            if(isFresh(next)) return take(next)
            if(pendingKey()!=next) return null   // 被顶掉/清空：别等了
            runCatching { Thread.sleep(50) } ?: return null
        }
        return null
    }
}
