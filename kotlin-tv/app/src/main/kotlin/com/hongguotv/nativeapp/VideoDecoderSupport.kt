// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.hongguotv.core.VideoTracks

@UnstableApi
internal fun VideoTracks.Track.videoFormat(): Format = Format.Builder()
    .setSampleMimeType(CodecCapabilities.mime(fourcc)).setCodecs(codecs)
    .setWidth(width).setHeight(height).build()

/** Software may ignore an overstated level, but a known profile/size mismatch is not equivalent. */
@UnstableApi
internal fun supportsSoftwareProfile(codec: MediaCodecInfo, format: Format): Boolean =
    codec.softwareOnly &&
        (codec.profileLevels.isEmpty() || codec.isFormatFunctionallySupported(format))

@UnstableApi
internal fun supportsSoftwareFormat(codec: MediaCodecInfo, format: Format): Boolean =
    supportsSoftwareProfile(codec, format) &&
        (format.width <= 0 || format.height <= 0 || codec.capabilities?.videoCapabilities == null ||
            codec.isVideoSizeAndRateSupportedV21(format.width, format.height, format.frameRate.toDouble()))

@UnstableApi
internal fun videoDecoderCandidates(
    candidates: List<MediaCodecInfo>,
    format: Format,
    mode: VideoDecoderMode,
): List<MediaCodecInfo> = orderVideoDecoders(
    candidates.filter {
        if (it.softwareOnly) supportsSoftwareFormat(it, format) else mode != VideoDecoderMode.SOFTWARE_ONLY
    },
    { it.softwareOnly }, { it.hardwareAccelerated }, { it.isFormatSupported(format) },
) { mode == VideoDecoderMode.COMPATIBLE_HARDWARE && supportsHevcWithoutLevel(it, format) }

internal data class VideoDecoderSupport(val playable: Boolean, val softwareOnly: Boolean)

@UnstableApi
internal fun videoDecoderSupport(track: VideoTracks.Track, mode: VideoDecoderMode): VideoDecoderSupport =
    try {
        val format = track.videoFormat()
        val mime = format.sampleMimeType
        // Existing size probing remains the fallback for containers without a usable HEVC record.
        if (mime != MimeTypes.VIDEO_H265 || track.codecs == null) {
            VideoDecoderSupport(CodecCapabilities.supports(track), mode == VideoDecoderMode.SOFTWARE_ONLY)
        } else {
            val candidates = videoDecoderCandidates(MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false), format, mode)
            VideoDecoderSupport(candidates.isNotEmpty(), candidates.isNotEmpty() && candidates.all { it.softwareOnly })
        }
    } catch (_: Exception) {
        // A broken ROM query must not become a global ban. The renderer applies the final filter.
        VideoDecoderSupport(CodecCapabilities.supports(track), mode == VideoDecoderMode.SOFTWARE_ONLY)
    }

@UnstableApi
internal fun hasSoftwareVideoDecoder(format: Format?): Boolean = format?.sampleMimeType?.let { mime ->
    runCatching {
        // The retry opens and validates a new rendition. A decoder that cannot handle the failed
        // large frame can still play a lower tier; keep size checks in preflight and the renderer.
        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false).any { supportsSoftwareProfile(it, format) }
    }.getOrDefault(false)
} == true

@UnstableApi
internal fun acceptsPlaybackTrack(
    track: VideoTracks.Track?,
    safety: PlaybackSafetyPolicy,
    mode: VideoDecoderMode,
    failureScope: String,
    automaticQuality: Boolean = true,
): Boolean {
    if (track == null) return !automaticQuality || safety.accepts(null, mode == VideoDecoderMode.SOFTWARE_ONLY)
    if (automaticQuality && !CodecCapabilities.supports(track, failureScope)) return false
    val support = videoDecoderSupport(track, mode)
    return support.playable && (!automaticQuality || safety.accepts(track, support.softwareOnly))
}
