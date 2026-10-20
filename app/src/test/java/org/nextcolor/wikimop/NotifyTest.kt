package org.nextcolor.wikimop

import android.Manifest
import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.nextcolor.wikimop.data.MemoryVault
import org.nextcolor.wikimop.data.Saved
import org.nextcolor.wikimop.data.SavedJson
import org.nextcolor.wikimop.data.SavedStore
import org.nextcolor.wikimop.data.WikiNotify
import org.nextcolor.wikimop.data.Change
import org.nextcolor.wikimop.notify.Checker
import org.nextcolor.wikimop.notify.sentence
import org.nextcolor.wikimop.notify.Notice
import org.nextcolor.wikimop.notify.Notifier
import org.nextcolor.wikimop.notify.Post
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.temporal.ChronoUnit

/** What the checks for notifications report, and what they keep to themselves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotifyTest {
    // Ninety minutes before the UTC day ends, which is when the streak reminder is due.
    private val evening = Instant.parse("2026-10-13T22:30:00Z")
    private var fake = FakeWiki()

    @After fun stop() = fake.stop()

    private fun store(notify: WikiNotify) =
        SavedStore(MemoryVault(SavedJson.write(Saved(listOf(fake.account), listOf(fake.wiki.copy(notify = notify)), "w1"))))

    private fun SavedStore.marks() = saved.marks.getValue("w1")

    @Test fun newChangesAreCountedFromWhatWasSeen() = runBlocking {
        val store = store(WikiNotify(changes = true))
        val checker = Checker(store, OkHttpClient())

        // Turning it on reports nothing that was already there.
        assertTrue(checker.run().isEmpty())
        assertEquals(fake.now.minus(4, ChronoUnit.MINUTES), store.marks().changes)

        store.update { s -> s.mark("w1") { it.copy(changes = fake.now.minus(30, ChronoUnit.MINUTES)) } }
        val post = checker.run().single()
        assertEquals(Notice.CHANGES, post.notice)
        assertEquals("Wandering Witch Wiki", post.title)
        assertEquals("3 new changes", post.text)
        // Newest first, each told as a sentence.
        assertEquals(
            listOf(
                "StardustReader edited Elaina (+102): \"Added her relationships\"",
                "203.0.113.42 edited Fran (−3,354)",
                "BroomKeeper created Saya (+2,210): \"Created page with her first appearance\"",
            ),
            post.lines,
        )

        // The same count is not sent a second time.
        assertTrue(checker.run().isEmpty())

        // Once they have been seen in the app, the notification is taken down.
        store.update { s -> s.mark("w1") { it.copy(changes = fake.now) } }
        assertNull(checker.run().single().text)
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun newChangesWithoutAnAccount() = runBlocking {
        val wiki = fake.wiki.copy(accountId = null, notify = WikiNotify(changes = true, watchlist = true, streak = true))
        val store = SavedStore(MemoryVault(SavedJson.write(Saved(emptyList(), listOf(wiki), "w1"))))
        val checker = Checker(store, OkHttpClient()) { evening }
        assertTrue(checker.run().isEmpty())

        store.update { s -> s.mark("w1") { it.copy(changes = fake.now.minus(2, ChronoUnit.DAYS)) } }
        val post = checker.run().single()
        assertEquals(Notice.CHANGES, post.notice)
        // Without an account nothing is left out as own changes, so Mopkeeper's delete counts too.
        assertEquals("7 new changes", post.text)
        // Only recent changes are read, and nothing asks for a signed-in user.
        assertTrue(fake.calls.all { it["assert"] == null && it["list"] == "recentchanges" })
    }

    @Test fun changesAreToldAsSentences() {
        fun edit(tags: List<String> = emptyList(), comment: String = "", delta: Int? = -40) =
            Change("rc", "edit", "Fran", user = "203.0.113.42", tags = tags, comment = comment, sizeDiff = delta).sentence()
        assertEquals("203.0.113.42 edited Fran (−40)", edit())
        assertEquals("203.0.113.42 edited Fran (0): \"typo\"", edit(comment = "typo", delta = 0))
        assertEquals("203.0.113.42 edited Fran", edit(delta = null))
        assertEquals("203.0.113.42 blanked Fran (−40): \"lol\"", edit(listOf("mobile edit", "mw-blank"), "lol"))
        assertEquals("203.0.113.42 rolled back edits on Fran (−40)", edit(listOf("mw-blank", "mw-rollback")))
        assertEquals("203.0.113.42 turned Fran (−40) into a redirect", edit(listOf("mw-new-redirect")))
        val block = Change("rc", "log", "User:SpamBlaster99", user = "BroomKeeper", comment = "Spam", logType = "block", logAction = "block")
        assertEquals("BroomKeeper blocked SpamBlaster99", block.sentence())
    }

    @Test fun onlyNewChangesCountWithoutAnAccount() {
        assertTrue(fake.wiki.copy(accountId = null, notify = WikiNotify(changes = true)).notifying)
        assertFalse(fake.wiki.copy(accountId = null, notify = WikiNotify(watchlist = true, streak = true)).notifying)
        assertTrue(fake.wiki.copy(notify = WikiNotify(streak = true)).notifying)
    }

    @Test fun watchlistLeavesOutOwnChanges() = runBlocking {
        val store = store(WikiNotify(watchlist = true))
        store.update { s -> s.mark("w1") { it.copy(watchlist = fake.now.minus(2, ChronoUnit.DAYS)) } }
        val post = Checker(store, OkHttpClient()).run().single()
        assertEquals(Notice.WATCHLIST, post.notice)
        // Seven changes, one of them by the account itself.
        assertEquals("6 new changes on your watchlist", post.text)
    }

    @Test fun signedOutIsToldOnceAndThenLeftAlone() = runBlocking {
        fake.signedIn = false
        val store = store(WikiNotify(changes = true, watchlist = true, streak = true))
        val checker = Checker(store, OkHttpClient())
        val post = checker.run().single()
        assertEquals(Notice.SIGNED_OUT, post.notice)
        assertEquals("a1", post.tag)
        assertEquals("Mopkeeper", post.title)
        assertEquals("Signed out. Sign in again.", post.text)

        val asked = fake.calls.size
        assertTrue(checker.run().isEmpty())
        assertEquals(asked, fake.calls.size)
    }

    @Test fun streakReminderComesOnceNearTheEndOfTheDay() = runBlocking {
        fake.stop()
        fake = FakeWiki(evening).apply { editedToday = false }
        val store = store(WikiNotify(streak = true))

        // In the morning the wiki is not even asked.
        assertTrue(Checker(store, OkHttpClient()) { Instant.parse("2026-10-13T09:00:00Z") }.run().isEmpty())
        assertTrue(fake.calls.none { it["list"] == "usercontribs" })

        val checker = Checker(store, OkHttpClient()) { evening }
        val post = checker.run().single()
        assertEquals(Notice.STREAK, post.notice)
        assertEquals("Not edited today", post.title)
        assertEquals("Streak: 11 days · Resets in 1 h 30 min (UTC)", post.text)
        assertEquals("Wandering Witch Wiki", post.wikiName)
        assertEquals(Instant.parse("2026-10-14T00:00:00Z"), post.until)
        assertTrue(checker.run().isEmpty())

        // An edit made outside the app takes the reminder down, once.
        fake.editedToday = true
        val down = checker.run().single()
        assertEquals(Notice.STREAK, down.notice)
        assertNull(down.text)
        assertTrue(checker.run().isEmpty())
    }

    @Test fun noStreakReminderAfterAnEdit() = runBlocking {
        fake.stop()
        fake = FakeWiki(evening)
        assertTrue(Checker(store(WikiNotify(streak = true)), OkHttpClient()) { evening }.run().isEmpty())
    }

    @Test fun settingsSurviveBeingSaved() {
        val store = store(WikiNotify(changes = true, streak = true))
        store.update { s -> s.copy(settings = s.settings.copy(checkMinutes = 60, blockTrackers = false, desktopPages = true, darkPages = true, markMinor = true), signedOutTold = setOf("a1")).mark("w1") { it.copy(changes = evening, changesTold = 4, streakDay = "2026-10-13", streakTold = true) } }
        assertEquals(store.saved, SavedJson.read(SavedJson.write(store.saved)))
        // Trackers are blocked unless that was turned off.
        assertTrue(SavedJson.read("{}").settings.blockTrackers)
    }

    @Test fun notificationsGoUpAndComeDown() {
        val app = RuntimeEnvironment.getApplication()
        val shade = shadowOf(app.getSystemService(NotificationManager::class.java))
        val post = Post(Notice.CHANGES, "w1", "w1", "Wandering Witch Wiki", "3 new changes")

        // Nothing is shown until notifications have been allowed.
        Notifier.show(app, post)
        assertEquals(0, shade.size())

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier.show(app, post)
        Notifier.show(app, post.copy(text = "5 new changes"))
        Notifier.show(app, post.copy(notice = Notice.WATCHLIST, text = "1 new change on your watchlist"))
        // Both sit under a header for the wiki.
        assertEquals(3, shade.size())
        assertTrue(shade.allNotifications.all { it.group == "wiki:w1" })
        assertEquals(1, shade.allNotifications.count { it.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 })
        assertEquals("5 new changes", shadowOf(shade.getNotification("w1", 1)).contentText)
        // One change is said in full instead of counted.
        Notifier.show(app, post.copy(text = "1 new change", lines = listOf("BroomKeeper created Saya (+2,210)")))
        assertEquals("BroomKeeper created Saya (+2,210)", shadowOf(shade.getNotification("w1", 1)).contentText)

        Notifier.show(app, post.copy(text = null))
        Notifier.dismiss(app, Notice.WATCHLIST, "w1")
        // The header goes with the last one under it.
        assertEquals(0, shade.size())

        // The streak reminder is kept apart, with a flame.
        Notifier.show(app, post.copy(notice = Notice.STREAK, title = "Not edited today", text = "Streak: 11 days", wikiName = "Wandering Witch Wiki"))
        val streak = shade.allNotifications.single()
        assertNull(streak.group)
        assertEquals(R.drawable.ic_streak, streak.smallIcon.resId)
    }
}
