// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.widget.TextView
import com.hongguotv.core.DanmakuMode
import com.hongguotv.core.DanmakuSpeed
import com.hongguotv.core.DanmakuStyle

/**
 * 弹幕的装载与设置（移植自 pan8664716/HGTV 1.0.8，纯文本版）。
 *
 * 装载走 [MainActivity.danmakuWorker] 专用的单线程：评论接口要按 30 秒窗口连拉几页，
 * 不能占内容请求的线程池。数据就绪后统一切到主线程交给 [com.hongguotv.nativeapp.player.DanmakuView]，
 * 绘制细节（轨道、不叠字、重复聚合）都在那一层，这里只管「取数 + 换数据」。
 *
 * 设置四项（显示方式 / 速度 / 字号 / 透明度）与 HGTV 一致挂在播放设置面板里；
 * 隐藏档整层不画也不发请求，其余三项在隐藏档下收起（见 [DanmakuMode.showsStyle]）。
 */

/**
 * 拉当前集的弹幕。同一集已在途 / 已加载时直接跳过（换集时 [MainActivity.playEpisode] 会
 * 先作废 token 并清空状态）；隐藏档不请求——省一次注定看不到的流量。
 */
internal fun MainActivity.loadDanmaku(force: Boolean=false) {
    val data=detail ?: return
    val vid=data.episodes.getOrNull(episodeIndex) ?: return
    if(!force && danmakuFor==vid && danmakuLoaded) return
    danmakuFor=vid; danmakuLoaded=false; danmakuCount=-1
    val ticket=danmakuToken.incrementAndGet()
    if(library.danmakuMode==DanmakuMode.HIDDEN) return
    val seriesId=data.series.id
    danmakuWorker.submit {
        // 边拉边上：整集最多 3000 条、按 30 秒窗口分页，等全量再上屏等于前十几秒没弹幕。
        val items=repository.danmaku(vid,seriesId,0L,{ isDestroyed || danmakuToken.get()!=ticket }) { partial ->
            if(danmakuToken.get()==ticket) main.post {
                if(danmakuToken.get()==ticket) danmakuView?.setDanmaku(partial)
            }
        }
        if(isDestroyed || danmakuToken.get()!=ticket) return@submit
        danmakuCount=items.size; danmakuLoaded=true
        main.post { if(danmakuToken.get()==ticket) danmakuView?.setDanmaku(items) }
    }
}

/** 把四项偏好同步到弹幕层（改了任意一项都调它；字号/透明度变化会触发重排）。 */
internal fun MainActivity.applyDanmakuStyle() {
    danmakuView?.let {
        it.mode=library.danmakuMode; it.speed=library.danmakuSpeed
        it.textScale=library.danmakuScale; it.textAlpha=library.danmakuAlpha
        it.restyle()
    }
}

/** 播放设置 → 弹幕：四个二级项，改完即时生效并落盘。 */
internal fun MainActivity.showDanmakuPicker(anchor: TextView) {
    val styleOn=library.danmakuMode.showsStyle
    val entries=buildList {
        add("显示方式 · ${library.danmakuMode.label}")
        if(styleOn) {
            add("速度 · ${library.danmakuSpeed.label}")
            add("字号 · ${DanmakuStyle.percent(library.danmakuScale)}")
            add("透明度 · ${DanmakuStyle.percent(library.danmakuAlpha)}")
        }
    }
    tvTools.choose("弹幕",entries,anchor,{ choice ->
        main.post {
            if(isDestroyed || screen!="player") return@post
            when {
                choice==0 -> showDanmakuModePicker(anchor)
                choice==1 && styleOn -> showDanmakuSpeedPicker(anchor)
                choice==2 && styleOn -> showDanmakuScalePicker(anchor)
                choice==3 && styleOn -> showDanmakuAlphaPicker(anchor)
            }
        }
    })
}

private fun MainActivity.showDanmakuModePicker(anchor: TextView) {
    val modes=DanmakuMode.entries
    tvTools.choose("弹幕显示方式",modes.map { "${it.label}  ·  ${it.hint}" },anchor,{ choice ->
        val next=modes.getOrNull(choice) ?: return@choose
        library.danmakuMode=next
        applyDanmakuStyle()
        // 从隐藏切回可见时，本集可能还没拉过数据：补一发。
        if(next!=DanmakuMode.HIDDEN && !danmakuLoaded) loadDanmaku(force=true)
    })
}

private fun MainActivity.showDanmakuSpeedPicker(anchor: TextView) {
    val speeds=DanmakuSpeed.entries
    tvTools.choose("弹幕速度",speeds.map { "${it.label}  ·  ${it.hint}" },anchor,{ choice ->
        library.danmakuSpeed=speeds.getOrNull(choice) ?: return@choose
        applyDanmakuStyle()
    })
}

private fun MainActivity.showDanmakuScalePicker(anchor: TextView) {
    val options=DanmakuStyle.SCALE_STEPS
    tvTools.choose("弹幕字号",options.map { DanmakuStyle.percent(it) },anchor,{ choice ->
        library.danmakuScale=options.getOrNull(choice) ?: return@choose
        applyDanmakuStyle()
    })
}

private fun MainActivity.showDanmakuAlphaPicker(anchor: TextView) {
    val options=DanmakuStyle.ALPHA_STEPS
    tvTools.choose("弹幕透明度",options.map { DanmakuStyle.percent(it) },anchor,{ choice ->
        library.danmakuAlpha=options.getOrNull(choice) ?: return@choose
        applyDanmakuStyle()
    })
}
