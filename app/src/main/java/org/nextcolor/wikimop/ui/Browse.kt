package org.nextcolor.wikimop.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Segment
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.nextcolor.wikimop.AppModel
import org.nextcolor.wikimop.Dest
import org.nextcolor.wikimop.Entry
import org.nextcolor.wikimop.Feed
import org.nextcolor.wikimop.FormKind
import org.nextcolor.wikimop.Session
import org.nextcolor.wikimop.data.Block
import org.nextcolor.wikimop.data.ChangeFilter
import org.nextcolor.wikimop.data.DiffKind
import org.nextcolor.wikimop.data.DiffLine
import org.nextcolor.wikimop.data.DiffSide
import org.nextcolor.wikimop.data.PageFacts
import org.nextcolor.wikimop.data.PendingWrite
import org.nextcolor.wikimop.data.Rendered
import org.nextcolor.wikimop.data.RevisionDiff
import org.nextcolor.wikimop.data.Span
import org.nextcolor.wikimop.data.UserDetails
import org.nextcolor.wikimop.say

// Diff

@Composable
fun DiffLines(lines: List<DiffLine>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) { lines.forEach { DiffLineRow(it) } }
}

@Composable
fun DiffLineRow(line: DiffLine) {
    val colors = LocalDiffColors.current
    val scheme = MaterialTheme.colorScheme
    if (line.kind == DiffKind.HEADER) {
        Text(
            line.text, Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = scheme.primary,
        )
        return
    }
    val (background, mark, textColor, sign) = when (line.kind) {
        DiffKind.ADDED -> Quad(colors.added, colors.addedMark, colors.onAdded, "+")
        DiffKind.REMOVED, DiffKind.MOVED -> Quad(colors.removed, colors.removedMark, colors.onRemoved, "−")
        else -> Quad(Color.Transparent, Color.Transparent, scheme.onSurfaceVariant, " ")
    }
    Row(Modifier.fillMaxWidth().background(background).padding(vertical = 2.dp)) {
        Text(
            (line.newNo ?: line.oldNo)?.toString().orEmpty(), Modifier.width(44.dp).padding(end = 6.dp),
            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = scheme.outline, textAlign = TextAlign.End,
        )
        Text(sign, Modifier.width(14.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = textColor)
        Text(
            buildAnnotatedString {
                for (span in line.spans) {
                    if (span.marked) withStyle(SpanStyle(background = mark)) { append(span.text) } else append(span.text)
                }
            },
            Modifier.weight(1f).padding(end = 12.dp),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = textColor,
        )
    }
}

private data class Quad(val a: Color, val b: Color, val c: Color, val d: String)

class DiffState {
    var diff by mutableStateOf<RevisionDiff?>(null)
    var facts by mutableStateOf<PageFacts?>(null)
    var error by mutableStateOf<String?>(null)
    var patrolled by mutableStateOf(false)
}

@Composable
fun DiffScreen(model: AppModel, entry: Entry, session: Session, dest: Dest.Diff) {
    val state = entry.hold("diff") { DiffState() }
    fun load() {
        state.error = null
        entry.scope.launch {
            try {
                val change = dest.change
                val diff = if (change != null && change.isNew && dest.from == 0L) {
                    // A new page has nothing before it, so its whole text is shown as added.
                    val text = session.api.revisionText(dest.to)
                    RevisionDiff(
                        dest.title, null, DiffSide(dest.to, change.user, change.timestamp, change.comment, change.newLen ?: text.length),
                        text.split('\n').mapIndexed { i, line -> DiffLine(DiffKind.ADDED, listOf(Span(line, false)), newNo = i + 1) },
                    )
                } else session.api.diff(dest.from, dest.to)
                // Both are shown at once, so the link to the newer edit does not appear after the rest.
                state.facts = session.api.pageFacts(dest.title)
                state.diff = diff
            } catch (e: Exception) {
                state.error = e.say()
            }
        }
    }
    LaunchedEffect(Unit) { if (state.diff == null) load() }
    val diff = state.diff
    val latest = diff != null && state.facts?.lastRevId == diff.to.revId
    var menu by remember { mutableStateOf(false) }
    // Beside the list of changes, the diff closes instead of going back, and the list's screen shows the messages.
    val beside = entry === model.beside
    // Older and newer edits of the same page, as on the wiki's own diff page. Back returns the same way.
    val older: (() -> Unit)? = diff?.from?.let { before -> { model.goFrom(entry, Dest.Diff(dest.title, 0, before.revId)) } }
    val newer: (() -> Unit)? = if (diff == null || state.facts == null || latest) null else ({
        entry.scope.launch {
            try {
                model.goFrom(entry, Dest.Diff(dest.title, diff.to.revId, session.api.nextRevision(diff.to.revId)))
            } catch (e: Exception) {
                model.say(e.say())
            }
        }
    })

    Scaffold(
        topBar = { BackBar(dest.title, { if (beside) model.closeBeside() else model.back() }, subtitle = diff?.let { "Revision ${it.to.revId}" }, close = beside) },
        snackbarHost = { if (!beside) SnackbarHost(model.snackbar) },
        floatingActionButton = {
            if (diff == null) return@Scaffold
            // Listed top to bottom. The ones used most are last, closest to the thumb.
            val actions = buildList<Triple<String, ImageVector, () -> Unit>> {
                if (session.can("delete")) add(Triple("Delete page", Icons.Rounded.Delete) { model.go(Dest.Form(FormKind.DELETE, dest.title)) })
                // The user's name stays out of the labels, since it can be long. It is in the card above, which opens the user.
                if (session.can("block")) add(Triple("Block user", Icons.Rounded.Block) { model.go(Dest.Form(FormKind.BLOCK, diff.to.user)) })
                add(Triple("View history", Icons.Rounded.History) { model.openPage(dest.title, tab = 1) })
                add(Triple("Open page", Icons.Rounded.Description) { model.openPage(dest.title) })
                if (session.can("edit")) add(Triple("Edit page", Icons.Rounded.Edit) { model.edit(dest.title) })
                if (diff.from != null && session.can("edit")) {
                    add(Triple("Undo", Icons.AutoMirrored.Rounded.Undo) { model.go(Dest.Form(FormKind.UNDO, dest.title, diff.to.user, diff.to.revId)) })
                }
                if (latest && diff.from != null && session.can("rollback")) {
                    add(Triple("Rollback", Icons.Rounded.SettingsBackupRestore) { model.go(Dest.Form(FormKind.ROLLBACK, dest.title, diff.to.user)) })
                }
                if (dest.change?.patrolled == false && !state.patrolled && session.can("patrol")) {
                    add(Triple("Patrol", Icons.Rounded.Check) {
                        model.ask(PendingWrite.Patrol(session.wiki, dest.title, diff.to.revId, diff.to.user)) { state.patrolled = true }
                    })
                }
            }
            ActionsMenu(actions, menu) { menu = it }
        },
    ) { padding ->
        when {
            state.error != null -> Notice(state.error.orEmpty(), Modifier.padding(padding), "Try again", ::load)
            diff == null -> Busy(Modifier.padding(padding))
            // Pulling sideways goes to the older or newer edit, and pulling down from the top opens the page's history.
            else -> PullToGo(
                start = older?.let { PullAction("Older edit", Icons.AutoMirrored.Rounded.ArrowBack, it) },
                end = newer?.let { PullAction("Newer edit", Icons.AutoMirrored.Rounded.ArrowForward, it) },
                top = PullAction("View history", Icons.Rounded.History) { model.openPage(dest.title, tab = 1) },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) { LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 112.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            if (older != null) {
                                TextButton(onClick = older) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.padding(end = 8.dp))
                                    Text("Older edit")
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (newer != null) {
                                TextButton(onClick = newer) {
                                    Text("Newer edit")
                                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                        diff.from?.let { SideCard("Before", it, null, segment(0, 2)) { model.go(Dest.User(it.user)) } }
                        SideCard(
                            if (diff.from == null) "New page" else "After", diff.to, diff.from?.let { diff.to.size - it.size } ?: diff.to.size,
                            if (diff.from == null) segment(0, 1) else segment(1, 2),
                            flags = listOfNotNull(
                                if (latest) "Current" else null,
                                if (state.patrolled) "Patrolled" else if (dest.change?.patrolled == false) "Unpatrolled" else null,
                            ),
                        ) { model.go(Dest.User(diff.to.user)) }
                    }
                }
                if (diff.lines.isEmpty()) item { Notice("No difference", Modifier.height(160.dp)) }
                items(diff.lines) { DiffLineRow(it) }
            } }
        }
    }
}

