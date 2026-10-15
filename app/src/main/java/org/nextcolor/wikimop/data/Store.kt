package org.nextcolor.wikimop.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the accounts are kept. They include sign-in cookies and bot passwords. */
interface Vault {
    fun read(): String?
    fun write(text: String)
}

class MemoryVault(private var text: String? = null) : Vault {
    override fun read() = text
    override fun write(text: String) { this.text = text }
}

/** A file encrypted with a key that is held by the phone's keystore and cannot be copied off it. */
class KeystoreVault(private val file: File) : Vault {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    override fun read(): String? = try {
        val bytes = file.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV))
        String(cipher.doFinal(bytes, IV, bytes.size - IV))
    } catch (e: Exception) {
        null
    }

    override fun write(text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val temp = File(file.path + ".new")
        temp.writeBytes(cipher.iv + cipher.doFinal(text.toByteArray()))
        temp.renameTo(file)
    }

    private companion object {
        const val ALIAS = "wikimop-accounts"
        const val IV = 12
    }
}

data class Saved(
    val accounts: List<Account> = emptyList(),
    val wikis: List<Wiki> = emptyList(),
    val currentWiki: String? = null,
    val settings: Settings = Settings(),
    val filter: ChangeFilter = ChangeFilter(),
    val recentPages: Map<String, List<String>> = emptyMap(),
    /** By wiki id. */
    val marks: Map<String, Marks> = emptyMap(),
    /** Accounts that a check found signed out. They are not checked again until they are signed in. */
    val signedOutTold: Set<String> = emptySet(),
) {
    fun mark(wikiId: String, change: (Marks) -> Marks) = copy(marks = marks + (wikiId to change(marks[wikiId] ?: Marks())))
}

/**
 * The saved accounts, wikis and settings, and each account's cookies. There is one
 * for the whole app, shared by the screens and the checks for notifications.
 */
class SavedStore(private val vault: Vault) {
    var saved by mutableStateOf(SavedJson.read(vault.read()))
        private set
    private val disk = Executors.newSingleThreadExecutor()
    private val jars = HashMap<String, AccountJar>()

    @Synchronized
    fun update(change: (Saved) -> Saved) {
        saved = change(saved)
        val text = SavedJson.write(saved)
        disk.execute { vault.write(text) }
    }

    @Synchronized
    fun jar(account: Account): AccountJar = jars.getOrPut(account.id) {
        AccountJar(account.cookies) { cookies ->
            update { s -> s.copy(accounts = s.accounts.map { if (it.id == account.id) it.copy(cookies = cookies) else it }) }
        }
    }

    @Synchronized
    fun forgetJar(accountId: String) { jars.remove(accountId) }

    /** Waits until everything changed so far is on disk. */
    fun flush() { disk.submit {}.get() }
}

object SavedJson {
    fun write(s: Saved): String = JSONObject().apply {
        put("accounts", JSONArray(s.accounts.map { a ->
            JSONObject().put("id", a.id).put("kind", a.kind.name).put("userName", a.userName)
                .put("botLogin", a.botLogin).put("botPassword", a.botPassword)
                .put("cookies", JSONArray(a.cookies.map { JSONObject().put("url", it.url).put("text", it.text) }))
        }))
        put("wikis", JSONArray(s.wikis.map {
            JSONObject().put("id", it.id).put("base", it.base).put("name", it.name).put("accountId", it.accountId ?: JSONObject.NULL)
                .put("notify", JSONObject().put("changes", it.notify.changes).put("watchlist", it.notify.watchlist).put("streak", it.notify.streak))
                .put("icon", it.icon ?: JSONObject.NULL)
        }))
        put("currentWiki", s.currentWiki ?: JSONObject.NULL)
        put("settings", JSONObject().put("changesLocked", s.settings.changesLocked).put("requireSummary", s.settings.requireSummary).put("dynamicColor", s.settings.dynamicColor)
            .put("checkMinutes", s.settings.checkMinutes).put("blockTrackers", s.settings.blockTrackers).put("desktopPages", s.settings.desktopPages)
            .put("markBot", s.settings.markBot))
        val f = s.filter
        put("filter", JSONObject().put("edits", f.edits).put("newPages", f.newPages).put("logs", f.logs).put("hideBots", f.hideBots)
            .put("hideMinor", f.hideMinor).put("hideOwn", f.hideOwn).put("unpatrolledOnly", f.unpatrolledOnly).put("anonOnly", f.anonOnly)
            .put("namespaces", JSONArray(f.namespaces.toList())))
        put("recentPages", JSONObject().apply { s.recentPages.forEach { (wiki, titles) -> put(wiki, JSONArray(titles)) } })
        put("marks", JSONObject().apply {
            s.marks.forEach { (wiki, m) ->
                put(wiki, JSONObject().put("changes", m.changes?.toString() ?: JSONObject.NULL).put("changesTold", m.changesTold)
                    .put("watchlist", m.watchlist?.toString() ?: JSONObject.NULL).put("watchlistTold", m.watchlistTold).put("streakDay", m.streakDay)
                    .put("streakTold", m.streakTold))
            }
        })
        put("signedOutTold", JSONArray(s.signedOutTold.toList()))
    }.toString()

