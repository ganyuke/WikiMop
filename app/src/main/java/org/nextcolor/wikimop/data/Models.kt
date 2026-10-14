package org.nextcolor.wikimop.data

import java.time.Instant

enum class AccountKind { FANDOM, BOT }

/** A cookie as it is kept on disk: the address it belongs to and its Set-Cookie text. */
data class StoredCookie(val url: String, val text: String)

/**
 * A signed-in identity. A Fandom account holds the cookies of a sign-in done on
 * Fandom's own page. A bot account holds a bot password, which is used to sign in
 * again on each wiki as needed.
 */
data class Account(
    val id: String,
    val kind: AccountKind,
    val userName: String,
    val botLogin: String = "",
    val botPassword: String = "",
    val cookies: List<StoredCookie> = emptyList(),
)

/** Which notifications are on for a wiki. They all need an account on it. */
data class WikiNotify(
    val changes: Boolean = false,
    val watchlist: Boolean = false,
    val streak: Boolean = false,
) {
    val any get() = changes || watchlist || streak
}

/** One wiki, addressed by its base such as https://wandering-witch.fandom.com or .../es. */
data class Wiki(
    val id: String,
    val base: String,
    val name: String,
    val accountId: String?,
    val notify: WikiNotify = WikiNotify(),
    /** Address of the wiki's own icon, once the wiki has been asked for it. */
    val icon: String? = null,
) {
    val api get() = "$base/api.php"
    val host get() = base.substringAfter("://").substringBefore('/')
    fun pageUrl(title: String) = "$base/wiki/" + java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        .replace("%2F", "/").replace("%3A", ":")
}

data class Settings(
    /** While on, nothing that changes a wiki can be started. */
    val changesLocked: Boolean = false,
    val requireSummary: Boolean = false,
    val dynamicColor: Boolean = true,
    /** How long the phone waits between two checks for notifications. */
    val checkMinutes: Int = 30,
)

data class Namespace(val id: Int, val name: String)

data class SiteInfo(
    val siteName: String,
    val base: String,
    val namespaces: List<Namespace>,
    val mainPage: String,
    val icon: String? = null,
)

data class Me(
    val name: String,
    val anon: Boolean,
    val groups: List<String>,
    val rights: Set<String>,
    val blocked: Boolean,
) {
    fun can(right: String) = right in rights
    val isAdmin get() = "sysop" in groups
}

/**
 * One row of recent changes, a watchlist, a page history, a user's contributions
 * or a log. The lists share most of their fields, so they share this class.
 */
data class Change(
    val key: String,
    val type: String,
    val title: String,
    val ns: Int = 0,
    val pageId: Long = 0,
    val revId: Long = 0,
    val oldRevId: Long = 0,
    val rcId: Long = 0,
    val user: String = "",
    val anon: Boolean = false,
    val timestamp: Instant = Instant.EPOCH,
    val comment: String = "",
    val oldLen: Int? = null,
    val newLen: Int? = null,
    val sizeDiff: Int? = null,
    val minor: Boolean = false,
    val bot: Boolean = false,
    val isNew: Boolean = false,
    /** Null when the list does not say, which is the case without the patrol right. */
    val patrolled: Boolean? = null,
    val top: Boolean = false,
    val tags: List<String> = emptyList(),
    val logType: String = "",
    val logAction: String = "",
    val logDetail: String = "",
) {
    val isLog get() = type == "log"
    val delta: Int? get() = sizeDiff ?: if (oldLen != null && newLen != null) newLen - oldLen else null
}

data class Chunk<T>(val items: List<T>, val next: String?)

data class PageText(
    val title: String,
    val exists: Boolean,
    val pageId: Long,
    val revId: Long,
    val revTimestamp: String,
    val serverTime: String,
    val text: String,
    val protection: List<Protection>,
    val watched: Boolean,
)

data class Protection(val type: String, val level: String, val expiry: String)

data class DiffSide(val revId: Long, val user: String, val timestamp: Instant?, val comment: String, val size: Int)

data class RevisionDiff(
    val title: String,
    val from: DiffSide?,
    val to: DiffSide,
    val lines: List<DiffLine>,
)

data class Rendered(val title: String, val html: String, val sections: List<Section>, val categories: List<String>, val revId: Long)

data class Section(val index: String, val level: Int, val heading: String)

data class SearchHit(val title: String, val snippet: String, val size: Int)

data class UserDetails(
    val name: String,
    val exists: Boolean,
    val anon: Boolean,
    val editCount: Int,
    val registration: Instant?,
    val groups: List<String>,
    val block: Block?,
)

data class Block(
    val id: Long,
    val user: String,
    val by: String,
    val timestamp: Instant?,
    val expiry: String,
    val reason: String,
    val flags: List<String> = emptyList(),
)

data class PageFacts(
    val title: String,
    val exists: Boolean,
    val length: Int,
    val lastRevId: Long,
    val lastUser: String,
    val lastTimestamp: Instant?,
    val protection: List<Protection>,
    val watched: Boolean,
    val linksHere: Int,
    val moreLinksHere: Boolean,
    val redirect: Boolean,
)

/** Edits made by the signed-in account on each of the last days, in UTC as Fandom counts them. */
data class DailyEdits(
    val today: Int,
    /** Days in a row with an edit, ending today or yesterday. */
    val streak: Int,
    /** True when the streak reaches the oldest day that was looked at. */
    val streakOpen: Boolean,
    /** Newest day last, one entry per day. */
    val days: List<Int>,
    val secondsLeft: Long,
)

/** What the checks for notifications remember about one wiki from one check to the next. */
data class Marks(
    /** The newest change that was shown in the app. Later ones are counted. Null until the first check. */
    val changes: Instant? = null,
    /** The count that the last notification gave, so the same count is not sent twice. */
    val changesTold: Int = 0,
    val watchlist: Instant? = null,
    val watchlistTold: Int = 0,
    /** The UTC day that was last looked at for the streak reminder. */
    val streakDay: String = "",
    /** True while a streak reminder is up, so it can be taken down once the account has edited. */
    val streakTold: Boolean = false,
)

/** An edit that is being written and has not been published. Kept on the phone only. */
data class Draft(
    val id: String,
    val wikiId: String,
    val title: String,
    val section: String? = null,
    val sectionHeading: String = "",
    val isNew: Boolean = false,
    val baseText: String = "",
    val text: String = "",
    val baseRevId: Long = 0,
    val baseTimestamp: String = "",
    val startTimestamp: String = "",
    val summary: String = "",
    val minor: Boolean = false,
    val updated: Long = 0,
) {
    val changed get() = text != baseText
}
