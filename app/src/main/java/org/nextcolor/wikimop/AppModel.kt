package org.nextcolor.wikimop

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.nextcolor.wikimop.data.Account
import org.nextcolor.wikimop.data.AccountJar
import org.nextcolor.wikimop.data.AccountKind
import org.nextcolor.wikimop.data.AccountRole
import org.nextcolor.wikimop.data.ApiError
import org.nextcolor.wikimop.data.Change
import org.nextcolor.wikimop.data.Chunk
import org.nextcolor.wikimop.data.Confirmation
import org.nextcolor.wikimop.data.DailyEdits
import org.nextcolor.wikimop.data.Draft
import org.nextcolor.wikimop.data.DraftStore
import org.nextcolor.wikimop.data.Me
import org.nextcolor.wikimop.data.PendingWrite
import org.nextcolor.wikimop.data.Saved
import org.nextcolor.wikimop.data.SavedStore
import org.nextcolor.wikimop.data.SignedOut
import org.nextcolor.wikimop.data.SiteInfo
import org.nextcolor.wikimop.data.Transport
import org.nextcolor.wikimop.data.Vault
import org.nextcolor.wikimop.data.Wiki
import org.nextcolor.wikimop.data.WikiApi
import org.nextcolor.wikimop.data.WikiNotify
import org.nextcolor.wikimop.data.WriteResult
import org.nextcolor.wikimop.notify.Notice
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

enum class FormKind { DELETE, RESTORE, PROTECT, MOVE, BLOCK, UNBLOCK, ROLLBACK, UNDO }

sealed interface Dest {
    data object Home : Dest
    data class Diff(val title: String, val from: Long, val to: Long, val change: Change? = null) : Dest
    /** [tab] is the tab it opens on: 0 page, 1 history, 2 details. */
    data class Page(val title: String, val tab: Int = 0) : Dest
    data class Editor(val draftId: String) : Dest
    data class Review(val draftId: String) : Dest
    data class User(val name: String) : Dest
    data class Logs(val type: String? = null, val title: String? = null, val user: String? = null) : Dest
    data object NewPages : Dest
    data object Blocks : Dest
    data class Form(val kind: FormKind, val target: String, val user: String = "", val revId: Long = 0) : Dest
    data object AddWiki : Dest
    data class SignIn(val base: String, val name: String) : Dest
    data class WikiAccount(val wikiId: String) : Dest
    data object Settings : Dest
    data object Notifications : Dest
}

/** One screen on the back stack, with the state it loaded. The state goes away when the screen is left. */
class Entry(val dest: Dest) {
    val id: String = UUID.randomUUID().toString()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val held = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> hold(key: String, make: () -> T): T = held.getOrPut(key, make) as T
}

/** A list that is loaded a part at a time. */
class Feed<T>(private val scope: CoroutineScope, private val load: suspend (String?) -> Chunk<T>) {
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var next by mutableStateOf<String?>(null)
        private set
    private var round = 0

    fun refresh() = fetch(null)
    fun more() { if (!loading && next != null) fetch(next) }
    fun start() { if (!loaded && !loading) refresh() }

    private fun fetch(from: String?) {
        val mine = ++round
        loading = true
        error = null
        scope.launch {
            try {
                val chunk = load(from)
                if (mine != round) return@launch
                if (from == null) items.clear()
                items.addAll(chunk.items)
                next = chunk.next?.ifEmpty { null }
                loaded = true
            } catch (e: Exception) {
                if (mine == round) error = e.say()
            } finally {
                if (mine == round) loading = false
            }
        }
    }
}

/** Says what went wrong in words meant for the person, not for a log. */
fun Throwable.say(): String = when (this) {
    is SignedOut -> "Signed out. Sign in again."
    is ApiError -> when (code) {
        "permissiondenied", "protectedpage", "cascadeprotected" -> "Permission denied"
        "blocked", "autoblocked" -> "You are blocked on this wiki"
        "editconflict" -> "Edit conflict"
        "articleexists" -> "Page already exists"
        "missingtitle" -> "Page not found"
        "alreadyrolled" -> "Already rolled back"
        "onlyauthor" -> "Cannot rollback: only one author"
        "badtoken" -> "Session expired. Try again."
        "ratelimited" -> "You are being rate limited. Try again later."
        "readonly" -> "The wiki is read-only. Try again later."
        "alreadyblocked" -> "Already blocked"
        else -> info
    }
    is IOException -> "No connection"
    else -> message ?: toString()
}

class Session(val wiki: Wiki, val account: Account?, val api: WikiApi) {
    var site by mutableStateOf<SiteInfo?>(null)
    var me by mutableStateOf<Me?>(null)
    var signedOut by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var daily by mutableStateOf<DailyEdits?>(null)
    var role by mutableStateOf<AccountRole?>(null)

