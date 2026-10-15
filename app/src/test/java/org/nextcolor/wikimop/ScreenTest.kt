package org.nextcolor.wikimop

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.nextcolor.wikimop.data.Account
import org.nextcolor.wikimop.data.AccountKind
import org.nextcolor.wikimop.data.Change
import org.nextcolor.wikimop.data.Draft
import org.nextcolor.wikimop.data.MemoryVault
import org.nextcolor.wikimop.data.PendingWrite
import org.nextcolor.wikimop.data.Saved
import org.nextcolor.wikimop.data.SavedJson
import org.nextcolor.wikimop.data.Settings
import org.nextcolor.wikimop.data.WikiNotify
import org.nextcolor.wikimop.ui.App
import org.nextcolor.wikimop.ui.ConfirmSheet
import org.nextcolor.wikimop.ui.WikiMopTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.net.UnknownHostException

/**
 * Runs the app against the made-up wiki. Each screen is written to
 * app/build/screenshots/phone (and by [TabletScreenTest] to app/build/screenshots/tablet)
 * so the layout can be looked at without a device, and
 * the path from an edit to its publication is walked through by hand.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
open class ScreenTest {
    /** The folder in app/build/screenshots that this size of screen is saved to. */
    protected open val device = "phone"

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    protected val fake = FakeWiki()

    @After fun stop() = fake.stop()

    protected fun model(settings: Settings = Settings(), wikis: Boolean = true, account: Boolean = true): AppModel {
        val dir = RuntimeEnvironment.getApplication().cacheDir.resolve("drafts-" + System.nanoTime())
        val saved = when {
            !wikis -> Saved()
            account -> Saved(listOf(fake.account), listOf(fake.wiki), "w1", settings)
            else -> Saved(emptyList(), listOf(fake.wiki.copy(accountId = null)), "w1", settings)
        }
        return AppModel(MemoryVault(SavedJson.write(saved)), dir, OkHttpClient())
    }

    protected fun show(model: AppModel, dark: Boolean = false) =
        rule.setContent { WikiMopTheme(dark = dark, dynamicColor = false) { App(model) } }

    /** Waits in real time. The test clock runs ahead of the fake server, so its own timeouts end too early. */
    protected fun until(what: String, condition: () -> Boolean) {
        repeat(500) {
            rule.waitForIdle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Still waiting for $what")
    }

    protected fun waitFor(text: String) = until(text) {
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    protected fun shot(name: String) = rule.onRoot().captureRoboImage("build/screenshots/$device/$name.png")
    private fun sheetShot(name: String) { rule.waitForIdle(); captureScreenRoboImage("build/screenshots/$device/$name.png") }

    private fun draft(summary: String = "Reworded her travels and added Sheila") = Draft(
        id = "d1", wikiId = "w1", title = "Elaina", baseText = fake.text,
        text = fake.text.replace("travel the world on her broom", "travel the world on her broom, one country at a time").replace("* [[Saya]]", "* [[Saya]]\n* [[Sheila]]"),
        baseRevId = 5120, baseTimestamp = "2026-10-13T10:00:00Z", startTimestamp = "2026-10-13T10:05:00Z",
        summary = summary, updated = System.currentTimeMillis(),
    )

    private val vandalism = Change("rc1", "edit", "Elaina", revId = 5120, oldRevId = 5101, user = "StardustReader", patrolled = false)

    @Test fun home() {
        show(model())
        waitFor("Fran")
        // The streak card and the role come from requests of their own, which can finish after the list.
        waitFor("Streak:")
        waitFor("Administrator")
        shot("home")
    }

    @Test fun homeDarkNoEditToday() {
        fake.editedToday = false
        show(model(), dark = true)
        waitFor("Fran")
        waitFor("Not edited today")
        shot("home-dark")
    }

    @Test fun notifications() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val m = model()
        show(m)
        waitFor("Fran")
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

    @Test fun notificationsWithoutAccount() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val m = model(account = false)
        show(m)
        m.go(Dest.Notifications)
        waitFor("Check every")
        rule.onAllNodes(isToggleable())[0].performClick()
        rule.waitForIdle()
        assertEquals(WikiNotify(changes = true), m.saved.wikis.single().notify)
        rule.onAllNodes(isToggleable())[1].assertIsNotEnabled()
        rule.onAllNodes(isToggleable())[2].assertIsNotEnabled()
        shot("notifications-no-account")
    }

    @Test fun filters() {
        show(model())
        waitFor("Fran")
        rule.onNodeWithContentDescription("Filters").performClick()
        waitFor("Namespaces")
        sheetShot("filters")
    }

    @Test fun tabs() {
        val m = model()
        m.saveDraft(draft())
        show(m)
        waitFor("Fran")
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
        waitFor("Fran")
        m.go(Dest.Diff("Elaina", 5101, 5120, vandalism))
        waitFor("world on her broom")
        shot("diff")
        rule.onNodeWithContentDescription("Actions").performClick()
        waitFor("Rollback")
        shot("diff-actions")
        rule.onNodeWithText("Rollback").performClick()
        waitFor("Summary")
        shot("form-rollback")
        rule.onNode(hasText("Rollback") and hasClickAction()).performClick()
        waitFor("Slide to rollback")
        sheetShot("confirm-rollback")
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun olderAndNewerEdits() {
        val m = model()
        show(m)
        waitFor("Fran")
        m.go(Dest.Diff("Elaina", 5101, 5120, vandalism))
        waitFor("world on her broom")
        // The newest edit has nothing newer.
        until("the page check") { rule.onAllNodesWithText("Current").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodesWithText("Newer edit").fetchSemanticsNodes().isEmpty())

        rule.onNodeWithText("Older edit").performClick()
        waitFor("BroomKeeper")
        until("the newer link") { rule.onAllNodesWithText("Newer edit").fetchSemanticsNodes().isNotEmpty() }
        shot("diff-older")
        assertEquals(Dest.Diff("Elaina", 0, 5101), m.top.dest)

        // The first revision of the page has nothing older.
        rule.onNodeWithText("Older edit").performClick()
        waitFor("New page")
        assertTrue(rule.onAllNodesWithText("Older edit").fetchSemanticsNodes().isEmpty())

        rule.onNodeWithText("Newer edit").performClick()
        until("the edit after the first") { m.top.dest == Dest.Diff("Elaina", 5090, 5101) }
        until("its newer link") { rule.onAllNodesWithText("Newer edit").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Newer edit").performClick()
        until("the newest edit") { m.top.dest == Dest.Diff("Elaina", 5101, 5120) }
        // Back goes through the same edits again.
        m.back()
        assertEquals(Dest.Diff("Elaina", 5090, 5101), m.top.dest)
        assertTrue(fake.writes().isEmpty())
    }

    private fun actionsFor(rights: List<String>, shown: List<String>, name: String) {
        fake.rights = rights
        val m = model()
        show(m)
        waitFor("Fran")
        m.go(Dest.Diff("Elaina", 5101, 5120, vandalism))
        waitFor("world on her broom")
        rule.onNodeWithContentDescription("Actions").performClick()
        waitFor("Open page")
        val all = listOf("Patrol", "Rollback", "Undo", "Edit page", "Open page", "Block user", "Delete page")
        for (label in all) assertEquals(label, label in shown, rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty())
        shot(name)
    }

    // The rights each group has on Fandom, as the wikis list them.
    private val user = listOf("read", "edit", "createpage")

    @Test fun actionsForEditor() = actionsFor(user, listOf("Undo", "Edit page", "Open page"), "diff-actions-editor")

    @Test fun actionsForRollbacker() =
        actionsFor(user + "rollback", listOf("Rollback", "Undo", "Edit page", "Open page"), "diff-actions-rollbacker")

    @Test fun actionsForContentModerator() = actionsFor(
        user + listOf("patrol", "rollback", "delete", "undelete", "protect"),
        listOf("Patrol", "Rollback", "Undo", "Edit page", "Open page", "Delete page"), "diff-actions-content-moderator",
    )

    @Test fun pageDetails() {
        val m = model()
        show(m)
        waitFor("Fran")
        m.openPage("Elaina")
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
        waitFor("Fran")
        m.go(Dest.Editor("d1"))
        waitFor("{{Character")
        shot("editor-dark")
    }

    @Test fun userAndBlock() {
        val m = model()
        show(m)
        waitFor("Fran")
        m.go(Dest.User("SpamBlaster99"))
        waitFor("Blocked")
        waitFor("Rewrote her abilities")
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

    @Test fun roleAndRights() {
        val m = model()
        show(m)
        waitFor("Fran")
        // The wiki's name for the group, with a capital, and no note when the sign-in has everything its groups give.
        waitFor("Administrator")
        assertTrue(rule.onAllNodesWithContentDescription("Fewer rights than usual").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Administrator").performClick()
        waitFor("Block or unblock other users from editing")
        assertTrue(rule.onAllNodesWithText("Missing from this sign-in").fetchSemanticsNodes().isEmpty())
        sheetShot("rights")
    }

    @Test fun signInWithFewerRights() {
        // Like a bot password made without the blocking and deleting permissions.
        fake.rights = fake.rights - setOf("block", "delete")
        val m = model()
        show(m)
        waitFor("Fran")
        waitFor("Administrator")
        until("the note") { rule.onAllNodesWithContentDescription("Fewer rights than usual").fetchSemanticsNodes().isNotEmpty() }
        waitFor("Streak:")
        shot("home-fewer-rights")
        rule.onNodeWithText("Administrator").performClick()
        waitFor("Missing from this sign-in")
        waitFor("Delete pages")
        sheetShot("rights-fewer")
    }

    @Test fun blockedAccount() {
        fake.blockedSelf = true
        val m = model()
        show(m)
        waitFor("Fran")
        waitFor("Blocked")
        waitFor("Streak:")
        // The block takes the role's place.
        assertTrue(rule.onAllNodesWithText("Administrator").fetchSemanticsNodes().isEmpty())
        shot("home-blocked")
        rule.onNodeWithText("Blocked").performClick()
        waitFor("Edit warring")
        waitFor("Whole wiki")
        waitFor("Block or unblock other users from editing")
        sheetShot("rights-blocked")
    }

    @Test fun partiallyBlockedAccount() {
        fake.blockedSelf = true
        fake.blockPartial = true
        val m = model()
        show(m)
        waitFor("Fran")
        waitFor("Partially blocked")
        rule.onNodeWithText("Partially blocked").performClick()
        waitFor("Some pages or namespaces")
    }

    @Test fun removingAnAccountSignsOut() {
        val m = model()
        show(m)
        waitFor("Fran")
        m.go(Dest.Settings)
        waitFor("Accounts")
        rule.onNodeWithContentDescription("Sign out").performScrollTo()
        shot("settings-accounts")
        rule.onNodeWithContentDescription("Sign out").performClick()
        waitFor("Sign out of Mopkeeper?")
        sheetShot("sign-out")
        rule.onNode(hasText("Sign out") and hasClickAction()).performClick()
        until("the sign-out") { fake.calls.any { it["action"] == "logout" } }
        val logout = fake.calls.single { it["action"] == "logout" }
        assertEquals("tok3n+\\", logout["token"])
        assertTrue(m.saved.accounts.isEmpty())
        assertEquals(null, m.saved.wikis.single().accountId)
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun switchingAccounts() {
        val bot = Account("b1", AccountKind.BOT, "Mopkeeper", botLogin = "Mopkeeper@WikiMop", botPassword = "s3cret")
        val dir = RuntimeEnvironment.getApplication().cacheDir.resolve("drafts-" + System.nanoTime())
        val m = AppModel(MemoryVault(SavedJson.write(Saved(listOf(fake.account, bot), listOf(fake.wiki), "w1"))), dir, OkHttpClient())
        show(m)
        waitFor("Fran")
        waitFor("Streak:")
        waitFor("Administrator")
        rule.onNodeWithText("Mopkeeper").performClick()
        waitFor("Bot password WikiMop")
        sheetShot("accounts")

        rule.onNodeWithText("Bot password WikiMop").performClick()
        until("the switch") { m.saved.wikis.single().accountId == "b1" }
        // A bot password has the same user name, so it says which one it is, also where changes are confirmed.
        waitFor("Mopkeeper (bot password)")
        waitFor("Fran")
        waitFor("Streak:")
        waitFor("Administrator")
        shot("home-bot-password")
        m.go(Dest.Diff("Elaina", 5101, 5120, vandalism))
        waitFor("world on her broom")
        rule.onNodeWithContentDescription("Actions").performClick()
        rule.onNodeWithText("Patrol").performClick()
        waitFor("as Mopkeeper (bot password)")
        m.cancelRequest()
        assertTrue(fake.writes().isEmpty())
    }

    @Test fun confirmSheets() {
        val m = model()
        show(m)
        waitFor("Fran")
        fun sheet(dest: Dest.Form, button: String, slide: String, name: String, fill: () -> Unit = {}) {
            m.go(dest)
            waitFor(dest.target)
            fill()
            rule.onNode(hasText(button) and hasClickAction()).performClick()
            waitFor(slide)
            sheetShot(name)
            m.cancelRequest()
            m.back()
        }
        sheet(Dest.Form(FormKind.UNDO, "Elaina", "StardustReader", 5120), "Undo edit", "Slide to undo", "confirm-undo")
        sheet(Dest.Form(FormKind.DELETE, "Elaina"), "Delete page", "Slide to delete", "confirm-delete") {
            rule.onAllNodesWithText("Vandalism")[0].performClick()
        }
        sheet(Dest.Form(FormKind.RESTORE, "Ashen Witch (old)"), "Restore page", "Slide to restore", "confirm-restore")
        sheet(Dest.Form(FormKind.PROTECT, "Elaina"), "Protect page", "Slide to protect", "confirm-protect") {
            rule.onAllNodesWithText("Autoconfirmed users")[0].performClick()
            rule.onNodeWithText("1 week").performClick()
        }
        sheet(Dest.Form(FormKind.MOVE, "Elaina"), "Move page", "Slide to move", "confirm-move") {
            rule.onNode(hasText("Elaina") and hasSetTextAction()).performTextReplacement("Elaina (character)")
        }
        sheet(Dest.Form(FormKind.UNBLOCK, "SpamBlaster99"), "Unblock user", "Slide to unblock", "confirm-unblock")

        // Patrol has no form. It goes straight from the diff's actions to the sheet.
        m.go(Dest.Diff("Elaina", 5101, 5120, vandalism))
        waitFor("world on her broom")
        rule.onNodeWithContentDescription("Actions").performClick()
        waitFor("Patrol")
        rule.onNodeWithText("Patrol").performClick()
        waitFor("Slide to mark as patrolled")
        sheetShot("confirm-patrol")
        m.cancelRequest()
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
        val request = Request(PendingWrite.Delete(fake.wiki, "Ashen Witch (old)", "Housekeeping")) {}.apply { sending = true }
        rule.setContent { WikiMopTheme(dynamicColor = false) { ConfirmSheet(request, "Wandering Witch Wiki", "Mopkeeper", {}, {}) } }
        waitFor("Sending")
        val label = rule.onNodeWithText("Sending").fetchSemanticsNode().boundsInRoot
        val cancel = rule.onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
        assertEquals(cancel.center.x, label.center.x, 1f)
    }

    private fun readOnly(m: AppModel, name: String) {
        show(m)
        waitFor("Fran")
        shot(name)
        m.go(Dest.Diff("Elaina", 5101, 5120, vandalism))
        waitFor("world on her broom")
        rule.onNodeWithContentDescription("Actions").performClick()
        waitFor("Open page")
        assertTrue(rule.onAllNodesWithText("Rollback").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Undo").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Patrol").fetchSemanticsNodes().isEmpty())
        m.back()
        m.openPage("Elaina")
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
        val other = fake.wiki.copy(id = "w2", base = fake.wiki.base + "/es", name = "Majo no Tabitabi Wiki")
        val saved = Saved(listOf(fake.account), listOf(fake.wiki, other), "w1", Settings())
        val m = AppModel(MemoryVault(SavedJson.write(saved)), RuntimeEnvironment.getApplication().cacheDir.resolve("drafts-" + System.nanoTime()), OkHttpClient())
        show(m)
        waitFor("Fran")
        until("the wiki's icon") { m.saved.wikis.first().icon != null }
        rule.onNodeWithContentDescription("Switch wiki").performClick()
        waitFor("Wikis")
        sheetShot("wikis")
        rule.onNodeWithContentDescription("Account for Wandering Witch Wiki").performClick()
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
    @Test fun addingAWiki() {
        val ww = FakeWiki.saved("wandering-witch")
        try {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url
                when (url.host) {
                    // Fandom's own answer for an old address.
                    "wandering-witch.wikia.com" -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(301).message("Moved Permanently")
                        .header("Location", "https://wandering-witch.fandom.com${url.encodedPath}?${url.encodedQuery}").body("".toResponseBody()).build()
                    ww.host -> chain.proceed(chain.request().newBuilder().url(url.newBuilder().scheme("http").host(ww.server.hostName).port(ww.server.port).build()).build())
                    else -> throw UnknownHostException(url.host)
                }
            }.build()
            val m = AppModel(MemoryVault(SavedJson.write(Saved())), RuntimeEnvironment.getApplication().cacheDir.resolve("drafts-" + System.nanoTime()), http)
            show(m)
            m.go(Dest.AddWiki)
            waitFor("Wiki address")
            fun look(address: String) {
                rule.onNode(hasSetTextAction()).performTextReplacement(address)
                rule.onNode(hasSetTextAction()).performImeAction()
            }
            val connectivity = shadowOf(RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java))

            // Offline, an address that cannot be reached is not called missing.
            connectivity.setActiveNetworkInfo(null)
            look("gone-wiki")
            waitFor("No connection")
            shot("add-wiki-offline")

            // Online, the same address does not exist.
            connectivity.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED))
            val caps = ShadowNetworkCapabilities.newInstance()
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            connectivity.setNetworkCapabilities(RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java).activeNetwork, caps)
            look("gone-wiki")
            waitFor("Wiki not found")

            // An old wikia.com address, typed with a space, finds the wiki at its current address.
            look("wandering witch.wikia.com")
            waitFor("Wandering Witch Wiki")
            assertTrue(rule.onAllNodesWithText("Wiki not found").fetchSemanticsNodes().isEmpty())
            shot("add-wiki-found")
            assertTrue(ww.writes().isEmpty())
        } finally {
            ww.stop()
        }
    }

    /** Searching by name, with a saved answer of Fandom's wiki search for "witch". Tapping a wiki looks it up. */
    @Test fun searchingForAWiki() {
        val ww = FakeWiki.saved("wandering-witch")
        val search = javaClass.getResourceAsStream("/wikis/search-witch.json")!!.use { it.readBytes().decodeToString() }
        try {
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val url = chain.request().url
                when (url.host) {
                    "services.fandom.com" -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(search.toResponseBody()).build()
                    ww.host -> chain.proceed(chain.request().newBuilder().url(url.newBuilder().scheme("http").host(ww.server.hostName).port(ww.server.port).build()).build())
                    else -> throw UnknownHostException(url.host)
                }
            }.build()
            val m = AppModel(MemoryVault(SavedJson.write(Saved())), RuntimeEnvironment.getApplication().cacheDir.resolve("drafts-" + System.nanoTime()), http)
            show(m)
            m.go(Dest.AddWiki)
            waitFor("Wiki address")
            // A name typed where the address goes is searched for right away.
            rule.onNode(hasSetTextAction()).performTextReplacement("witch")
            rule.onNodeWithText("Search").performClick()
            waitFor("Use full words")
            waitFor("Witch Hat Atelier Wiki")
            sheetShot("add-wiki-search")
            rule.onNode(hasScrollToKeyAction()).performScrollToKey("https://wandering-witch.fandom.com")
            rule.onNodeWithText("wandering-witch.fandom.com").performClick()
            waitFor("Account")
            assertEquals("wandering-witch.fandom.com", rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().single().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            assertTrue(ww.writes().isEmpty())
        } finally {
            ww.stop()
        }
    }

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
        waitFor("Streak:")
        waitFor("Administrator")
        settle()
        shot("store-home")
        rule.onNodeWithContentDescription("Switch wiki").performClick()
        waitFor("Wikis")
        settle()
        sheetShot("store-wikis")
    }

    @Test fun storeDiff() = store("witch-hat-atelier") { m, w ->
        m.go(Dest.Diff(w.pageTitle, w.parentRevId, w.latestRevId, Change("rc1", "edit", w.pageTitle, revId = w.latestRevId, oldRevId = w.parentRevId, user = w.latestUser)))
        until("the diff to load") { rule.onAllNodesWithContentDescription("Actions").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Actions").performClick()
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
        waitFor("Fran")
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
        assertEquals("Reworded her travels and added Sheila", sent["summary"])
        assertTrue(m.drafts.isEmpty())
    }

    @Test fun lockedAppOpensNoConfirmSheet() {
        val m = model(Settings(changesLocked = true))
        m.saveDraft(draft())
        show(m)
        waitFor("Fran")
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
        fake.latestUser = "BroomKeeper"
        val m = model()
        m.saveDraft(draft())
        show(m)
        waitFor("Fran")
        m.go(Dest.Review("d1"))
        waitFor("BroomKeeper edited this page after you started")
        shot("review-page-changed")
        rule.onNodeWithText("Publish").performClick()
        rule.waitForIdle()
        assertEquals(null, m.request)
    }

    private fun undoWithBotDefault(rights: List<String>): PendingWrite.Undo {
        fake.rights = rights
        val m = model(Settings(markBot = true))
        show(m)
        waitFor("Fran")
        m.go(Dest.Form(FormKind.UNDO, "Elaina", "StardustReader", 5120))
        waitFor("Summary")
        rule.onNode(hasText("Undo edit") and hasClickAction()).performClick()
        until("the confirm sheet") { m.request != null }
        return m.request!!.write as PendingWrite.Undo
    }

    @Test fun botDefaultTurnsOnTheSwitch() {
        val undo = undoWithBotDefault(fake.rights + "bot")
        assertTrue(undo.markBot)
        assertTrue(undo.facts.contains("Bot edit" to "Yes"))
    }

    @Test fun botDefaultSkipsAccountsWithoutTheRight() {
        val undo = undoWithBotDefault(fake.rights)
        assertTrue(rule.onAllNodesWithText("Bot edit").fetchSemanticsNodes().isEmpty())
        assertTrue(!undo.markBot)
    }

    private fun rollbackWithBotDefault(rights: List<String>): PendingWrite.Rollback {
        fake.rights = rights
        val m = model(Settings(markBot = true))
        show(m)
        waitFor("Fran")
        m.go(Dest.Form(FormKind.ROLLBACK, "Elaina", "StardustReader"))
        waitFor("Mark as bot edits")
        rule.onNode(hasText("Rollback") and hasClickAction()).performClick()
        until("the confirm sheet") { m.request != null }
        return m.request!!.write as PendingWrite.Rollback
    }

    @Test fun botDefaultMarksRollbacksOfBotAccounts() {
        assertTrue(rollbackWithBotDefault(fake.rights + "markbotedits" + "bot").markBot)
    }

    @Test fun botDefaultLeavesAdminRollbacksUnmarked() {
        assertTrue(!rollbackWithBotDefault(fake.rights + "markbotedits").markBot)
    }

    @Test fun summaryIsNeededBeforePublishing() {
        val m = model(Settings(requireSummary = true))
        m.saveDraft(draft(summary = ""))
        show(m)
        waitFor("Fran")
        m.go(Dest.Review("d1"))
        waitFor("Summary required")
        rule.onNodeWithText("Publish").performClick()
        rule.waitForIdle()
        assertEquals(null, m.request)
    }
}
