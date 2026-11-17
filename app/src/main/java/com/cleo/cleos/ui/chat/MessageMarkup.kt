package com.cleo.cleos.ui.chat

/** Display-only, bounded scanner. Code, prices and unfinished delimiters are never rewritten. */
internal object MessageMarkup {
    const val MAX_LENGTH = 32_000
    private const val PREFIX = "CLEOSMATHPLACEHOLDER"
    data class Formula(val token: String, val raw: String, val tex: String, val block: Boolean)
    data class Tint(val start: String, val end: String, val requested: String)
    data class StyledText(val token: String, val fragment: LatexTextStyles.Fragment)
    data class Plan(val markdown: String, val formulas: List<Formula>, val tints: List<Tint> = emptyList(), val styles: List<StyledText> = emptyList())
    private val colorTag = Regex("""<(span\s+style=["']color:\s*([a-zA-Z]+|#[0-9a-fA-F]{6});?["']|font\s+color=["']([a-zA-Z]+|#[0-9a-fA-F]{6})["'])>([^<>`\n]{1,1000})</(?:span|font)>""")
    private val commands = Regex("""\\([a-zA-Z]+)""")
    // An allowlist also excludes file/network access, mutable macro definitions and size primitives.
    private val allowed = ("frac dfrac tfrac sqrt left right middle over atop choose binom dbinom tbinom " +
        "sum prod int iint iiint oint lim limsup liminf infty partial nabla prime cdot cdots ldots vdots ddots " +
        "times div pm mp le leq ge geq ne neq approx sim simeq equiv propto to mapsto rightarrow leftarrow " +
        "Rightarrow Leftarrow Leftrightarrow leftrightarrow implies iff in notin subset supset subseteq supseteq " +
        "cup cap emptyset forall exists neg land lor wedge vee angle perp parallel degree circ top bot " +
        "alpha beta gamma delta epsilon varepsilon zeta eta theta vartheta iota kappa lambda mu nu xi omicron " +
        "pi varpi rho varrho sigma varsigma tau upsilon phi varphi chi psi omega Gamma Delta Theta Lambda Xi " +
        "Pi Sigma Upsilon Phi Psi Omega sin cos tan cot sec csc arcsin arccos arctan sinh cosh tanh log ln exp " +
        "min max sup inf det gcd mod bmod pmod operatorname text textrm mathrm mathbf mathit mathsf mathtt " +
        "mathcal mathbb boldsymbol vec hat widehat bar overline underline dot ddot tilde widetilde " +
        "overbrace underbrace begin end quad qquad enskip thinspace backslash lbrace rbrace langle rangle " +
        "lvert rvert lVert rVert vert Vert ell hbar Re Im aleph nothing displaystyle textstyle textcolor color").split(' ').toSet()
    private val environments = Regex("""\\(?:begin|end)\{([^}]+)\}""")
    private val safeEnvironments = setOf("matrix", "pmatrix", "bmatrix", "vmatrix", "Vmatrix", "cases", "aligned", "array")

    fun safe(tex: String): Boolean {
        if (tex.isBlank() || tex.length > 1_600 || tex.count { it == '\\' } > 180) return false
        var depth = 0
        tex.forEachIndexed { i, c ->
            if (!escaped(tex, i)) {
                if (c == '{' && ++depth > 24) return false
                if (c == '}' && --depth < 0) return false
            }
        }
        return depth == 0 && commands.findAll(tex).all { escaped(tex, it.range.first) || it.groupValues[1] in allowed } &&
            environments.findAll(tex).all { it.groupValues[1] in safeEnvironments } &&
            !Regex("""[\p{Cntrl}&&[^\n\r\t]]""").containsMatchIn(tex)
    }

    fun needsRendering(text: String): Boolean = text.length <= MAX_LENGTH &&
        (text.any { it == '$' || it == '`' } || listOf("\\(", "\\[", "**", "__", "<span", "<font", "\n- ", "\n1. ", "\n> ").any { it in text } ||
            Regex("""(?m)^\s*(?:#{1,6} |[-+*] |\d+\. |> )|(?:^|\s)[*_][^*_\n]+[*_](?:$|\s)""").containsMatchIn(text) || LatexTextStyles.mayContain(text))

