package org.nextcolor.wikimop.data

import java.security.MessageDigest

/**
 * A change to a wiki that has been prepared and not sent. It describes itself in
 * words for the confirm sheet, and those words are built from the same values
 * that are sent, so what is shown is what is done.
 */
sealed class PendingWrite {
    abstract val wiki: Wiki
    /** What the confirm sheet is titled, such as "Delete page". */
    abstract val action: String
    /** The label on the slide control, such as "Slide to delete". */
    abstract val slide: String
    /** The page or user the change is made to. */
    abstract val target: String
    abstract val facts: List<Pair<String, String>>
    /** The user right the wiki asks for. */
    abstract val right: String
    open val tokenType: String get() = "csrf"
    /** Shown in the error color: hard to take back, or it takes something away from someone. */
    open val severe: Boolean get() = false
    internal abstract fun params(): Map<String, String>
    /** Reads the wiki's answer. Returns what to tell the person. */
    internal open fun outcome(json: org.json.JSONObject): WriteResult = WriteResult(done)
    abstract val done: String

    data class Edit(
        override val wiki: Wiki,
        val title: String,
        val text: String,
        val summary: String,
        val minor: Boolean,
        val isNew: Boolean,
        val section: String? = null,
        val sectionHeading: String = "",
        val baseRevId: Long = 0,
        val baseTimestamp: String = "",
        val startTimestamp: String = "",
        val markBot: Boolean = false,
        val added: Int = 0,
        val removed: Int = 0,
    ) : PendingWrite() {
        override val action get() = if (isNew) "Create page" else "Publish edit"
        override val slide get() = if (isNew) "Slide to create" else "Slide to publish"
        override val target get() = title
        override val right get() = if (isNew) "createpage" else "edit"
        override val done get() = if (isNew) "Page created" else "Edit published"
        override val facts
            get() = buildList {
                if (section != null) add("Section" to sectionHeading.ifEmpty { section })
                add("Lines" to "+$added −$removed")
                add("Summary" to summary.ifEmpty { "None" })
                if (minor) add("Minor edit" to "Yes")
                if (markBot) add("Bot edit" to "Yes")
            }

        override fun params() = buildMap {
            put("action", "edit")
            put("title", title)
            put("text", text)
            put("md5", md5(text))
            put("summary", summary)
            put(if (minor) "minor" else "notminor", "1")
            put("watchlist", "nochange")
            if (section != null) put("section", section)
            if (markBot) put("bot", "1")
            if (isNew) {
                // Fails when someone made the page in the meantime, so their text is not replaced.
                put("createonly", "1")
            } else {
                // Fails when the page was deleted in the meantime, so it is not made again.
                put("nocreate", "1")
                put("baserevid", baseRevId.toString())
                put("basetimestamp", baseTimestamp)
            }
            if (startTimestamp.isNotEmpty()) put("starttimestamp", startTimestamp)
        }

        override fun outcome(json: org.json.JSONObject): WriteResult {
            val edit = json.optJSONObject("edit") ?: throw ApiError("noresult", "Unknown result. Check the page history.")
            if (edit.optString("result") != "Success") {
                val why = edit.optJSONObject("abusefilter")?.optString("description")
                    ?: edit.optString("info").ifEmpty { edit.optString("code").ifEmpty { "The edit was not published." } }
                throw ApiError("editrejected", why)
            }
            if (edit.has("nochange")) return WriteResult("No changes")
            return WriteResult(done, edit.optLong("newrevid"))
        }
    }

    data class Undo(
        override val wiki: Wiki,
        val title: String,
        val revId: Long,
        val user: String,
        val summary: String,
        val markBot: Boolean = false,
    ) : PendingWrite() {
        override val action get() = "Undo edit"
        override val slide get() = "Slide to undo"
        override val target get() = title
        override val right get() = "edit"
        override val done get() = "Edit undone"
        override val facts
            get() = buildList {
                add("Revision" to revId.toString())
                add("By" to user)
                add("Summary" to summary.ifEmpty { "Default" })
                if (markBot) add("Bot edit" to "Yes")
            }

        override fun params() = buildMap {
            put("action", "edit")
            put("title", title)
            put("undo", revId.toString())
            put("nocreate", "1")
            put("watchlist", "nochange")
            if (summary.isNotEmpty()) put("summary", summary)
            if (markBot) put("bot", "1")
        }
    }

