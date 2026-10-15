package org.nextcolor.wikimop.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import org.jsoup.Jsoup
import org.nextcolor.wikimop.data.Trackers
import java.io.ByteArrayInputStream
import java.net.URLDecoder

/** An empty answer in place of a request to an ad or tracking address, or null to let the request go out. */
fun refuseTracker(request: WebResourceRequest, block: Boolean): WebResourceResponse? {
    if (!block || !Trackers.blocked(request.url.host.orEmpty(), request.url.path.orEmpty())) return null
    return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

/** The phone's own browser engine, introduced as desktop Chrome so Fandom sends its desktop layout. */
private fun desktopAgent(context: Context): String {
    val chrome = Regex("Chrome/[\\d.]+").find(WebSettings.getDefaultUserAgent(context))?.value ?: "Chrome/140.0.0.0"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
}

/**
 * Fandom's desktop pages ask for the width of the screen, which squeezes the desktop
 * layout into a phone. This gives them the width of a laptop. The view does not zoom
 * out by itself when the width changes after loading, so [FandomPage] does that.
 */
private const val WIDEN = "(function(){var m=document.querySelector('meta[name=viewport]');" +
    "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
    "if(m.content!='width=1280')m.content='width=1280';})()"

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

/**
 * The page as Fandom shows it, in its mobile or desktop layout. Pages on this wiki
 * that are tapped open in the app, and other links in the browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FandomPage(url: String, base: String, desktop: Boolean, blockTrackers: Boolean, onPage: (String) -> Unit) {
    // A change of layout needs a new user agent before the first request, so the view starts over.
    key(url, desktop, blockTrackers) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    if (desktop) {
                        settings.userAgentString = desktopAgent(context)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = refuseTracker(request, blockTrackers)

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // Only taps are sent elsewhere. Redirects and Fandom's own reloads, like its bot check, stay here.
                            if (!request.isForMainFrame || !request.hasGesture()) return false
                            val target = request.url.toString()
                            if (target.substringBefore('#') == view.url?.substringBefore('#')) return false
                            val title = wikiTitle(target, base)
                            if (title != null) onPage(title) else openOutside(context, request)
                            return true
                        }

                        override fun onPageCommitVisible(view: WebView, url: String) {
                            if (!desktop) return
                            view.evaluateJavascript(WIDEN) {
                                // Zooming out stops at the page's full width. The new width needs a moment to take hold first.
                                view.postDelayed({ repeat(20) { if (!view.zoomOut()) return@postDelayed } }, 150)
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            // Fandom's scripts can set the width back. The zoom is left as the reader has it by now.
                            if (desktop) view.evaluateJavascript(WIDEN, null)
                        }
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A whole page around [html] that loads Fandom's own styles for the mobile or desktop
 * layout. [styles] are the modules the page itself asks for, such as the infobox's.
 */
fun previewPage(html: String, styles: List<String>, base: String, desktop: Boolean, dark: Boolean): String {
    val doc = Jsoup.parseBodyFragment(html, "$base/wiki/")
    // Fandom swaps pictures in with a script as they scroll into view. Scripts are off here, so it is done up front.
    doc.select("img[data-src]").forEach { it.attr("src", it.attr("data-src")) }
    doc.select("script, .mw-editsection").remove()
    val skin = if (desktop) "fandomdesktop" else "fandommobile"
    val theme = "theme-$skin-" + if (dark) "dark" else "light"
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
            """<div id="content" class="page-content"><div id="mw-content-text" class="mw-body-content">${doc.body().html()}</div></div></main></div></div></div>"""
    } else {
        """<main id="fandom-mobile-wrapper"><section class="article-content"><article id="content">""" +
            """<div id="mw-content-text" class="mw-body-content">${doc.body().html()}</div></article></section></main>"""
    }
    val viewport = if (desktop) "width=1280" else "width=device-width, initial-scale=1"
    return """<!doctype html><html class="client-nojs"><head><meta name="viewport" content="$viewport">$head</head>""" +
        """<body class="mediawiki ltr sitedir-ltr skin-$skin $theme action-view">$body</body></html>"""
}

/**
 * Wikitext that has not been published, rendered by the wiki and shown in [previewPage].
 * Scripts are off.
 */
@Composable
fun PreviewHtml(html: String, styles: List<String>, base: String, desktop: Boolean, blockTrackers: Boolean, onPage: (String) -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val page = remember(html, styles, desktop, dark) { previewPage(html, styles, base, desktop, dark) }
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
                        if (title != null) onPage(title) else openOutside(context, request)
                        return true
                    }
                }
            }
        },
        update = {
            it.settings.useWideViewPort = desktop
            it.settings.loadWithOverviewMode = desktop
            it.loadDataWithBaseURL("$base/wiki/", page, "text/html", "utf-8", null)
        },
        modifier = Modifier.fillMaxSize(),
    )
}
