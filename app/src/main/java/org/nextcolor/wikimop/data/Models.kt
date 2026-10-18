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

/** Which notifications are on for a wiki. New changes work without an account, the others need one. */
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
    /** Without an account only new changes can be checked, so the other switches count for nothing. */
    val notifying get() = notify.changes || accountId != null && notify.any
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
    /** Requests to the ad and tracking hosts in [org.nextcolor.wikimop.data.Trackers] are refused in the app's web views. */
    val blockTrackers: Boolean = true,
    /** Pages are shown with Fandom's desktop layout instead of its mobile one. */
    val desktopPages: Boolean = false,
    /** Pages are shown with Fandom's dark theme instead of its light one. */
    val darkPages: Boolean = false,
    /** Starts the bot edit switch turned on. Only accounts with the right to mark bot edits see that switch. */
    val markBot: Boolean = false,
)

/** [subpages] is whether a slash in a title there makes a subpage. */
data class Namespace(val id: Int, val name: String, val subpages: Boolean = false)

data class SiteInfo(
    val siteName: String,
    val base: String,
    val namespaces: List<Namespace>,
    val mainPage: String,
    val icon: String? = null,
    /** The language pages are written in, and whether it reads right to left. */
    val lang: String = "en",
    val rtl: Boolean = false,
)

data class Me(
    val name: String,
    val anon: Boolean,
    val groups: List<String>,
    val rights: Set<String>,
    /** The block on the account itself. A partial one carries "partial" in its flags. */
    val block: Block? = null,
) {
    val blocked get() = block != null
    fun can(right: String) = right in rights
    val isAdmin get() = "sysop" in groups
}

/** The groups the signed-in account is in, and the rights they would give that this sign-in lacks. */
data class AccountRole(
    /** Group id and the wiki's name for a member, highest first. */
    val groups: List<Pair<String, String>>,
    /** Empty unless the sign-in was given fewer rights than its groups, as a bot password can be. */
    val missing: Set<String>,
) {
    val top get() = groups.firstOrNull()?.second
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

/** [styles] are the style modules the page needs on top of the skin's own, as in [Preview]. */
data class Rendered(val title: String, val html: String, val sections: List<Section>, val categories: List<String>, val revId: Long, val styles: List<String> = emptyList())

/** [styles] are the style modules the page needs on top of the skin's own, such as the infobox's. */
data class Preview(val html: String, val styles: List<String>)

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

/** One version of an uploaded file. [thumbUrl] is a picture of it sized for a phone, when the wiki can draw one. */
data class FileVersion(
    val timestamp: Instant?,
    val user: String,
    val comment: String,
    val url: String,
    val thumbUrl: String?,
    val width: Int,
    val height: Int,
    val size: Long,
    val mime: String,
)

/** An uploaded file and its versions, newest first. A file that was deleted or never uploaded has none. */
data class FileInfo(val title: String, val versions: List<FileVersion>) {
    /** The version uploaded at [time], which is the one an entry in the upload log is about. */
    fun at(time: Instant?): FileVersion? = versions.firstOrNull { it.timestamp == time } ?: versions.firstOrNull()
}

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
    /** When each counted edit was made, by revision, so the days can be counted again later or with edits seen since. */
    val edits: Map<Long, Instant> = emptyMap(),
    /** False when the account made more edits than were looked at. */
    val complete: Boolean = true,
) {
    /** The same edits counted at [now], so the card stays right as time passes and a new UTC day starts. */
    fun at(now: Instant): DailyEdits = WikiApi.countDays(edits, now, days.size, complete)

    /** Counts [more] edits as well, such as the account's own ones in a list of changes that was just loaded. */
    fun with(more: Map<Long, Instant>, now: Instant = Instant.now()): DailyEdits = WikiApi.countDays(edits + more, now, days.size, complete)
}

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
