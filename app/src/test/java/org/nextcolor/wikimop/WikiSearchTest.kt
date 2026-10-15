package org.nextcolor.wikimop

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nextcolor.wikimop.data.ApiError
import org.nextcolor.wikimop.data.USER_AGENT
import org.nextcolor.wikimop.data.searchWikis
import org.nextcolor.wikimop.data.wikiLanguagesInOrder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Uses a saved answer of Fandom's wiki search for "witch" in every language. */
class WikiSearchTest {
    private val saved = javaClass.getResourceAsStream("/wikis/search-witch.json")!!.use { it.readBytes().decodeToString() }
    private val asked = CopyOnWriteArrayList<Request>()

    private fun http(code: Int = 200, body: String = saved) = OkHttpClient.Builder().addInterceptor { chain ->
        asked += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun asksForTheFirstHundred() = runTest {
        searchWikis(http(), "  witch ", null)
        val url = asked.single().url
        assertEquals("services.fandom.com", url.host)
        assertEquals("witch", url.queryParameter("query"))
        assertEquals("100", url.queryParameter("limit"))
        assertNull(url.queryParameter("lang"))
        assertEquals(USER_AGENT, asked.single().header("User-Agent"))

        searchWikis(http(), "witch", "pt-br")
        assertEquals("pt-br", asked.last().url.queryParameter("lang"))
    }

    @Test fun wikisNamedAfterTheSearchComeFirst() = runTest {
        val hits = searchWikis(http(), "witch", null)
        assertEquals(100, hits.size)
        val named = hits.takeWhile { "witch" in it.name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")) }
        assertTrue(named.size in 1 until hits.size)
        // The Marvel wiki has the most views but only mentions witches, so it leads the rest instead of the list.
        assertEquals("Marvel Cinematic Universe Wiki", hits[named.size].name)
        assertEquals(named.map { it.views }.sortedDescending(), named.map { it.views })
        assertEquals(hits.drop(named.size).map { it.views }.sortedDescending(), hits.drop(named.size).map { it.views })
    }

    @Test fun resultsAreTidied() = runTest {
        val hits = searchWikis(http(), "witch", null)
        val ww = hits.single { it.base == "https://wandering-witch.fandom.com" }
        assertEquals("Wandering Witch Wiki", ww.name)
        assertEquals("anime", ww.hub)
        assertTrue(ww.thumbnail!!.contains("/revision/latest/scale-to-width-down/96"))
        // Fandom's description for every new wiki, and its placeholder picture, say nothing about the wiki.
        assertFalse(hits.any { it.description.orEmpty().endsWith("anyone can contribute to. Discover, share and add your knowledge!") })
        assertFalse(hits.any { it.thumbnail.orEmpty().contains("placeholder") })
        assertTrue(hits.any { it.description != null })
        assertFalse(hits.any { it.description.orEmpty().contains('\n') })
        // Other languages keep their path.
        assertTrue(hits.filter { it.language != "en" }.all { it.base.endsWith("/" + it.language) })
    }

    @Test fun errorsAreErrors() = runTest {
        val e = runCatching { searchWikis(http(503, "<html>down</html>"), "witch", null) }.exceptionOrNull()
        assertTrue(e is ApiError)
    }

    @Test fun phoneLanguageAndEnglishComeFirst() {
        assertEquals(listOf("de", "en"), wikiLanguagesInOrder(Locale.GERMANY).take(2))
        assertEquals(listOf("pt-br", "en"), wikiLanguagesInOrder(Locale.forLanguageTag("pt-BR")).take(2))
        assertEquals("en", wikiLanguagesInOrder(Locale.US).first())
        assertFalse(wikiLanguagesInOrder(Locale.US).drop(1).contains("en"))
        // A language Fandom has no wikis in is not offered.
        assertEquals("en", wikiLanguagesInOrder(Locale.forLanguageTag("is")).first())
    }
}
