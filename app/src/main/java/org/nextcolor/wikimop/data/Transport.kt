package org.nextcolor.wikimop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

val USER_AGENT = "WikiMop/${org.nextcolor.wikimop.BuildConfig.VERSION_NAME}"

/** The wiki answered with an error. [code] is MediaWiki's own short name for it. */
open class ApiError(val code: String, val info: String) : Exception(info)

/** The account is no longer signed in on this wiki. */
class SignedOut : ApiError("assertuserfailed", "Signed out. Sign in again.")

/** A change was sent but no answer came back, so it may or may not have been made. */
class UnknownOutcome(cause: IOException) : Exception("No response. Check the page history before trying again.", cause)

/** The cookies of one account. Each account has its own, so two accounts never mix. */
class AccountJar(initial: List<StoredCookie>, private val onChange: (List<StoredCookie>) -> Unit = {}) : CookieJar {
    private val cookies = ArrayList<Cookie>()

    init {
        for (stored in initial) {
            val url = stored.url.toHttpUrlOrNull() ?: continue
            Cookie.parse(url, stored.text)?.let { cookies += it }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt < now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (cookie in cookies) put(cookie)
        onChange(snapshot())
    }

    @Synchronized
    fun put(cookie: Cookie) {
        cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
        cookies += cookie
    }

    @Synchronized
    fun snapshot(): List<StoredCookie> = cookies.map { StoredCookie("https://${it.domain}/", it.toString()) }
}

/**
 * Talks to one wiki's api.php. There are two doors. [read] only lets through
 * requests that cannot change the wiki, and [write] needs a [Confirmation], which
 * exists only after the person has slid the confirm control for that exact change.
 */
class Transport(
    val wiki: Wiki,
    val account: Account?,
    http: OkHttpClient,
    private val jar: AccountJar,
) {
    private val client = http.newBuilder().cookieJar(jar).followRedirects(false).followSslRedirects(false).build()
    // The HTTP library sends a request again by itself when a connection drops. For a change that could
    // mean making it twice, so changes go through a client with that turned off.
    private val once = client.newBuilder().retryOnConnectionFailure(false).build()

    /** Signs a bot account in again. Set by [WikiApi], used when a session has run out. */
    internal var relogin: (suspend () -> Unit)? = null

    /** Set once the account turns out to be signed out. Reading then carries on without it. */
    @Volatile var guest = false
    var onSignedOut: () -> Unit = {}

    suspend fun read(params: Map<String, String>, post: Boolean = false, signedIn: Boolean = account != null): JSONObject {
        val action = params["action"]
        check(action in READ_ACTIONS) { "$action is not a read. Changes go through write()." }
        if (guest) return send(params, post, false)
        return try {
            send(params, post, signedIn)
        } catch (e: SignedOut) {
            val again = relogin
            if (again != null) {
                try {
                    again()
                    return send(params, post, signedIn)
                } catch (failed: ApiError) {
                    // The bot password no longer works either.
                }
            }
            guest = true
            onSignedOut()
            send(params, post, false)
        }
    }

    /** Sends one change, once. Nothing here retries. */
    internal suspend fun write(confirmation: Confirmation, write: PendingWrite, params: Map<String, String>): JSONObject {
        check(confirmation.spend(write)) { "This change has not been confirmed." }
        check(params["action"] !in READ_ACTIONS)
        return try {
            send(params, post = true, signedIn = true, client = once)
        } catch (e: IOException) {
            throw UnknownOutcome(e)
        }
    }

    private suspend fun send(params: Map<String, String>, post: Boolean, signedIn: Boolean, client: OkHttpClient = this.client): JSONObject =
        withContext(Dispatchers.IO) {
            val all = LinkedHashMap(params)
            all["format"] = "json"
            all["formatversion"] = "2"
            if (signedIn) {
                val name = account?.userName ?: throw SignedOut()
                all["assert"] = "user"
                all["assertuser"] = name
            }
            val request = Request.Builder().header("User-Agent", USER_AGENT)
            if (post) {
                val form = FormBody.Builder()
                all.forEach { (k, v) -> form.add(k, v) }
                request.url(wiki.api).post(form.build())
            } else {
                val url = wiki.api.toHttpUrl().newBuilder()
                all.forEach { (k, v) -> url.addQueryParameter(k, v) }
                request.url(url.build())
            }
            client.newCall(request.build()).execute().use { response ->
                if (response.isRedirect) {
                    throw ApiError("moved", "${wiki.host} has moved to ${response.header("Location")}")
                }
                val body = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(body)
                } catch (e: Exception) {
                    throw ApiError("badresponse", "Unexpected response (HTTP ${response.code})")
                }
                json.optJSONObject("error")?.let { error ->
                    val code = error.optString("code")
                    if (code == "assertuserfailed" || code == "assertnameduserfailed") throw SignedOut()
                    throw ApiError(code, error.optString("info").ifEmpty { code })
                }
                json
            }
        }

    companion object {
        /** Everything else is treated as a change to the wiki. Signing in changes nothing on the wiki. */
        val READ_ACTIONS = setOf("query", "parse", "compare", "login", "opensearch")
    }
}
