package org.nextcolor.wikimop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nextcolor.wikimop.data.Namespace
import org.nextcolor.wikimop.data.SiteInfo
import org.nextcolor.wikimop.data.Trackers
import org.nextcolor.wikimop.ui.escapeClass
import org.nextcolor.wikimop.ui.pageClasses
import org.nextcolor.wikimop.ui.pageFrame
import org.nextcolor.wikimop.ui.previewPage

/** Ads and tracking are refused, and what the pages and sign-in need still loads. */
class TrackersTest {
    @Test fun previewLoadsFandomStyles() {
        val html = """<div class="mw-parser-output"><style>.lamp{color:red}</style><script>alert(1)</script><p class="lamp">Hi</p></div>"""
        val styles = listOf("ext.fandom.PortableInfoboxFandomDesktop.css", "site.styles", "user.styles")
        val base = "https://wandering-witch.fandom.com"

        val desktop = previewPage(html, styles, base, desktop = true, dark = false)
        assertTrue(desktop.contains("$base/load.php?modules=skin.fandomdesktop.styles|"))
        assertTrue(desktop.contains("|ext.fandom.PortableInfoboxFandomDesktop.css&amp;only=styles&amp;skin=fandomdesktop"))
        assertTrue(desktop.contains("$base/load.php?modules=site.styles&amp;only=styles&amp;skin=fandomdesktop"))
        assertTrue(desktop.contains("variant=light"))
        assertTrue(desktop.contains("skin-fandomdesktop action-view theme-fandomdesktop-light"))
        // Styles written by the wiki's editors stay, scripts go, and so do the modules that need a signed-in reader.
        assertTrue(desktop.contains(".lamp{color:red}"))
        assertFalse(desktop.contains("<script"))
        assertFalse(desktop.contains("user.styles"))

        val mobile = previewPage(html, styles, base, desktop = false, dark = true)
        assertTrue(mobile.contains("$base/load.php?modules=skin.fandommobile.styles|"))
        assertTrue(mobile.contains("skin-fandommobile action-view theme-fandommobile-dark"))
        assertTrue(mobile.contains("variant=dark"))
        assertFalse(mobile.contains("FandomDesktop"))
    }

    @Test fun pagesCarryTheClassesAndDirectionWikisStyleBy() {
        val base = "https://wandering-witch.fandom.com"
        val site = SiteInfo("Wandering Witch Wiki", base, listOf(Namespace(0, "(Main)"), Namespace(1, "Talk"), Namespace(2, "User", subpages = true)), "Main Page")
        assertEquals("ns-0 ns-subject page-Wandering_Witch_Wiki rootpage-Wandering_Witch_Wiki", pageClasses(pageFrame(site, "Wandering Witch Wiki")))
        // Main pages have no subpages, so a slash stays part of the title.
        assertEquals("ns-0 ns-subject page-Elaina_Light_Novel_Volume_1 rootpage-Elaina_Light_Novel_Volume_1", pageClasses(pageFrame(site, "Elaina/Light Novel: Volume 1")))
        assertEquals("ns-1 ns-talk page-Talk_Elaina rootpage-Talk_Elaina", pageClasses(pageFrame(site, "Talk:Elaina")))
        assertEquals("ns-2 ns-subject page-User_Mopkeeper_common_css rootpage-User_Mopkeeper", pageClasses(pageFrame(site, "User:Mopkeeper/common.css")))
        assertEquals("page-イレイナ", escapeClass("page-イレイナ"))

        val ltr = previewPage("<p>Hi</p>", emptyList(), base, desktop = false, dark = false, frame = pageFrame(site, "Talk:Elaina"))
        assertTrue(ltr.contains("""<html class="client-nojs" lang="en" dir="ltr">"""))
        assertTrue(ltr.contains("mediawiki ltr sitedir-ltr mw-hide-empty-elt ns-1 ns-talk page-Talk_Elaina rootpage-Talk_Elaina"))
        val rtl = previewPage("<p>Hi</p>", emptyList(), base, desktop = true, dark = false, frame = pageFrame(site.copy(lang = "ar", rtl = true), "Elaina"))
        assertTrue(rtl.contains("""<html class="client-nojs" lang="ar" dir="rtl">"""))
        assertTrue(rtl.contains("mediawiki rtl sitedir-rtl"))
        assertTrue(rtl.contains("""class="mw-body-content mw-content-rtl" lang="ar" dir="rtl">"""))
    }

    @Test fun adsAndTrackingAreBlocked() {
        assertTrue(Trackers.blocked("securepubads.g.doubleclick.net", "/tag/js/gpt.js"))
        assertTrue(Trackers.blocked("beacon.wikia-services.com", "/__track/view"))
        assertTrue(Trackers.blocked("services.fandom.com", "/adeng/api/adengine/meter/time"))
        assertTrue(Trackers.blocked("script.wikia.nocookie.net", "/fandom-ae-assets/odyssey/ad-engine/production/main.js"))
        assertTrue(Trackers.blocked("RP.LIADM.COM", "/j"))
    }

    @Test fun pagesAndSignInStillLoad() {
        assertFalse(Trackers.blocked("witch-hat-atelier.fandom.com", "/load.php"))
        assertFalse(Trackers.blocked("auth.fandom.com", "/signin"))
        assertFalse(Trackers.blocked("services.fandom.com", "/galactus-user-profile/v2/profiles/me"))
        assertFalse(Trackers.blocked("static.wikia.nocookie.net", "/fandom-ae-assets/fonts/rubik/v23_new/a.woff2"))
        assertFalse(Trackers.blocked("static.wikia.nocookie.net", "/witch-hat-atelier/images/4/43/Coco-a.png/revision/latest"))
        assertFalse(Trackers.blocked("accounts.google.com", "/gsi/client"))
        assertFalse(Trackers.blocked("www.fandom.com", "/cdn-cgi/challenge-platform/scripts/jsd/main.js"))
        // A name that only ends the same way is not the blocked host.
        assertFalse(Trackers.blocked("notdoubleclick.net", "/"))
    }
}
