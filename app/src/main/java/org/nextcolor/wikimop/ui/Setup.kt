package org.nextcolor.wikimop.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import okhttp3.Cookie
import org.nextcolor.wikimop.AppModel
import org.nextcolor.wikimop.Dest
import org.nextcolor.wikimop.Entry
import org.nextcolor.wikimop.data.Account
import org.nextcolor.wikimop.data.AccountJar
import org.nextcolor.wikimop.data.AccountKind
import org.nextcolor.wikimop.data.SiteInfo
import org.nextcolor.wikimop.data.Wiki
import org.nextcolor.wikimop.data.WikiApi
import org.nextcolor.wikimop.data.WikiNotify
import org.nextcolor.wikimop.data.wikiBase
import org.nextcolor.wikimop.notify.CheckJob
import org.nextcolor.wikimop.notify.Notifier
import org.nextcolor.wikimop.say
import java.net.URLEncoder
import java.util.UUID

@Composable
fun Welcome(model: AppModel) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie12Sided.toShape()), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CleaningServices, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(24.dp))
        Text("WikiMop", style = MaterialTheme.typography.displaySmall)
        Text(
            "Monitor and edit Fandom wikis",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = { model.go(Dest.AddWiki) }) { Text("Add a wiki") }
    }
}

class AddWikiState {
    var address by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var found by mutableStateOf<SiteInfo?>(null)
    /** An account id, or one of "fandom", "bot" and "none". */
    var choice by mutableStateOf("fandom")
    var botLogin by mutableStateOf("")
    var botPassword by mutableStateOf("")
}

