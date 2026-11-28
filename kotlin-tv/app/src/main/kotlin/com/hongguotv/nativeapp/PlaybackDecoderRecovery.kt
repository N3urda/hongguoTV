// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException

/** Audio decoder failures must never blacklist a video rendition or change its decoder. */
@androidx.media3.common.util.UnstableApi
internal fun isVideoDecoderFailure(error: Throwable?): Boolean =
    error is PlaybackException &&
        error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED &&
        (error !is ExoPlaybackException || error.type == ExoPlaybackException.TYPE_RENDERER &&
            error.rendererFormat?.sampleMimeType?.let(MimeTypes::isVideo) == true)

/** One software retry per episode in this Activity, shared by feed and full-screen playback. */
internal class PlaybackDecoderRecovery {
    private val softwareEpisodes = linkedSetOf<String>()

    @Synchronized fun mode(id: String?, preferred: VideoDecoderMode): VideoDecoderMode =
        if (id in softwareEpisodes) VideoDecoderMode.SOFTWARE_ONLY else preferred

    @androidx.media3.common.util.UnstableApi
    @Synchronized fun trySoftware(
        id: String,
        preferred: VideoDecoderMode,
        error: PlaybackException,
        decoder: ActiveVideoDecoder?,
        softwareAvailable: Boolean,
    ): Boolean {
        // Media3 already exhausts decoder initialization candidates. This covers failures AFTER
        // hardware initialization, without retrying network/audio errors or looping on software.
        if (mode(id, preferred) == VideoDecoderMode.SOFTWARE_ONLY || !softwareAvailable ||
            decoder?.hardwareAccelerated != true || decoder.softwareOnly || !isVideoDecoderFailure(error) ||
            error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false
        softwareEpisodes += id
        while (softwareEpisodes.size > 16) softwareEpisodes.remove(softwareEpisodes.first())
        return true
    }

    /** Explicit decoder choice starts a new attempt; ordinary recovery does not reset the budget. */
    @Synchronized fun clear(id: String) { softwareEpisodes.remove(id) }
}