    /** False for every right without a signed-in account, so nothing that changes the wiki is offered. */
    fun can(right: String) = account != null && !signedOut && me?.can(right) == true
}

class Request(val write: PendingWrite, val onDone: (WriteResult) -> Unit) {
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

class AppModel(private val store: SavedStore, draftDir: File, val http: OkHttpClient = OkHttpClient()) {
    constructor(vault: Vault, draftDir: File, http: OkHttpClient = OkHttpClient()) : this(SavedStore(vault), draftDir, http)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val saved get() = store.saved
    val draftStore = DraftStore(draftDir)
    var drafts by mutableStateOf(draftStore.all())
        private set
    var session by mutableStateOf<Session?>(null)
        private set
    val stack = mutableStateListOf(Entry(Dest.Home))

    /** Whether the window is wide enough to show a change beside the list of changes. Set by the app from the window width. */
    var wide by mutableStateOf(false)

    /** The change shown beside the list of changes on a wide screen. It is not on the back stack. */
    var beside by mutableStateOf<Entry?>(null)
        private set

    var homeTab by mutableStateOf(0)
    /** Whether the Changes tab shows the watchlist instead of recent changes. */
    var homeWatch by mutableStateOf(false)
    /** The change waiting on the confirm sheet, if any. Nothing is sent while it only waits. */
    var request by mutableStateOf<Request?>(null)
        private set
    val snackbar = SnackbarHostState()

    /** Takes down a notification once what it was about has been seen. Set by the app, and does nothing in tests. */
    var dismiss: (Notice, String) -> Unit = { _, _ -> }

    init {
        saved.wikis.firstOrNull { it.id == saved.currentWiki }?.let(::open) ?: saved.wikis.firstOrNull()?.let(::open)
    }

    // Saved things

    fun update(change: (Saved) -> Saved) = store.update(change)

    fun account(id: String?) = saved.accounts.firstOrNull { it.id == id }

    fun jar(account: Account): AccountJar = store.jar(account)

    fun api(wiki: Wiki, account: Account?) =
        WikiApi(wiki, account, http, account?.let(::jar) ?: AccountJar(emptyList())) { saved.settings.changesLocked }

    // Wikis and accounts

    fun open(wiki: Wiki) {
        val account = account(wiki.accountId)
        val opened = Session(wiki, account, api(wiki, account))
        opened.api.transport.onSignedOut = { scope.launch { opened.signedOut = true } }
        session = opened
        if (saved.currentWiki != wiki.id) update { it.copy(currentWiki = wiki.id) }
        while (stack.size > 1) stack.removeAt(stack.lastIndex).scope.cancel()
        stack[0].scope.cancel()
        stack[0] = Entry(Dest.Home)
        closeBeside()
        scope.launch { connect(opened) }
    }

    suspend fun connect(s: Session) {
        s.error = null
        try {
            if (s.site == null) s.site = s.api.siteInfo()
            // Wikis added before icons were shown get theirs here, and a changed icon is picked up.
            s.site?.icon?.let { icon ->
                if (saved.wikis.any { it.id == s.wiki.id && it.icon != icon }) {
                    update { saved -> saved.copy(wikis = saved.wikis.map { if (it.id == s.wiki.id) it.copy(icon = icon) else it }) }
                }
            }
            var me = s.api.whoAmI()
            if (s.account != null && me.anon && s.account.kind == AccountKind.BOT) {
                WikiApi.botLogin(s.api.transport, s.account.botLogin, s.account.botPassword)
                me = s.api.whoAmI()
            }
            s.me = me
            s.signedOut = s.account != null && (me.anon || !me.name.equals(s.account.userName, ignoreCase = true))
            s.api.transport.guest = s.signedOut
            if (s.signedOut && s.account?.kind == AccountKind.BOT && s.me != null) say("Incorrect bot login or password")
            if (s.account != null && !s.signedOut) {
                // The checks for notifications leave an account alone after finding it signed out. This lets them go on.
                if (s.account.id in saved.signedOutTold) update { it.copy(signedOutTold = it.signedOutTold - s.account.id) }
                dismiss(Notice.SIGNED_OUT, s.account.id)
                refreshDaily(s)
                // Only for showing. Nothing is decided by it, the rights in [Me] are what count.
                s.role = try {
                    s.api.role(me)
                } catch (e: IOException) {
                    null
                } catch (e: ApiError) {
                    null
                }
            }
        } catch (e: Exception) {
            s.error = e.say()
        }
    }