/** The button that opens a change's actions, listed top to bottom with the ones used most last, closest to the thumb. */
@Composable
fun ActionsMenu(actions: List<Triple<String, ImageVector, () -> Unit>>, open: Boolean, onOpen: (Boolean) -> Unit) {
    BackHandler(open) { onOpen(false) }
    FloatingActionButtonMenu(
        expanded = open,
        button = {
            ToggleFloatingActionButton(open, onOpen, Modifier.semantics { contentDescription = "Actions" }) {
                Icon(
                    if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.CleaningServices, null,
                    Modifier.animateIcon({ checkedProgress }),
                )
            }
        },
    ) {
        for ((label, icon, action) in actions) {
            FloatingActionButtonMenuItem(onClick = { onOpen(false); action() }, text = { Text(label) }, icon = { Icon(icon, null) })
        }
    }
}

@Composable
private fun SideCard(label: String, side: DiffSide, delta: Int?, shape: androidx.compose.ui.graphics.Shape, flags: List<String> = emptyList(), onUser: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainerLow, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                Text(side.timestamp?.full().orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                flags.forEach {
                    if (it == "Unpatrolled") Flag(it, scheme.onErrorContainer, scheme.errorContainer) else Flag(it)
                }
                Delta(delta)
            }
            Text(
                side.user, Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onUser).padding(vertical = 2.dp),
                style = MaterialTheme.typography.titleSmall, color = scheme.primary,
            )
            if (side.comment.isNotBlank()) {
                Text(side.comment, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant)
            }
        }
    }
}

