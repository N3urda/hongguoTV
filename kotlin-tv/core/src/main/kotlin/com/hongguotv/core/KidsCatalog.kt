// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Only accept the provider's explicit taxonomy, never matches in title or description. */
object KidsCatalog {
    const val LABEL = "少儿"

    fun selection(groups: List<DiscoveryGroup>): DiscoverySelection? =
        groups.asSequence().filter { it.type == "category_dim_theme" || it.type == "genre" }
            .firstNotNullOfOrNull { group ->
                group.options.firstOrNull { it.label.trim() == LABEL }
                    ?.let { DiscoverySelection().choose(group.type, it.id) }
            }

    fun includes(series: Series): Boolean = series.tags.split('·').any { it.trim() == LABEL }
}
