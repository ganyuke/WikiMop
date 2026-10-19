package org.nextcolor.wikimop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NoAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nextcolor.wikimop.Feed
import org.nextcolor.wikimop.data.Account
import org.nextcolor.wikimop.data.AccountKind
import org.nextcolor.wikimop.data.Change

/** Rounds the outer corners of a group of rows more than the corners between rows. */
fun segment(index: Int, count: Int): Shape {
    val outer = 20.dp
    val inner = 4.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(top, top, bottom, bottom)
}

@Composable
fun BackBar(title: String, onBack: () -> Unit, subtitle: String? = null, close: Boolean = false, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { if (close) Icon(Icons.Rounded.Close, "Close") else Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        },
        actions = { actions() },
    )
}

/** The widest that settings, forms and lists get. Past this, rows are hard to follow from one side to the other. */
val MaxContentWidth = 840.dp

/** Holds [content] to [MaxContentWidth], in the middle of a wider window. */
@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = MaxContentWidth)) { content() }
    }
}

@Composable
fun Busy(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
}

@Composable
fun Notice(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun Delta(delta: Int?, modifier: Modifier = Modifier) {
    delta ?: return
    val colors = LocalDiffColors.current
    val (text, color) = when {
        delta > 0 -> "+$delta" to colors.plus
        delta < 0 -> "−${-delta}" to colors.minus
        else -> "0" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text, modifier, color = color, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
        // A change of 500 bytes or more is worth a second look, as on the website.
        fontWeight = if (kotlin.math.abs(delta) >= 500) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
fun Flag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    Surface(color = container, contentColor = color, shape = RoundedCornerShape(6.dp)) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
    }
}

fun changeIcon(change: Change): ImageVector = when {
    !change.isLog -> if (change.isNew) Icons.AutoMirrored.Rounded.NoteAdd else Icons.Rounded.Edit
    else -> when (change.logType) {
        "block" -> Icons.Rounded.Block
        "delete" -> Icons.Rounded.Delete
        "move" -> Icons.AutoMirrored.Rounded.DriveFileMove
        "protect" -> Icons.Rounded.Lock
        "rights" -> Icons.Rounded.AdminPanelSettings
        "upload" -> Icons.Rounded.Upload
        "newusers" -> Icons.Rounded.PersonAdd
        else -> Icons.AutoMirrored.Rounded.ListAlt
    }
}

/** What was done in a log entry, as a verb that goes between the user and the page. */
fun logVerb(change: Change): String = when (change.logType to change.logAction) {
    "block" to "block" -> "blocked"
    "block" to "reblock" -> "changed block settings for"
    "block" to "unblock" -> "unblocked"
    "delete" to "delete" -> "deleted"
    "delete" to "restore" -> "restored"
    "move" to "move", "move" to "move_redir" -> "moved"
    "protect" to "protect" -> "protected"
    "protect" to "modify" -> "changed protection level for"
    "protect" to "unprotect" -> "unprotected"
    "rights" to "rights" -> "changed group membership for"
    "upload" to "upload" -> "uploaded"
    "upload" to "overwrite" -> "uploaded a new version of"
    "newusers" to "create", "newusers" to "autocreate" -> "created user account"
    else -> "${change.logType}: ${change.logAction}"
}

@Composable
fun ChangeRow(
    change: Change,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    showUser: Boolean = true,
    selected: Boolean = false,
    patrolled: Boolean? = change.patrolled,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow, shape = shape, modifier = modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            val unpatrolled = patrolled == false
            Box(
                Modifier.size(36.dp).background(
                    if (unpatrolled) scheme.errorContainer else if (change.isLog) scheme.tertiaryContainer else scheme.secondaryContainer,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    changeIcon(change), null, Modifier.size(20.dp),
                    tint = if (unpatrolled) scheme.onErrorContainer else if (change.isLog) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val headline = when {
                        change.isLog -> "${change.user} ${logVerb(change)} " +
                            if (change.logType in setOf("block", "rights", "newusers")) change.title.substringAfter(':') else change.title
                        showTitle -> change.title
                        else -> change.user
                    }
                    // A log entry opens its page or user, not the entry, so all of it is shown here.
                    Text(
                        headline, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        maxLines = if (change.isLog) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (!change.isLog) {
                        Spacer(Modifier.width(8.dp))
                        Delta(change.delta)
                    }
                }
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val who = if (change.isLog || !showTitle || !showUser) "" else change.user + " · "
                    Text(
                        who + change.timestamp.clock(), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (unpatrolled) Flag("Unpatrolled", scheme.onErrorContainer, scheme.errorContainer)
                    if (change.isNew && !change.isLog) Flag("New")
                    if (change.minor) Flag("Minor")
                    if (change.bot) Flag("Bot")
                    if (change.top) Flag("Current")
                }
                val note = listOf(change.logDetail, change.comment).filter { it.isNotBlank() }.joinToString(" · ")
                if (note.isNotEmpty()) {
                    Text(
                        note, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                        color = scheme.onSurfaceVariant, maxLines = if (change.isLog) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (change.tags.isNotEmpty()) {
                    Text(
                        change.tags.joinToString(" · "), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall,
                        color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Shows a list that loads in parts, with the usual loading, empty and failed states. */
@Composable
fun <T> FeedList(
    feed: Feed<T>,
    empty: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
    /** Runs as well as loading the list again when it is pulled down. */
    onRefresh: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    LaunchedEffect(feed) { feed.start() }
    PullToRefreshBox(isRefreshing = feed.loading && feed.loaded, onRefresh = { feed.refresh(); onRefresh() }, modifier = modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), feed.list, contentPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
            when {
                feed.error != null -> item(key = "error") {
                    Notice(feed.error.orEmpty(), Modifier.fillParentMaxHeight(0.5f), "Try again", feed::refresh)
                }
                !feed.loaded -> item(key = "busy") { Busy(Modifier.fillParentMaxHeight(0.5f)) }
                feed.items.isEmpty() -> item(key = "empty") { Notice(empty, Modifier.fillParentMaxHeight(0.5f)) }
                feed.next != null -> item(key = "more") {
                    LaunchedEffect(feed.next) { feed.more() }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
            }
        }
    }
}

/** Rows of changes under a heading for each day. */
fun LazyListScope.changeRows(
    items: List<Change>,
    onOpen: (Change) -> Unit,
    showTitle: Boolean = true,
    showUser: Boolean = true,
    selected: String? = null,
    patrolled: (Change) -> Boolean? = { it.patrolled },
) {
    val days = items.groupBy { it.timestamp.dayName() }
    for ((day, rows) in days) {
        item(key = "day-$day", contentType = "day") { SectionLabel(day) }
        rows.forEachIndexed { index, change ->
            item(key = change.key, contentType = "change") {
                ChangeRow(change, { onOpen(change) }, segment(index, rows.size), showTitle = showTitle, showUser = showUser, selected = change.key == selected, patrolled = patrolled(change))
            }
        }
    }
}

/** A screen with a collapsing app bar, a back arrow and the app's messages at the bottom. */
@Composable
fun Frame(
    model: org.nextcolor.wikimop.AppModel,
    title: String,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Centered {
        androidx.compose.material3.Scaffold(
            modifier = Modifier.imePadding().nestedScroll(behavior.nestedScrollConnection),
            topBar = {
                androidx.compose.material3.MediumFlexibleTopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    subtitle = { if (subtitle != null) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = { model.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    actions = { actions() },
                    scrollBehavior = behavior,
                )
            },
            bottomBar = bottomBar,
            snackbarHost = { androidx.compose.material3.SnackbarHost(model.snackbar) },
            content = content,
        )
    }
}

/** A problem that needs attention, on a neutral card so its buttons keep their usual colors. */
@Composable
fun Alert(title: String, modifier: Modifier = Modifier, text: String? = null, actions: @Composable () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        if (text == null) {
            // A title alone sits on one line with its button.
            Row(Modifier.padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = scheme.error)
                Spacer(Modifier.width(12.dp))
                Text(title, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.titleSmall, color = scheme.error)
                actions()
            }
        } else {
            Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.padding(top = 2.dp), tint = scheme.error)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.error)
                    Text(text, Modifier.padding(top = 2.dp, end = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { actions() }
                }
            }
        }
    }
}

/** A filter chip with the check that Material shows on a selected one. */
@Composable
fun CheckChip(selected: Boolean, onClick: () -> Unit, label: String) = FilterChip(
    selected, onClick, { Text(label) },
    leadingIcon = if (selected) {
        { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
    } else null,
)

/** How an account is named where it acts. A bot password has the same user name as the account, so it says which it is. */
fun accountName(account: Account?): String = when {
    account == null -> ""
    account.kind == AccountKind.BOT -> "${account.userName} (bot password)"
    else -> account.userName
}

/** The icon for how someone is signed in, the same wherever an account is shown. Null is no account. */
fun accountIcon(account: Account?): ImageVector = when {
    // Not the crossed-out person, which stands for blocked users in Tools.
    account == null -> Icons.Rounded.NoAccounts
    account.kind == AccountKind.BOT -> Icons.Rounded.SmartToy
    else -> Icons.Rounded.Person
}
