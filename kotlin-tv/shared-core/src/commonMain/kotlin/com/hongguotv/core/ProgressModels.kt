// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

data class BackupProgress(
    val series: Series,
    val episodeId: String,
    val episodeIndex: Int,
    val position: Long,
    val duration: Long,
    val completed: Boolean,
    val updatedAt: Long,
)

data class CloudProgress(
    val series: Series,
    val episodeId: String,
    val episodeIndex: Int,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val episodeCount: Int = 0,
)
