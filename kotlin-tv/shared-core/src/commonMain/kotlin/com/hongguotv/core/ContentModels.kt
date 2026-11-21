// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

data class Series(
    val id: String,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val badge: String = "",
    val tags: String = "",
) {
    companion object
}

data class Detail(val series: Series, val episodes: List<String>)

data class RankPosition(val rank: Int?, val heat: String, val metrics: String = "")

data class ComicRanking(
    val positions: Map<String, RankPosition>,
    val totalPages: Int,
    val updatedText: String,
)

data class TrialHint(val episodeId: String, val episodeCount: Int)

data class CatalogPage(
    val items: List<Series>,
    val hasMore: Boolean,
    val ranking: ComicRanking? = null,
    val discovery: DiscoveryInfo? = null,
    val trialHints: Map<String, TrialHint> = emptyMap(),
)

data class StreamInfo(val url: String, val key: ByteArray?, val quality: String, val backupUrl: String? = null)

private val AVC_LABELS = setOf("h264", "avc", "avc1", "avc3")

/** One playable rendition; width/height are the coded dimensions when the API reports them. */
data class Rendition(
    val quality: Int,
    val codec: String,
    val width: Int?,
    val height: Int?,
    val url: String,
    val key: ByteArray?,
    val backupUrl: String? = null,
) {
    internal val isAvc get() = codec in AVC_LABELS

    fun toStreamInfo() = StreamInfo(url, key, if (quality > 0) "${quality}P" else "自动", backupUrl)
}

/**
 * All playable renditions of one episode. Capability-first selection: TV boxes commonly cap AVC
 * decoders at 1920x1088 with width/height checked independently, rejecting portrait sizes.
 */
class StreamLadder(val renditions: List<Rendition>) {
    // Ascending order: a greater element is the better tier (higher quality, h264 over others).
    // maxWithOrNull picks the best tier; minWithOrNull yields the closest-below fallback.
    private val better =
        compareBy<Rendition> { it.quality }.thenBy { if (it.isAvc) 1 else 0 }

    fun select(maxQuality: Int, supported: (Rendition) -> Boolean = { true }): Rendition? {
        val compatible = BooleanArray(renditions.size) { supported(renditions[it]) }
        nativeRenditionIndex(renditions, compatible, maxQuality)?.let { return renditions[it] }
        val pool = renditions.filterIndexed { index, _ -> compatible[index] }.ifEmpty { renditions }
        return pool.filter { it.quality in 1..maxQuality }.maxWithOrNull(better)
            ?: pool.filter { it.quality <= 0 }.maxWithOrNull(better)
            ?: pool.minWithOrNull(better)
    }

    /** select() first, then every lower or equal tier best-first for verified downgrade. */
    fun playbackOrder(
        maxQuality: Int,
        supported: (Rendition) -> Boolean = { true },
    ): List<Rendition> {
        val pick = select(maxQuality, supported) ?: return emptyList()
        val alternates =
            renditions
                .filter { it !== pick && it.quality <= pick.quality }
                .sortedWith(better.reversed())
        return listOf(pick) + alternates
    }
}
