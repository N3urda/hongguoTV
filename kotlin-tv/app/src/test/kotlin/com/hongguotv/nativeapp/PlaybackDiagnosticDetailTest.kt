// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.common.PlaybackException
import com.hongguotv.core.ContentHttpException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PlaybackException 构造依赖 SystemClock，需 Robolectric；纯函数分支在 PlaybackSourcesTest。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PlaybackDiagnosticDetailTest {
    @Test
    fun diagnosticDetailJoinsExoAndHttpCodesFromTheCauseChain() {
        val problem = PlaybackException("wrap", ContentHttpException(503), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertEquals("exo=IO_UNSPECIFIED · http=503", diagnosticDetail(problem))
    }

    @Test
    fun diagnosticDetailNeverLeaksMessagesCookiesOrUrls() {
        // 诊断 detail 只取结构化字段；异常消息即使误含敏感样式文本也不会进入。
        val problem =
            PlaybackException(
                "Cookie: secret; https://example.invalid/path",
                IOException("Cookie: secret; https://example.invalid/path"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        val detail = diagnosticDetail(problem)!!
        assertFalse(detail.contains("Cookie"))
        assertFalse(detail.contains("https"))
    }
}