    suspend fun refreshDaily(s: Session) {
        val name = s.account?.userName ?: return
        try { s.daily = s.api.dailyEdits(name) } catch (e: Exception) { /* The card stays as it was. */ }
        if ((s.daily?.today ?: 0) > 0) {
            if (saved.marks[s.wiki.id]?.streakTold == true) update { saved -> saved.mark(s.wiki.id) { it.copy(streakTold = false) } }
            dismiss(Notice.STREAK, s.wiki.id)
        }
    }

    fun addWiki(base: String, name: String, account: Account?, icon: String? = null) {
        // Signing in again as the same user replaces the old sign-in instead of adding a second one.
        val same = account?.let { new -> saved.accounts.firstOrNull { it.kind == new.kind && it.userName == new.userName && it.id != new.id } }
        val kept = if (account != null && same != null) account.copy(id = same.id) else account
        if (kept != null) store.forgetJar(kept.id)
        val existing = saved.wikis.firstOrNull { it.base == base }
        val wiki = Wiki(existing?.id ?: UUID.randomUUID().toString(), base, name, kept?.id, existing?.notify ?: WikiNotify(), icon ?: existing?.icon)
        update { s ->
            s.copy(
                accounts = if (kept == null) s.accounts else s.accounts.filter { it.id != kept.id } + kept,
                wikis = s.wikis.filter { it.id != wiki.id } + wiki,
            )
        }
        open(wiki)
    }

    /** Takes a wiki off the list. Its account and drafts are left as they are. */
    fun removeWiki(wiki: Wiki) {
        update { s -> s.copy(wikis = s.wikis.filter { it.id != wiki.id }, marks = s.marks - wiki.id) }
        for (notice in listOf(Notice.CHANGES, Notice.WATCHLIST, Notice.STREAK)) dismiss(notice, wiki.id)
        if (session?.wiki?.id == wiki.id) {
            val other = saved.wikis.firstOrNull()
            if (other != null) open(other) else { session = null; update { it.copy(currentWiki = null) } }
        }
    }

    /** Signs a wiki in with an account the app already has. A bot password is tried on the wiki first. */
    suspend fun signIn(base: String, name: String, account: Account, icon: String? = null) {
        if (account.kind == AccountKind.FANDOM) return addWiki(base, name, account, icon)
        val jar = AccountJar(account.cookies)
        val userName = WikiApi.botLogin(Transport(Wiki("", base, name, null), null, http, jar), account.botLogin, account.botPassword)
        addWiki(base, name, account.copy(userName = userName, cookies = jar.snapshot()), icon)
    }

    /**
     * Signs the account out on the wiki and forgets its cookies or bot password. Wikis
     * that used it are kept, without an account. The phone forgets it right away, even
     * when the wiki cannot be reached to sign out.
     */
    fun removeAccount(account: Account) {
        // A copy, so what the wiki sends back while signing out is not saved for an account that is gone.
        val jar = AccountJar(store.jar(account).snapshot())
        val wiki = saved.wikis.firstOrNull { it.accountId == account.id } ?: saved.wikis.firstOrNull()
        if (wiki != null) {
            scope.launch {
                try {
                    WikiApi.logout(Transport(wiki, account, http, jar))
                } catch (e: IOException) {
                    // No connection. The session runs out on its own.
                } catch (e: ApiError) {
                    // Already signed out, or the wiki refused.
                }
            }
        }
        store.forgetJar(account.id)
        dismiss(Notice.SIGNED_OUT, account.id)
        update { s ->
            s.copy(
                accounts = s.accounts.filter { it.id != account.id },
                signedOutTold = s.signedOutTold - account.id,
                wikis = s.wikis.map { if (it.accountId == account.id) it.copy(accountId = null) else it },
            )
        }
        session?.wiki?.let { current -> saved.wikis.firstOrNull { it.id == current.id }?.let { if (it.accountId != current.accountId) open(it) } }
    }

    // Notifications

    /** Turns a wiki's notifications on or off. One that is turned off starts over when it is turned on again. */
    fun setNotify(wiki: Wiki, notify: WikiNotify) {
        update { s ->
            s.copy(wikis = s.wikis.map { if (it.id == wiki.id) it.copy(notify = notify) else it }).mark(wiki.id) {
                it.copy(
                    changes = if (notify.changes) it.changes else null,
                    watchlist = if (notify.watchlist) it.watchlist else null,
                    streakDay = if (notify.streak) it.streakDay else "",
                    streakTold = notify.streak && it.streakTold,
                )
            }
        }
        if (!notify.changes) dismiss(Notice.CHANGES, wiki.id)
        if (!notify.watchlist) dismiss(Notice.WATCHLIST, wiki.id)
        if (!notify.streak) dismiss(Notice.STREAK, wiki.id)
    }

