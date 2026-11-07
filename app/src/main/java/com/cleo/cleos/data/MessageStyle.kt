package com.cleo.cleos.data

import com.cleo.cleos.data.db.CompanionEntity

/** An expression preference, never a client-side text splitter or a permission to send. */
enum class MessageStyle(val id: String, val title: String, val description: String) {
    Natural("natural", "自然发送", "日常聊天可以分条，认真、连贯的表达保留为一条。"),
    Separate("separate", "偏好分条", "几个独立意思尽量分开发送，完整长文仍可保持一条。"),
    Complete("complete", "偏好长文", "更倾向一次完整展开，保留段落和前后文。"),
    ;

    fun instruction(messagesEnabled: Boolean): String = buildString {
        append("私聊文字消息的发送偏好：$title。这是表达偏好，不是固定字数或消息条数。")
        append("优先遵守对方本轮直接提出的格式要求，例如完整说、别分条或分开发；引用或材料中的这些词不自动改变偏好。")
        when (this@MessageStyle) {
            Natural -> append("日常聊天可按几个独立意思自然分条，连贯的长表达整条发送。")
            Separate -> append("平常聊天有多个独立意思时，优先分成几条自然的消息，一条说清一个意思；只有一个意思时一条就够，不为了凑条数拆句。")
            Complete -> append("更倾向一次完整展开，把前后文和段落留在同一条里，减少零碎气泡；对方本轮明确要分条时再分开发送。")
        }
        append("认真表达、连续解释、信件、诗歌、代码、列表和引用需要保持完整；不能只因为字多或有换行就拆开，拿不准时保留整条。")
        if (messagesEnabled) {
            append("确实要发几条时，在同一次回复中按顺序调用几次 send_message，每次是一条完整消息。")
            append("完整的一条可直接回复，也可只调用一次 send_message；已经发出的内容不再重复，也不追加发送完成的说明。")
        } else {
            append("本次没有分开发送的能力，直接用完整的普通正文回复，可以自然分段，不输出分条标记或调用代码。")
        }
        append("这些偏好只影响文字组织，不要求额外请求或补发消息，也不改变语音、通话或其他操作的使用条件。")
    }

    companion object {
        fun of(value: String): MessageStyle = entries.firstOrNull { it.id == value } ?: Natural
        fun valid(value: String) = entries.any { it.id == value }
        fun validateBackup(companions: List<CompanionEntity>) {
            require(companions.all { valid(it.messageStyle) }) { "消息发送偏好无效，未恢复" }
        }
    }
}
