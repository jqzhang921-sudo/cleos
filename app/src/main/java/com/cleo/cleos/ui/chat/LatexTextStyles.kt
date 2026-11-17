package com.cleo.cleos.ui.chat

/** A small text-style reader, not a TeX interpreter. Never changes stored messages or model input. */
internal object LatexTextStyles {
    data class Style(val scale: Float = 1f, val bold: Boolean = false, val italic: Boolean = false, val color: String? = null)
    data class Run(val text: String, val style: Style)
    data class Fragment(val consumed: Int, val runs: List<Run>) {
        val text: String get() = runs.joinToString("") { it.text }
    }
    private val sizes = mapOf("tiny" to .9f, "scriptsize" to .9f, "footnotesize" to .9f, "small" to .9f,
        "normalsize" to 1f, "large" to 1.18f, "Large" to 1.18f, "LARGE" to 1.18f, "huge" to 1.18f, "Huge" to 1.18f)
    private val wrappers = setOf("textbf", "textmd", "textnormal", "textit", "emph", "textrm", "text", "textcolor")
    private val start = Regex("""\\(?:textbf|textmd|textnormal|textit|emph|textrm|text|textcolor|tiny|scriptsize|footnotesize|small|normalsize|large|Large|LARGE|huge|Huge)\b|\{\s*\\(?:color|bfseries|mdseries|itshape|upshape|tiny|scriptsize|footnotesize|small|normalsize|large|Large|LARGE|huge|Huge)\b""")
    fun mayContain(text: String): Boolean = start.containsMatchIn(text)

    fun at(source: String, at: Int): Fragment? {
        if (source.getOrNull(at) !in listOf('\\', '{')) return null
        if (at > 0 && source[at - 1] == '\\') return null
        val commandAt = if (source[at] == '{') {
            var i = at + 1
            while (source.getOrNull(i)?.isWhitespace() == true) i++
            i
        } else at
        if (source.getOrNull(commandAt) != '\\') return null
        return runCatching { Reader(source, at).read() }.getOrNull()
    }

    fun whole(source: String): Fragment? {
        val trimmed = source.trim()
        val found = at(trimmed, 0) ?: return null
        // An expression with math operators needs the formula renderer, not literal text spans.
        return found.takeIf { it.consumed == trimmed.length && it.text.none { c -> c == '^' || c == '_' } }
    }

    private class Reader(val source: String, val begin: Int) {
        var p = begin
        var touched = false
        val result = mutableListOf<Run>()
        fun read(): Fragment? {
            if (source[p] == '{') scope(Style(), 0) else command(Style(), 0, root = true)
            return if (touched && result.isNotEmpty()) Fragment(p - begin, result.toList()) else null
        }
        fun checkBound() { require(p - begin <= 1600 && result.size <= 96) }
        fun skipSpace() { while (source.getOrNull(p)?.isWhitespace() == true) { p++; checkBound() } }
        fun word(): String {
            require(source.getOrNull(p) == '\\'); p++
            val from = p
            while (source.getOrNull(p)?.isLetter() == true && source[p].code < 128) p++
            require(p > from); checkBound()
            return source.substring(from, p)
        }
        fun color(): String {
            skipSpace(); require(source.getOrNull(p) == '{'); p++
            val from = p
            while (source.getOrNull(p) != '}' && p < source.length && p - from <= 20) p++
            require(source.getOrNull(p) == '}')
            val value = source.substring(from, p).trim(); p++; checkBound()
            require(Regex("""[a-zA-Z]{1,16}|#[0-9a-fA-F]{6}""").matches(value))
            return value
        }
        fun append(text: String, style: Style) {
            if (text.isEmpty()) return
            if (result.lastOrNull()?.style == style) result[result.lastIndex] = Run(result.last().text + text, style)
            else result += Run(text, style)
            checkBound()
        }
        fun scope(initial: Style, depth: Int) {
            require(depth < 12 && source.getOrNull(p) == '{'); p++
            var style = initial
            while (p < source.length && source[p] != '}') {
                checkBound()
                when (source[p]) {
                    '{' -> scope(style, depth + 1)
                    '\\' -> {
                        val next = source.getOrNull(p + 1)
                        if (next in listOf('{', '}', '\\', '%', '#', '$', '_', '&')) {
                            append(if (next == '\\') "\n" else next.toString(), style); p += 2
                        } else style = command(style, depth + 1)
                    }
                    '`' -> error("Code stays literal")
                    else -> { append(source[p].toString(), style); p++ }
                }
            }
            require(source.getOrNull(p) == '}'); p++; checkBound()
        }
        fun command(style: Style, depth: Int, root: Boolean = false): Style {
            val name = word()
            val scale = sizes[name]
            if (scale != null) {
                touched = true
                if (root) { skipSpace(); scope(style.copy(scale = scale), depth + 1); return style }
                return style.copy(scale = scale)
            }
            if (!root && name in setOf("color", "bfseries", "mdseries", "itshape", "upshape")) {
                touched = true
                return when (name) {
                    "color" -> style.copy(color = color())
                    "bfseries" -> style.copy(bold = true)
                    "mdseries" -> style.copy(bold = false)
                    "itshape" -> style.copy(italic = true)
                    else -> style.copy(italic = false)
                }
            }
            require(name in wrappers)
            val child = when (name) {
                "textbf" -> style.copy(bold = true)
                "textmd" -> style.copy(bold = false)
                "textnormal", "textrm" -> style.copy(bold = false, italic = false)
                "textit", "emph" -> style.copy(italic = true)
                "textcolor" -> style.copy(color = color())
                else -> style
            }
            touched = true; skipSpace(); scope(child, depth + 1)
            return style
        }
    }
}
