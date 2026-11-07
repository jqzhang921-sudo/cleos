package com.cleo.cleos.data

/** User-supplied text, not a fetched or verified web page. No network or clipboard access. */
object WorldPastedText {
    const val MAX_SOURCE = 480
    data class Text(val title: String, val source: String, val body: String)
    fun prepare(title: String, source: String, raw: String): Text {
        val heading = title.trim(); val credit = source.trim(); val body = WorldReading.normalize(raw)
        require(heading.isNotEmpty() && heading.length <= 120 && clean(heading, false)) { "请填写文章标题，最多 120 个字符" }
        require(credit.length <= MAX_SOURCE && clean(credit, false)) { "来源最多 480 个字符，请去掉换行或不可读字符" }
        require(body.isNotBlank() && body.length <= WorldReading.MAX_TEXT && clean(body, true)) { "请粘贴可读正文，每篇最多 50 万个字符" }
        require(!Regex("https?://\\S+", RegexOption.IGNORE_CASE).matches(body)) { "这里只粘贴网页正文，单独一个链接不能读取；链接可以填在来源里" }
        return Text(heading, "用户粘贴文字" + if (credit.isEmpty()) "" else " · $credit", body)
    }
    private fun clean(text: String, multiline: Boolean): Boolean {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isISOControl() && !(multiline && ch in "\n\t")) return false
            if (ch.isHighSurrogate()) {
                if (i + 1 == text.length || !text[i + 1].isLowSurrogate()) return false
                i++
            } else if (ch.isLowSurrogate()) return false
            i++
        }
        return true
    }
}
