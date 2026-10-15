package org.nextcolor.wikimop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

/** One wiki found by [searchWikis]. Fandom rounds the counts to one significant figure. */
data class WikiHit(
    val name: String,
    val base: String,
    val language: String,
    val description: String?,
    val thumbnail: String?,
    val hub: String,
    val pages: Long,
    val images: Long,
    val videos: Long,
    val views: Long,
)

/** Fandom's wiki search, the one behind Special:SearchCommunity. It only matches whole words, give or take a typo or two. */
private const val COMMUNITY_SEARCH = "https://services.fandom.com/unified-search/community-search"

/** The most the search gives in one answer, whatever limit is asked for. */
private const val SEARCH_LIMIT = 100

/** Languages that have wikis on Fandom, as the search names them. */
val WIKI_LANGUAGES = listOf(
    "ar", "bg", "ca", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi", "fr", "he", "hi", "hu", "id", "it", "ja", "ko",
    "lt", "ms", "nl", "pl", "pt", "pt-br", "ro", "ru", "sk", "sr", "sv", "th", "tl", "tr", "uk", "vi", "zh", "zh-hk", "zh-tw",
)

/** A language's name in the phone's own language, such as "Portuguese (Brazil)" for pt-br. */
fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayName(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** [WIKI_LANGUAGES] with the phone's language and English first, then the rest by name. */
fun wikiLanguagesInOrder(phone: Locale = Locale.getDefault()): List<String> {
    val tag = phone.toLanguageTag().lowercase()
    val own = WIKI_LANGUAGES.firstOrNull { it == tag } ?: WIKI_LANGUAGES.firstOrNull { it == phone.language }
    val first = listOfNotNull(own, "en").distinct()
    return first + (WIKI_LANGUAGES - first.toSet()).sortedBy { languageName(it) }
}

/**
 * Searches Fandom for wikis by name. [language] is a code from [WIKI_LANGUAGES], or null for every language.
 * Answers with the first [SEARCH_LIMIT], in the order of [rankWikis].
 */
suspend fun searchWikis(http: OkHttpClient, query: String, language: String?): List<WikiHit> = withContext(Dispatchers.IO) {
    val url = COMMUNITY_SEARCH.toHttpUrl().newBuilder()
        .addQueryParameter("query", query.trim())
        .addQueryParameter("limit", SEARCH_LIMIT.toString())
        .apply { if (language != null) addQueryParameter("lang", language) }
        .build()
    http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
        val body = response.body?.string().orEmpty()
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ApiError("badresponse", "Unexpected response (HTTP ${response.code})")
        }
        if (!response.isSuccessful) throw ApiError("badresponse", json.optString("detail").ifEmpty { "Unexpected response (HTTP ${response.code})" })
        rankWikis(parseWikiHits(json), query)
    }
}

internal fun parseWikiHits(json: JSONObject): List<WikiHit> = json.optJSONArray("results").objects().mapNotNull { r ->
    val base = wikiBase(r.optString("url")) ?: return@mapNotNull null
    WikiHit(
        name = r.optString("name").ifEmpty { base.removePrefix("https://") },
        base = base,
        language = r.optString("language"),
        // Most wikis keep the description Fandom gives every new wiki, which says nothing about this one.
        description = r.optString("description").replace(Regex("\\s+"), " ").trim()
            .takeUnless { it.isEmpty() || it.endsWith("is a community site that anyone can contribute to. Discover, share and add your knowledge!") },
        // Wikis without a picture of their own get Fandom's placeholder, which is left out so the first letter shows instead.
        thumbnail = r.optString("thumbnail").takeUnless { it.isEmpty() || it.contains("placeholder", ignoreCase = true) }?.let(::smallThumbnail),
        hub = r.optString("hub"),
        pages = r.optLong("pageCount"),
        images = r.optLong("imageCount"),
        videos = r.optLong("videoCount"),
        views = r.optLong("pageViews"),
    )
}

/** Asks Fandom's image server for a small copy, so a list of icons does not fetch full-size pictures. */
internal fun smallThumbnail(url: String): String =
    if (url.contains("/revision/latest") && !url.contains("/scale-to-width-down/")) url.replaceFirst("/revision/latest", "/revision/latest/scale-to-width-down/96")
    else url

/**
 * Fandom ranks by text match, which puts empty wikis next to big ones and wikis that only mention a word in their
 * description above wikis named after it. Here wikis with every searched word in their name come first, and within
 * that, and among the rest, the most viewed first.
 */
internal fun rankWikis(hits: List<WikiHit>, query: String): List<WikiHit> {
    fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    val wanted = words(query)
    return hits.sortedWith(compareBy<WikiHit> { hit -> if (wanted.isNotEmpty() && words(hit.name).containsAll(wanted)) 0 else 1 }.thenByDescending { it.views })
}