// Page

class PageState(tab: Int = 0) {
    var rendered by mutableStateOf<Rendered?>(null)
    var facts by mutableStateOf<PageFacts?>(null)
    var error by mutableStateOf<String?>(null)
    var tab by mutableStateOf(tab)
}

@Composable
fun PageScreen(model: AppModel, entry: Entry, session: Session, dest: Dest.Page) {
    val state = entry.hold("page") { PageState(dest.tab) }
    val history = entry.hold("history") { Feed(entry.scope) { session.api.history(dest.title, it) } }
    fun load() {
        state.error = null
        entry.scope.launch {
            try {
                val facts = session.api.pageFacts(dest.title)
                state.facts = facts
                if (facts.exists) state.rendered = session.api.render(dest.title)
            } catch (e: Exception) {
                state.error = e.say()
            }
        }
    }
    LaunchedEffect(Unit) { if (state.facts == null) load() }
    val facts = state.facts
    var sections by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            Column {
                BackBar(facts?.title ?: dest.title, model::back) {
                    if (facts?.exists == true && state.tab == 0) {
                        val desktop = model.saved.settings.desktopPages
                        IconToggleButton(desktop, { on -> model.update { it.copy(settings = it.settings.copy(desktopPages = on)) } }) {
                            Icon(Icons.Rounded.DesktopWindows, "Desktop layout")
                        }
                        IconToggleButton(model.saved.settings.darkPages, { on -> model.update { it.copy(settings = it.settings.copy(darkPages = on)) } }) {
                            Icon(Icons.Rounded.DarkMode, "Dark mode")
                        }
                    }
                    IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.wiki.pageUrl(dest.title)))) }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser")
                    }
                }
                if (facts?.exists == true) {
                    PrimaryTabRow(state.tab) {
                        listOf("Page", "History", "Details").forEachIndexed { index, label ->
                            Tab(state.tab == index, { state.tab = index }, text = { Text(label) })
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(model.snackbar) },
        floatingActionButton = {
            if (facts != null && session.can(if (facts.exists) "edit" else "createpage")) {
                ExtendedFloatingActionButton(
                    onClick = { model.edit(dest.title) },
                    icon = { Icon(Icons.Rounded.Edit, null) },
                    text = { Text(if (facts.exists) "Edit" else "Create") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.error != null -> Notice(state.error.orEmpty(), action = "Try again", onAction = ::load)
                facts == null -> Busy()
                !facts.exists -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Notice("Page not found", Modifier.height(200.dp))
                    ToolRow(Icons.AutoMirrored.Rounded.ListAlt, "Logs", segment(0, if (session.can("undelete")) 2 else 1)) {
                        model.go(Dest.Logs(title = dest.title))
                    }
                    if (session.can("undelete")) {
                        ToolRow(Icons.Rounded.RestoreFromTrash, "Restore", segment(1, 2)) { model.go(Dest.Form(FormKind.RESTORE, dest.title)) }
                    }
                }
                // The page text from the wiki, without Fandom's site around it. The full site is one tap away in the browser.
                state.tab == 0 -> state.rendered?.let {
                    val settings = model.saved.settings
                    PreviewHtml(
                        it.html, it.styles, session.wiki.base, pageFrame(session.site, facts.title),
                        settings.desktopPages, settings.darkPages, settings.blockTrackers, model::openPage,
                    )
                } ?: Busy()
                state.tab == 1 -> FeedList(history, "No revisions") {
                    changeRows(history.items, { model.go(Dest.Diff(dest.title, it.oldRevId, it.revId, it)) }, showTitle = false)
                }
                else -> PageDetails(model, session, facts) { sections = true }
            }
        }
    }

    if (sections) {
        ModalBottomSheet(onDismissRequest = { sections = false }) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                item { Text("Edit section", Modifier.padding(8.dp), style = MaterialTheme.typography.headlineSmall) }
                val list = state.rendered?.sections.orEmpty()
                if (list.isEmpty()) item { Text("No sections", Modifier.padding(8.dp)) }
                itemsIndexed(list) { index, section ->
                    ToolRow(Icons.Rounded.Segment, section.heading, segment(index, list.size), Modifier.padding(start = ((section.level - 2).coerceIn(0, 3) * 16).dp)) {
                        sections = false
                        model.edit(dest.title, section.index, section.heading)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDetails(model: AppModel, session: Session, facts: PageFacts, onSections: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
        SectionLabel("Page")
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Fact("Size", "${facts.length} bytes")
                Fact("Last edit", listOfNotNull(facts.lastUser.ifEmpty { null }, facts.lastTimestamp?.full()).joinToString(" · "))
                Fact("What links here", facts.linksHere.toString() + if (facts.moreLinksHere) " or more" else "")
                if (facts.redirect) Fact("Kind", "Redirect")
                val protection = facts.protection.filter { it.type == "edit" || it.type == "move" }
                if (protection.isEmpty()) Fact("Protection", "None")
                protection.forEach {
                    Fact(if (it.type == "edit") "Edit protection" else "Move protection", PendingWrite.levelName(it.level) + " · " + expiryText(it.expiry))
                }
            }
        }
        if (session.can("edit")) {
            SectionLabel("Edit")
            ToolRow(Icons.Rounded.Segment, "Edit section", segment(0, 1), onClick = onSections)
        }
        val admin = buildList<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, FormKind>> {
            if (session.can("protect")) add(Triple("Protect", Icons.Rounded.Lock, FormKind.PROTECT))
            if (session.can("move")) add(Triple("Move", Icons.AutoMirrored.Rounded.DriveFileMove, FormKind.MOVE))
            if (session.can("delete")) add(Triple("Delete", Icons.Rounded.Delete, FormKind.DELETE))
        }
        if (admin.isNotEmpty()) SectionLabel("Administration")
        admin.forEachIndexed { index, (label, icon, kind) ->
            ToolRow(icon, label, segment(index, admin.size)) { model.go(Dest.Form(kind, facts.title)) }
        }
        SectionLabel("Logs")
        ToolRow(Icons.AutoMirrored.Rounded.ListAlt, "Logs", segment(0, 1)) { model.go(Dest.Logs(title = facts.title)) }
    }
}

@Composable
fun Fact(name: String, value: String) {
    Row {
        Text(name, Modifier.weight(0.45f).padding(end = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium)
    }
}

// User

class UserState {
    var details by mutableStateOf<UserDetails?>(null)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun UserScreen(model: AppModel, entry: Entry, session: Session, dest: Dest.User) {
    val state = entry.hold("user") { UserState() }
    val edits = entry.hold("edits") { Feed(entry.scope) { session.api.contributions(dest.name, it) } }
    fun load() {
        entry.scope.launch {
            try { state.details = session.api.user(dest.name) } catch (e: Exception) { state.error = e.say() }
        }
    }
    LaunchedEffect(Unit) { load() }
    val details = state.details
    Scaffold(topBar = { BackBar(details?.name ?: dest.name, model::back, subtitle = "User") }, snackbarHost = { SnackbarHost(model.snackbar) }) { padding ->
        Centered(Modifier.padding(padding)) {
            FeedList(edits, "No edits") {
                item(key = "head") {
                    Column {
                        if (details != null) UserHeader(model, session, details)
                        else if (state.error != null) Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    }
                }
                changeRows(edits.items, { model.go(Dest.Diff(it.title, it.oldRevId, it.revId, it)) }, showUser = false)
            }
        }
    }
}

@Composable
private fun UserHeader(model: AppModel, session: Session, user: UserDetails) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (user.anon) Fact("Account", "Unregistered")
            else if (!user.exists) Fact("Account", "Not found")
            else {
                Fact("Edits", user.editCount.toString())
                user.registration?.let { Fact("Registered", it.full()) }
                Fact("Groups", user.groups.joinToString(", ").ifEmpty { "None" })
            }
        }
    }
    user.block?.let { block ->
        Spacer(Modifier.height(8.dp))
        Surface(color = scheme.errorContainer, contentColor = scheme.onErrorContainer, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Blocked", style = MaterialTheme.typography.titleMedium)
                Text("Expires: ${expiryText(block.expiry)}", style = MaterialTheme.typography.bodyMedium)
                Text("By: ${block.by}", style = MaterialTheme.typography.bodyMedium)
                if (block.reason.isNotBlank()) Text("Reason: ${block.reason}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session.can("block")) {
            AssistChip({ model.go(Dest.Form(FormKind.BLOCK, user.name)) }, { Text(if (user.block != null) "Change block" else "Block") }, leadingIcon = { Icon(Icons.Rounded.Block, null) })
            if (user.block != null) AssistChip({ model.go(Dest.Form(FormKind.UNBLOCK, user.name)) }, { Text("Unblock") })
        }
        if (!user.anon) AssistChip({ model.openPage("User:${user.name}") }, { Text("User page") }, leadingIcon = { Icon(Icons.Rounded.Person, null) })
        AssistChip({ model.go(Dest.Logs(title = "User:${user.name}")) }, { Text("Logs") }, leadingIcon = { Icon(Icons.Rounded.History, null) })
        if (!user.anon) AssistChip({ model.go(Dest.Logs(user = user.name)) }, { Text("Logs by user") })
    }
}

// Lists

@Composable
fun LogsScreen(model: AppModel, entry: Entry, session: Session, dest: Dest.Logs) {
    val feed = entry.hold("logs") { Feed(entry.scope) { session.api.logs(dest.type, dest.title, dest.user, it) } }
    val title = when (dest.type) {
        "block" -> "Block log"
        "delete" -> "Deletion log"
        "move" -> "Move log"
        "protect" -> "Protection log"
        "rights" -> "User rights log"
        "upload" -> "Upload log"
        else -> "Logs"
    }
    Scaffold(topBar = { BackBar(title, model::back, subtitle = dest.title ?: dest.user?.let { "By $it" }) }, snackbarHost = { SnackbarHost(model.snackbar) }) { padding ->
        Centered(Modifier.padding(padding)) { FeedList(feed, "No log entries") { changeRows(feed.items, { openChange(model, it) }) } }
    }
}

@Composable
fun NewPagesScreen(model: AppModel, entry: Entry, session: Session) {
    val feed = entry.hold("new") {
        Feed(entry.scope) { session.api.recentChanges(ChangeFilter(edits = false, logs = false, hideBots = false), session.me, it) }
    }
    Scaffold(topBar = { BackBar("New pages", model::back) }, snackbarHost = { SnackbarHost(model.snackbar) }) { padding ->
        Centered(Modifier.padding(padding)) { FeedList(feed, "No new pages") { changeRows(feed.items, { openChange(model, it) }) } }
    }
}

@Composable
fun BlocksScreen(model: AppModel, entry: Entry, session: Session) {
    val feed = entry.hold("blocks") { Feed<Block>(entry.scope) { session.api.blocks(it) } }
    Scaffold(topBar = { BackBar("Blocked users", model::back) }, snackbarHost = { SnackbarHost(model.snackbar) }) { padding ->
        Centered(Modifier.padding(padding)) {
            FeedList(feed, "No blocks", contentPadding = PaddingValues(16.dp)) {
                itemsIndexed(feed.items, key = { _, b -> b.id }) { index, block ->
                    ToolRow(
                        Icons.Rounded.Block, block.user.ifEmpty { "Autoblock #${block.id}" }, segment(index, feed.items.size),
                        note = "Expires: ${expiryText(block.expiry)} · By: ${block.by}" + if (block.reason.isBlank()) "" else "\n${block.reason}",
                    ) { if (block.user.isNotEmpty()) model.go(Dest.User(block.user)) }
                }
            }
        }
    }
}
