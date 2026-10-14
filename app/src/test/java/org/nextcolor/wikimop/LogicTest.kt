package org.nextcolor.wikimop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nextcolor.wikimop.data.DiffKind
import org.nextcolor.wikimop.data.DiffParser
import org.nextcolor.wikimop.data.LocalDiff
import org.nextcolor.wikimop.data.WikiApi
import org.nextcolor.wikimop.data.wikiBase
import org.nextcolor.wikimop.logic.Edited
import org.nextcolor.wikimop.logic.EditorOps
import java.time.Instant

class LogicTest {
    @Test fun websiteDiffBecomesOneColumn() {
        val lines = DiffParser.parse(FakeWiki.DIFF)
        assertEquals(listOf(DiffKind.HEADER, DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.ADDED, DiffKind.ADDED, DiffKind.ADDED, DiffKind.ADDED), lines.map { it.kind })
        assertEquals("Line 8", lines[0].text)
        assertEquals(8, lines[1].newNo)
        assertEquals("war", lines[2].spans.single { it.marked }.text)
        assertEquals("Long Dusk", lines[3].spans.single { it.marked }.text)
        assertEquals(9, lines[3].newNo)
        assertEquals("* [[Wick Knight]]", lines.last().text)
        assertEquals(13, lines.last().newNo)
    }

    @Test fun localDiffMarksChangedWords() {
        val old = "one\ntwo\nthree\nfour\nfive\nsix\nseven"
        val new = "one\ntwo\nthree\nfour and more\nfive\nsix\nseven\neight"
        val lines = LocalDiff.lines(old, new)
        assertEquals(1, lines.count { it.kind == DiffKind.REMOVED })
        assertEquals(2, lines.count { it.kind == DiffKind.ADDED })
        val added = lines.first { it.kind == DiffKind.ADDED }
        assertEquals(" and more", added.spans.filter { it.marked }.joinToString("") { it.text })
        assertEquals(4, added.newNo)
        assertFalse(lines.any { it.text == "one" })
        assertTrue(LocalDiff.lines("same", "same").isEmpty())
        assertEquals(2, LocalDiff.lines("", "a\nb").count { it.kind == DiffKind.ADDED })
    }

    @Test fun toolbarChangesText() {
        assertEquals(Edited("a '''b''' c", 5, 6), EditorOps.wrap(Edited("a b c", 2, 3), "'''", "'''"))
        assertEquals(Edited("[[]]", 2, 2), EditorOps.wrap(Edited("", 0), "[[", "]]"))
        assertEquals("* a\n* b", EditorOps.prefixLines(Edited("a\nb", 0, 3), "* ").text)
        assertEquals("a\nb", EditorOps.prefixLines(Edited("* a\n* b", 0, 7), "* ").text)
        assertEquals("== Loot ==", EditorOps.heading(Edited("Loot", 2), 2).text)
        assertEquals("Loot", EditorOps.heading(Edited("== Loot ==", 2), 2).text)
    }

    @Test fun linksAndTemplatesAreCompleted() {
        val text = "See [[The La"
        val link = EditorOps.completion(text, text.length)!!
        assertFalse(link.template)
        assertEquals("The La", link.prefix)
        assertEquals(Edited("See [[The Lamplighter]]", 23), EditorOps.complete(Edited(text, text.length), link, "The Lamplighter"))

        val template = EditorOps.completion("{{Info", 6)!!
        assertTrue(template.template)
        assertEquals(Edited("{{Infobox location}}", 18), EditorOps.complete(Edited("{{Info", 6), template, "Template:Infobox location"))

        assertNull(EditorOps.completion("[[Page|lab", 10))
        assertNull(EditorOps.completion("{{{param", 8))
        assertNull(EditorOps.completion("plain text", 10))
        assertEquals(listOf(0, 9), EditorOps.matches("Wick and wick", "wick", matchCase = false))
        assertEquals(listOf(0), EditorOps.matches("Wick and wick", "Wick", matchCase = true))
    }

    @Test fun daysAreCountedInUtc() {
        val now = Instant.parse("2026-10-13T22:30:00Z")
        fun at(text: String) = Instant.parse(text)
        // 23:50 UTC on the 12th is yesterday, even where the phone's clock already says the 13th.
        val edited = WikiApi.countDays(listOf(at("2026-10-13T00:00:01Z"), at("2026-10-12T23:50:00Z"), at("2026-10-11T08:00:00Z")), now, 30, true)
        assertEquals(1, edited.today)
        assertEquals(3, edited.streak)
        assertFalse(edited.streakOpen)
        assertEquals(5400, edited.secondsLeft)

        val notYet = WikiApi.countDays(listOf(at("2026-10-12T23:50:00Z"), at("2026-10-11T08:00:00Z")), now, 30, true)
        assertEquals(0, notYet.today)
        assertEquals(2, notYet.streak)

        val broken = WikiApi.countDays(listOf(at("2026-10-10T08:00:00Z")), now, 30, true)
        assertEquals(0, broken.streak)

        val whole = WikiApi.countDays((0L..4L).map { now.minusSeconds(it * 86400) }, now, 5, true)
        assertEquals(5, whole.streak)
        assertTrue(whole.streakOpen)
    }

    @Test fun wikiAddressesAreRead() {
        assertEquals("https://lanternfall.fandom.com", wikiBase("lanternfall.fandom.com"))
        assertEquals("https://lanternfall.fandom.com", wikiBase("https://lanternfall.fandom.com/wiki/Emberfall_Keep"))
        assertEquals("https://lanternfall.fandom.com/es", wikiBase("lanternfall.fandom.com/es/wiki/Portada"))
        assertEquals("https://lanternfall.fandom.com/pt-br", wikiBase("https://lanternfall.fandom.com/pt-br/"))
        assertEquals("https://lanternfall.fandom.com", wikiBase("lanternfall"))
        assertNull(wikiBase("not a wiki"))
        assertNull(wikiBase(""))
    }
}
