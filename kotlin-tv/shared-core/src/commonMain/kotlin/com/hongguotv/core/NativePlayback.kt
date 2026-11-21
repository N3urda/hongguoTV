// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

internal expect fun nativeResumeTarget(count: Int, matched: Int, position: Long, completed: Boolean): ResumeTarget?

internal expect fun nativeRenditionIndex(rows: List<Rendition>, supported: BooleanArray, maximum: Int): Int?
