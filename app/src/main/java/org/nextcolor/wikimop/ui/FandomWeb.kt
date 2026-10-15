package org.nextcolor.wikimop.ui

import android.content.Context
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.jsoup.Jsoup
import org.nextcolor.wikimop.data.SiteInfo
import org.nextcolor.wikimop.data.Trackers
import java.io.ByteArrayInputStream
import java.net.URLDecoder

/** An empty answer in place of a request to an ad or tracking address, or null to let the request go out. */
fun refuseTracker(request: WebResourceRequest, block: Boolean): WebResourceResponse? {
    if (!block || !Trackers.blocked(request.url.host.orEmpty(), request.url.path.orEmpty())) return null
    return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

/** Title of a page on this wiki that [url] points to, or null when it points anywhere else. */
private fun wikiTitle(url: String, base: String): String? {
    val prefix = "$base/wiki/"
    if (!url.startsWith(prefix)) return null
    // Red links carry ?action=edit. The app opens the page and offers to create it instead.
    return URLDecoder.decode(url.removePrefix(prefix).substringBefore('#').substringBefore('?'), "UTF-8").replace('_', ' ')
}

private fun openOutside(context: Context, request: WebResourceRequest) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (e: Exception) { /* Nothing can open it. */ }
}

/** What the wiki's styles can tell a page by: its title, namespace, and the wiki's language and direction. */
data class PageFrame(val title: String = "", val ns: Int = 0, val subpages: Boolean = false, val lang: String = "en", val rtl: Boolean = false)

/** The frame for [title] on a wiki, with its namespace found from the title's prefix. */
fun pageFrame(site: SiteInfo?, title: String): PageFrame {
    val prefix = title.substringBefore(':', "")
    val ns = site?.namespaces?.firstOrNull { it.id != 0 && it.name.equals(prefix, ignoreCase = true) } ?: site?.namespaces?.firstOrNull { it.id == 0 }
    return PageFrame(title, ns?.id ?: 0, ns?.subpages == true, site?.lang ?: "en", site?.rtl == true)
}

/** A class name as MediaWiki makes one, with spaces and punctuation turned into underscores. */
fun escapeClass(name: String) =
    name.replace(Regex("""(^[0-9\-])|[\x00-\x20!"#$%&'()*+,./:;<=>?@\[\\\]^`{|}~]|\u00A0"""), "_").replace(Regex("_+"), "_").trimEnd('_')

/** The classes MediaWiki gives a page's body for its namespace and title, which wikis use to style single pages. */
fun pageClasses(frame: PageFrame): String {
    val type = when {
        frame.ns < 0 -> "ns-special"
        frame.ns % 2 == 1 -> "ns-talk"
        else -> "ns-subject"
    }
    val root = if (frame.subpages) frame.title.substringBefore('/') else frame.title
    return listOf("ns-${frame.ns}", type, escapeClass("page-${frame.title}"), escapeClass("rootpage-$root")).joinToString(" ")
}

/**
 * A whole page around [html] that loads Fandom's own styles for the mobile or desktop
 * layout. [styles] are the modules the page itself asks for, such as the infobox's.
 */
fun previewPage(html: String, styles: List<String>, base: String, desktop: Boolean, dark: Boolean, frame: PageFrame = PageFrame()): String {
    val doc = Jsoup.parseBodyFragment(html, "$base/wiki/")
    // Fandom swaps pictures in with a script as they scroll into view. Scripts are off here, so it is done up front.
    doc.select("img[data-src]").forEach { it.attr("src", it.attr("data-src")) }
    doc.select("script, .mw-editsection").remove()
    val skin = if (desktop) "fandomdesktop" else "fandommobile"
    val theme = "theme-$skin-" + if (dark) "dark" else "light"
    val dir = if (frame.rtl) "rtl" else "ltr"
    val lang = frame.lang.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "en" }
    val text = """<div id="mw-content-text" class="mw-body-content mw-content-$dir" lang="$lang" dir="$dir">${doc.body().html()}</div>"""
    // The skin's own modules, as Fandom's real pages load them. user.styles needs a signed-in reader and noscript is for pages without scripts.
    val skinModules = if (desktop) {
        listOf("skin.fandomdesktop.styles", "ext.fandom.Thumbnails.css", "ext.fandom.photoGallery.gallery.css", "jquery.makeCollapsible.styles")
    } else {
        listOf("skin.fandommobile.styles", "mobile.init.styles", "ext.portableInfobox.mobile.css", "ext.fandom.MobileImageGallery.css", "jquery.makeCollapsible.styles")
    }
    val modules = (skinModules + styles.filter { it != "site.styles" && it != "user.styles" && it != "noscript" })
        .filter { desktop || !it.contains("FandomDesktop") }.distinct().joinToString("|")
    fun sheet(href: String) = """<link rel="stylesheet" href="${href.replace("&", "&amp;")}">"""
    val head = sheet("$base/load.php?modules=$modules&only=styles&skin=$skin") +
        sheet("$base/load.php?modules=site.styles&only=styles&skin=$skin") +
        sheet("$base/wikia.php?controller=ThemeApi&method=themeVariables&variant=" + if (dark) "dark" else "light")
    // The same elements that hold the page text on Fandom, since the skin's styles are written for them.
    val body = if (desktop) {
        """<div class="main-container"><div class="resizable-container"><div class="page"><main class="page__main">""" +
            """<div id="content" class="page-content">$text</div></main></div></div></div>"""
    } else {
        """<main id="fandom-mobile-wrapper"><section class="article-content"><article id="content">$text</article></section></main>"""
    }
    val viewport = if (desktop) "width=1280" else "width=device-width, initial-scale=1"
    return """<!doctype html><html class="client-nojs" lang="$lang" dir="$dir"><head><meta name="viewport" content="$viewport">$head</head>""" +
        """<body class="mediawiki $dir sitedir-$dir mw-hide-empty-elt ${pageClasses(frame)} skin-$skin action-view $theme">$body</body></html>"""
}

/**
 * Wikitext that has not been published, rendered by the wiki and shown in [previewPage].
 * Scripts are off.
 */
@Composable
fun PreviewHtml(
    html: String, styles: List<String>, base: String, frame: PageFrame, desktop: Boolean, dark: Boolean, blockTrackers: Boolean, onPage: (String) -> Unit,
) {
    val page = remember(html, styles, frame, desktop, dark) { previewPage(html, styles, base, desktop, dark, frame) }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = refuseTracker(request, blockTrackers)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val title = wikiTitle(request.url.toString(), base)
                        // Links to a section of the same page only scroll.
                        if (title == "") return false
                        if (title != null) onPage(title) else openOutside(context, request)
                        return true
                    }
                }
            }
        },
        update = {
            it.settings.useWideViewPort = desktop
            it.settings.loadWithOverviewMode = desktop
            // Only a new page is loaded, so the reader keeps their place when the screen around it changes.
            if (it.tag != page) {
                it.tag = page
                it.loadDataWithBaseURL("$base/wiki/", page, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