@Composable
fun AddWikiScreen(model: AppModel, entry: Entry) {
    val state = entry.hold("add") { AddWikiState() }
    val context = LocalContext.current
    fun find() {
        val base = wikiBase(state.address)
        if (base == null) {
            state.error = "Invalid address"
            return
        }
        state.busy = true
        state.error = null
        entry.scope.launch {
            try {
                state.found = model.api(Wiki("", base, "", null), null).siteInfo()
            } catch (e: Exception) {
                state.error = "Wiki not found"
            } finally {
                state.busy = false
            }
        }
    }
    Frame(model, "Add a wiki") { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                state.address, { state.address = it; state.found = null }, Modifier.fillMaxWidth(),
                label = { Text("Wiki address") }, placeholder = { Text("wandering-witch.fandom.com") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { find() }),
            )
            if (state.busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            state.error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            val site = state.found
            if (site == null) {
                FilledTonalButton(onClick = ::find, enabled = !state.busy && state.address.isNotBlank(), modifier = Modifier.padding(top = 12.dp)) { Text("Find wiki") }
                return@Column
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    WikiIcon(model, site.siteName, site.icon, Modifier.padding(end = 16.dp), size = 48.dp)
                    Column {
                        Text(site.siteName, style = MaterialTheme.typography.titleLarge)
                        Text(site.base.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            AccountChoice(model, entry, state, site.base, site.siteName, site.icon, "Add wiki")
        }
    }
}

/**
 * Picks the account a wiki is used with: one the app already has, a new Fandom
 * sign-in, a new bot password, or none. Used when adding a wiki and when changing
 * the account of one that is already added.
 */
@Composable
private fun AccountChoice(model: AppModel, entry: Entry, state: AddWikiState, base: String, name: String, icon: String?, confirm: String, label: Boolean = true) {
    val context = LocalContext.current
    fun use() {
        val existing = model.saved.accounts.firstOrNull { it.id == state.choice }
        when {
            state.choice == "none" -> model.addWiki(base, name, null, icon)
            state.choice == "fandom" -> model.go(Dest.SignIn(base, name))
            else -> {
                state.busy = true
                state.error = null
                entry.scope.launch {
                    try {
                        val account = existing ?: Account(UUID.randomUUID().toString(), AccountKind.BOT, "", state.botLogin.trim(), state.botPassword.trim())
                        model.signIn(base, name, account, icon)
                    } catch (e: Exception) {
                        state.error = e.say()
                    } finally {
                        state.busy = false
                    }
                }
            }
        }
    }
    if (label) SectionLabel("Account") else Spacer(Modifier.height(12.dp))
    val saved = model.saved.accounts.isNotEmpty()
    val options = model.saved.accounts.map { it.id to (it.userName + if (it.kind == AccountKind.BOT) " · bot password" else " · Fandom account") } +
        listOf(
            "fandom" to if (saved) "Another Fandom account" else "Fandom account",
            "bot" to if (saved) "Another bot password" else "Bot password",
            "none" to "No account (read-only)",
        )
    options.forEachIndexed { index, (id, label) ->
        val shape = segment(index, options.size)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = shape, modifier = Modifier.padding(bottom = 2.dp).fillMaxWidth().clip(shape).clickable(role = Role.RadioButton) { state.choice = id }) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.choice == id, onClick = null, Modifier.padding(12.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (state.choice == "bot") {
        OutlinedTextField(
            state.botLogin, { state.botLogin = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
            label = { Text("Bot login") }, placeholder = { Text("UserName@BotName") }, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        OutlinedTextField(
            state.botPassword, { state.botPassword = it }, Modifier.fillMaxWidth().padding(top = 8.dp),
            label = { Text("Bot password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/wiki/Special:BotPasswords")))
        }) { Text("Open Special:BotPasswords") }
    }
    Button(
        onClick = ::use, modifier = Modifier.padding(vertical = 16.dp),
        enabled = !state.busy && (state.choice != "bot" || (state.botLogin.contains('@') && state.botPassword.isNotBlank())),
    ) { Text(if (state.choice == "fandom") "Sign in" else confirm) }
}

/** The account of a wiki that is already added. Choosing one opens the wiki with it. */
@Composable
fun WikiAccountScreen(model: AppModel, entry: Entry, dest: Dest.WikiAccount) {
    val wiki = model.saved.wikis.firstOrNull { it.id == dest.wikiId } ?: return
    val state = entry.hold("account") { AddWikiState().apply { choice = wiki.accountId ?: "none" } }
    Frame(model, "Account", wiki.name) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (state.busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            state.error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            AccountChoice(model, entry, state, wiki.base, wiki.name, wiki.icon, "Save", label = false)
        }
    }
}

/**
 * Fandom's own sign-in page. Once it has signed someone in, the cookies it set
 * are moved into the app's account store and removed from the web view.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInScreen(model: AppModel, dest: Dest.SignIn) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val wiki = remember { Wiki("", dest.base, dest.name, null) }
    val domain = remember { wiki.host.split('.').takeLast(2).joinToString(".") }

    fun check() {
        if (checking || done) return
        val raw = listOf(dest.base, "https://www.$domain", "https://auth.$domain").mapNotNull { CookieManager.getInstance().getCookie(it) }
        val jar = AccountJar(emptyList())
        val expires = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
        for (pair in raw.flatMap { it.split(';') }) {
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty()) continue
            try {
                jar.put(Cookie.Builder().name(name).value(value).domain(domain).path("/").secure().httpOnly().expiresAt(expires).build())
            } catch (e: Exception) {
                // A cookie the HTTP library will not carry is left out.
            }
        }
        checking = true
        scope.launch {
            try {
                val me = WikiApi(wiki, null, model.http, jar).whoAmI()
                if (!me.anon) {
                    done = true
                    CookieManager.getInstance().removeAllCookies(null)
                    model.addWiki(dest.base, dest.name, Account(UUID.randomUUID().toString(), AccountKind.FANDOM, me.name, cookies = jar.snapshot()))
                }
            } catch (e: Exception) {
                // Not signed in yet, or no connection. The page stays open either way.
            } finally {
                checking = false
            }
        }
    }

    Scaffold(topBar = { BackBar("Sign in", model::back) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (checking) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                factory = { context ->
                    CookieManager.getInstance().removeAllCookies(null)
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (!url.contains("://auth.")) check()
                            }
                        }
                        loadUrl("https://auth.$domain/signin?redirect=" + URLEncoder.encode("${dest.base}/wiki/Special:MyPage", "UTF-8"))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun SettingsScreen(model: AppModel) {
    val settings = model.saved.settings
    var removeWiki by remember { mutableStateOf<Wiki?>(null) }
    var removeAccount by remember { mutableStateOf<Account?>(null) }
    Frame(model, "Settings") { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionLabel("Changes to wikis")
            SwitchRow("Lock changes", settings.changesLocked, note = "Read-only mode") {
                on -> model.update { it.copy(settings = it.settings.copy(changesLocked = on)) }
            }
            SwitchRow("Require edit summary", settings.requireSummary) { on -> model.update { it.copy(settings = it.settings.copy(requireSummary = on)) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SectionLabel("Appearance")
                SwitchRow("Dynamic color", settings.dynamicColor) { on -> model.update { it.copy(settings = it.settings.copy(dynamicColor = on)) } }
            }
            val notifying = model.saved.wikis.filter { it.notify.any && it.accountId != null }
            ToolRow(
                Icons.Rounded.Notifications, "Notifications", segment(0, 1), Modifier.padding(top = 24.dp),
                note = when (notifying.size) {
                    0 -> "Off"
                    1 -> "On for ${notifying[0].name}"
                    else -> "On for ${notifying.size} wikis"
                },
            ) { model.go(Dest.Notifications) }
            SectionLabel("Wikis")
            val wikis = model.saved.wikis
            wikis.forEachIndexed { index, wiki ->
                Removable(
                    wiki.name, wiki.base.removePrefix("https://") + " · " + (model.account(wiki.accountId)?.userName ?: "No account"),
                    segment(index, wikis.size + 1), "Remove wiki",
                    leading = { WikiIcon(model, wiki.name, wiki.icon, Modifier.padding(end = 12.dp)) },
                    action = {
                        IconButton(onClick = { model.go(Dest.WikiAccount(wiki.id)) }) { Icon(Icons.Rounded.ManageAccounts, "Account for ${wiki.name}") }
                    },
                ) { removeWiki = wiki }
            }
            ToolRow(Icons.Rounded.Add, "Add a wiki", segment(wikis.size, wikis.size + 1)) { model.go(Dest.AddWiki) }
            val accounts = model.saved.accounts
            if (accounts.isNotEmpty()) SectionLabel("Accounts")
            accounts.forEachIndexed { index, account ->
                Removable(
                    account.userName, if (account.kind == AccountKind.BOT) "Bot password ${account.botLogin.substringAfter('@')}" else "Fandom account",
                    segment(index, accounts.size), "Remove account",
                ) { removeAccount = account }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    removeWiki?.let { wiki ->
        AlertDialog(
            onDismissRequest = { removeWiki = null },
            title = { Text("Remove ${wiki.name}?") },
            text = { Text("The account and drafts are kept.") },
            confirmButton = { TextButton(onClick = { model.removeWiki(wiki); removeWiki = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeWiki = null }) { Text("Cancel") } },
        )
    }
    removeAccount?.let { account ->
        AlertDialog(
            onDismissRequest = { removeAccount = null },
            title = { Text("Remove ${account.userName}?") },
            text = { Text("Wikis using this account are kept without an account.") },
            confirmButton = { TextButton(onClick = { model.removeAccount(account); removeAccount = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeAccount = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun NotificationsScreen(model: AppModel) {
    val context = LocalContext.current
    // What was being turned on when Android had to be asked first.
    var wanted by remember { mutableStateOf<Pair<Wiki, WikiNotify>?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val (wiki, notify) = wanted ?: return@rememberLauncherForActivityResult
        wanted = null
        if (granted) model.setNotify(wiki, notify)
        else model.say("Notifications are turned off for WikiMop", "Settings") {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    }
    fun set(wiki: Wiki, notify: WikiNotify) {
        if (!notify.any || Notifier.allowed(context)) model.setNotify(wiki, notify)
        else {
            wanted = wiki to notify
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Frame(model, "Notifications") { padding ->
        val wikis = model.saved.wikis.filter { it.accountId != null }
        if (wikis.isEmpty()) {
            Notice("No wikis with an account", Modifier.padding(padding))
            return@Frame
        }
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionLabel("Check every")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (minutes in CheckJob.INTERVALS) {
                    FilterChip(
                        model.saved.settings.checkMinutes == minutes,
                        { model.update { it.copy(settings = it.settings.copy(checkMinutes = minutes)) } },
                        { Text(if (minutes < 60) "$minutes minutes" else if (minutes == 60) "1 hour" else "${minutes / 60} hours") },
                    )
                }
            }
            Text(
                "Android can wait longer to save battery.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (wiki in wikis) {
                val on = wiki.notify
                SectionLabel(wiki.name)
                SwitchRow("New changes", on.changes, note = "Filtered like Recent changes, without your own changes") { set(wiki, on.copy(changes = it)) }
                SwitchRow("Watchlist", on.watchlist, note = "Changes to pages you watch, without your own changes") { set(wiki, on.copy(watchlist = it)) }
                SwitchRow("Streak reminder", on.streak, note = "A few hours before the day ends (UTC), if you haven't edited") { set(wiki, on.copy(streak = it)) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Removable(
    title: String, note: String, shape: androidx.compose.ui.graphics.Shape, describe: String,
    leading: @Composable () -> Unit = {}, action: @Composable () -> Unit = {}, onRemove: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = shape, modifier = Modifier.padding(bottom = 2.dp).fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action()
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, describe) }
        }
    }
}