    /** Notes that a list of changes has been shown up to [newest], so only later changes are counted. */
    fun seen(wiki: Wiki, watchlist: Boolean, newest: Instant?) {
        val on = saved.wikis.firstOrNull { it.id == wiki.id }?.notify ?: return
        if (watchlist) {
            if (on.watchlist && newest != null) update { s -> s.mark(wiki.id) { it.copy(watchlist = newest, watchlistTold = 0) } }
            dismiss(Notice.WATCHLIST, wiki.id)
        } else {
            if (on.changes && newest != null) update { s -> s.mark(wiki.id) { it.copy(changes = newest, changesTold = 0) } }
            dismiss(Notice.CHANGES, wiki.id)
        }
    }

    /** Goes to what a tapped notification was about. */
    fun openNotice(wikiId: String, notice: Notice) {
        val wiki = saved.wikis.firstOrNull { it.id == wikiId } ?: return
        // Opening the wiki again also loads its lists again.
        open(wiki)
        homeTab = 0
        if (notice == Notice.CHANGES) homeWatch = false
        if (notice == Notice.WATCHLIST) homeWatch = true
    }

    // Moving between screens

    val top get() = stack.last()

    fun go(dest: Dest) { stack.add(Entry(dest)) }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex).scope.cancel()
        return true
    }

    fun replaceTop(dest: Dest) {
        stack.removeAt(stack.lastIndex).scope.cancel()
        stack.add(Entry(dest))
    }

    fun openBeside(dest: Dest) {
        beside?.scope?.cancel()
        beside = Entry(dest)
    }

    fun closeBeside() {
        beside?.scope?.cancel()
        beside = null
    }

    /** Opens [dest] from the screen [from]. Another diff opened from the one beside the list takes its place there. */
    fun goFrom(from: Entry, dest: Dest) {
        if (from === beside && dest is Dest.Diff) openBeside(dest) else go(dest)
    }

    /** When the window gets too narrow for two panes, the change beside the list becomes a screen of its own, as it is. */
    fun narrow() {
        val shown = beside ?: return
        beside = null
        stack.add(shown)
    }

    fun openPage(title: String, tab: Int = 0) {
        val wikiId = session?.wiki?.id ?: return
        update { s ->
            val list = listOf(title) + s.recentPages[wikiId].orEmpty().filter { it != title }
            s.copy(recentPages = s.recentPages + (wikiId to list.take(15)))
        }
        go(Dest.Page(title, tab))
    }

    fun say(message: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, action, duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    // Drafts

    fun saveDraft(draft: Draft) {
        draftStore.save(draft)
        drafts = draftStore.all()
    }

    fun discardDraft(id: String) {
        draftStore.delete(id)
        drafts = draftStore.all()
    }

    /** Opens the editor on a page or one of its sections, carrying on from a draft when there is one. */
    fun edit(title: String, section: String? = null, sectionHeading: String = "") {
        val s = session ?: return
        val draft = draftStore.find(s.wiki.id, title, section)
        if (draft != null) {
            go(Dest.Editor(draft.id))
            return
        }
        scope.launch {
            try {
                val page = s.api.pageText(title, section)
                val made = Draft(
                    id = UUID.randomUUID().toString(), wikiId = s.wiki.id, title = page.title, section = section,
                    sectionHeading = sectionHeading, isNew = !page.exists, baseText = page.text, text = page.text,
                    baseRevId = page.revId, baseTimestamp = page.revTimestamp, startTimestamp = page.serverTime,
                    updated = System.currentTimeMillis(),
                )
                saveDraft(made)
                go(Dest.Editor(made.id))
            } catch (e: Exception) {
                say(e.say())
            }
        }
    }

    // Changes to the wiki

    /**
     * Puts a change on the confirm sheet. This sends nothing. The change is made
     * only by [confirm], which the sheet calls once its control has been slid.
     */
    fun ask(write: PendingWrite, onDone: (WriteResult) -> Unit = {}) {
        val s = session ?: return
        when {
            saved.settings.changesLocked -> say("Changes are locked", "Settings") { go(Dest.Settings) }
            s.account == null -> say("Not signed in")
            s.signedOut -> say("Signed out. Sign in again.")
            !s.can(write.right) -> say("Permission denied")
            else -> request = Request(write, onDone)
        }
    }

    fun cancelRequest() { if (request?.sending != true) request = null }

    fun confirm(confirmation: Confirmation) {
        val r = request ?: return
        val s = session ?: return
        if (r.sending) return
        r.sending = true
        r.error = null
        scope.launch {
            try {
                val result = s.api.perform(r.write, confirmation)
                request = null
                r.onDone(result)
                say(result.message)
                if (r.write is PendingWrite.Edit || r.write is PendingWrite.Undo || r.write is PendingWrite.Rollback) refreshDaily(s)
            } catch (e: Exception) {
                r.error = e.say()
            } finally {
                r.sending = false
            }
        }
    }
}
