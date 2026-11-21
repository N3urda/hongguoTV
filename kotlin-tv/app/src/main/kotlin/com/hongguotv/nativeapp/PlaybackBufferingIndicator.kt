// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import androidx.media3.common.Player

/** Give short buffering intervals (including episode transitions) time to settle. */
internal class PlaybackBufferingIndicator(
    private val handler: Handler,
    private val setVisible: (Boolean) -> Unit,
) : Player.Listener {
    private val reveal = Runnable { setVisible(true) }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_BUFFERING) {
            handler.postDelayed(reveal, 1000)
        } else {
            clear()
        }
    }

    fun clear() {
        handler.removeCallbacks(reveal)
        setVisible(false)
    }
}