    fun scan(text: String): Plan {
        if (text.length > MAX_LENGTH || PREFIX in text) return Plan(text, emptyList())
        val out = StringBuilder()
        val formulas = mutableListOf<Formula>()
        val tints = mutableListOf<Tint>()
        val styles = mutableListOf<StyledText>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '<' && tints.size < 24) {
                val tint = colorTag.find(text, i)?.takeIf { it.range.first == i }
                if (tint != null) {
                    val n = tints.size
                    val start = "${PREFIX}COLOR${n}START"
                    val end = "${PREFIX}COLOR${n}STOP"
                    tints += Tint(start, end, tint.groupValues[2].ifEmpty { tint.groupValues[3] })
                    out.append(start).append(tint.groupValues[4]).append(end)
                    i = tint.range.last + 1; continue
                }
            }
            // Fences and inline backticks are copied verbatim, including an unfinished code block.
            if ((c == '`' || c == '~' && text.startsWith("~~~", i)) && !escaped(text, i)) {
                var count = 1
                while (text.getOrNull(i + count) == c) count++
                val fence = c.toString().repeat(count)
                val end = text.indexOf(fence, i + count)
                val next = if (end < 0) text.length else end + count
                out.append(text, i, next); i = next; continue
            }
            val open = when {
                escaped(text, i) -> null
                text.startsWith("$$", i) -> "$$"
                c == '$' -> "$"
                text.startsWith("\\(", i) -> "\\("
                text.startsWith("\\[", i) -> "\\["
                else -> null
            }
            if (open == null && styles.size < 24 && (c == '\\' || c == '{') && !escaped(text, i)) {
                val fragment = LatexTextStyles.at(text, i)
                if (fragment != null) {
                    val token = "${PREFIX}STYLE${styles.size}END"
                    styles += StyledText(token, fragment); out.append(token); i += fragment.consumed; continue
                }
            }
            if (open == null || formulas.size >= 24) { out.append(c); i++; continue }
            val close = when (open) { "\\(" -> "\\)"; "\\[" -> "\\]"; else -> open }
            var end = text.indexOf(close, i + open.length)
            while (end >= 0 && escaped(text, end)) end = text.indexOf(close, end + close.length)
            if (end < 0) { if (c == '\\') out.append('\\'); out.append(c); i++; continue }
            val tex = text.substring(i + open.length, end)
            // A single dollar followed by a price is ambiguous: retain it as literal money.
            val price = open == "$" && (tex.contains('\n') || tex.firstOrNull()?.isWhitespace() == true ||
                text.getOrNull(end - 1)?.isWhitespace() == true ||
                tex.firstOrNull()?.isDigit() == true && !Regex("""^\d+(?:\.\d+)?[+*/^_=({\\-]""").containsMatchIn(tex))
            // A stray dollar/price must not swallow an independent formula on a later line.
            if (price) { out.append(c); i++; continue }
            val token = PREFIX + formulas.size + "END"
            val block = open == "$$" || open == "\\["
            formulas += Formula(token, text.substring(i, end + close.length), tex, block)
            if (block) out.append("\n\n")
            out.append(token)
            if (block) out.append("\n\n")
            i = end + close.length
        }
        return Plan(out.toString(), formulas, tints, styles)
    }

    /** Only colour directives are removed; all expression braces/content remain intact. */
    fun withoutMathColors(tex: String): Pair<String, String?> {
        var requested: String? = null
        val stripped = Regex("""\\(textcolor|color)\{([a-zA-Z]+|#[0-9a-fA-F]{6})\}""").replace(tex) {
            if (requested == null) requested = it.groupValues[2]
            ""
        }
        // Reject remaining malformed/unsupported colour syntax instead of executing arbitrary TeX.
        return stripped to requested
    }

    private fun escaped(s: String, at: Int): Boolean {
        var p = at - 1
        while (p >= 0 && s[p] == '\\') p--
        return (at - p - 1) % 2 == 1
    }
}
