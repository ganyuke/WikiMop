package org.nextcolor.wikimop

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.nextcolor.wikimop.data.Account
import org.nextcolor.wikimop.data.AccountKind
import org.nextcolor.wikimop.data.Wiki
import java.net.URLDecoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A made-up wiki that answers api.php the way MediaWiki does, and keeps a list
 * of everything it was asked. Tests use the list to check what was and was not sent.
 */
class FakeWiki(
    val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
    /**
     * A snapshot of a real wiki from scripts/snapshot_wikis.py. With one, the wiki's name, icon, changes, logs,
     * search results and its sample page are the real ones. The signed-in user and everything else stay made up.
     */
    private val seed: JSONObject? = null,
    private val key: String = "lanternfall",
) : Dispatcher() {
    val server = MockWebServer()
    val calls = CopyOnWriteArrayList<Map<String, String>>()

    var signedIn = true
    var rights = listOf("read", "edit", "createpage", "move", "delete", "undelete", "block", "protect", "rollback", "patrol", "suppressredirect")
    /** The newest revision of the sample page. Raise it to act as if someone else edited. */
    var latestRevId = seed?.getJSONObject("page")?.getJSONArray("revisions")?.getJSONObject(0)?.getLong("revid") ?: 5120L
    var latestUser = seed?.getJSONObject("page")?.getJSONArray("revisions")?.getJSONObject(0)?.getString("user") ?: "Quillfeather"
    var dropWrites = false
    var pageExists = true
    var editedToday = true

    /** The address the app uses for a saved wiki. The test's HTTP client sends it to [server]. */
    val host: String? = seed?.getString("host")
    val pageTitle: String = seed?.getJSONObject("page")?.getString("title") ?: "Emberfall Keep"
    /** The revision before the newest one of the sample page. */
    val parentRevId: Long = seed?.getJSONObject("page")?.getJSONArray("revisions")?.getJSONObject(0)?.getLong("parentid") ?: 5101L
    /** The page at the top of recent changes. */
    val newestTitle: String = seed?.getJSONArray("changes")?.getJSONObject(0)?.getString("title") ?: "Emberfall Keep"

    val text = seed?.getJSONObject("page")?.getString("text") ?: """
        {{Infobox location
        |name   = Emberfall Keep
        |region = Northern Reach
        |boss   = [[The Lamplighter]]
        }}
        '''Emberfall Keep''' is a ruined fortress in the [[Northern Reach]].

        == History ==
        The keep was built by the [[Order of the Wick]] and fell during the Long Dusk.<ref>Loading screen text</ref>

        == Enemies ==
        * [[Ash Hound]]
        * [[Wick Knight]]
        <!-- Drop table goes here -->
    """.trimIndent()

    init {
        server.dispatcher = this
        server.start()
    }

    val wiki get() =
        if (seed == null) Wiki("w1", server.url("/").toString().trimEnd('/'), "Lanternfall Wiki", "a1")
        else Wiki(key, "https://$host", seed.getJSONObject("general").getString("sitename"), "a1", icon = "https://$host/favicon.ico")

    /** Saved times are moved forward together, so the newest saved change happened a few minutes ago. */
    private val shift: Long = seed?.getJSONArray("changes")?.getJSONObject(0)?.getString("timestamp")
        ?.let { ChronoUnit.SECONDS.between(Instant.parse(it), now.minusSeconds(240)) } ?: 0
    private fun moved(time: String) = Instant.parse(time).plusSeconds(shift).toString()
    private fun kept(name: String, vararg times: String): List<JSONObject> {
        val list = seed!!.getJSONArray(name)
        return (0 until list.length()).map { i ->
            JSONObject(list.getJSONObject(i).toString()).also { item -> for (t in times) if (item.has(t)) item.put(t, moved(item.getString(t))) }
        }
    }
    val account = Account("a1", AccountKind.FANDOM, "Mopkeeper")

    fun writes() = calls.filter { it["action"] !in setOf("query", "parse", "compare", "login") }
    fun stop() = server.shutdown()

    private fun ago(minutes: Long) = now.minus(minutes, ChronoUnit.MINUTES).toString()

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.path == "/favicon.ico") {
            val icon = javaClass.getResourceAsStream(if (seed == null) "/lanternfall.ico" else "/wikis/$key.ico")!!.use { it.readBytes() }
            return MockResponse().setHeader("Content-Type", "image/x-icon").setBody(okio.Buffer().write(icon))
        }
        val params = HashMap<String, String>()
        val raw = (request.requestUrl?.encodedQuery.orEmpty() + "&" + request.body.readUtf8())
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            params[URLDecoder.decode(pair.substringBefore('='), "UTF-8")] = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        calls += params
        if (params["assert"] == "user" && !signedIn) return json("""{"error":{"code":"assertuserfailed","info":"You are no longer logged in."}}""")
        return when (params["action"]) {
            "query" -> json(query(params).toString())
            "parse" -> json(JSONObject().put("parse", JSONObject().put("title", pageTitle).put("revid", latestRevId)
                .put("text", if (seed == null) "<p><b>Emberfall Keep</b> is a ruined fortress.</p>" else "<p><b>$pageTitle</b></p>")
                .put("sections", seed?.getJSONObject("page")?.getJSONArray("sections")
                    ?: JSONArray("""[{"index":"1","level":"2","line":"History"},{"index":"2","level":"2","line":"Enemies"}]"""))
                .put("categories", JSONArray())).toString())
            "compare" -> json(compare())
            "login" -> { signedIn = true; json("""{"login":{"result":"Success","lgusername":"Mopkeeper"}}""") }
            else -> {
                if (dropWrites) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                when (params["action"]) {
                    "edit" -> json("""{"edit":{"result":"Success","pageid":77,"title":"Emberfall Keep","oldrevid":$latestRevId,"newrevid":${latestRevId + 1}}}""")
                    else -> json("""{"${params["action"]}":{}}""")
                }
            }
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun query(p: Map<String, String>): JSONObject {
        val q = JSONObject()
        val out = JSONObject().put("batchcomplete", true).put("query", q)
        if (p["curtimestamp"] != null) out.put("curtimestamp", now.toString())
        for (meta in p["meta"].orEmpty().split('|')) when (meta) {
            "siteinfo" -> {
                val general = seed?.getJSONObject("general")
                q.put("general", JSONObject().put("sitename", general?.getString("sitename") ?: "Lanternfall Wiki").put("server", wiki.base).put("scriptpath", "")
                    .put("mainpage", general?.getString("mainpage") ?: "Lanternfall Wiki"))
                val spaces = JSONObject()
                val named = seed?.let { kept("namespaces").map { n -> n.getInt("id") to n.getString("name") } }
                    ?: listOf(0 to "", 1 to "Talk", 2 to "User", 4 to "Lanternfall Wiki", 6 to "File", 8 to "MediaWiki", 10 to "Template", 14 to "Category", 828 to "Module")
                named.forEach { (id, name) -> spaces.put(id.toString(), JSONObject().put("id", id).put("name", name)) }
                q.put("namespaces", spaces)
            }
            "userinfo" -> q.put("userinfo", if (signedIn) JSONObject().put("id", 9).put("name", "Mopkeeper")
                .put("groups", JSONArray(listOf("*", "user", "sysop"))).put("rights", JSONArray(rights))
            else JSONObject().put("id", 0).put("name", "203.0.113.9").put("anon", true).put("groups", JSONArray()).put("rights", JSONArray(listOf("read"))))
            "tokens" -> q.put("tokens", JSONObject().put("${p["type"]}token", if (signedIn) "tok3n+\\" else "+\\"))
        }
        for (list in p["list"].orEmpty().split('|')) when (list) {
            "recentchanges", "watchlist" -> {
                val prefix = if (list == "watchlist") "wl" else "rc"
                val end = p["${prefix}end"]?.let(Instant::parse)
                q.put(list, JSONArray(changes().filter { p["rctype"] == null || it.getString("type") in p["rctype"]!!.split('|') }
                    .filter { end == null || Instant.parse(it.getString("timestamp")) >= end }
                    .filter { it.getString("user") != p["${prefix}excludeuser"] }))
            }
            "logevents" -> q.put(list, JSONArray(logs().filter { p["letype"] == null || it.getString("type") == p["letype"] }))
            "usercontribs" -> q.put(list, JSONArray(if (p["ucprop"] == "timestamp") streak() else contribs()))
            "blocks" -> q.put(list, JSONArray(if (p["bkusers"] == "SpamBlaster99" || p["bkusers"] == null) listOf(
                JSONObject().put("id", 31).put("user", "SpamBlaster99").put("by", "Tindercap").put("timestamp", ago(600))
                    .put("expiry", "infinity").put("reason", "Spam").put("nocreate", true).put("autoblock", true),
            ) else emptyList()))
            "users" -> q.put(list, JSONArray(listOf(JSONObject().put("userid", 41).put("name", p["ususers"]).put("editcount", 213)
                .put("registration", "2024-03-02T10:11:12Z").put("groups", JSONArray(listOf("*", "user", "autoconfirmed"))))))
            "backlinks" -> q.put(list, JSONArray((1..7).map { JSONObject().put("title", "Page $it") }))
            "search" -> q.put(list, JSONArray(if (seed != null) kept("search") else listOf("Emberfall Keep", "Ember Shard", "Template:Infobox location").map {
                JSONObject().put("title", it).put("snippet", "…ruined fortress in the <span class=\"searchmatch\">Ember</span> valley…").put("size", 1200)
            }))
            "prefixsearch" -> q.put(list, JSONArray(if (seed != null) kept("prefixsearch") else listOf("The Lamplighter", "The Long Dusk", "The Wick").map { JSONObject().put("title", it) }))
        }
        if (p["prop"] == "imageinfo") {
            q.put("pages", JSONArray(listOf(JSONObject().put("title", p["titles"])
                .put("imageinfo", JSONArray(listOf(JSONObject().put("url", if (seed == null) server.url("/favicon.ico").toString() else "https://$host/favicon.ico")))))))
        } else if (p["prop"] != null) {
            val page = JSONObject().put("ns", 0).put("title", p["titles"] ?: pageTitle)
            if (!pageExists) page.put("missing", true) else {
                page.put("pageid", 77).put("length", text.length).put("lastrevid", latestRevId)
                page.put("protection", JSONArray())
                if (p["rvlimit"] != null && seed != null) {
                    val history = seed.getJSONObject("page").getJSONArray("revisions")
                    page.put("revisions", JSONArray((0 until history.length()).map { JSONObject(history.getJSONObject(it).toString()).apply { put("timestamp", moved(getString("timestamp"))) } }))
                } else if (p["rvlimit"] != null) {
                    page.put("revisions", JSONArray(listOf(
                        rev(latestRevId, 5101, latestUser, 30, "Added the enemies list", 412),
                        rev(5101, 5050, "203.0.113.42", 190, "", 310),
                        rev(5050, 0, "Tindercap", 3000, "Created page", 290),
                    )))
                } else {
                    page.put("revisions", JSONArray(listOf(JSONObject().put("revid", latestRevId).put("parentid", 5101).put("user", latestUser)
                        .put("timestamp", ago(30)).put("slots", JSONObject().put("main", JSONObject().put("content", text))))))
                }
            }
            q.put("pages", JSONArray(listOf(page)))
        }
        return out
    }

    private fun rev(id: Long, parent: Long, user: String, minutes: Long, comment: String, size: Int) =
        JSONObject().put("revid", id).put("parentid", parent).put("user", user).put("timestamp", ago(minutes)).put("comment", comment).put("size", size).put("tags", JSONArray())

    private fun changes() = if (seed != null) kept("changes", "timestamp").onEach { it.put("patrolled", true) } else listOf(
        rc("edit", "Emberfall Keep", latestUser, 4, "Added the enemies list", 310, 412, id = latestRevId, old = 5101),
        rc("edit", "The Lamplighter", "203.0.113.42", 11, "", 5230, 1876, anon = true, patrolled = false, tags = listOf("mobile edit", "blanking")),
        rc("new", "Wick Knight", "Tindercap", 26, "Created page with stats from the 1.4 patch", 0, 2210, patrolled = false),
        rc("log", "User:SpamBlaster99", "Tindercap", 40, "Spam", 0, 0).put("logtype", "block").put("logaction", "block")
            .put("logparams", JSONObject().put("duration", "infinity").put("flags", JSONArray(listOf("nocreate")))),
        rc("edit", "Template:Infobox location", "Quillfeather", 95, "fix region param", 904, 921, minor = true),
        rc("log", "Ash Hound (old)", "Mopkeeper", 60 * 26, "Housekeeping", 0, 0).put("logtype", "delete").put("logaction", "delete"),
        rc("edit", "Northern Reach", "Quillfeather", 60 * 27, "Map updated", 3301, 3340),
    )

    private var counter = 9000L
    private fun rc(
        type: String, title: String, user: String, minutes: Long, comment: String, oldLen: Int, newLen: Int, anon: Boolean = false,
        patrolled: Boolean = true, minor: Boolean = false, tags: List<String> = emptyList(), id: Long = ++counter, old: Long = id - 1,
    ) = JSONObject().put("type", type).put("ns", 0).put("title", title).put("pageid", 77).put("revid", if (type == "log") 0 else id)
        .put("old_revid", if (type == "edit") old else 0).put("rcid", id + 100000).put("user", user).put("anon", anon)
        .put("bot", false).put("new", type == "new").put("minor", minor).put("oldlen", oldLen).put("newlen", newLen).put("timestamp", ago(minutes))
        .put("comment", comment).put("tags", JSONArray(tags)).put(if (patrolled) "patrolled" else "unpatrolled", true)

    private fun logs() = if (seed != null) kept("logs", "timestamp") else listOf(
        log("block", "block", "User:SpamBlaster99", "Tindercap", 40, "Spam", JSONObject().put("duration", "infinity").put("flags", JSONArray(listOf("nocreate")))),
        log("delete", "delete", "Ash Hound (old)", "Mopkeeper", 60 * 26, "Housekeeping", JSONObject()),
        log("move", "move", "Wick Knights", "Quillfeather", 60 * 30, "Singular page names", JSONObject().put("target_title", "Wick Knight")),
        log("protect", "protect", "Lanternfall Wiki", "Mopkeeper", 60 * 50, "High-traffic page", JSONObject().put("description", "[edit=sysop] (indefinite)")),
    )

    private fun log(type: String, action: String, title: String, user: String, minutes: Long, comment: String, params: JSONObject) =
        JSONObject().put("logid", ++counter).put("ns", 0).put("title", title).put("type", type).put("action", action).put("user", user)
            .put("timestamp", ago(minutes)).put("comment", comment).put("params", params)

    private fun contribs() = listOf(
        JSONObject().put("revid", 5101).put("parentid", 5050).put("title", "Emberfall Keep").put("timestamp", ago(190)).put("comment", "").put("size", 310).put("sizediff", 20).put("tags", JSONArray()),
        JSONObject().put("revid", 4990).put("parentid", 4980).put("title", "The Lamplighter").put("timestamp", ago(60 * 25)).put("comment", "phase 2 moves").put("size", 5230).put("sizediff", -3354).put("tags", JSONArray()),
    )

    /** Edits on each of the last twelve days, and on today when [editedToday] is set. */
    private fun streak() = buildList {
        if (editedToday) repeat(3) { add(JSONObject().put("timestamp", now.minus(it * 7L, ChronoUnit.SECONDS).toString())) }
        for (day in 1L..11L) add(JSONObject().put("timestamp", now.minus(day, ChronoUnit.DAYS).toString()))
        add(JSONObject().put("timestamp", now.minus(14, ChronoUnit.DAYS).toString()))
    }

    private fun compare() = if (seed != null) {
        val saved = JSONObject(seed.getJSONObject("compare").toString())
        for (t in listOf("fromtimestamp", "totimestamp")) saved.put(t, moved(saved.getString(t)))
        JSONObject().put("compare", saved).toString()
    } else JSONObject().put("compare", JSONObject()
        .put("fromrevid", 5101).put("fromuser", "203.0.113.42").put("fromtimestamp", ago(190)).put("fromcomment", "").put("fromsize", 310)
        .put("torevid", latestRevId).put("touser", latestUser).put("totimestamp", ago(4)).put("tocomment", "Added the enemies list").put("tosize", 412)
        .put("totitle", "Emberfall Keep").put("body", DIFF)).toString()

    companion object {
        /** A fake wiki that answers with what was saved from a real one. */
        fun saved(key: String) = FakeWiki(seed = JSONObject(FakeWiki::class.java.getResourceAsStream("/wikis/$key.json")!!.use { it.readBytes().decodeToString() }), key = key)

        val DIFF = """
            <tr><td colspan="2" class="diff-lineno">Line 8:</td><td colspan="2" class="diff-lineno">Line 8:</td></tr>
            <tr><td class="diff-marker"></td><td class="diff-context diff-side-deleted"><div>== History ==</div></td>
                <td class="diff-marker"></td><td class="diff-context diff-side-added"><div>== History ==</div></td></tr>
            <tr><td class="diff-marker" data-marker="−"></td><td class="diff-deletedline diff-side-deleted"><div>The keep was built by the Order and fell during the <del class="diffchange diffchange-inline">war</del>.</div></td>
                <td class="diff-marker" data-marker="+"></td><td class="diff-addedline diff-side-added"><div>The keep was built by the Order and fell during the <ins class="diffchange diffchange-inline">Long Dusk</ins>.</div></td></tr>
            <tr><td colspan="2" class="diff-empty diff-side-deleted"></td><td class="diff-marker" data-marker="+"></td><td class="diff-addedline diff-side-added"><br /></td></tr>
            <tr><td colspan="2" class="diff-empty diff-side-deleted"></td><td class="diff-marker" data-marker="+"></td><td class="diff-addedline diff-side-added"><div>== Enemies ==</div></td></tr>
            <tr><td colspan="2" class="diff-empty diff-side-deleted"></td><td class="diff-marker" data-marker="+"></td><td class="diff-addedline diff-side-added"><div>* [[Ash Hound]]</div></td></tr>
            <tr><td colspan="2" class="diff-empty diff-side-deleted"></td><td class="diff-marker" data-marker="+"></td><td class="diff-addedline diff-side-added"><div>* [[Wick Knight]]</div></td></tr>
        """.trimIndent()
    }
}
