// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Test

/** 临时探针（跑完即删）：全新注册设备直调 stream，验证播放 API 是否平台级收紧。 */
class RealStreamProbe {
    @Test fun probe() {
        val repo=ContentRepository()
        try {
            val info=repo.stream("7698309376561400856",1080)
            println("=== STREAM OK url=${info.url.take(80)} quality=${info.quality} ${info.width}x${info.height}")
        } catch(e: Exception) {
            println("=== STREAM FAIL: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