    data class Rollback(
        override val wiki: Wiki,
        val title: String,
        val user: String,
        val summary: String = "",
        val markBot: Boolean = false,
    ) : PendingWrite() {
        override val action get() = "Rollback"
        override val slide get() = "Slide to rollback"
        override val target get() = title
        override val right get() = "rollback"
        override val tokenType get() = "rollback"
        override val done get() = "Edits rolled back"
        override val facts
            get() = buildList {
                add("User" to user)
                if (summary.isNotEmpty()) add("Summary" to summary)
                if (markBot) add("Mark as bot edits" to "Yes")
            }

        override fun params() = buildMap {
            put("action", "rollback")
            put("title", title)
            put("user", user)
            put("watchlist", "nochange")
            if (summary.isNotEmpty()) put("summary", summary)
            if (markBot) put("markbot", "1")
        }
    }

    /** An edit is patrolled by its revision. A log entry, such as an upload, has none, so it is patrolled by its entry in recent changes, [rcId]. */
    data class Patrol(override val wiki: Wiki, val title: String, val revId: Long, val user: String, val rcId: Long = 0) : PendingWrite() {
        override val action get() = "Mark as patrolled"
        override val slide get() = "Slide to mark as patrolled"
        override val target get() = title
        override val right get() = "patrol"
        override val tokenType get() = "patrol"
        override val done get() = "Marked as patrolled"
        override val facts get() = listOf(if (revId > 0) "Revision" to revId.toString() else "Change" to rcId.toString(), "By" to user)
        override fun params() = if (revId > 0) mapOf("action" to "patrol", "revid" to revId.toString()) else mapOf("action" to "patrol", "rcid" to rcId.toString())
    }

    data class Delete(
        override val wiki: Wiki,
        val title: String,
        val reason: String,
        val linksHere: String = "",
    ) : PendingWrite() {
        override val action get() = "Delete page"
        override val slide get() = "Slide to delete"
        override val target get() = title
        override val right get() = "delete"
        override val severe get() = true
        override val done get() = "Page deleted"
        override val facts
            get() = buildList {
                add("Reason" to reason.ifEmpty { "None" })
                if (linksHere.isNotEmpty()) add("What links here" to linksHere)
            }

        override fun params() = mapOf("action" to "delete", "title" to title, "reason" to reason, "watchlist" to "nochange")
    }

    data class Restore(override val wiki: Wiki, val title: String, val reason: String) : PendingWrite() {
        override val action get() = "Restore page"
        override val slide get() = "Slide to restore"
        override val target get() = title
        override val right get() = "undelete"
        override val done get() = "Page restored"
        override val facts get() = listOf("Revisions" to "All", "Reason" to reason.ifEmpty { "None" })
        override fun params() = mapOf("action" to "undelete", "title" to title, "reason" to reason, "watchlist" to "nochange")
    }

    data class Protect(
        override val wiki: Wiki,
        val title: String,
        val edit: String,
        val move: String,
        val expiry: String,
        val reason: String,
    ) : PendingWrite() {
        override val action get() = "Protect page"
        override val slide get() = "Slide to protect"
        override val target get() = title
        override val right get() = "protect"
        override val done get() = "Protection changed"
        override val facts
            get() = listOf(
                "Edit" to levelName(edit),
                "Move" to levelName(move),
                "Duration" to expiryName(expiry),
                "Reason" to reason.ifEmpty { "None" },
            )

        override fun params() = mapOf(
            "action" to "protect",
            "title" to title,
            "protections" to "edit=${edit.ifEmpty { "all" }}|move=${move.ifEmpty { "all" }}",
            "expiry" to expiry,
            "reason" to reason,
            "watchlist" to "nochange",
        )
    }