    fun read(text: String?): Saved {
        val o = try { JSONObject(text ?: return Saved()) } catch (e: Exception) { return Saved() }
        val set = o.optJSONObject("settings") ?: JSONObject()
        val f = o.optJSONObject("filter") ?: JSONObject()
        val recent = o.optJSONObject("recentPages") ?: JSONObject()
        val marks = o.optJSONObject("marks") ?: JSONObject()
        return Saved(
            accounts = o.optJSONArray("accounts").objects().map { a ->
                Account(
                    a.getString("id"), AccountKind.valueOf(a.getString("kind")), a.getString("userName"),
                    a.optString("botLogin"), a.optString("botPassword"),
                    a.optJSONArray("cookies").objects().map { StoredCookie(it.getString("url"), it.getString("text")) },
                )
            },
            wikis = o.optJSONArray("wikis").objects().map {
                val n = it.optJSONObject("notify") ?: JSONObject()
                Wiki(
                    it.getString("id"), it.getString("base"), it.getString("name"), if (it.isNull("accountId")) null else it.getString("accountId"),
                    WikiNotify(n.optBoolean("changes"), n.optBoolean("watchlist"), n.optBoolean("streak")),
                    if (it.isNull("icon")) null else it.optString("icon").ifEmpty { null },
                )
            },
            currentWiki = if (o.isNull("currentWiki")) null else o.optString("currentWiki"),
            settings = Settings(
                set.optBoolean("changesLocked"), set.optBoolean("requireSummary"), set.optBoolean("dynamicColor", true), set.optInt("checkMinutes", 30),
                set.optBoolean("blockTrackers", true), set.optBoolean("desktopPages"), set.optBoolean("markBot"),
            ),
            filter = ChangeFilter(
                f.optBoolean("edits", true), f.optBoolean("newPages", true), f.optBoolean("logs", true), f.optBoolean("hideBots", true),
                f.optBoolean("hideMinor"), f.optBoolean("hideOwn"), f.optBoolean("unpatrolledOnly"), f.optBoolean("anonOnly"),
                f.optJSONArray("namespaces").let { arr -> if (arr == null) emptySet() else (0 until arr.length()).map { arr.getInt(it) }.toSet() },
            ),
            recentPages = recent.keys().asSequence().associateWith { recent.optJSONArray(it).strings() },
            marks = marks.keys().asSequence().associateWith { id ->
                val m = marks.getJSONObject(id)
                Marks(
                    if (m.isNull("changes")) null else m.optString("changes").instant(), m.optInt("changesTold"),
                    if (m.isNull("watchlist")) null else m.optString("watchlist").instant(), m.optInt("watchlistTold"), m.optString("streakDay"),
                    m.optBoolean("streakTold"),
                )
            },
            signedOutTold = o.optJSONArray("signedOutTold").strings().toSet(),
        )
    }
}

/** Unpublished edits, one small file each, so that closing the app never loses or publishes anything. */
class DraftStore(private val dir: File) {
    init { dir.mkdirs() }

    fun all(): List<Draft> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { try { read(JSONObject(it.readText())) } catch (e: Exception) { null } }
        .sortedByDescending { it.updated }

    fun get(id: String): Draft? = all().firstOrNull { it.id == id }

    fun find(wikiId: String, title: String, section: String?) =
        all().firstOrNull { it.wikiId == wikiId && it.title == title && it.section == section }

    fun save(draft: Draft) {
        val temp = File(dir, draft.id + ".new")
        temp.writeText(write(draft).toString())
        temp.renameTo(File(dir, draft.id + ".json"))
    }

    fun delete(id: String) { File(dir, "$id.json").delete() }

    private fun write(d: Draft) = JSONObject().put("id", d.id).put("wikiId", d.wikiId).put("title", d.title)
        .put("section", d.section ?: JSONObject.NULL).put("sectionHeading", d.sectionHeading).put("isNew", d.isNew)
        .put("baseText", d.baseText).put("text", d.text).put("baseRevId", d.baseRevId).put("baseTimestamp", d.baseTimestamp)
        .put("startTimestamp", d.startTimestamp).put("summary", d.summary).put("minor", d.minor).put("updated", d.updated)

    private fun read(o: JSONObject) = Draft(
        o.getString("id"), o.getString("wikiId"), o.getString("title"), if (o.isNull("section")) null else o.getString("section"),
        o.optString("sectionHeading"), o.optBoolean("isNew"), o.optString("baseText"), o.optString("text"), o.optLong("baseRevId"),
        o.optString("baseTimestamp"), o.optString("startTimestamp"), o.optString("summary"), o.optBoolean("minor"), o.optLong("updated"),
    )
}
