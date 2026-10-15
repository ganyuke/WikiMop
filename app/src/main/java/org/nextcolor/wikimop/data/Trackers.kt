package org.nextcolor.wikimop.data

/**
 * The ad and tracking addresses that Fandom's pages and sign-in page load. Most were
 * seen in a real page load, the rest are the usual ad networks those load in turn.
 * Fandom's own services are only listed by path, because the sign-in page needs the
 * rest of them, and the fonts under fandom-ae-assets are part of the page's look.
 */
object Trackers {
    private val hosts = listOf(
        // Fandom's own tracking.
        "wikia-services.com",
        // Ads, identity sync and analytics.
        "3lift.com", "ad-delivery.net", "ad.gt", "adnxs.com", "adsafeprotected.com", "adsrvr.org", "amazon-adsystem.com",
        "analytics.yahoo.com", "btmessage.com", "casalemedia.com", "cloudflareinsights.com", "confiant-integrations.net",
        "connatix.com", "cookielaw.org", "criteo.com", "criteo.net", "crwdcntrl.net", "doubleclick.net", "doubleverify.com",
        "facebook.net", "fastly-insights.com", "formafore.com", "google-analytics.com", "googlesyndication.com",
        "googletagmanager.com", "googletagservices.com", "gumgum.com", "id5-sync.com", "imasdk.googleapis.com", "indexww.com",
        "jwpltx.com", "liadm.com", "moatads.com", "openx.net", "outbrain.com", "permutive.app", "permutive.com",
        "privacymanager.io", "pubmatic.com", "quantcount.com", "quantserve.com", "rlcdn.com", "rubiconproject.com",
        "scorecardresearch.com", "sharethrough.com", "taboola.com", "teads.tv",
    )

    private val paths = listOf(
        "services.fandom.com" to "/active-data-management-service/",
        "services.fandom.com" to "/adeng/",
        "services.fandom.com" to "/browser-errors/",
        "services.fandom.com" to "/campaign-provider/",
        "services.fandom.com" to "/geoip/",
        "services.fandom.com" to "/icbm/",
        "services.fandom.com" to "/identity-storage/",
        "wikia.nocookie.net" to "/fandom-ae-assets/identity-service/",
        "wikia.nocookie.net" to "/fandom-ae-assets/morpheus/",
        "wikia.nocookie.net" to "/fandom-ae-assets/odyssey/",
        "wikia.nocookie.net" to "/silversurfer/",
    )

    fun blocked(host: String, path: String): Boolean {
        val h = host.lowercase()
        fun under(domain: String) = h == domain || h.endsWith(".$domain")
        return hosts.any(::under) || paths.any { (domain, prefix) -> under(domain) && path.startsWith(prefix) }
    }
}
