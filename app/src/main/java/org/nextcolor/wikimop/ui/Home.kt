package org.nextcolor.wikimop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.key
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRail
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nextcolor.wikimop.AppModel
import org.nextcolor.wikimop.Dest
import org.nextcolor.wikimop.Entry
import org.nextcolor.wikimop.Feed
import org.nextcolor.wikimop.Session
import org.nextcolor.wikimop.data.AccountKind
import org.nextcolor.wikimop.data.AccountRole
import org.nextcolor.wikimop.data.ApiError
import org.nextcolor.wikimop.data.Block
import org.nextcolor.wikimop.data.Change
import org.nextcolor.wikimop.data.ChangeFilter
import org.nextcolor.wikimop.data.DailyEdits
import org.nextcolor.wikimop.data.DiffKind
import org.nextcolor.wikimop.data.Draft
import org.nextcolor.wikimop.data.LocalDiff
import org.nextcolor.wikimop.data.SearchHit
import org.nextcolor.wikimop.say
import java.io.IOException

fun openChange(model: AppModel, change: Change) {
    when {
        change.isLog && change.logType in setOf("block", "rights", "newusers") -> model.go(Dest.User(change.title.substringAfter(':')))
        change.isLog -> model.openPage(change.title)
        else -> {
            val diff = Dest.Diff(change.title, change.oldRevId, change.revId, change)
            // On a wide screen, a change opened from Home is shown beside the list.
            if (model.wide && model.stack.size == 1) model.openBeside(diff) else model.go(diff)
        }
    }
}

