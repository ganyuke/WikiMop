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
 * A made-up copy of the Wandering Witch Wiki that answers api.php the way MediaWiki
 * does, and keeps a list of everything it was asked. Tests use the list to check what
 * was and was not sent. The edits, users and pages beyond the main characters are made up.
 */
class FakeWiki(
    val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
    /**
     * A snapshot of a real wiki from scripts/snapshot_wikis.py. With one, the wiki's name, icon, changes, logs,
     * search results and its sample page are the real ones. The signed-in user and everything else stay made up.
     */
    private val seed: JSONObject? = null,
    private val key: String = "wandering-witch",
) : Dispatcher() {
    val server = MockWebServer()
    val calls = CopyOnWriteArrayList<Map<String, String>>()

    var signedIn = true
    /** What each group gives. Together, the account's groups give exactly [rights]. */
    val groupRights = mapOf(
        "*" to listOf("read"),
        "user" to listOf("edit", "createpage", "move"),
        "sysop" to listOf("delete", "undelete", "block", "protect", "rollback", "patrol", "suppressredirect"),
        "bureaucrat" to listOf("userrights"),
        "rollback" to listOf("rollback"),
    )
    private val messages = mapOf(
        "group-sysop-member" to "administrator", "group-user-member" to "User", "group-bureaucrat-member" to "Bureaucrat",
        "right-block" to "Block or unblock other users from editing", "right-delete" to "Delete pages",
        "right-rollback" to "Quickly rollback the edits of the last user who edited a particular page", "right-edit" to "Edit pages",
    )
    /** The signed-in account is blocked, on the whole wiki or with [blockPartial] only on some pages. */
    var blockedSelf = false
    var blockPartial = false
    var rights = listOf("read", "edit", "createpage", "move", "delete", "undelete", "block", "protect", "rollback", "patrol", "suppressredirect")
    /** The newest revision of the sample page. Raise it to act as if someone else edited. */
    var latestRevId = seed?.getJSONObject("page")?.getJSONArray("revisions")?.getJSONObject(0)?.getLong("revid") ?: 5120L
    var latestUser = seed?.getJSONObject("page")?.getJSONArray("revisions")?.getJSONObject(0)?.getString("user") ?: "StardustReader"
    var dropWrites = false
    var pageExists = true
    var editedToday = true
    /** Puts an unpatrolled upload of [FILE] in recent changes. */
    var upload = false
    /** Puts an edit by the signed-in account, made a minute ago, in recent changes but not yet in its contributions. */
    var ownEdit = false
    /** Served for the uploaded file and its pictures. */
    var pictureBytes: ByteArray? = null

    /** The address the app uses for a saved wiki. The test's HTTP client sends it to [server]. */
    val host: String? = seed?.getString("host")
    val pageTitle: String = seed?.getJSONObject("page")?.getString("title") ?: "Elaina"
    /** The revision before the newest one of the sample page. */
    val parentRevId: Long = seed?.getJSONObject("page")?.getJSONArray("revisions")?.getJSONObject(0)?.getLong("parentid") ?: 5101L
    /** The page at the top of recent changes. */
    val newestTitle: String = seed?.getJSONArray("changes")?.getJSONObject(0)?.getString("title") ?: "Elaina"

    val text = seed?.getJSONObject("page")?.getString("text") ?: """
        {{Character
        |name    = Elaina
        |title   = Ashen Witch
        |teacher = [[Fran]]
        }}
        '''Elaina''' is the main character of the series, a witch who travels from country to country.

        == Biography ==
        Elaina grew up in [[Robetta]] and left home to travel the world on her broom.<ref>Light Novel Volume 1</ref>

        == Relationships ==
        * [[Fran]]
        * [[Saya]]
        <!-- Add other people she meets here -->
    """.trimIndent()

    init {
        server.dispatcher = this
        server.start()
    }

    val wiki get() =
        if (seed == null) Wiki("w1", server.url("/").toString().trimEnd('/'), "Wandering Witch Wiki", "a1")
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

    /** The reason on the block in recent changes. */
    var blockReason = "Spam"

    /** Served in place of the wiki's own icon when set. */
    var iconBytes: ByteArray? = null

    fun writes() = calls.filter { it["action"] !in setOf("query", "parse", "compare", "login", "logout") }
    fun stop() = server.shutdown()

    private fun ago(minutes: Long) = now.minus(minutes, ChronoUnit.MINUTES).toString()

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.path?.startsWith("/images/") == true) {
            return MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(pictureBytes ?: ByteArray(0)))
        }
        if (request.path == "/favicon.ico") {
            val icon = iconBytes ?: javaClass.getResourceAsStream("/wikis/$key.ico")!!.use { it.readBytes() }
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
                .put("text", if (seed == null) "<p><b>Elaina</b> is the main character of the series.</p>" else "<p><b>$pageTitle</b></p>")
                .put("sections", seed?.getJSONObject("page")?.getJSONArray("sections")
                    ?: JSONArray("""[{"index":"1","level":"2","line":"Biography"},{"index":"2","level":"2","line":"Relationships"}]"""))
                .put("categories", JSONArray()).put("modulestyles", JSONArray(listOf("ext.fandom.PortableInfobox.styles")))).toString())
            "compare" -> json(compare(params))
            // Like MediaWiki, a bot password session can't sign in again.
            "login" -> if (request.getHeader("Cookie").orEmpty().contains("_BPsession=")) {
                json("""{"login":{"result":"Aborted","reason":"Cannot log in when using MediaWiki\\Session\\BotPasswordSessionProvider sessions."}}""")
            } else {
                signedIn = true
                json("""{"login":{"result":"Success","lgusername":"Mopkeeper"}}""").setHeader("Set-Cookie", "wiki_BPsession=s${++counter}; Path=/; HttpOnly")
            }
            "logout" -> { signedIn = false; json("{}") }
            else -> {
                if (dropWrites) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                when (params["action"]) {
                    "edit" -> json("""{"edit":{"result":"Success","pageid":77,"title":"Elaina","oldrevid":$latestRevId,"newrevid":${latestRevId + 1}}}""")
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
                q.put("general", JSONObject().put("sitename", general?.getString("sitename") ?: "Wandering Witch Wiki").put("server", wiki.base).put("scriptpath", "")
                    .put("mainpage", general?.getString("mainpage") ?: "Wandering Witch Wiki"))
                val spaces = JSONObject()
                val named = seed?.let { kept("namespaces").map { n -> n.getInt("id") to n.getString("name") } }
                    ?: listOf(0 to "", 1 to "Talk", 2 to "User", 4 to "Wandering Witch Wiki", 6 to "File", 8 to "MediaWiki", 10 to "Template", 14 to "Category", 828 to "Module")
                named.forEach { (id, name) -> spaces.put(id.toString(), JSONObject().put("id", id).put("name", name)) }
                q.put("namespaces", spaces)
                if ("usergroups" in p["siprop"].orEmpty()) q.put("usergroups", JSONArray(groupRights.map { (name, rights) ->
                    JSONObject().put("name", name).put("rights", JSONArray(rights))
                }))
            }
            "allmessages" -> q.put("allmessages", JSONArray(p["ammessages"].orEmpty().split('|').map { name ->
                messages[name]?.let { JSONObject().put("name", name).put("content", it) } ?: JSONObject().put("name", name).put("missing", true)
            }))
            "userinfo" -> q.put("userinfo", if (signedIn) JSONObject().put("id", 9).put("name", "Mopkeeper")
                .put("groups", JSONArray(listOf("*", "user", "sysop"))).put("rights", JSONArray(rights)).apply {
                    if (blockedSelf) put("blockid", 77).put("blockedby", "BroomKeeper").put("blockedbyid", 4).put("blockreason", "Edit warring")
                        .put("blockedtimestamp", ago(60)).put("blockexpiry", "2026-12-01T00:00:00Z").put("blockpartial", blockPartial)
                }
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
            "usercontribs" -> q.put(list, JSONArray(if (p["ucprop"] == "ids|timestamp") streak() else contribs()))
            "blocks" -> q.put(list, JSONArray(if (p["bkusers"] == "SpamBlaster99" || p["bkusers"] == null) listOf(
                JSONObject().put("id", 31).put("user", "SpamBlaster99").put("by", "BroomKeeper").put("timestamp", ago(600))
                    .put("expiry", "infinity").put("reason", "Spam").put("nocreate", true).put("autoblock", true),
            ) else emptyList()))
            "users" -> q.put(list, JSONArray(listOf(JSONObject().put("userid", 41).put("name", p["ususers"]).put("editcount", 213)
                .put("registration", "2024-03-02T10:11:12Z").put("groups", JSONArray(listOf("*", "user", "autoconfirmed"))))))
            "backlinks" -> q.put(list, JSONArray((1..7).map { JSONObject().put("title", "Page $it") }))
            "search" -> q.put(list, JSONArray(if (seed != null) kept("search") else listOf("Elaina", "Ashen Witch", "Template:Character").map {
                JSONObject().put("title", it).put("snippet", "…known as the <span class=\"searchmatch\">Ashen</span> Witch…").put("size", 1200)
            }))
            "prefixsearch" -> q.put(list, JSONArray(if (seed != null) kept("prefixsearch") else listOf("Fran", "Fran (anime)", "Fran (manga)").map { JSONObject().put("title", it) }))
        }
        if (p["prop"] == "imageinfo" && p["titles"] == FILE) {
            fun version(name: String, user: String, minutes: Long, comment: String, width: Int) = JSONObject().put("timestamp", ago(minutes)).put("user", user)
                .put("comment", comment).put("url", server.url("/images/$name").toString()).put("thumburl", server.url("/images/thumb-$name").toString())
                .put("width", width).put("height", width * 3 / 4).put("size", 48213).put("mime", "image/png")
            q.put("pages", JSONArray(listOf(JSONObject().put("ns", 6).put("title", FILE).put("imageinfo", JSONArray(listOf(
                version("new.png", "203.0.113.42", 15, "Better picture", 1200),
                version("old.png", "BroomKeeper", 60 * 40, "Her broom from the cover", 800),
            ))))))
        } else if (p["prop"] == "imageinfo") {
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
                        rev(latestRevId, 5101, latestUser, 30, "Added her relationships", 412),
                        rev(5101, 5050, "203.0.113.42", 190, "", 310),
                        rev(5050, 0, "BroomKeeper", 3000, "Created page", 290),
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

    private fun changes() = if (seed != null) kept("changes", "timestamp").onEach { it.put("patrolled", true).put("unpatrolled", false) } else listOfNotNull(
        if (ownEdit) rc("edit", "Robetta", "Mopkeeper", 1, "Fixed a link", 3340, 3342, id = 5200) else null,
        if (upload) rc("log", FILE, "203.0.113.42", 15, "Better picture", 0, 0, patrolled = false, id = 5300).put("logtype", "upload").put("logaction", "overwrite") else null,
        rc("edit", "Elaina", latestUser, 4, "Added her relationships", 310, 412, id = latestRevId, old = 5101),
        rc("edit", "Fran", "203.0.113.42", 11, "", 5230, 1876, anon = true, patrolled = false, tags = listOf("mobile edit", "blanking")),
        rc("new", "Saya", "BroomKeeper", 26, "Created page with her first appearance", 0, 2210, patrolled = false),
        rc("log", "User:SpamBlaster99", "BroomKeeper", 40, blockReason, 0, 0).put("logtype", "block").put("logaction", "block")
            .put("logparams", JSONObject().put("duration", "infinity").put("flags", JSONArray(listOf("nocreate")))),
        rc("edit", "Template:Character", "StardustReader", 95, "fix title param", 904, 921, minor = true),
        rc("log", "Ashen Witch (old)", "Mopkeeper", 60 * 26, "Housekeeping", 0, 0).put("logtype", "delete").put("logaction", "delete"),
        rc("edit", "Robetta", "StardustReader", 60 * 27, "Added a map", 3301, 3340),
    )

    private var counter = 9000L
    private fun rc(
        type: String, title: String, user: String, minutes: Long, comment: String, oldLen: Int, newLen: Int, anon: Boolean = false,
        patrolled: Boolean = true, minor: Boolean = false, tags: List<String> = emptyList(), id: Long = ++counter, old: Long = id - 1,
    ) = JSONObject().put("type", type).put("ns", 0).put("title", title).put("pageid", 77).put("revid", if (type == "log") 0 else id).put("logid", if (type == "log") id else 0)
        .put("old_revid", if (type == "edit") old else 0).put("rcid", id + 100000).put("user", user).put("anon", anon)
        .put("bot", false).put("new", type == "new").put("minor", minor).put("oldlen", oldLen).put("newlen", newLen).put("timestamp", ago(minutes))
        .put("comment", comment).put("tags", JSONArray(tags)).put("patrolled", patrolled).put("unpatrolled", !patrolled)

    private fun logs() = if (seed != null) kept("logs", "timestamp") else listOf(
        log("block", "block", "User:SpamBlaster99", "BroomKeeper", 40, "Spam", JSONObject().put("duration", "infinity").put("flags", JSONArray(listOf("nocreate")))),
        log("delete", "delete", "Ashen Witch (old)", "Mopkeeper", 60 * 26, "Housekeeping", JSONObject()),
        log("move", "move", "Light Novel Volume 01", "StardustReader", 60 * 30, "Match the other volume pages", JSONObject().put("target_title", "Light Novel Volume 1")),
        log("protect", "protect", "Wandering Witch Wiki", "Mopkeeper", 60 * 50, "High-traffic page", JSONObject().put("description", "[edit=sysop] (indefinite)")),
    )

    private fun log(type: String, action: String, title: String, user: String, minutes: Long, comment: String, params: JSONObject) =
        JSONObject().put("logid", ++counter).put("ns", 0).put("title", title).put("type", type).put("action", action).put("user", user)
            .put("timestamp", ago(minutes)).put("comment", comment).put("params", params)

    private fun contribs() = listOf(
        JSONObject().put("revid", 5101).put("parentid", 5050).put("title", "Elaina").put("timestamp", ago(190)).put("comment", "").put("size", 310).put("sizediff", 20).put("tags", JSONArray()),
        JSONObject().put("revid", 4990).put("parentid", 4980).put("title", "Fran").put("timestamp", ago(60 * 25)).put("comment", "Rewrote her abilities").put("size", 5230).put("sizediff", -3354).put("tags", JSONArray()),
    )

    /** Edits on each of the last twelve days, and on today when [editedToday] is set. */
    private fun streak() = buildList {
        if (editedToday) repeat(3) { add(JSONObject().put("revid", 7000 + it).put("timestamp", now.minus(it * 7L, ChronoUnit.SECONDS).toString())) }
        for (day in 1L..11L) add(JSONObject().put("revid", 6000 + day).put("timestamp", now.minus(day, ChronoUnit.DAYS).toString()))
        add(JSONObject().put("revid", 5990).put("timestamp", now.minus(14, ChronoUnit.DAYS).toString()))
    }

    /** Elaina's revisions, oldest first: the page was created, then edited twice. */
    private fun revisions() = listOf(
        JSONObject().put("revid", 5090).put("user", "BroomKeeper").put("timestamp", ago(60 * 30)).put("comment", "Created page").put("size", 250),
        JSONObject().put("revid", 5101).put("user", "203.0.113.42").put("timestamp", ago(190)).put("comment", "").put("size", 310),
        JSONObject().put("revid", latestRevId).put("user", latestUser).put("timestamp", ago(4)).put("comment", "Added her relationships").put("size", 412),
    )

    private fun compare(p: Map<String, String>): String {
        if (seed != null) {
            val saved = JSONObject(seed.getJSONObject("compare").toString())
            for (t in listOf("fromtimestamp", "totimestamp")) saved.put(t, moved(saved.getString(t)))
            return JSONObject().put("compare", saved).toString()
        }
        val revs = revisions()
        val at = revs.indexOfFirst { it.getLong("revid") == p["fromrev"]?.toLong() }
        val (from, to) = when (p["torelative"]) {
            "prev" -> revs.getOrNull(at - 1) to revs[at]
            "next" -> revs[at] to revs[at + 1]
            else -> revs[at] to revs.first { it.getLong("revid") == p["torev"]?.toLong() }
        }
        val c = JSONObject().put("totitle", "Elaina").put("body", DIFF)
        for ((prefix, rev) in listOf("from" to from, "to" to to)) {
            if (rev == null) continue
            for (key in listOf("revid", "user", "timestamp", "comment", "size")) c.put(prefix + key, rev.get(key))
        }
        return JSONObject().put("compare", c).toString()
    }

    companion object {
        /** The file that [upload] puts in recent changes. */
        const val FILE = "File:Elaina on her broom.png"

        /** A fake wiki that answers with what was saved from a real one. */
        fun saved(key: String) = FakeWiki(seed = JSONObject(FakeWiki::class.java.getResourceAsStream("/wikis/$key.json")!!.use { it.readBytes().decodeToString() }), key = key)

        val DIFF = """
            <tr><td colspan="2" class="diff-lineno">Line 8:</td><td colspan="2" class="diff-lineno">Line 8:</td></tr>
            <tr><td class="diff-marker"></td><td class="diff-context diff-side-deleted"><div>== Biography ==</div></td>
                <td class="diff-marker"></td><td class="diff-context diff-side-added"><div>== Biography ==</div></td></tr>
            <tr><td class="diff-marker" data-marker="−"></td><td class="diff-deletedline diff-side-deleted"><div>Elaina grew up in Robetta and left home to travel the <del class="diffchange diffchange-inline">country</del>.</div></td>
                <td class="diff-marker" data-marker="+"></td><td class="diff-addedline diff-side-added"><div>Elaina grew up in Robetta and left home to travel the <ins class="diffchange diffchange-inline">world on her broom</ins>.</div></td></tr>
            <tr><td colspan="2" class="diff-empty diff-side-deleted"></td><td class="diff-marker" data-marker="+"></td><td class="diff-addedline diff-side-added"><br /></td></tr>
            <tr><td colspan="2" class="diff-empty diff-side-deleted"></td><td class="diff-marker" data-marker="+"></td><td class="diff-addedline diff-side-added"><div>== Relationships ==</div></td></tr>
            <tr><td colspan="2" class="diff-empty diff-side-deleted"></td><td class="diff-marker" data-marker="+"></td><td class="diff-addedline diff-side-added"><div>* [[Fran]]</div></td></tr>
            <tr><td colspan="2" class="diff-empty diff-side-deleted"></td><td class="diff-marker" data-marker="+"></td><td class="diff-addedline diff-side-added"><div>* [[Saya]]</div></td></tr>
        """.trimIndent()
    }
}
