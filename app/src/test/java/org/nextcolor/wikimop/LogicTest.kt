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
import org.nextcolor.wikimop.data.rankGroups
import org.nextcolor.wikimop.data.wikiBase
import org.nextcolor.wikimop.logic.Edited
import org.nextcolor.wikimop.logic.EditorOps
import org.nextcolor.wikimop.ui.diffStep
import java.time.Instant

class LogicTest {
    @Test fun editsOnNewPageOnlyPatrolWikisAreNotUnpatrolled() {
        // Wikis that only patrol new pages leave every edit with patrolled false but unpatrolled false too.
        fun parse(json: String) = WikiApi.change(org.json.JSONObject(json), "rc1").patrolled
        assertEquals(true, parse("""{"type":"edit","patrolled":false,"unpatrolled":false,"autopatrolled":false}"""))
        assertEquals(false, parse("""{"type":"new","patrolled":false,"unpatrolled":true,"autopatrolled":false}"""))
        assertEquals(true, parse("""{"type":"edit","patrolled":true,"unpatrolled":false,"autopatrolled":true}"""))
        assertNull(parse("""{"type":"edit"}"""))
    }

    @Test fun olderAndNewerEditsComeFromTheirSides() {
        val at = Dest.Diff("Elaina", 5101, 5120)
        assertEquals(-1, diffStep(at, Dest.Diff("Elaina", 0, 5101)))
        assertEquals(1, diffStep(at, Dest.Diff("Elaina", 5120, 5133)))
        assertEquals(0, diffStep(at, Dest.Diff("Saya", 0, 5101)))
        assertEquals(0, diffStep(at, Dest.Home))
    }

    @Test fun websiteDiffBecomesOneColumn() {
        val lines = DiffParser.parse(FakeWiki.DIFF)
        assertEquals(listOf(DiffKind.HEADER, DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.ADDED, DiffKind.ADDED, DiffKind.ADDED, DiffKind.ADDED), lines.map { it.kind })
        assertEquals("Line 8", lines[0].text)
        assertEquals(8, lines[1].newNo)
        assertEquals("country", lines[2].spans.single { it.marked }.text)
        assertEquals("world on her broom", lines[3].spans.single { it.marked }.text)
        assertEquals(9, lines[3].newNo)
        assertEquals("* [[Saya]]", lines.last().text)
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
        val text = "See [[Fr"
        val link = EditorOps.completion(text, text.length)!!
        assertFalse(link.template)
        assertEquals("Fr", link.prefix)
        assertEquals(Edited("See [[Fran]]", 12), EditorOps.complete(Edited(text, text.length), link, "Fran"))

        val template = EditorOps.completion("{{Char", 6)!!
        assertTrue(template.template)
        assertEquals(Edited("{{Character}}", 11), EditorOps.complete(Edited("{{Char", 6), template, "Template:Character"))

        assertNull(EditorOps.completion("[[Page|lab", 10))
        assertNull(EditorOps.completion("{{{param", 8))
        assertNull(EditorOps.completion("plain text", 10))
        assertEquals(listOf(0, 10), EditorOps.matches("Witch and witch", "witch", matchCase = false))
        assertEquals(listOf(0), EditorOps.matches("Witch and witch", "Witch", matchCase = true))
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

    @Test fun daysAreCountedAgainAsTimePasses() {
        val loaded = WikiApi.countDays(
            mapOf(1L to Instant.parse("2026-10-13T09:00:00Z"), 2L to Instant.parse("2026-10-12T09:00:00Z")),
            Instant.parse("2026-10-13T22:30:00Z"), 30, true,
        )
        assertEquals(1, loaded.today)
        assertEquals(2, loaded.streak)
        assertEquals(5400, loaded.secondsLeft)

        // Ten minutes later, there is less time left.
        assertEquals(4800, loaded.at(Instant.parse("2026-10-13T22:40:00Z")).secondsLeft)

        // After midnight UTC it is a new day without an edit yet. The streak is still alive until that day ends.
        val nextDay = loaded.at(Instant.parse("2026-10-14T00:05:00Z"))
        assertEquals(0, nextDay.today)
        assertEquals(2, nextDay.streak)
        assertEquals(86100, nextDay.secondsLeft)

        // A day with no edit at all ends it.
        assertEquals(0, loaded.at(Instant.parse("2026-10-15T00:05:00Z")).streak)

        // An edit seen in a list of changes counts, and one already counted is not counted twice.
        val edited = nextDay.with(mapOf(3L to Instant.parse("2026-10-14T00:03:00Z"), 1L to Instant.parse("2026-10-13T09:00:00Z")), Instant.parse("2026-10-14T00:05:00Z"))
        assertEquals(1, edited.today)
        assertEquals(3, edited.streak)
    }

    @Test fun wikiAddressesAreRead() {
        assertEquals("https://wandering-witch.fandom.com", wikiBase("wandering-witch.fandom.com"))
        assertEquals("https://wandering-witch.fandom.com", wikiBase("https://wandering-witch.fandom.com/wiki/Elaina"))
        assertEquals("https://wandering-witch.fandom.com/es", wikiBase("wandering-witch.fandom.com/es/wiki/Portada"))
        assertEquals("https://wandering-witch.fandom.com/pt-br", wikiBase("https://wandering-witch.fandom.com/pt-br/"))
        assertEquals("https://wandering-witch.fandom.com", wikiBase("wandering-witch"))
        // Typed loosely, the way people say the name.
        assertEquals("https://wandering-witch.fandom.com", wikiBase("Wandering Witch"))
        assertEquals("https://wandering-witch.fandom.com", wikiBase("wandering_witch"))
        // Links copied with spaces in the page name, or with the scheme in capitals.
        assertEquals("https://wandering-witch.fandom.com", wikiBase("HTTPS://wandering-witch.fandom.com/wiki/Light Novel Volume 1"))
        assertNull(wikiBase("???"))
        assertNull(wikiBase(""))
    }

    @Test fun groupsAreRanked() {
        val rights = mapOf("sysop" to setOf("block", "delete"), "bureaucrat" to setOf("userrights"), "util" to (1..20).map { "r$it" }.toSet(), "imagereviewer" to setOf("a"))
        assertEquals(
            listOf("bureaucrat", "sysop", "util", "imagereviewer", "autoconfirmed", "user"),
            rankGroups(listOf("*", "user", "autoconfirmed", "imagereviewer", "sysop", "util", "bureaucrat"), rights),
        )
    }
}
