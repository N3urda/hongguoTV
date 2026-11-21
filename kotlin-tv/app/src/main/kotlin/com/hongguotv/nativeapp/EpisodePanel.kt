// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.widget.LinearLayout

/** Inline playback expansion using the detail page's episode selector. */
class EpisodePanel(
    private val activity: Activity,
    private val count: Int,
    private val current: Int,
    private val locate: (Int, View, (Int) -> Unit) -> Unit,
    private val select: (Int) -> Unit,
    private val closed: () -> Unit,
    private val lastWatched: Int? = null,
    private val lastCompleted: Boolean = false,
) {
    private var group = current / 20
    private var host: LinearLayout? = null
    private lateinit var anchor: View
    private val content = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }
    private val downPaths = mutableListOf<Pair<View, Int>>()

    fun show(host: LinearLayout, anchor: View) {
        if (this.host != null) return
        this.host = host
        this.anchor = anchor
        val menu = host.getChildAt(0) as? LinearLayout
        if (menu != null) for (i in 0 until menu.childCount) {
            val button = menu.getChildAt(i)
            downPaths += button to button.nextFocusDownId
        }
        host.addView(content, minOf(1, host.childCount))
        render()
    }

    fun dismiss() {
        val parent = host ?: return
        host = null
        parent.removeView(content)
        downPaths.forEach { (view, id) -> view.nextFocusDownId = id }
        downPaths.clear()
        if (anchor.isAttachedToWindow) anchor.requestFocus()
        closed()
    }

    private fun render(focusGroup: Boolean = false) {
        content.removeAllViews()
        val selector = EpisodeSelectorView(
            activity, count, current, group, lastWatched, lastCompleted,
            onGroup = { next -> group = next; render(focusGroup = true) },
            onJump = { focusedEpisode, view -> locate(focusedEpisode, view) { target -> if (target in 0 until count) select(target) } },
            onSelect = select,
            transparent = true,
            showJump = false,
        )
        content.addView(selector)
        selector.groupButtons.forEach { it.nextFocusUpId = anchor.id }
        selector.jump.nextFocusUpId = anchor.id
        downPaths.forEach { (view, _) -> view.nextFocusDownId = selector.groupButtons[group].id }
        if (focusGroup) selector.groupButtons[group].requestFocus()
        else (selector.episodes.getOrNull(current - group * 20) ?: selector.jump).requestFocus()
    }
}
