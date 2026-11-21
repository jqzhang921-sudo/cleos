package com.cleo.cleos.ui.chat

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

internal val LocalChatType = compositionLocalOf { ChatType(ChatType.DEFAULT) }

/**
 * The chat's text: its size (设置 › 聊天), with lines 1.6 times as tall, so smaller text still has
 * room to breathe. The extra goes between lines only, trimmed above the first and below the last:
 * a one-line bubble is no taller for it.
 */
internal class ChatType(val size: Int) {
    val body = style(size)

    /** What a voice message said, under its player: a step smaller than what is written. */
    val small = style(size - 1)

    companion object {
        /** What the chat's text can be set to. */
        val SIZES = listOf(14, 15, 16, 17)
        const val DEFAULT = 15
        private const val LINE = 1.6f

        private fun style(size: Int) = TextStyle(
            fontSize = size.sp,
            lineHeight = (size * LINE).sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        )
    }
}
