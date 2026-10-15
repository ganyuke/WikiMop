package org.nextcolor.wikimop

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.nextcolor.wikimop.data.AccountJar
import org.nextcolor.wikimop.data.AccountKind
import org.nextcolor.wikimop.data.ChangesLocked
import org.nextcolor.wikimop.data.Confirmation
import org.nextcolor.wikimop.data.PageChanged
import org.nextcolor.wikimop.data.PendingWrite
import org.nextcolor.wikimop.data.UnknownOutcome
import org.nextcolor.wikimop.data.WikiApi

/** The rules that keep a wiki from being changed by accident. */
class WriteSafetyTest {
    private val fake = FakeWiki()
    private var locked = false
    private val api = WikiApi(fake.wiki, fake.account, OkHttpClient(), AccountJar(emptyList())) { locked }

    @After fun stop() = fake.stop()

    private fun edit(base: Long = 5120, isNew: Boolean = false) = PendingWrite.Edit(
        fake.wiki, "Elaina", "new text", "typo", minor = false, isNew = isNew,
        baseRevId = base, baseTimestamp = "2026-10-13T10:00:00Z", startTimestamp = "2026-10-13T10:05:00Z",
    )

    private inline fun <reified T : Throwable> failsWith(block: () -> Unit) {
        try { block() } catch (e: Throwable) { if (e is T) return else throw e }
        fail("Expected ${T::class.simpleName}")
    }

