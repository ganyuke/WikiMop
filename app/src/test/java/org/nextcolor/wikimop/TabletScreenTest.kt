package org.nextcolor.wikimop

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** Every test in [ScreenTest] again, on a 10-inch tablet held sideways, the size of a Pixel Tablet. */
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class TabletScreenTest : ScreenTest() {
    override val device = "tablet"

    @Test fun changeOpensBesideTheList() {
        val m = model()
        show(m)
        waitFor("No change selected")
        waitFor("Fran")
        rule.onAllNodesWithText("Elaina").onFirst().performClick()
        waitFor("world on her broom")
        // The diff is beside the list, not a screen of its own, and the list stays.
        assertEquals(1, m.stack.size)
        assertEquals("Elaina", (m.beside?.dest as Dest.Diff).title)
        assertTrue(rule.onAllNodesWithText("Saya").fetchSemanticsNodes().isNotEmpty())
        shot("home-change-open")

        // Older edit stays beside the list too.
        rule.onNodeWithText("Older edit").performClick()
        until("the older edit") { (m.beside?.dest as? Dest.Diff)?.to == 5101L }
        assertEquals(1, m.stack.size)

        // Another change from the list takes its place.
        rule.onAllNodesWithText("Fran").onFirst().performClick()
        until("Fran beside the list") { (m.beside?.dest as? Dest.Diff)?.title == "Fran" }
        assertEquals(1, m.stack.size)

        // Back closes it.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        until("closed") { m.beside == null }
        waitFor("No change selected")

        // Too narrow for two panes, an open change becomes a screen of its own.
        rule.onAllNodesWithText("Fran").onFirst().performClick()
        until("Fran beside the list") { m.beside != null }
        rule.runOnUiThread { m.narrow() }
        assertNull(m.beside)
        assertEquals(2, m.stack.size)
        assertEquals("Fran", (m.top.dest as Dest.Diff).title)
        assertTrue(fake.writes().isEmpty())
    }
}
