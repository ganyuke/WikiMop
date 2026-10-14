package org.nextcolor.wikimop

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.nextcolor.wikimop.data.Change
import org.nextcolor.wikimop.data.Draft
import org.nextcolor.wikimop.data.MemoryVault
import org.nextcolor.wikimop.data.Saved
import org.nextcolor.wikimop.data.SavedJson
import org.nextcolor.wikimop.data.Settings
import org.nextcolor.wikimop.data.PendingWrite
import org.nextcolor.wikimop.data.WikiNotify
import org.nextcolor.wikimop.ui.App
import org.nextcolor.wikimop.ui.ConfirmSheet
import org.nextcolor.wikimop.ui.WikiMopTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Runs the app against the made-up wiki. Each screen is written to
 * app/build/screenshots so the layout can be looked at without a phone, and
 * the path from an edit to its publication is walked through by hand.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class ScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val fake = FakeWiki()

    @After fun stop() = fake.stop()

    private fun model(settings: Settings = Settings(), wikis: Boolean = true, account: Boolean = true): AppModel {
        val dir = RuntimeEnvironment.getApplication().cacheDir.resolve("drafts-" + System.nanoTime())
        val saved = when {
            !wikis -> Saved()
            account -> Saved(listOf(fake.account), listOf(fake.wiki), "w1", settings)
            else -> Saved(emptyList(), listOf(fake.wiki.copy(accountId = null)), "w1", settings)
        }
        return AppModel(MemoryVault(SavedJson.write(saved)), dir, OkHttpClient())
    }

    private fun show(model: AppModel, dark: Boolean = false) =
        rule.setContent { WikiMopTheme(dark = dark, dynamicColor = false) { App(model) } }

    /** Waits in real time. The test clock runs ahead of the fake server, so its own timeouts end too early. */
    private fun until(what: String, condition: () -> Boolean) {
        repeat(500) {
            rule.waitForIdle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Still waiting for $what")
    }

    private fun waitFor(text: String) = until(text) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage("build/screenshots/$name.png")
    private fun sheetShot(name: String) { rule.waitForIdle(); captureScreenRoboImage("build/screenshots/$name.png") }

    private fun draft(summary: String = "Named the war and listed a third enemy") = Draft(
        id = "d1", wikiId = "w1", title = "Emberfall Keep", baseText = fake.text,
        text = fake.text.replace("the Long Dusk", "the Long Dusk of 412").replace("* [[Wick Knight]]", "* [[Wick Knight]]\n* [[Cinder Moth]]"),
        baseRevId = 5120, baseTimestamp = "2026-10-13T10:00:00Z", startTimestamp = "2026-10-13T10:05:00Z",
        summary = summary, updated = System.currentTimeMillis(),
    )

    private val vandalism = Change("rc1", "edit", "Emberfall Keep", revId = 5120, oldRevId = 5101, user = "Quillfeather", patrolled = false)

    @Test fun home() {
        show(model())
        waitFor("The Lamplighter")
        shot("home")
    }

    @Test fun homeDarkNoEditToday() {
        fake.editedToday = false
        show(model(), dark = true)
        waitFor("The Lamplighter")
        waitFor("Not edited today")
        shot("home-dark")
    }

    @Test fun notifications() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val m = model()
        show(m)
        waitFor("The Lamplighter")
        rule.onNodeWithContentDescription("Settings").performClick()
        waitFor("Notifications")
        rule.onNodeWithText("Notifications").performClick()
        waitFor("Check every")
        rule.onNodeWithText("1 hour").performClick()
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.onAllNodes(isToggleable())[2].performClick()
        rule.waitForIdle()
        assertEquals(60, m.saved.settings.checkMinutes)
        assertEquals(WikiNotify(changes = true, streak = true), m.saved.wikis.single().notify)
        shot("notifications")

        // Looking at the list moves the point that new changes are counted from.
        m.back()
        m.back()
        m.open(m.saved.wikis.single())
        until("the newest change to be noted") { m.saved.marks["w1"]?.changes == fake.now.minusSeconds(240) }
    }

    @Test fun filters() {
        show(model())
        waitFor("The Lamplighter")
        rule.onNodeWithContentDescription("Filters").performClick()
        waitFor("Namespaces")
        sheetShot("filters")
    }

    @Test fun tabs() {
        val m = model()
        m.saveDraft(draft())
        show(m)
        waitFor("The Lamplighter")
        rule.onNodeWithText("Pages").performClick()
        waitFor("Main page")
        shot("pages")
        rule.onNodeWithText("Drafts").performClick()
        waitFor("Unpublished edits")
        shot("drafts")
        rule.onNodeWithText("Tools").performClick()
        waitFor("Blocked users")
        shot("tools")
    }

    @Test fun diffAndRollback() {
        val m = model()
        show(m)
        waitFor("The Lamplighter")
        m.go(Dest.Diff("Emberfall Keep", 5101, 5120, vandalism))
        waitFor("Long Dusk")
        shot("diff")
        rule.onNodeWithText("Rollback").performClick()
        waitFor("Summary")
        shot("form-rollback")
        rule.onNode(hasText("Rollback") and hasClickAction()).performClick()
        waitFor("Slide to rollback")
        sheetShot("confirm-rollback")
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun pageDetails() {
        val m = model()
        show(m)
        waitFor("The Lamplighter")
        m.openPage("Emberfall Keep")
        waitFor("Details")
        rule.onNodeWithText("History").performClick()
        waitFor("Created page")
        shot("page-history")
        rule.onNodeWithText("Details").performClick()
        waitFor("What links here")
        shot("page-details")
    }

    @Test fun editor() {
        val m = model()
        m.saveDraft(draft())
        show(m, dark = true)
        waitFor("The Lamplighter")
        m.go(Dest.Editor("d1"))
        waitFor("Infobox location")
        shot("editor-dark")
    }

    @Test fun userAndBlock() {
        val m = model()
        show(m)
        waitFor("The Lamplighter")
        m.go(Dest.User("SpamBlaster99"))
        waitFor("Blocked")
        waitFor("phase 2 moves")
        shot("user")
        m.go(Dest.Form(FormKind.BLOCK, "203.0.113.42"))
        waitFor("Duration")
        rule.onNodeWithText("1 week").performClick()
        rule.onAllNodesWithText("Vandalism")[0].performClick()
        shot("form-block")
        rule.onNode(hasText("Block user") and hasClickAction()).performClick()
        waitFor("Slide to block")
        sheetShot("confirm-block")
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun setup() {
        val m = model(wikis = false)
        show(m)
        waitFor("Add a wiki")
        shot("welcome")
        m.go(Dest.AddWiki)
        waitFor("Wiki address")
        shot("add-wiki")
        m.back()
        m.go(Dest.Settings)
        waitFor("Lock changes")
        shot("settings")
    }

    @Test fun sendingLabelIsCentred() {
        val request = Request(PendingWrite.Delete(fake.wiki, "Ash Hound (old)", "Housekeeping")) {}.apply { sending = true }
        rule.setContent { WikiMopTheme(dynamicColor = false) { ConfirmSheet(request, "Lanternfall Wiki", "Mopkeeper", {}, {}) } }
        waitFor("Sending")
        val label = rule.onNodeWithText("Sending").fetchSemanticsNode().boundsInRoot
        val cancel = rule.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        assertEquals(cancel.center.x, label.center.x, 1f)
    }

    private fun readOnly(m: AppModel, name: String) {
        show(m)
        waitFor("The Lamplighter")
        shot(name)
        m.go(Dest.Diff("Emberfall Keep", 5101, 5120, vandalism))
        waitFor("Long Dusk")
        assertTrue(rule.onAllNodesWithText("Rollback").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Undo").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Patrol").fetchSemanticsNodes().isEmpty())
        m.back()
        m.openPage("Emberfall Keep")
        waitFor("Details")
        rule.onNodeWithText("History").performClick()
        waitFor("Created page")
        assertTrue(rule.onAllNodesWithText("Edit").fetchSemanticsNodes().isEmpty())
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun wikiWithoutAccountIsReadOnly() {
        fake.signedIn = false
        readOnly(model(account = false), "home-no-account")
    }

    @Test fun accountIsChosenPerWiki() {
        val other = fake.wiki.copy(id = "w2", base = fake.wiki.base + "/es", name = "Wiki de Lanternfall")
        val saved = Saved(listOf(fake.account), listOf(fake.wiki, other), "w1", Settings())
        val m = AppModel(MemoryVault(SavedJson.write(saved)), RuntimeEnvironment.getApplication().cacheDir.resolve("drafts-" + System.nanoTime()), OkHttpClient())
        show(m)
        waitFor("The Lamplighter")
        until("the wiki's icon") { m.saved.wikis.first().icon != null }
        rule.onNodeWithContentDescription("Switch wiki").performClick()
        waitFor("Wikis")
        sheetShot("wikis")
        rule.onNodeWithContentDescription("Account for Lanternfall Wiki").performClick()
        waitFor("No account (read-only)")
        shot("wiki-account")
        rule.onNodeWithText("No account (read-only)").performClick()
        rule.onNodeWithText("Save").performClick()
        // Only the one wiki lost its account. The account itself and the other wiki are as they were.
        until("no account") { m.saved.wikis.first { it.id == "w1" }.accountId == null }
        assertEquals("a1", m.saved.wikis.first { it.id == "w2" }.accountId)
        assertEquals(listOf("a1"), m.saved.accounts.map { it.id })
        waitFor("Sign in")
        rule.onNodeWithText("Sign in").performClick()
        waitFor("Mopkeeper · Fandom account")
        rule.onNodeWithText("Mopkeeper · Fandom account").performClick()
        rule.onNodeWithText("Save").performClick()
        until("signed in again") { m.saved.wikis.first { it.id == "w1" }.accountId == "a1" }
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun iconFilesAreRead() {
        for (name in listOf("/lanternfall.ico", "/lanternfall-png.ico")) {
            val icon = org.nextcolor.wikimop.ui.decodeIcon(javaClass.getResourceAsStream(name)!!.use { it.readBytes() })!!
            assertEquals(64, icon.width)
            assertEquals(64, icon.height)
            assertEquals(0, icon.getPixel(0, 0) ushr 24)
            assertEquals(0xFFE08A1E.toInt(), icon.getPixel(5, 32))
            assertEquals(0xFFFFFFFF.toInt(), icon.getPixel(24, 40))
        }
        assertNull(org.nextcolor.wikimop.ui.decodeIcon("<html>not an icon</html>".toByteArray()))
    }

    /**
     * The pictures for the store listing and the README. They show saved copies of three real wikis
     * (see scripts/snapshot_wikis.py) with their own icons. The signed-in user is still made up.
     */
    private fun store(current: String, dark: Boolean = false, before: (AppModel, FakeWiki) -> Unit = { _, _ -> }, then: (AppModel, FakeWiki) -> Unit) {
        val wikis = listOf("wandering-witch", "witch-hat-atelier", "virtualyoutuber").map(FakeWiki::saved)
        try {
            // The app asks https://<wiki>.fandom.com as it would on a phone. Each of those goes to that wiki's fake.
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url
                val to = wikis.firstOrNull { it.host == url.host }?.server ?: return@addInterceptor chain.proceed(chain.request())
                chain.proceed(chain.request().newBuilder().url(url.newBuilder().scheme("http").host(to.hostName).port(to.port).build()).build())
            }.build()
            val saved = Saved(listOf(fake.account), wikis.map { it.wiki }, current, Settings())
            val m = AppModel(MemoryVault(SavedJson.write(saved)), RuntimeEnvironment.getApplication().cacheDir.resolve("drafts-" + System.nanoTime()), http)
            val shown = wikis.first { it.wiki.id == current }
            before(m, shown)
            show(m, dark)
            waitFor(shown.newestTitle)
            then(m, shown)
            assertTrue(wikis.all { it.writes().isEmpty() })
        } finally {
            wikis.forEach { it.stop() }
        }
    }

    /** Gives pictures that load on their own, like wiki icons, time to arrive. */
    private fun settle() = repeat(15) { rule.waitForIdle(); Thread.sleep(40) }

    private fun commaFix(w: FakeWiki) = Draft(
        id = "d1", wikiId = w.wiki.id, title = w.pageTitle, baseText = w.text,
        text = w.text.replace("(リチェ, ''Riche'') is a young", "(リチェ, ''Riche''), is a young").also { assertTrue(it != w.text) },
        baseRevId = w.latestRevId, baseTimestamp = w.now.minusSeconds(240).toString(), startTimestamp = w.now.toString(),
        summary = "Added a missing comma", updated = System.currentTimeMillis(),
    )

    @Test fun storeHomeAndWikis() = store("wandering-witch") { _, _ ->
        settle()
        shot("store-home")
        rule.onNodeWithContentDescription("Switch wiki").performClick()
        waitFor("Wikis")
        settle()
        sheetShot("store-wikis")
    }

    @Test fun storeDiff() = store("witch-hat-atelier") { m, w ->
        m.go(Dest.Diff(w.pageTitle, w.parentRevId, w.latestRevId, Change("rc1", "edit", w.pageTitle, revId = w.latestRevId, oldRevId = w.parentRevId, user = w.latestUser)))
        waitFor("Rollback")
        settle()
        shot("store-diff")
    }

    @Test fun storePublish() = store("witch-hat-atelier", before = { m, w -> m.saveDraft(commaFix(w)) }) { m, _ ->
        m.go(Dest.Review("d1"))
        waitFor("Publish")
        until("the page check") { rule.onAllNodesWithText("Checking").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Publish").performClick()
        waitFor("Slide to publish")
        sheetShot("store-confirm-publish")
    }

    @Test fun storeEditor() = store("witch-hat-atelier", dark = true, before = { m, w -> m.saveDraft(commaFix(w)) }) { m, _ ->
        m.go(Dest.Editor("d1"))
        waitFor("Top Tab")
        shot("store-editor-dark")
    }

    @Test fun storeChangesDark() = store("virtualyoutuber", dark = true) { _, _ ->
        settle()
        shot("store-changes-dark")
    }

    @Test fun signedOutAccountStillReads() {
        fake.signedIn = false
        val m = model()
        readOnly(m, "home-signed-out")
        assertTrue(m.session!!.signedOut)
    }

    /** Publishing takes a review, a button and a full slide. Nothing short of that sends anything. */
    @Test fun publishNeedsTheWholeSlide() {
        val m = model()
        m.saveDraft(draft())
        show(m)
        waitFor("The Lamplighter")
        m.go(Dest.Review("d1"))
        waitFor("Publish")
        until("the page check") { rule.onAllNodesWithText("Checking").fetchSemanticsNodes().isEmpty() }
        shot("review")
        rule.onNodeWithText("Publish").performClick()
        waitFor("Slide to publish")
        sheetShot("confirm-publish")
        assertTrue(fake.writes().isEmpty())

        val slide = rule.onNodeWithContentDescription("Slide to publish")
        slide.performClick()
        slide.performTouchInput { swipeRight(startX = 60f, endX = centerX) }
        rule.waitForIdle()
        assertTrue(fake.writes().isEmpty())
        assertEquals(1, m.drafts.size)

        slide.performTouchInput { swipeRight(startX = 60f, endX = right - 10f) }
        until("the edit to be sent") { fake.writes().isNotEmpty() && m.request == null }
        val sent = fake.writes().single()
        assertEquals("edit", sent["action"])
        assertEquals(draft().text, sent["text"])
        assertEquals("Named the war and listed a third enemy", sent["summary"])
        assertTrue(m.drafts.isEmpty())
    }

    @Test fun lockedAppOpensNoConfirmSheet() {
        val m = model(Settings(changesLocked = true))
        m.saveDraft(draft())
        show(m)
        waitFor("The Lamplighter")
        m.go(Dest.Review("d1"))
        waitFor("Publish")
        until("the page check") { rule.onAllNodesWithText("Checking").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Publish").performClick()
        rule.waitForIdle()
        assertEquals(null, m.request)
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun reviewStopsWhenSomeoneElseEdited() {
        fake.latestRevId = 5133
        fake.latestUser = "Tindercap"
        val m = model()
        m.saveDraft(draft())
        show(m)
        waitFor("The Lamplighter")
        m.go(Dest.Review("d1"))
        waitFor("Tindercap edited this page after you started")
        shot("review-page-changed")
        rule.onNodeWithText("Publish").performClick()
        rule.waitForIdle()
        assertEquals(null, m.request)
    }

    @Test fun summaryIsNeededBeforePublishing() {
        val m = model(Settings(requireSummary = true))
        m.saveDraft(draft(summary = ""))
        show(m)
        waitFor("The Lamplighter")
        m.go(Dest.Review("d1"))
        waitFor("Summary required")
        rule.onNodeWithText("Publish").performClick()
        rule.waitForIdle()
        assertEquals(null, m.request)
    }
}
