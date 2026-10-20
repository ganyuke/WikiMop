package org.nextcolor.wikimop.logic

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** Text with a selection. [start] equals [end] when nothing is selected. */
data class Edited(val text: String, val start: Int, val end: Int = start)

/** A link or template name that is being typed, which page names can be offered for. */
data class Completion(val template: Boolean, val from: Int, val prefix: String)

object EditorOps {
    /** Puts [before] and [after] around the selection, or around [placeholder] when nothing is selected. */
    fun wrap(e: Edited, before: String, after: String, placeholder: String = ""): Edited {
        val selected = e.text.substring(e.start, e.end).ifEmpty { placeholder }
        val text = e.text.substring(0, e.start) + before + selected + after + e.text.substring(e.end)
        val from = e.start + before.length
        return Edited(text, from, from + selected.length)
    }

    /** Starts every selected line with [prefix], or takes it off when they all have it. */
    fun prefixLines(e: Edited, prefix: String): Edited {
        val first = e.text.lastIndexOf('\n', e.start - 1) + 1
        val last = e.text.indexOf('\n', e.end).let { if (it < 0) e.text.length else it }
        val lines = e.text.substring(first, last).split('\n')
        val remove = lines.all { it.startsWith(prefix) }
        val changed = lines.joinToString("\n") { if (remove) it.removePrefix(prefix) else prefix + it }
        val text = e.text.substring(0, first) + changed + e.text.substring(last)
        return Edited(text, first, first + changed.length)
    }

    /** Makes the current line a heading of [level], or plain text again when it already is one. */
    fun heading(e: Edited, level: Int): Edited {
        val first = e.text.lastIndexOf('\n', e.start - 1) + 1
        val last = e.text.indexOf('\n', e.start).let { if (it < 0) e.text.length else it }
        val bare = e.text.substring(first, last).trim().trim('=').trim()
        val marks = "=".repeat(level)
        val was = e.text.substring(first, last).trim()
        val line = if (was == "$marks $bare $marks") bare else "$marks ${bare.ifEmpty { "Heading" }} $marks"
        val text = e.text.substring(0, first) + line + e.text.substring(last)
        return Edited(text, first + line.length)
    }

    fun insert(e: Edited, piece: String): Edited =
        Edited(e.text.substring(0, e.start) + piece + e.text.substring(e.end), e.start + piece.length)

    /** Looks back from the cursor for an unfinished [[link or {{template name. */
    fun completion(text: String, cursor: Int): Completion? {
        var i = cursor - 1
        while (i >= 1 && cursor - i <= 80) {
            val c = text[i]
            if (c == '\n' || c == '|' || c == ']' || c == '}' || c == '#') return null
            if ((c == '[' && text[i - 1] == '[') || (c == '{' && text[i - 1] == '{')) {
                // {{{ is a template parameter, not a template.
                if (c == '{' && i >= 2 && text[i - 2] == '{') return null
                val prefix = text.substring(i + 1, cursor)
                if (prefix.isBlank() || prefix.startsWith(" ")) return null
                return Completion(c == '{', i + 1, prefix)
            }
            i--
        }
        return null
    }

    /** Replaces what was typed with [title] and closes the brackets when they are not closed yet. */
    fun complete(e: Edited, c: Completion, title: String): Edited {
        val name = if (c.template) title.substringAfter(':') else title
        val close = if (c.template) "}}" else "]]"
        val rest = e.text.substring(e.start)
        val closed = rest.startsWith(close) || rest.startsWith("|")
        val text = e.text.substring(0, c.from) + name + (if (closed) "" else close) + rest
        // A template is left open for its parameters, a link is finished.
        val cursor = c.from + name.length + if (closed || c.template) 0 else close.length
        return Edited(text, cursor)
    }

    fun matches(text: String, query: String, matchCase: Boolean): List<Int> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var at = text.indexOf(query, 0, !matchCase)
        while (at >= 0) {
            out += at
            at = text.indexOf(query, at + query.length, !matchCase)
        }
        return out
    }

    fun replaceAll(text: String, query: String, with: String, matchCase: Boolean): String =
        if (query.isEmpty()) text else text.replace(query, with, !matchCase)
}

data class SyntaxColors(val template: Color, val link: Color, val tag: Color, val comment: Color, val mark: Color, val heading: Color)

object Wikitext {
    /** Above this size the text is shown plain, so typing stays quick on very long pages. */
    const val LIMIT = 120_000

    private val pattern = Regex(
        """(?<comment><!--[\s\S]*?(?:-->|$))""" +
            """|(?<heading>^=+.*=+[ \t]*$)""" +
            """|(?<tname>\{\{[^{}|\n]*)""" +
            """|(?<brace>\}\}|\{\{\{|\}\}\})""" +
            """|(?<link>\[\[[^\[\]|\n]*)""" +
            """|(?<lclose>\]\])""" +
            """|(?<param>\|[ \t]*[\w \-]+?[ \t]*=)""" +
            """|(?<tag></?[a-zA-Z][^<>\n]*>)""" +
            """|(?<quote>'{2,5})""" +
            """|(?<list>^[*#:;]+)""",
        RegexOption.MULTILINE,
    )

    /** Hands each colored stretch of [text] to [add], as the style and where it starts and ends. */
    fun spans(text: CharSequence, colors: SyntaxColors, add: (SpanStyle, Int, Int) -> Unit) {
        if (text.length > LIMIT) return
        for (match in pattern.findAll(text)) {
            val groups = match.groups
            val range = match.range
            fun style(style: SpanStyle, from: Int = range.first, to: Int = range.last + 1) = add(style, from, to)
            when {
                groups["comment"] != null -> style(SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic))
                groups["heading"] != null -> style(SpanStyle(color = colors.heading, fontWeight = FontWeight.Bold))
                groups["tname"] != null -> style(SpanStyle(color = colors.template, fontWeight = FontWeight.Medium))
                groups["brace"] != null -> style(SpanStyle(color = colors.template))
                groups["link"] != null -> style(SpanStyle(color = colors.link))
                groups["lclose"] != null -> style(SpanStyle(color = colors.link))
                groups["param"] != null -> style(SpanStyle(color = colors.template), range.first + 1, range.last)
                groups["tag"] != null -> style(SpanStyle(color = colors.tag))
                groups["quote"] != null -> style(SpanStyle(color = colors.mark))
                groups["list"] != null -> style(SpanStyle(color = colors.mark, fontWeight = FontWeight.Bold))
            }
        }
    }
}