    @Test fun readDoorRefusesChanges() = runBlocking {
        for (action in listOf("edit", "delete", "block", "protect", "move", "rollback", "patrol", "undelete", "unblock", "watch", "upload")) {
            failsWith<IllegalStateException> { api.transport.read(mapOf("action" to action, "title" to "X"), post = true) }
        }
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun confirmedEditIsSentOnceWithItsGuards() = runBlocking {
        val write = edit()
        val result = api.perform(write, Confirmation.bySlide(write))
        assertEquals(5121, result.newRevId)
        val sent = fake.writes().single()
        assertEquals("edit", sent["action"])
        assertEquals("new text", sent["text"])
        assertEquals("1", sent["nocreate"])
        assertEquals("5120", sent["baserevid"])
        assertEquals("user", sent["assert"])
        assertEquals("Mopkeeper", sent["assertuser"])
        assertEquals("tok3n+\\", sent["token"])
        assertEquals(PendingWrite.md5("new text"), sent["md5"])
        assertEquals("nochange", sent["watchlist"])
        assertEquals(null, sent["createonly"])
    }

    @Test fun newPageIsCreateOnly() = runBlocking {
        val write = edit(isNew = true)
        api.perform(write, Confirmation.bySlide(write))
        val sent = fake.writes().single()
        assertEquals("1", sent["createonly"])
        assertEquals(null, sent["nocreate"])
    }

    @Test fun confirmationCoversOneChangeOnly() = runBlocking {
        val confirmed = edit()
        val other = PendingWrite.Delete(fake.wiki, "Elaina", "Spam")
        failsWith<IllegalStateException> { api.perform(other, Confirmation.bySlide(confirmed)) }
        // An equal change that is a different object was not the one on the sheet either.
        failsWith<IllegalStateException> { api.perform(edit(), Confirmation.bySlide(confirmed)) }
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun confirmationWorksOnce() = runBlocking {
        val write = edit()
        val confirmation = Confirmation.bySlide(write)
        api.perform(write, confirmation)
        failsWith<IllegalStateException> { api.perform(write, confirmation) }
        assertEquals(1, fake.writes().size)
    }

    @Test fun editIsHeldBackWhenThePageMovedOn() = runBlocking {
        fake.latestRevId = 5130
        fake.latestUser = "BroomKeeper"
        val write = edit(base = 5120)
        try {
            api.perform(write, Confirmation.bySlide(write))
            fail("Expected PageChanged")
        } catch (e: PageChanged) {
            assertEquals("BroomKeeper", e.by)
        }
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun lockStopsEverythingBeforeTheNetwork() = runBlocking {
        locked = true
        val write = PendingWrite.BlockUser(fake.wiki, "SpamBlaster99", "1 week", "Spam")
        failsWith<ChangesLocked> { api.perform(write, Confirmation.bySlide(write)) }
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun lostAnswerIsNotSentAgain() = runBlocking {
        fake.dropWrites = true
        val write = PendingWrite.Delete(fake.wiki, "Ashen Witch (old)", "Housekeeping")
        failsWith<UnknownOutcome> { api.perform(write, Confirmation.bySlide(write)) }
        assertEquals(1, fake.writes().size)
    }

    @Test fun signedOutAccountChangesNothing() = runBlocking {
        fake.signedIn = false
        val write = edit()
        failsWith<org.nextcolor.wikimop.data.SignedOut> { api.perform(write, Confirmation.bySlide(write)) }
        assertTrue(fake.writes().isEmpty())
        // Reading goes on as a guest.
        assertEquals(7, api.recentChanges(org.nextcolor.wikimop.data.ChangeFilter(), null, null).items.size)
    }

    @Test fun botSignsInAgainForReadsOnly() = runBlocking {
        fake.signedIn = false
        val bot = fake.account.copy(kind = AccountKind.BOT, botLogin = "Mopkeeper@phone", botPassword = "secret")
        val botApi = WikiApi(fake.wiki, bot, OkHttpClient(), AccountJar(emptyList()))
        assertEquals(7, botApi.recentChanges(org.nextcolor.wikimop.data.ChangeFilter(), null, null).items.size)
        assertEquals(1, fake.calls.count { it["action"] == "login" })
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun botSignsInAgainOverAnOldSession() = runBlocking {
        val jar = AccountJar(emptyList())
        val transport = WikiApi(fake.wiki, null, OkHttpClient(), jar).transport
        assertEquals("Mopkeeper", WikiApi.botLogin(transport, "Mopkeeper@phone", "secret"))
        // Switching back to the account later signs in again with the cookies it kept.
        assertEquals("Mopkeeper", WikiApi.botLogin(transport, "Mopkeeper@phone", "secret"))
        assertEquals(1, jar.snapshot().size)
    }

    @Test fun eachChangeSendsWhatItsSheetSays() = runBlocking {
        val block = PendingWrite.BlockUser(fake.wiki, "SpamBlaster99", "1 week", "Spam", noCreate = true, autoBlock = false, allowTalk = true)
        api.perform(block, Confirmation.bySlide(block))
        val sent = fake.writes().single()
        assertEquals("1 week", sent["expiry"])
        assertEquals("1", sent["nocreate"])
        assertEquals(null, sent["autoblock"])
        assertEquals(null, sent["reblock"])
        assertTrue(block.facts.contains("Autoblock" to "No"))

        val address = PendingWrite.BlockUser(fake.wiki, "203.0.113.42", "1 day", "Vandalism")
        api.perform(address, Confirmation.bySlide(address))
        assertEquals(null, fake.writes().last()["autoblock"])
        assertTrue(address.facts.none { it.first == "Autoblock" })

        val move = PendingWrite.Move(fake.wiki, "Light Novel Volume 01", "Light Novel Volume 1", "Match the other volume pages", leaveRedirect = false, moveTalk = false)
        api.perform(move, Confirmation.bySlide(move))
        val moved = fake.writes().last()
        assertEquals("move", moved["action"])
        assertEquals("1", moved["noredirect"])
        assertEquals(null, moved["movetalk"])

        val undo = PendingWrite.Undo(fake.wiki, "Elaina", 5120, "StardustReader", "", markBot = true)
        api.perform(undo, Confirmation.bySlide(undo))
        assertEquals("1", fake.writes().last()["bot"])
        assertTrue(undo.facts.contains("Bot edit" to "Yes"))

        val plainUndo = PendingWrite.Undo(fake.wiki, "Elaina", 5120, "StardustReader", "")
        api.perform(plainUndo, Confirmation.bySlide(plainUndo))
        assertEquals(null, fake.writes().last()["bot"])

        val roll = PendingWrite.Rollback(fake.wiki, "Fran", "203.0.113.42")
        api.perform(roll, Confirmation.bySlide(roll))
        assertEquals("rollback", fake.calls.last { it["meta"] == "tokens" }["type"])
    }
}