    data class Move(
        override val wiki: Wiki,
        val from: String,
        val to: String,
        val reason: String,
        val leaveRedirect: Boolean,
        val moveTalk: Boolean,
    ) : PendingWrite() {
        override val action get() = "Move page"
        override val slide get() = "Slide to move"
        override val target get() = from
        override val right get() = "move"
        override val done get() = "Page moved"
        override val facts
            get() = listOf(
                "New title" to to,
                "Leave a redirect behind" to if (leaveRedirect) "Yes" else "No",
                "Move associated talk page" to if (moveTalk) "Yes" else "No",
                "Reason" to reason.ifEmpty { "None" },
            )

        override fun params() = buildMap {
            put("action", "move")
            put("from", from)
            put("to", to)
            put("reason", reason)
            put("watchlist", "nochange")
            if (!leaveRedirect) put("noredirect", "1")
            if (moveTalk) put("movetalk", "1")
        }
    }

    data class BlockUser(
        override val wiki: Wiki,
        val user: String,
        val expiry: String,
        val reason: String,
        val noCreate: Boolean = true,
        val autoBlock: Boolean = true,
        val allowTalk: Boolean = true,
        val replace: Boolean = false,
    ) : PendingWrite() {
        override val action get() = if (replace) "Change block" else "Block user"
        override val slide get() = "Slide to block"
        override val target get() = user
        override val right get() = "block"
        override val severe get() = true
        override val done get() = "$user blocked"
        override val facts
            get() = buildList {
                add("Duration" to expiryName(expiry))
                add("Reason" to reason.ifEmpty { "None" })
                add("Prevent account creation" to if (noCreate) "Yes" else "No")
                add("Edit own talk page" to if (allowTalk) "Allowed" else "Not allowed")
                // An IP address has no account behind it whose other addresses could be blocked.
                if (!isAddress(user)) add("Autoblock" to if (autoBlock) "Yes" else "No")
            }

        override fun params() = buildMap {
            put("action", "block")
            put("user", user)
            put("expiry", expiry)
            put("reason", reason)
            if (noCreate) put("nocreate", "1")
            if (autoBlock && !isAddress(user)) put("autoblock", "1")
            if (allowTalk) put("allowusertalk", "1")
            if (replace) put("reblock", "1")
        }
    }

    data class Unblock(override val wiki: Wiki, val user: String, val reason: String) : PendingWrite() {
        override val action get() = "Unblock user"
        override val slide get() = "Slide to unblock"
        override val target get() = user
        override val right get() = "block"
        override val done get() = "$user unblocked"
        override val facts get() = listOf("Reason" to reason.ifEmpty { "None" })
        override fun params() = mapOf("action" to "unblock", "user" to user, "reason" to reason)
    }

    companion object {
        fun levelName(level: String) = when (level) {
            "", "all" -> "All users"
            "autoconfirmed" -> "Autoconfirmed users"
            "sysop" -> "Administrators"
            else -> level
        }

        fun expiryName(expiry: String) = when (expiry) {
            "infinite", "infinity", "indefinite", "never" -> "Indefinite"
            else -> expiry
        }

        fun isAddress(user: String) = user.matches(Regex("""[\d.]+(/\d+)?|[0-9a-fA-F:]*:[0-9a-fA-F:]+(/\d+)?"""))

        internal fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class WriteResult(val message: String, val newRevId: Long = 0)

/**
 * Proof that the person slid the confirm control for one particular change.
 * It covers that change only, can be used once, and runs out after two minutes.
 * [bySlide] is called from the confirm sheet and nowhere else.
 */
class Confirmation private constructor(private val write: PendingWrite, private val at: Long) {
    private var used = false

    @Synchronized
    internal fun covers(candidate: PendingWrite) = candidate === write && !used && System.currentTimeMillis() - at < LIFETIME

    @Synchronized
    internal fun spend(candidate: PendingWrite): Boolean {
        if (!covers(candidate)) return false
        used = true
        return true
    }

    companion object {
        private const val LIFETIME = 120_000L
        fun bySlide(write: PendingWrite) = Confirmation(write, System.currentTimeMillis())
    }
}
