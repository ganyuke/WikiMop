package org.nextcolor.wikimop.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

enum class DiffKind { HEADER, CONTEXT, REMOVED, ADDED, MOVED }

/** A piece of a line. [marked] pieces are the words that changed inside the line. */
data class Span(val text: String, val marked: Boolean)

/**
 * One line of a single-column diff, which reads better on a narrow screen than
 * the two columns of the website.
 */
data class DiffLine(val kind: DiffKind, val spans: List<Span>, val oldNo: Int? = null, val newNo: Int? = null) {
    val text get() = spans.joinToString("") { it.text }
}

object DiffParser {
    private val lineNo = Regex("""(\d[\d,.\s]*)""")

    /** Turns the two-column table rows of a MediaWiki diff into single-column lines. */
    fun parse(tableRows: String): List<DiffLine> {
        val doc = Jsoup.parseBodyFragment("<table>$tableRows</table>")
        val out = ArrayList<DiffLine>()
        var oldNo = 0
        var newNo = 0
        for (row in doc.select("tr")) {
            val cells = row.children()
            val headers = cells.filter { it.hasClass("diff-lineno") }
            if (headers.isNotEmpty()) {
                oldNo = number(headers.first().text()) ?: oldNo
                newNo = number(headers.last().text()) ?: newNo
                out += DiffLine(DiffKind.HEADER, listOf(Span(headers.last().text().trim().trimEnd(':'), false)))
                continue
            }
            val context = cells.firstOrNull { it.hasClass("diff-context") }
            if (context != null) {
                out += DiffLine(DiffKind.CONTEXT, spans(context), oldNo++, newNo++)
                continue
            }
            cells.firstOrNull { it.hasClass("diff-deletedline") }?.let {
                val moved = it.selectFirst(".mw-diff-movedpara-left") != null
                out += DiffLine(if (moved) DiffKind.MOVED else DiffKind.REMOVED, spans(it), oldNo = oldNo++)
            }
            cells.firstOrNull { it.hasClass("diff-addedline") }?.let {
                out += DiffLine(DiffKind.ADDED, spans(it), newNo = newNo++)
            }
        }
        return out
    }

    private fun number(text: String) = lineNo.find(text)?.value?.filter { it.isDigit() }?.toIntOrNull()

    private fun spans(cell: Element): List<Span> {
        val out = ArrayList<Span>()
        fun walk(node: org.jsoup.nodes.Node, marked: Boolean) {
            when (node) {
                is TextNode -> if (node.wholeText.isNotEmpty()) out += Span(node.wholeText, marked)
                is Element -> {
                    val inner = marked || node.tagName() == "ins" || node.tagName() == "del"
                    node.childNodes().forEach { walk(it, inner) }
                }
            }
        }
        walk(cell, false)
        return out
    }
}

/** Compares two texts on the phone, so an edit can be reviewed exactly as it will be sent. */
object LocalDiff {
    private const val CONTEXT = 2
    private const val MAX_CELLS = 4_000_000L

    fun lines(old: String, new: String): List<DiffLine> {
        if (old == new) return emptyList()
        val a = if (old.isEmpty()) emptyList() else old.split('\n')
        val b = if (new.isEmpty()) emptyList() else new.split('\n')
        val ops = ops(a, b)

        // Pair a run of removed lines with the added lines that follow it, to mark the changed words.
        val rows = ArrayList<DiffLine>()
        var i = 0
        var oldNo = 1
        var newNo = 1
        while (i < ops.size) {
            if (ops[i].first == '=') {
                rows += DiffLine(DiffKind.CONTEXT, listOf(Span(ops[i].second, false)), oldNo++, newNo++)
                i++
                continue
            }
            val removed = ArrayList<String>()
            val added = ArrayList<String>()
            while (i < ops.size && ops[i].first != '=') {
                if (ops[i].first == '-') removed += ops[i].second else added += ops[i].second
                i++
            }
            val paired = removed.size == added.size
            removed.forEachIndexed { n, line ->
                val spans = if (paired) words(line, added[n]).first else listOf(Span(line, false))
                rows += DiffLine(DiffKind.REMOVED, spans, oldNo = oldNo++)
            }
            added.forEachIndexed { n, line ->
                val spans = if (paired) words(removed[n], line).second else listOf(Span(line, false))
                rows += DiffLine(DiffKind.ADDED, spans, newNo = newNo++)
            }
        }
        return withContext(rows)
    }

    /** Keeps the changed lines and a little around them, with a header before each group. */
    private fun withContext(rows: List<DiffLine>): List<DiffLine> {
        val keep = BooleanArray(rows.size)
        rows.forEachIndexed { n, row ->
            if (row.kind != DiffKind.CONTEXT) {
                for (k in maxOf(0, n - CONTEXT)..minOf(rows.lastIndex, n + CONTEXT)) keep[k] = true
            }
        }
        val out = ArrayList<DiffLine>()
        var previous = -2
        rows.forEachIndexed { n, row ->
            if (!keep[n]) return@forEachIndexed
            if (n != previous + 1) {
                out += DiffLine(DiffKind.HEADER, listOf(Span("Line ${row.newNo ?: row.oldNo}", false)))
            }
            out += row
            previous = n
        }
        return out
    }

    private fun ops(a: List<String>, b: List<String>): List<Pair<Char, String>> {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        val out = ArrayList<Pair<Char, String>>()
        for (k in 0 until start) out += '=' to a[k]
        val midA = a.subList(start, endA)
        val midB = b.subList(start, endB)
        if (midA.size.toLong() * midB.size > MAX_CELLS) {
            midA.forEach { out += '-' to it }
            midB.forEach { out += '+' to it }
        } else {
            out += lcs(midA, midB)
        }
        for (k in endA until a.size) out += '=' to a[k]
        return out
    }

    private fun <T> lcs(a: List<T>, b: List<T>): List<Pair<Char, T>> {
        val n = a.size
        val m = b.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
        val out = ArrayList<Pair<Char, T>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += '=' to a[i]; i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> { out += '-' to a[i]; i++ }
                else -> { out += '+' to b[j]; j++ }
            }
        }
        while (i < n) out += '-' to a[i++]
        while (j < m) out += '+' to b[j++]
        return out
    }

    private val token = Regex("""\s+|\w+|[^\w\s]""")

    private fun words(old: String, new: String): Pair<List<Span>, List<Span>> {
        val a = token.findAll(old).map { it.value }.toList()
        val b = token.findAll(new).map { it.value }.toList()
        if (a.size.toLong() * b.size > 250_000) return listOf(Span(old, true)) to listOf(Span(new, true))
        val left = ArrayList<Span>()
        val right = ArrayList<Span>()
        for ((op, word) in lcs(a, b)) when (op) {
            '=' -> { left += Span(word, false); right += Span(word, false) }
            '-' -> left += Span(word, true)
            else -> right += Span(word, true)
        }
        return merge(left) to merge(right)
    }

    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (span in spans) {
            val last = out.lastOrNull()
            if (last != null && last.marked == span.marked) out[out.lastIndex] = Span(last.text + span.text, span.marked)
            else out += span
        }
        return out
    }
}
