// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan

/**
 * Generic Fluent emoji illustrations, not the platform's original stickers. Unknown tags remain
 * intact.
 */
class CommentEmoji(private val context: Context) {
    private val names =
        mapOf(
            "笑哭" to "1f602",
            "微笑" to "1f642",
            "呲牙" to "1f601",
            "大笑" to "1f606",
            "流泪" to "1f622",
            "大哭" to "1f62d",
            "赞" to "1f44d",
            "点赞" to "1f44d",
            "鼓掌" to "1f44f",
            "爱心" to "2764-fe0f",
            "比心" to "2764-fe0f",
            "玫瑰" to "1f339",
            "捂脸" to "1f926",
            "偷笑" to "1f92d",
            "思考" to "1f914",
            "害羞" to "1f60a",
            "发怒" to "1f620",
            "惊讶" to "1f62e",
        )
    private val cache = mutableMapOf<String, android.graphics.Bitmap>()

    fun render(text: String, size: Float): CharSequence {
        val result = SpannableStringBuilder(text)
        val pattern = Regex("""(?:\[|［|【)([^\]］】]{1,20})(?:\]|］|】)""")
        for (match in pattern.findAll(text)) {
            val file = names[match.groupValues[1]] ?: continue
            val bitmap =
                cache[file]
                    ?: runCatching {
                            context.assets.open("comment_emoji/$file.webp").use {
                                BitmapFactory.decodeStream(it)
                            }
                        }
                        .getOrNull()
                        ?.also { cache[file] = it }
                    ?: continue
            val drawable =
                BitmapDrawable(context.resources, bitmap).apply {
                    setBounds(0, 0, size.toInt(), size.toInt())
                }
            result.setSpan(
                ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return result
    }
}
