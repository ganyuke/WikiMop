package org.nextcolor.wikimop.data

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Which recent changes are asked for. */
data class ChangeFilter(
    val edits: Boolean = true,
    val newPages: Boolean = true,
    val logs: Boolean = true,
    val hideBots: Boolean = true,
    val hideMinor: Boolean = false,
    val hideOwn: Boolean = false,
    val unpatrolledOnly: Boolean = false,
    val anonOnly: Boolean = false,
    /** Empty means every namespace. */
    val namespaces: Set<Int> = emptySet(),
) {
    val active: Int
        get() = listOf(!edits, !newPages, !logs, !hideBots, hideMinor, hideOwn, unpatrolledOnly, anonOnly, namespaces.isNotEmpty()).count { it }
}

/** The page changed on the wiki after the text being published was loaded. */
class PageChanged(val by: String, val revId: Long) :
    Exception("Edit conflict: $by edited this page")

class ChangesLocked : Exception("Changes are locked")

class WikiApi(
    val wiki: Wiki,
    val account: Account?,
    http: OkHttpClient,
    jar: AccountJar,
    private val locked: () -> Boolean = { false },
) {
    val transport = Transport(wiki, account, http, jar)

    init {
        if (account?.kind == AccountKind.BOT) transport.relogin = { botLogin(transport, account.botLogin, account.botPassword) }
    }

    private suspend fun query(vararg params: Pair<String, String?>, post: Boolean = false): JSONObject {
        val map = LinkedHashMap<String, String>()
        map["action"] = "query"
        for ((k, v) in params) if (v != null) map[k] = v
        return transport.read(map, post)
    }

    // Site and account

    suspend fun siteInfo(): SiteInfo {
        // The favicon an admin uploaded is a file page on the wiki. Asking for it here saves a second request.
        val q = transport.read(
            mapOf(
                "action" to "query", "meta" to "siteinfo", "siprop" to "general|namespaces",
                "titles" to "File:Site-favicon.ico", "prop" to "imageinfo", "iiprop" to "url",
            ),
            signedIn = false,
        ).getJSONObject("query")
        val general = q.getJSONObject("general")
        val spaces = q.getJSONObject("namespaces")
        val list = spaces.keys().asSequence().map { spaces.getJSONObject(it) }
            .map { Namespace(it.getInt("id"), it.optString("name").ifEmpty { "(Main)" }) }
            .filter { it.id >= 0 }.sortedBy { it.id }.toList()
        return SiteInfo(
            siteName = general.optString("sitename"),
            base = general.optString("server") + general.optString("scriptpath"),
            namespaces = list,
            mainPage = general.optString("mainpage"),
            icon = q.optJSONArray("pages")?.optJSONObject(0)?.optJSONArray("imageinfo")?.optJSONObject(0)
                ?.optString("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        )
    }

    /** Who the wiki says is signed in. Never fails for being signed out, it says so in [Me.anon]. */
    suspend fun whoAmI(): Me {
        val info = transport.read(
            mapOf("action" to "query", "meta" to "userinfo", "uiprop" to "rights|groups|blockinfo"),
            signedIn = false,
        ).getJSONObject("query").getJSONObject("userinfo")
        return Me(
            name = info.optString("name"),
            anon = info.optBoolean("anon"),
            groups = info.optJSONArray("groups").strings(),
            rights = info.optJSONArray("rights").strings().toSet(),
            blocked = info.has("blockid"),
        )
    }

    // Lists of changes

    /** With [since], only changes made after it are listed. */
    suspend fun recentChanges(filter: ChangeFilter, me: Me?, next: String?, since: Instant? = null): Chunk<Change> {
        val types = buildList {
            if (filter.edits) add("edit")
            if (filter.newPages) add("new")
            if (filter.logs) add("log")
        }
        if (types.isEmpty()) return Chunk(emptyList(), null)
        val canPatrol = me?.can("patrol") == true
        val show = buildList {
            if (filter.hideBots) add("!bot")
            if (filter.hideMinor) add("!minor")
            if (filter.anonOnly) add("anon")
            if (filter.unpatrolledOnly && canPatrol) add("!patrolled")
        }
        val json = query(
            "list" to "recentchanges",
            "rcprop" to "title|ids|sizes|flags|user|comment|timestamp|tags|loginfo" + if (canPatrol) "|patrolled" else "",
            "rctype" to types.joinToString("|"),
            "rcshow" to show.joinToString("|").ifEmpty { null },
            "rcnamespace" to filter.namespaces.sorted().joinToString("|").ifEmpty { null },
            "rcexcludeuser" to if (filter.hideOwn && me != null && !me.anon) me.name else null,
            "rcend" to since?.plusSeconds(1)?.toString(),
            "rclimit" to "50",
            "rccontinue" to next,
        )
        val items = json.getJSONObject("query").optJSONArray("recentchanges").objects().map { change(it, "rc" + it.optLong("rcid")) }
        return Chunk(items, json.optJSONObject("continue")?.optString("rccontinue"))
    }

    /** With [since], only changes made after it are listed. [excludeUser] leaves out that user's own changes. */
    suspend fun watchlist(next: String?, since: Instant? = null, excludeUser: String? = null): Chunk<Change> {
        val json = query(
            "list" to "watchlist",
            "wlprop" to "ids|title|flags|user|comment|timestamp|sizes|loginfo|tags",
            "wltype" to "edit|new|log",
            "wlend" to since?.plusSeconds(1)?.toString(),
            "wlexcludeuser" to excludeUser,
            "wllimit" to "50",
            "wlcontinue" to next,
        )
        val items = json.getJSONObject("query").optJSONArray("watchlist").objects()
            .map { change(it, "wl" + it.optLong("revid") + "-" + it.optLong("logid")) }
        return Chunk(items, json.optJSONObject("continue")?.optString("wlcontinue"))
    }

    suspend fun history(title: String, next: String?): Chunk<Change> {
        val json = query(
            "prop" to "revisions",
            "titles" to title,
            "rvprop" to "ids|timestamp|user|comment|size|flags|tags",
            "rvlimit" to "50",
            "rvcontinue" to next,
        )
        val page = json.getJSONObject("query").optJSONArray("pages").objects().firstOrNull()
        val revisions = page?.optJSONArray("revisions").objects()
        val items = revisions.mapIndexed { index, rev ->
            // Each revision carries its own size, so the change in size comes from the one after it in the list.
            val older = revisions.getOrNull(index + 1)?.optInt("size")
            change(rev, "rv" + rev.optLong("revid")).copy(
                type = "edit",
                title = page!!.optString("title"),
                oldRevId = rev.optLong("parentid"),
                isNew = rev.optLong("parentid") == 0L,
                newLen = rev.optInt("size"),
                sizeDiff = when {
                    rev.optLong("parentid") == 0L -> rev.optInt("size")
                    older != null -> rev.optInt("size") - older
                    else -> null
                },
                top = next == null && index == 0,
            )
        }
        return Chunk(items, json.optJSONObject("continue")?.optString("rvcontinue"))
    }

    suspend fun contributions(user: String, next: String?): Chunk<Change> {
        val json = query(
            "list" to "usercontribs",
            "ucuser" to user,
            "ucprop" to "ids|title|timestamp|comment|size|sizediff|flags|tags",
            "uclimit" to "50",
            "uccontinue" to next,
        )
        val items = json.getJSONObject("query").optJSONArray("usercontribs").objects().map {
            change(it, "uc" + it.optLong("revid")).copy(
                type = "edit",
                oldRevId = it.optLong("parentid"),
                newLen = it.optInt("size"),
                sizeDiff = if (it.has("sizediff")) it.optInt("sizediff") else null,
            )
        }
        return Chunk(items, json.optJSONObject("continue")?.optString("uccontinue"))
    }

    /** [type] is a log name such as block, delete, move or protect. [title] and [user] narrow it further. */
    suspend fun logs(type: String?, title: String?, user: String?, next: String?): Chunk<Change> {
        val json = query(
            "list" to "logevents",
            "leprop" to "ids|title|type|user|timestamp|comment|details",
            "letype" to type,
            "letitle" to title,
            "leuser" to user,
            "lelimit" to "50",
            "lecontinue" to next,
        )
        val items = json.getJSONObject("query").optJSONArray("logevents").objects().map {
            change(it, "le" + it.optLong("logid")).copy(
                type = "log",
                logType = it.optString("type"),
                logAction = it.optString("action"),
                logDetail = logDetail(it.optString("type"), it.optJSONObject("params")),
            )
        }
        return Chunk(items, json.optJSONObject("continue")?.optString("lecontinue"))
    }

    suspend fun blocks(next: String?): Chunk<Block> {
        val json = query(
            "list" to "blocks",
            "bkprop" to "id|user|by|timestamp|expiry|reason|flags",
            "bklimit" to "50",
            "bkcontinue" to next,
        )
        val items = json.getJSONObject("query").optJSONArray("blocks").objects().map(::block)
        return Chunk(items, json.optJSONObject("continue")?.optString("bkcontinue"))
    }

    // Pages

    suspend fun pageText(title: String, section: String? = null): PageText {
        val json = query(
            "prop" to "revisions|info",
            "titles" to title,
            "rvprop" to "ids|timestamp|content",
            "rvslots" to "main",
            "rvsection" to section,
            "inprop" to "protection|watched",
            "curtimestamp" to "1",
        )
        val page = json.getJSONObject("query").getJSONArray("pages").getJSONObject(0)
        if (page.optBoolean("invalid")) throw ApiError("invalidtitle", page.optString("invalidreason").ifEmpty { "Invalid title" })
        val rev = page.optJSONArray("revisions")?.optJSONObject(0)
        return PageText(
            title = page.optString("title"),
            exists = !page.optBoolean("missing"),
            pageId = page.optLong("pageid"),
            revId = rev?.optLong("revid") ?: 0,
            revTimestamp = rev?.optString("timestamp").orEmpty(),
            serverTime = json.optString("curtimestamp"),
            text = rev?.optJSONObject("slots")?.optJSONObject("main")?.optString("content").orEmpty(),
            protection = protections(page),
            watched = page.optBoolean("watched"),
        )
    }

    suspend fun pageFacts(title: String): PageFacts {
        val json = query(
            "prop" to "info|revisions",
            "titles" to title,
            "inprop" to "protection|watched",
            "rvprop" to "ids|timestamp|user",
            "list" to "backlinks",
            "bltitle" to title,
            "bllimit" to "50",
        )
        val q = json.getJSONObject("query")
        val page = q.getJSONArray("pages").getJSONObject(0)
        val rev = page.optJSONArray("revisions")?.optJSONObject(0)
        return PageFacts(
            title = page.optString("title"),
            exists = !page.optBoolean("missing"),
            length = page.optInt("length"),
            lastRevId = page.optLong("lastrevid"),
            lastUser = rev?.optString("user").orEmpty(),
            lastTimestamp = rev?.optString("timestamp")?.instant(),
            protection = protections(page),
            watched = page.optBoolean("watched"),
            linksHere = q.optJSONArray("backlinks")?.length() ?: 0,
            moreLinksHere = json.optJSONObject("continue")?.has("blcontinue") == true,
            redirect = page.optBoolean("redirect"),
        )
    }

    suspend fun render(title: String): Rendered {
        val parse = transport.read(
            mapOf(
                "action" to "parse", "page" to title, "prop" to "text|sections|categories|revid|displaytitle",
                "disableeditsection" to "1", "disablelimitreport" to "1", "redirects" to "1",
            ),
        ).getJSONObject("parse")
        return Rendered(
            title = parse.optString("title"),
            html = parse.optString("text"),
            sections = parse.optJSONArray("sections").objects().map {
                Section(it.optString("index"), it.optString("level").toIntOrNull() ?: 2, it.optString("line").replace(Regex("<[^>]+>"), ""))
            }.filter { it.index.toIntOrNull() != null },
            categories = parse.optJSONArray("categories").objects().filter { !it.optBoolean("hidden") }.map { it.optString("category").replace('_', ' ') },
            revId = parse.optLong("revid"),
        )
    }

    /** Renders text that has not been published. Nothing is saved by this. */
    suspend fun preview(title: String, text: String): String = transport.read(
        mapOf(
            "action" to "parse", "title" to title, "text" to text, "pst" to "1", "prop" to "text",
            "contentmodel" to "wikitext", "disableeditsection" to "1", "disablelimitreport" to "1",
        ),
        post = true,
    ).getJSONObject("parse").optString("text")

    /** [from] zero compares [to] with the revision before it. */
    suspend fun diff(from: Long, to: Long): RevisionDiff {
        val params = LinkedHashMap<String, String>()
        params["action"] = "compare"
        params["prop"] = "diff|ids|title|user|comment|timestamp|size"
        if (from > 0) {
            params["fromrev"] = from.toString()
            params["torev"] = to.toString()
        } else {
            params["fromrev"] = to.toString()
            params["torelative"] = "prev"
        }
        val c = transport.read(params).getJSONObject("compare")
        // With "prev", the answer puts the older revision in "from", or leaves it out for a page's first revision.
        fun side(prefix: String) = if (!c.has("${prefix}revid")) null else DiffSide(
            c.optLong("${prefix}revid"), c.optString("${prefix}user"), c.optString("${prefix}timestamp").instant(),
            c.optString("${prefix}comment"), c.optInt("${prefix}size"),
        )
        return RevisionDiff(
            title = c.optString("totitle").ifEmpty { c.optString("fromtitle") },
            from = side("from"),
            to = side("to") ?: side("from")!!,
            lines = DiffParser.parse(c.optString("body")),
        )
    }

    /** The text of one revision, for showing a page's first revision when there is nothing to compare it with. */
    suspend fun revisionText(revId: Long): String {
        val page = query("prop" to "revisions", "revids" to revId.toString(), "rvprop" to "content", "rvslots" to "main")
            .getJSONObject("query").optJSONArray("pages").objects().firstOrNull()
        return page?.optJSONArray("revisions")?.optJSONObject(0)?.optJSONObject("slots")?.optJSONObject("main")?.optString("content").orEmpty()
    }

    suspend fun search(text: String, namespaces: Set<Int>): List<SearchHit> {
        val json = query(
            "list" to "search",
            "srsearch" to text,
            "srnamespace" to namespaces.sorted().joinToString("|").ifEmpty { "*" },
            "srlimit" to "30",
            "srprop" to "snippet|size",
        )
        return json.getJSONObject("query").optJSONArray("search").objects().map {
            SearchHit(it.optString("title"), it.optString("snippet").replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"), it.optInt("size"))
        }
    }

    /** Page names that start with [prefix], for completing links and templates while typing. */
    suspend fun titlesStarting(prefix: String, namespace: Int? = null): List<String> {
        val json = query(
            "list" to "prefixsearch",
            "pssearch" to prefix,
            "psnamespace" to namespace?.toString(),
            "pslimit" to "8",
        )
        return json.getJSONObject("query").optJSONArray("prefixsearch").objects().map { it.optString("title") }
    }

    // Users

    suspend fun user(name: String): UserDetails {
        val q = query(
            "list" to "users|blocks",
            "ususers" to name,
            "usprop" to "groups|editcount|registration",
            "bkusers" to name,
            "bkprop" to "id|user|by|timestamp|expiry|reason|flags",
        ).getJSONObject("query")
        val u = q.optJSONArray("users").objects().firstOrNull() ?: JSONObject()
        val anon = u.has("invalid") || name.matches(Regex("""[\d.]+|[0-9a-fA-F:]*:[0-9a-fA-F:]+"""))
        return UserDetails(
            name = u.optString("name").ifEmpty { name },
            exists = u.has("userid"),
            anon = anon,
            editCount = u.optInt("editcount"),
            registration = u.optString("registration").instant(),
            groups = u.optJSONArray("groups").strings().filter { it != "*" && it != "user" && it != "autoconfirmed" },
            block = q.optJSONArray("blocks").objects().firstOrNull()?.let(::block),
        )
    }

    /** Counts the account's edits per UTC day over the last [days] days. */
    suspend fun dailyEdits(user: String, now: Instant = Instant.now(), days: Int = 30): DailyEdits {
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        val oldest = today.minusDays(days - 1L).atStartOfDay().toInstant(ZoneOffset.UTC)
        val stamps = ArrayList<Instant>()
        var next: String? = null
        var complete = true
        for (round in 1..6) {
            val json = query(
                "list" to "usercontribs",
                "ucuser" to user,
                "ucprop" to "timestamp",
                "ucend" to oldest.toString(),
                "uclimit" to "500",
                "uccontinue" to next,
            )
            json.getJSONObject("query").optJSONArray("usercontribs").objects().mapNotNullTo(stamps) { it.optString("timestamp").instant() }
            next = json.optJSONObject("continue")?.optString("uccontinue")
            if (next == null) break
            if (round == 6) complete = false
        }
        return countDays(stamps, now, days, complete)
    }

    // Changes

    /**
     * Makes one confirmed change. For an edit, the page is looked at once more first,
     * and nothing is sent if someone else has edited it since the text was loaded.
     */
    suspend fun perform(write: PendingWrite, confirmation: Confirmation): WriteResult {
        check(confirmation.covers(write)) { "This change has not been confirmed." }
        check(write.wiki.id == wiki.id) { "This change is for another wiki." }
        if (locked()) throw ChangesLocked()
        if (account == null || transport.guest) throw SignedOut()

        if (write is PendingWrite.Edit && !write.isNew) {
            val now = query("prop" to "revisions", "titles" to write.title, "rvprop" to "ids|user")
                .getJSONObject("query").getJSONArray("pages").getJSONObject(0)
            val latest = now.optJSONArray("revisions")?.optJSONObject(0)
            if (latest != null && latest.optLong("revid") != write.baseRevId) {
                throw PageChanged(latest.optString("user"), latest.optLong("revid"))
            }
        }

        val tokens = query("meta" to "tokens", "type" to write.tokenType).getJSONObject("query").getJSONObject("tokens")
        val token = tokens.optString(write.tokenType + "token")
        // The reads above carry on as a guest when the sign-in has run out. A change must not.
        if (transport.guest || token.isEmpty() || token == "+\\") throw SignedOut()

        val json = transport.write(confirmation, write, write.params() + ("token" to token))
        return write.outcome(json)
    }

    companion object {
        /** Signs in with a bot password. Returns the account's user name. */
        suspend fun botLogin(transport: Transport, login: String, password: String): String {
            val token = transport.read(mapOf("action" to "query", "meta" to "tokens", "type" to "login"), signedIn = false)
                .getJSONObject("query").getJSONObject("tokens").getString("logintoken")
            val result = transport.read(
                mapOf("action" to "login", "lgname" to login, "lgpassword" to password, "lgtoken" to token),
                post = true, signedIn = false,
            ).getJSONObject("login")
            if (result.optString("result") != "Success") {
                throw ApiError("loginfailed", result.optString("reason").ifEmpty { "Incorrect bot login or password" })
            }
            return result.optString("lgusername")
        }

        fun countDays(stamps: List<Instant>, now: Instant, days: Int, complete: Boolean): DailyEdits {
            val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
            val counts = IntArray(days)
            for (stamp in stamps) {
                val ago = Duration.between(stamp.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay(), today.atStartOfDay()).toDays().toInt()
                if (ago in 0 until days) counts[days - 1 - ago]++
            }
            // The streak is still alive when today has no edit yet, so it is counted from yesterday then.
            var index = if (counts[days - 1] > 0) days - 1 else days - 2
            var streak = 0
            while (index >= 0 && counts[index] > 0) { streak++; index-- }
            val midnight = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            return DailyEdits(
                today = counts[days - 1],
                streak = streak,
                streakOpen = streak > 0 && (index < 0 || !complete),
                days = counts.toList(),
                secondsLeft = Duration.between(now, midnight).seconds,
            )
        }

        private fun change(o: JSONObject, key: String) = Change(
            key = key,
            type = o.optString("type", "edit"),
            title = o.optString("title"),
            ns = o.optInt("ns"),
            pageId = o.optLong("pageid"),
            revId = o.optLong("revid"),
            oldRevId = o.optLong("old_revid"),
            rcId = o.optLong("rcid"),
            user = o.optString("user"),
            anon = o.optBoolean("anon"),
            timestamp = o.optString("timestamp").instant() ?: Instant.EPOCH,
            comment = o.optString("comment"),
            oldLen = if (o.has("oldlen")) o.optInt("oldlen") else null,
            newLen = if (o.has("newlen")) o.optInt("newlen") else null,
            minor = o.optBoolean("minor"),
            bot = o.optBoolean("bot"),
            isNew = o.optBoolean("new") || o.optString("type") == "new",
            patrolled = if (o.has("patrolled") || o.has("unpatrolled")) o.optBoolean("patrolled") else null,
            top = o.optBoolean("top"),
            tags = o.optJSONArray("tags").strings(),
            logType = o.optString("logtype"),
            logAction = o.optString("logaction"),
            logDetail = logDetail(o.optString("logtype"), o.optJSONObject("logparams")),
        )

        private fun block(o: JSONObject) = Block(
            id = o.optLong("id"),
            user = o.optString("user"),
            by = o.optString("by"),
            timestamp = o.optString("timestamp").instant(),
            expiry = o.optString("expiry"),
            reason = o.optString("reason"),
            flags = listOf("nocreate", "autoblock", "allowusertalk", "anononly").filter { o.optBoolean(it) },
        )

        private fun protections(page: JSONObject) = page.optJSONArray("protection").objects()
            .map { Protection(it.optString("type"), it.optString("level"), it.optString("expiry")) }

        fun logDetail(type: String, params: JSONObject?): String {
            params ?: return ""
            return when (type) {
                "block" -> params.optString("duration").let { if (it.isEmpty()) "" else "Duration: " + PendingWrite.expiryName(it).replaceFirstChar { c -> c.lowercase() } }
                "move" -> params.optString("target_title").let { if (it.isEmpty()) "" else "to $it" }
                "rights" -> params.optJSONArray("oldgroups").strings().joinToString(", ").ifEmpty { "none" } + " to " +
                    params.optJSONArray("newgroups").strings().joinToString(", ").ifEmpty { "none" }
                "protect" -> params.optString("description")
                else -> ""
            }
        }
    }
}

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
fun String.instant(): Instant? = try { if (isEmpty()) null else Instant.parse(this) } catch (e: Exception) { null }

/** Works out a wiki's base address from whatever was typed or pasted, such as a page link. */
fun wikiBase(input: String): String? {
    var text = input.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    if (text.isEmpty() || text.contains(' ')) return null
    val host = text.substringBefore('/').lowercase()
    val path = text.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
    val full = if (host.contains('.')) host else "$host.fandom.com"
    if (!full.matches(Regex("""[a-z0-9.-]+\.[a-z]{2,}"""))) return null
    // Fandom keeps other languages of a wiki under a short path such as /es or /pt-br.
    val language = path.firstOrNull()?.takeIf { it.matches(Regex("""[a-z]{2,3}(-[a-z]{2,4})?""")) && it != "wiki" }
    return "https://$full" + if (language != null) "/$language" else ""
}
