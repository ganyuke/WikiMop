package org.nextcolor.wikimop.notify

import okhttp3.OkHttpClient
import org.nextcolor.wikimop.data.Account
import org.nextcolor.wikimop.data.AccountJar
import org.nextcolor.wikimop.data.AccountKind
import org.nextcolor.wikimop.data.ApiError
import org.nextcolor.wikimop.data.Marks
import org.nextcolor.wikimop.data.Me
import org.nextcolor.wikimop.data.SavedStore
import org.nextcolor.wikimop.data.Wiki
import org.nextcolor.wikimop.data.WikiApi
import org.nextcolor.wikimop.ui.timeLeft
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

enum class Notice { CHANGES, WATCHLIST, STREAK, SIGNED_OUT }

/**
 * One notification to put up, or to take down when [text] is null. [tag] is the
 * wiki's id, or the account's for [Notice.SIGNED_OUT]. Tapping it opens [wikiId].
 */
data class Post(
    val notice: Notice,
    val tag: String,
    val wikiId: String,
    val title: String = "",
    val text: String? = null,
    /** The wiki's name, shown small above the title when the title is something else. */
    val wikiName: String? = null,
    /** When the notification stops being true and goes away by itself. */
    val until: Instant? = null,
    /** The newest changes told as sentences, shown when the notification is opened up. */
    val lines: List<String> = emptyList(),
)

/**
 * Looks at every wiki that has notifications on and says what to show. It only
 * reads from the wikis. What it has already told is kept in the store, so the
 * same news is not sent twice.
 */
class Checker(private val store: SavedStore, private val http: OkHttpClient, private val now: () -> Instant = Instant::now) {
    suspend fun run(): List<Post> {
        val posts = ArrayList<Post>()
        for (wiki in store.saved.wikis) {
            if (!wiki.notifying) continue
            val account = store.saved.accounts.firstOrNull { it.id == wiki.accountId }
            if (account != null && account.id in store.saved.signedOutTold) continue
            try {
                posts += if (account != null) check(wiki, account) else changes(wiki, WikiApi(wiki, null, http, AccountJar(emptyList())), null)
            } catch (e: IOException) {
                // No connection. The next check tries again.
            } catch (e: ApiError) {
                // The wiki refused or is not well. The next check tries again.
            }
        }
        return posts
    }

    private suspend fun check(wiki: Wiki, account: Account): List<Post> {
        val api = WikiApi(wiki, account, http, store.jar(account))
        var me = api.whoAmI()
        if (me.anon && account.kind == AccountKind.BOT) {
            try {
                WikiApi.botLogin(api.transport, account.botLogin, account.botPassword)
            } catch (e: ApiError) {
                // The bot password no longer works.
            }
            me = api.whoAmI()
        }
        if (me.anon || !me.name.equals(account.userName, ignoreCase = true)) {
            store.update { it.copy(signedOutTold = it.signedOutTold + account.id) }
            val text = if (account.kind == AccountKind.BOT) "Incorrect bot login or password" else "Signed out. Sign in again."
            return listOf(Post(Notice.SIGNED_OUT, account.id, wiki.id, account.userName, text))
        }

        val posts = ArrayList(changes(wiki, api, me))
        val marks = store.saved.marks[wiki.id] ?: Marks()

        if (wiki.notify.watchlist) {
            val chunk = api.watchlist(null, marks.watchlist, me.name)
            if (marks.watchlist == null) {
                val newest = chunk.items.firstOrNull()?.timestamp ?: Instant.EPOCH
                store.update { s -> s.mark(wiki.id) { it.copy(watchlist = newest, watchlistTold = 0) } }
            } else if (chunk.items.size != marks.watchlistTold) {
                val count = chunk.items.size
                store.update { s -> s.mark(wiki.id) { it.copy(watchlistTold = count) } }
                posts += Post(
                    Notice.WATCHLIST, wiki.id, wiki.id, wiki.name,
                    counted(count, chunk.next != null, "new change on your watchlist", "new changes on your watchlist"),
                    lines = chunk.items.take(SHOWN).map { it.sentence() },
                )
            }
        }

        if (wiki.notify.streak) {
            val time = now()
            val today = time.atOffset(ZoneOffset.UTC).toLocalDate()
            val midnight = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            // The account's edits are only looked at when the day is close to its end.
            if (Duration.between(time, midnight) <= REMIND_BEFORE) {
                val daily = api.dailyEdits(account.userName, time)
                if (daily.today > 0) {
                    // An edit made anywhere, also outside the app, takes the reminder down.
                    if (marks.streakTold) {
                        store.update { s -> s.mark(wiki.id) { it.copy(streakTold = false) } }
                        posts += Post(Notice.STREAK, wiki.id, wiki.id)
                    }
                } else if (daily.streak > 0 && marks.streakDay != today.toString()) {
                    // The reminder is sent once for each UTC day.
                    store.update { s -> s.mark(wiki.id) { it.copy(streakDay = today.toString(), streakTold = true) } }
                    posts += Post(
                        Notice.STREAK, wiki.id, wiki.id, "Not edited today",
                        "Streak: ${daily.streak}${if (daily.streakOpen) "+" else ""} days · Resets in ${timeLeft(daily.secondsLeft)} (UTC)",
                        wikiName = wiki.name, until = midnight,
                    )
                }
            }
        }
        return posts
    }

    /** Recent changes are public, so this also runs for a wiki without an account. Then [me] is null and nothing is left out as own changes. */
    private suspend fun changes(wiki: Wiki, api: WikiApi, me: Me?): List<Post> {
        if (!wiki.notify.changes) return emptyList()
        val marks = store.saved.marks[wiki.id] ?: Marks()
        val chunk = api.recentChanges(store.saved.filter.copy(hideOwn = true), me, null, marks.changes)
        if (marks.changes == null) {
            // The first check only notes where the list stands, so turning this on does not report old changes.
            val newest = chunk.items.firstOrNull()?.timestamp ?: Instant.EPOCH
            store.update { s -> s.mark(wiki.id) { it.copy(changes = newest, changesTold = 0) } }
        } else if (chunk.items.size != marks.changesTold) {
            val count = chunk.items.size
            store.update { s -> s.mark(wiki.id) { it.copy(changesTold = count) } }
            return listOf(
                Post(
                    Notice.CHANGES, wiki.id, wiki.id, wiki.name, counted(count, chunk.next != null, "new change", "new changes"),
                    lines = chunk.items.take(SHOWN).map { it.sentence() },
                ),
            )
        }
        return emptyList()
    }

    /** Null for a count of zero, which takes the notification down. */
    private fun counted(count: Int, more: Boolean, one: String, many: String): String? = when {
        count == 0 -> null
        more -> "$count+ $many"
        count == 1 -> "1 $one"
        else -> "$count $many"
    }

    companion object {
        val REMIND_BEFORE: Duration = Duration.ofHours(4)
        /** How many of the newest changes a notification lists. About as many as fit when it is opened up. */
        const val SHOWN = 5
    }
}