@Composable
fun HomeScreen(model: AppModel, entry: Entry, session: Session) {
    var switcher by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    var rights by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf(false) }
    // The session keeps the wiki as it was when opened. The saved one has the icon once it is known.
    val icon = model.saved.wikis.firstOrNull { it.id == session.wiki.id }?.icon
    val tab = model.homeTab
    val locked = model.saved.settings.changesLocked

    val tabs = listOf(
        "Changes" to Icons.Rounded.Timeline,
        "Pages" to Icons.Rounded.Description,
        "Drafts" to Icons.Rounded.EditNote,
        "Tools" to Icons.Rounded.Build,
    )
    @Composable
    fun tabIcon(index: Int, icon: ImageVector) =
        BadgedBox(badge = { if (index == 2 && model.drafts.isNotEmpty()) Badge { Text(model.drafts.size.toString()) } }) { Icon(icon, null) }
    val rail = LocalConfiguration.current.screenWidthDp >= MEDIUM_WIDTH
    // On a wide screen, Changes shows the list on one side and the change opened from it on the other.
    val split = model.wide && tab == 0 && session.me != null

    // The app bar stays as collapsed as it was, to match the list that keeps its place.
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(entry.hold("bar") { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) })
    Row(Modifier.fillMaxSize()) {
        if (rail) {
            WideNavigationRail {
                tabs.forEachIndexed { index, (label, icon) ->
                    WideNavigationRailItem(tab == index, { model.homeTab = index }, { tabIcon(index, icon) }, label = { Text(label) }, railExpanded = false)
                }
            }
        }
        Scaffold(
            // Without a second pane, the screen keeps to the same width as Settings, app bar and all.
            modifier = (if (split) Modifier.width(LIST_PANE_WIDTH) else Modifier.weight(1f).wrapContentWidth().widthIn(max = MaxContentWidth))
                .nestedScroll(behavior.nestedScrollConnection),
            topBar = {
                MediumFlexibleTopAppBar(
                    title = {
                        // The name opens the list of wikis too.
                        Text(
                            session.wiki.name, Modifier.clip(MaterialTheme.shapes.medium).clickable(onClickLabel = "Switch wiki") { switcher = true },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        // The swap icon beside the wiki's own shows that it opens the list of wikis.
                        Row(
                            Modifier.padding(start = 8.dp).clip(MaterialTheme.shapes.medium).clickable(onClickLabel = "Switch wiki") { switcher = true }
                                .semantics(mergeDescendants = true) { contentDescription = "Switch wiki" }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WikiIcon(model, session.wiki.name, icon, size = 32.dp)
                            Icon(Icons.Rounded.SwapHoriz, null, Modifier.padding(start = 6.dp))
                        }
                    },
                    subtitle = {
                        val role = session.role
                        val top = role?.top
                        val block = session.me?.block?.takeIf { session.account != null && !session.signedOut }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // The name opens the accounts to switch between, as the role opens the rights. The person icon shows it can be tapped.
                            Row(
                                Modifier.weight(1f, fill = false).clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Switch account") { accounts = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    accountIcon(session.account?.takeIf { !session.signedOut }), null,
                                    Modifier.padding(end = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    if (session.account == null || session.signedOut) "No account" else accountName(session.account),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (block != null || role != null && top != null && !session.signedOut) {
                                Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(
                                    Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Show rights") { rights = true },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // A block says more about what can be done here than the role does, so it takes the role's place.
                                    if (block != null) Text(blockLabel(block), color = MaterialTheme.colorScheme.error, maxLines = 1)
                                    else if (top != null) Text(top, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                                    if (block == null && role != null && role.missing.isNotEmpty()) {
                                        Icon(
                                            Icons.Rounded.Info, "Fewer rights than usual", Modifier.padding(start = 4.dp).size(16.dp),
                                            tint = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                            }
                        }
                    },
                    actions = {
                        if (locked) {
                            IconButton(onClick = { model.go(Dest.Settings) }) {
                                Icon(Icons.Rounded.Lock, "Changes are locked", tint = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        if (tab == 0) {
                            IconButton(onClick = { filters = true }) {
                                BadgedBox(badge = { if (model.saved.filter.active > 0) Badge { Text(model.saved.filter.active.toString()) } }) {
                                    Icon(Icons.Rounded.FilterList, "Filters")
                                }
                            }
                        }
                        IconButton(onClick = { model.go(Dest.Settings) }) { Icon(Icons.Rounded.Settings, "Settings") }
                    },
                    scrollBehavior = behavior,
                )
            },
            bottomBar = {
                if (!rail) {
                    ShortNavigationBar {
                        tabs.forEachIndexed { index, (label, icon) ->
                            ShortNavigationBarItem(tab == index, { model.homeTab = index }, { tabIcon(index, icon) }, { Text(label) })
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(model.snackbar) },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                when {
                    session.error != null && session.me == null ->
                        Notice(session.error.orEmpty(), action = "Try again") { model.scope.launch { model.connect(session) } }
                    session.me == null -> Busy()
                    tab == 0 -> ChangesTab(model, entry, session)
                    tab == 1 -> PagesTab(model, entry, session)
                    tab == 2 -> DraftsTab(model)
                    else -> ToolsTab(model, session)
                }
            }
        }
        if (split) {
            VerticalDivider()
            Box(Modifier.weight(1f)) {
                val shown = model.beside
                val dest = shown?.dest
                if (shown != null && dest is Dest.Diff) key(shown.id) { DiffScreen(model, shown, session, dest) }
                else Notice("No change selected")
            }
        }
    }

    if (switcher) WikiSwitcher(model, session) { switcher = false }
    if (accounts) AccountSheet(model, session) { accounts = false }
    if (rights) RightsSheet(session, session.role, session.me?.block) { rights = false }
    if (filters) {
        val feed = entry.hold("rc") { recentFeed(model, entry, session) }
        FilterSheet(model.saved.filter, session, { f -> model.update { it.copy(filter = f) }; feed.refresh() }) { filters = false }
    }
}

/** The list of changes beside an open change. About as wide as a phone, so rows read the same as on one. */
private val LIST_PANE_WIDTH = 420.dp

private fun recentFeed(model: AppModel, entry: Entry, session: Session) = Feed(entry.scope) { next ->
    session.api.recentChanges(model.saved.filter, session.me, next).also { if (next == null) model.seen(session.wiki, false, it.items.firstOrNull()?.timestamp) }
}

@Composable
private fun ChangesTab(model: AppModel, entry: Entry, session: Session) {
    val watch = model.homeWatch
    val recent = entry.hold("rc") { recentFeed(model, entry, session) }
    val watched = entry.hold("wl") {
        Feed(entry.scope) { next ->
            session.api.watchlist(next).also { if (next == null) model.seen(session.wiki, true, it.items.firstOrNull()?.timestamp) }
        }
    }
    val signedIn = session.account != null && !session.signedOut
    val feed = if (watch && signedIn) watched else recent

    FeedList(feed, if (watch) "No changes" else "No changes") {
        if (signedIn) {
            item(key = "daily") { session.daily?.let { DailyCard(it, Modifier.padding(top = 4.dp, bottom = 12.dp)) } }
            item(key = "which") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    ToggleButton(!watch, { model.homeWatch = false }, Modifier.weight(1f).semantics { role = Role.RadioButton }, shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()) {
                        Text("Recent changes")
                    }
                    ToggleButton(watch, { model.homeWatch = true }, Modifier.weight(1f).semantics { role = Role.RadioButton }, shapes = ButtonGroupDefaults.connectedTrailingButtonShapes()) {
                        Text("Watchlist")
                    }
                }
            }
        }
        if (!signedIn) {
            item(key = "signin") {
                ToolRow(Icons.AutoMirrored.Rounded.Login, "Sign in", segment(0, 1), Modifier.padding(top = 4.dp)) {
                    // A bot password is tried again as it is. Anything else goes to Fandom's sign-in page.
                    if (session.account?.kind == AccountKind.BOT) model.scope.launch { model.connect(session) }
                    else if (session.account == null) model.go(Dest.WikiAccount(session.wiki.id))
                    else model.go(Dest.SignIn(session.wiki.base, session.wiki.name))
                }
            }
        }
        changeRows(feed.items, { openChange(model, it) }, selected = (model.beside?.dest as? Dest.Diff)?.change?.key)
    }
}

/** Whether the account has edited during the current UTC day, which is the day Fandom counts by. */
@Composable
fun DailyCard(daily: DailyEdits, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val done = daily.today > 0
    val container = if (done) scheme.primaryContainer else scheme.tertiaryContainer
    val content = if (done) scheme.onPrimaryContainer else scheme.onTertiaryContainer
    val accent = if (done) scheme.primary else scheme.tertiary
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).background(accent, if (done) MaterialShapes.Cookie9Sided.toShape() else MaterialShapes.Circle.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        daily.streak.toString() + if (daily.streakOpen) "+" else "",
                        style = MaterialTheme.typography.titleLarge, color = if (done) scheme.onPrimary else scheme.onTertiary,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (done) "Edited today" else "Not edited today", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Edits today: ${daily.today} · Streak: ${daily.streak}${if (daily.streakOpen) "+" else ""} days",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Resets in ${timeLeft(daily.secondsLeft)} (UTC)", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics { contentDescription = "Last 14 days" }) {
                    daily.days.takeLast(14).forEachIndexed { index, count ->
                        val today = index == minOf(daily.days.size, 14) - 1
                        Box(
                            Modifier.size(width = if (today) 16.dp else 10.dp, height = 10.dp)
                                .background(if (count > 0) accent else content.copy(alpha = 0.18f), CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSheet(filter: ChangeFilter, session: Session, onChange: (ChangeFilter) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall)
            @Composable
            fun group(title: String, chips: @Composable () -> Unit) {
                SectionLabel(title, Modifier.padding(start = 0.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
            }
            @Composable
            fun chip(label: String, on: Boolean, change: () -> ChangeFilter) =
                CheckChip(on, { onChange(change()) }, label)

            group("Type") {
                chip("Edits", filter.edits) { filter.copy(edits = !filter.edits) }
                chip("New pages", filter.newPages) { filter.copy(newPages = !filter.newPages) }
                chip("Log entries", filter.logs) { filter.copy(logs = !filter.logs) }
            }
            group("Filters") {
                chip("Hide bots", filter.hideBots) { filter.copy(hideBots = !filter.hideBots) }
                chip("Hide minor edits", filter.hideMinor) { filter.copy(hideMinor = !filter.hideMinor) }
                if (session.account != null) chip("Hide my edits", filter.hideOwn) { filter.copy(hideOwn = !filter.hideOwn) }
                if (session.can("patrol")) chip("Unpatrolled only", filter.unpatrolledOnly) { filter.copy(unpatrolledOnly = !filter.unpatrolledOnly) }
                chip("Unregistered users only", filter.anonOnly) { filter.copy(anonOnly = !filter.anonOnly) }
            }
            group("Namespaces") {
                chip("All", filter.namespaces.isEmpty()) { filter.copy(namespaces = emptySet()) }
                session.site?.namespaces.orEmpty().forEach { ns ->
                    chip(ns.name, ns.id in filter.namespaces) {
                        filter.copy(namespaces = if (ns.id in filter.namespaces) filter.namespaces - ns.id else filter.namespaces + ns.id)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The saved accounts, to switch this wiki to another one without going through Settings. */
@Composable
private fun AccountSheet(model: AppModel, session: Session, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = model.saved.accounts
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Accounts", Modifier.padding(start = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
            if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            error?.let { Text(it, Modifier.padding(start = 8.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            list.forEachIndexed { index, account ->
                val current = account.id == session.wiki.accountId
                val shape = segment(index, list.size + 1)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = shape,
                    modifier = Modifier.padding(bottom = 2.dp).fillMaxWidth().clip(shape).clickable(enabled = !busy && !current, role = Role.RadioButton) {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                // The same as choosing it in Settings. The wiki opens again as this account.
                                model.signIn(session.wiki.base, session.wiki.name, account, session.wiki.icon)
                                onClose()
                            } catch (e: Exception) {
                                error = e.say()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Row(Modifier.padding(end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(current, onClick = null, Modifier.padding(12.dp))
                        Icon(accountIcon(account), null, Modifier.padding(end = 16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column {
                            Text(account.userName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (account.kind == AccountKind.BOT) "Bot password ${account.botLogin.substringAfter('@')}" else "Fandom account",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            ToolRow(Icons.Rounded.PersonAdd, "Add an account", segment(list.size, list.size + 1)) {
                onClose()
                model.go(Dest.WikiAccount(session.wiki.id))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun blockLabel(block: Block) = if ("partial" in block.flags) "Partially blocked" else "Blocked"

/** The account's block, groups and rights on this wiki, described in the wiki's own words. */
@Composable
private fun RightsSheet(session: Session, role: AccountRole?, block: Block?, onClose: () -> Unit) {
    val have = session.me?.rights.orEmpty().sorted()
    val missing = role?.missing.orEmpty().sorted()
    var described by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        // Without the descriptions the rights are still listed, by their short names.
        described = try {
            session.api.messages((have + missing).map { "right-$it" })
        } catch (e: IOException) {
            emptyMap()
        } catch (e: ApiError) {
            emptyMap()
        }
    }
    @Composable
    fun RightRow(right: String) = Column(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
        val description = described["right-$right"]
        Text(description ?: right, style = MaterialTheme.typography.bodyMedium)
        if (description != null) {
            Text(right, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Text("Rights", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.headlineSmall) }
            if (block != null) item {
                val scheme = MaterialTheme.colorScheme
                Surface(color = scheme.errorContainer, contentColor = scheme.onErrorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(blockLabel(block), style = MaterialTheme.typography.titleMedium)
                        Fact("Applies to", if ("partial" in block.flags) "Some pages or namespaces" else "Whole wiki")
                        Fact("Expires", expiryText(block.expiry))
                        Fact("By", block.by)
                        if (block.reason.isNotBlank()) Fact("Reason", block.reason)
                    }
                }
            }
            if (role != null) item {
                SectionLabel("Groups")
                Text(role.groups.joinToString(", ") { it.second }, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (missing.isNotEmpty()) {
                item {
                    Row(Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null, Modifier.padding(end = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Text("Missing from this sign-in", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                items(missing) { RightRow(it) }
            }
            item { SectionLabel("Your rights") }
            items(have) { RightRow(it) }
        }
    }
}

@Composable
private fun WikiSwitcher(model: AppModel, session: Session, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text("Wikis", Modifier.padding(start = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
            val wikis = model.saved.wikis
            wikis.forEachIndexed { index, wiki ->
                val current = wiki.id == session.wiki.id
                Surface(
                    color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = segment(index, wikis.size),
                    modifier = Modifier.padding(bottom = 2.dp).clip(segment(index, wikis.size))
                        .selectable(current, role = Role.RadioButton) { onClose(); if (!current) model.open(wiki) },
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        WikiIcon(model, wiki.name, wiki.icon, Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(wiki.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                wiki.base.removePrefix("https://") + " · " + (model.account(wiki.accountId)?.userName ?: "No account"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onClose(); model.go(Dest.WikiAccount(wiki.id)) }) { Icon(Icons.Rounded.ManageAccounts, "Account for ${wiki.name}") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            ToolRow(Icons.Rounded.Add, "Add a wiki", segment(0, 1)) { onClose(); model.go(Dest.AddWiki) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ToolRow(icon: ImageVector, label: String, shape: Shape, modifier: Modifier = Modifier, note: String? = null, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = shape,
        modifier = modifier.padding(bottom = 2.dp).fillMaxWidth().clip(shape).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Pages

class SearchState {
    var query by mutableStateOf("")
    var namespace by mutableStateOf<Int?>(null)
    var hits by mutableStateOf<List<SearchHit>?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun PagesTab(model: AppModel, entry: Entry, session: Session) {
    val state = entry.hold("search") { SearchState() }
    fun search() {
        val text = state.query.trim()
        if (text.isEmpty()) return
        state.busy = true
        state.error = null
        entry.scope.launch {
            try {
                state.hits = session.api.search(text, state.namespace?.let { setOf(it) } ?: emptySet())
            } catch (e: Exception) {
                state.error = e.say()
            } finally {
                state.busy = false
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { state.query = it; if (it.isBlank()) state.hits = null },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { state.query = ""; state.hits = null }) { Icon(Icons.Rounded.Close, "Clear") }
            },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val known = session.site?.namespaces.orEmpty().associateBy { it.id }
            CheckChip(state.namespace == null, { state.namespace = null; search() }, "All")
            for (id in listOf(0, 10, 14, 6, 828, 8, 2, 4)) {
                val name = if (id == 0) "Articles" else known[id]?.name ?: continue
                CheckChip(state.namespace == id, { state.namespace = id; search() }, name)
            }
        }
        val typed = state.query.trim()
        val recent = model.saved.recentPages[session.wiki.id].orEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            if (typed.isNotEmpty()) {
                item(key = "goto") {
                    ToolRow(Icons.AutoMirrored.Rounded.ArrowForward, "Go to “$typed”", segment(0, 1), Modifier.padding(top = 8.dp)) { model.openPage(typed) }
                }
            }
            val hits = state.hits
            when {
                state.busy -> item { Busy(Modifier.fillParentMaxHeight(0.4f)) }
                state.error != null -> item { Notice(state.error.orEmpty(), Modifier.fillParentMaxHeight(0.4f)) }
                hits != null -> {
                    item { SectionLabel(if (hits.isEmpty()) "No results" else "Results") }
                    itemsIndexed(hits, key = { _, hit -> hit.title }) { index, hit ->
                        ToolRow(Icons.Rounded.Description, hit.title, segment(index, hits.size), note = hit.snippet.take(140).ifBlank { null }) { model.openPage(hit.title) }
                    }
                }
                typed.isEmpty() -> {
                    session.site?.mainPage?.takeIf { it.isNotEmpty() }?.let { main ->
                        item { SectionLabel("Main page") }
                        item { ToolRow(Icons.Rounded.Description, main, segment(0, 1)) { model.openPage(main) } }
                    }
                    if (recent.isNotEmpty()) {
                        item { SectionLabel("Recent") }
                        itemsIndexed(recent, key = { _, title -> "recent-$title" }) { index, title ->
                            ToolRow(Icons.Rounded.History, title, segment(index, recent.size)) { model.openPage(title) }
                        }
                    }
                }
            }
        }
    }
}

// Drafts

fun draftLines(draft: Draft): Pair<Int, Int> {
    val lines = LocalDiff.lines(draft.baseText, draft.text)
    return lines.count { it.kind == DiffKind.ADDED } to lines.count { it.kind == DiffKind.REMOVED }
}

@Composable
private fun DraftsTab(model: AppModel) {
    var discard by remember { mutableStateOf<Draft?>(null) }
    val drafts = model.drafts
    if (drafts.isEmpty()) {
        Notice("No drafts")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { SectionLabel("Unpublished edits") }
        itemsIndexed(drafts, key = { _, d -> d.id }) { index, draft ->
            val wiki = model.saved.wikis.firstOrNull { it.id == draft.wikiId }
            val shape = segment(index, drafts.size)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow, shape = shape,
                modifier = Modifier.fillMaxWidth().clip(shape).clickable(enabled = wiki != null) {
                    if (wiki != null && model.session?.wiki?.id != wiki.id) model.open(wiki)
                    model.go(Dest.Editor(draft.id))
                },
            ) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(draft.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(wiki?.name ?: "Removed wiki", draft.sectionHeading.ifEmpty { null }, if (draft.isNew) "New page" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val (added, removed) = remember(draft.text) { draftLines(draft) }
                        val colors = LocalDiffColors.current
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!draft.changed) Text("No changes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            else {
                                Text("+$added", style = MaterialTheme.typography.bodySmall, color = colors.plus)
                                Text("−$removed", style = MaterialTheme.typography.bodySmall, color = colors.minus)
                            }
                            Text(java.time.Instant.ofEpochMilli(draft.updated).let { it.dayName() + " " + it.clock() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    IconButton(onClick = { discard = draft }) { Icon(Icons.Rounded.Delete, "Discard draft") }
                }
            }
        }
    }
    discard?.let { draft ->
        AlertDialog(
            onDismissRequest = { discard = null },
            title = { Text("Discard draft?") },
            text = { Text("Unpublished changes to ${draft.title} will be lost.") },
            confirmButton = { TextButton(onClick = { model.discardDraft(draft.id); discard = null }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discard = null }) { Text("Keep") } },
        )
    }
}

// Tools

@Composable
private fun ToolsTab(model: AppModel, session: Session) {
    var user by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        SectionLabel("User")
        OutlinedTextField(
            value = user, onValueChange = { user = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Username or IP address") }, singleLine = true, shape = CircleShape,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (user.isNotBlank()) model.go(Dest.User(user.trim())) }),
        )
        SectionLabel("Lists")
        ToolRow(Icons.AutoMirrored.Rounded.NoteAdd, "New pages", segment(0, 2)) { model.go(Dest.NewPages) }
        ToolRow(Icons.Rounded.PersonOff, "Blocked users", segment(1, 2)) { model.go(Dest.Blocks) }
        SectionLabel("Logs")
        val logs = listOf(
            Triple("All logs", Icons.AutoMirrored.Rounded.ListAlt, null),
            Triple("Block log", Icons.Rounded.Block, "block"),
            Triple("Deletion log", Icons.Rounded.Delete, "delete"),
            Triple("Move log", Icons.AutoMirrored.Rounded.DriveFileMove, "move"),
            Triple("Protection log", Icons.Rounded.Lock, "protect"),
            Triple("User rights log", Icons.Rounded.AdminPanelSettings, "rights"),
            Triple("Upload log", Icons.Rounded.Upload, "upload"),
        )
        logs.forEachIndexed { index, (label, icon, type) ->
            ToolRow(icon, label, segment(index, logs.size)) { model.go(Dest.Logs(type)) }
        }
    }
}
