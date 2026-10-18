package org.nextcolor.wikimop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.nextcolor.wikimop.AppModel
import org.nextcolor.wikimop.Dest
import org.nextcolor.wikimop.Entry
import org.nextcolor.wikimop.FormKind
import org.nextcolor.wikimop.Session
import org.nextcolor.wikimop.data.Block
import org.nextcolor.wikimop.data.PageFacts
import org.nextcolor.wikimop.data.PendingWrite

class FormState(target: String, markBot: Boolean) {
    var reason by mutableStateOf("")
    var expiry by mutableStateOf("")
    var editLevel by mutableStateOf("")
    var moveLevel by mutableStateOf("")
    var newName by mutableStateOf(target)
    var leaveRedirect by mutableStateOf(true)
    var moveTalk by mutableStateOf(true)
    var noCreate by mutableStateOf(true)
    var autoBlock by mutableStateOf(true)
    var allowTalk by mutableStateOf(true)
    var markBot by mutableStateOf(markBot)
    var facts by mutableStateOf<PageFacts?>(null)
    var block by mutableStateOf<Block?>(null)
    var loaded by mutableStateOf(false)
}

private val durations = listOf(
    "2 hours" to "2 hours", "1 day" to "1 day", "3 days" to "3 days", "1 week" to "1 week", "2 weeks" to "2 weeks",
    "1 month" to "1 month", "3 months" to "3 months", "6 months" to "6 months", "1 year" to "1 year", "Indefinite" to "infinite",
)

private val levels = listOf("" to "All users", "autoconfirmed" to "Autoconfirmed users", "sysop" to "Administrators")

private fun reasons(kind: FormKind) = when (kind) {
    FormKind.DELETE -> listOf("Spam", "Vandalism", "Author request", "Housekeeping", "Duplicate page")
    FormKind.BLOCK -> listOf("Vandalism", "Spam", "Harassment", "Inserting false information", "Abusing multiple accounts")
    FormKind.PROTECT -> listOf("Vandalism", "Edit warring", "High-traffic page")
    else -> emptyList()
}

/**
 * Where the details of an administrative change are filled in. The button at the
 * bottom does not make the change. It opens the confirm sheet, which does.
 */
@Composable
fun FormScreen(model: AppModel, entry: Entry, session: Session, dest: Dest.Form) {
    val kind = dest.kind
    // On a rollback the switch also hides the edits rolled back, so the default is only for bot accounts.
    val state = entry.hold("form") {
        FormState(dest.target, model.saved.settings.markBot && (kind != FormKind.ROLLBACK || session.can("bot")))
    }
    LaunchedEffect(Unit) {
        if (state.loaded) return@LaunchedEffect
        try {
            when (kind) {
                FormKind.DELETE -> state.facts = session.api.pageFacts(dest.target)
                FormKind.PROTECT -> {
                    val facts = session.api.pageFacts(dest.target)
                    state.facts = facts
                    state.editLevel = facts.protection.firstOrNull { it.type == "edit" }?.level.orEmpty()
                    state.moveLevel = facts.protection.firstOrNull { it.type == "move" }?.level.orEmpty()
                }
                FormKind.BLOCK -> state.block = session.api.user(dest.target).block
                else -> {}
            }
        } catch (e: Exception) {
            // The form works without what was looked up. It only has less to show.
        }
        state.loaded = true
    }

    val reason = state.reason.trim()
    // The switch is hidden without the right, and so is its default.
    val botSwitch = when (kind) {
        FormKind.ROLLBACK -> session.can("markbotedits")
        FormKind.UNDO -> session.can("bot")
        else -> false
    }
    val markBot = botSwitch && state.markBot
    val write: PendingWrite? = when (kind) {
        FormKind.DELETE -> PendingWrite.Delete(
            session.wiki, dest.target, reason,
            state.facts?.let { it.linksHere.toString() + if (it.moreLinksHere) " or more" else "" }.orEmpty(),
        )
        FormKind.RESTORE -> PendingWrite.Restore(session.wiki, dest.target, reason)
        FormKind.PROTECT -> if (state.expiry.isEmpty()) null else PendingWrite.Protect(session.wiki, dest.target, state.editLevel, state.moveLevel, state.expiry, reason)
        FormKind.MOVE -> state.newName.trim().let { name ->
            if (name.isEmpty() || name == dest.target) null
            else PendingWrite.Move(session.wiki, dest.target, name, reason, state.leaveRedirect, state.moveTalk)
        }
        FormKind.BLOCK -> if (state.expiry.isEmpty()) null else PendingWrite.BlockUser(
            session.wiki, dest.target, state.expiry, reason, state.noCreate, state.autoBlock, state.allowTalk, replace = state.block != null,
        )
        FormKind.UNBLOCK -> PendingWrite.Unblock(session.wiki, dest.target, reason)
        FormKind.ROLLBACK -> PendingWrite.Rollback(session.wiki, dest.target, dest.user, reason, markBot)
        FormKind.UNDO -> PendingWrite.Undo(session.wiki, dest.target, dest.revId, dest.user, reason, markBot)
    }
    val title = when (kind) {
        FormKind.DELETE -> "Delete page"
        FormKind.RESTORE -> "Restore page"
        FormKind.PROTECT -> "Protect page"
        FormKind.MOVE -> "Move page"
        FormKind.BLOCK -> if (state.block != null) "Change block" else "Block user"
        FormKind.UNBLOCK -> "Unblock user"
        FormKind.ROLLBACK -> "Rollback"
        FormKind.UNDO -> "Undo edit"
    }
    val severe = kind == FormKind.DELETE || kind == FormKind.BLOCK

    Frame(
        model, title, subtitle = dest.target,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            write != null -> ""
                            kind == FormKind.MOVE -> "New title required"
                            else -> "Duration required"
                        },
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { write?.let { model.ask(it) { model.back(); refreshBehind(model) } } },
                        enabled = write != null,
                        colors = if (severe) ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
                    ) { Text("$title") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            when (kind) {
                FormKind.DELETE -> state.facts?.let { facts ->
                    Info {
                        Fact("Size", "${facts.length} bytes")
                        Fact("Last edit", listOfNotNull(facts.lastUser.ifEmpty { null }, facts.lastTimestamp?.full()).joinToString(" · "))
                        Fact("What links here", facts.linksHere.toString() + if (facts.moreLinksHere) " or more" else "")
                    }
                }
                FormKind.ROLLBACK -> Info { Fact("User", dest.user) }
                FormKind.UNDO -> Info {
                    Fact("Revision", dest.revId.toString())
                    Fact("User", dest.user)
                }
                FormKind.BLOCK -> state.block?.let { block ->
                    Info {
                        Fact("Expires", expiryText(block.expiry))
                        Fact("By", block.by)
                        if (block.reason.isNotBlank()) Fact("Reason", block.reason)
                    }
                }
                FormKind.MOVE -> {
                    OutlinedTextField(state.newName, { state.newName = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("New title") }, singleLine = true)
                    SwitchRow("Leave a redirect behind", state.leaveRedirect, enabled = session.can("suppressredirect")) { state.leaveRedirect = it }
                    SwitchRow("Move associated talk page", state.moveTalk) { state.moveTalk = it }
                }
                else -> {}
            }

            if (kind == FormKind.PROTECT) {
                Choice("Edit", levels, state.editLevel) { state.editLevel = it }
                Choice("Move", levels, state.moveLevel) { state.moveLevel = it }
            }
            if (kind == FormKind.PROTECT || kind == FormKind.BLOCK) {
                Choice("Duration", durations.map { it.second to it.first }, state.expiry) { state.expiry = it }
            }
            if (kind == FormKind.BLOCK) {
                SwitchRow("Prevent account creation", state.noCreate) { state.noCreate = it }
                if (!PendingWrite.isAddress(dest.target)) SwitchRow("Autoblock IP addresses", state.autoBlock) { state.autoBlock = it }
                SwitchRow("Allow editing own talk page", state.allowTalk) { state.allowTalk = it }
            }
            if (botSwitch) {
                SwitchRow(if (kind == FormKind.ROLLBACK) "Mark as bot edits" else "Bot edit", state.markBot) { state.markBot = it }
            }

            OutlinedTextField(
                state.reason, { if (it.length <= 500) state.reason = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text(if (kind == FormKind.ROLLBACK || kind == FormKind.UNDO) "Summary" else "Reason") }, maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                reasons(kind).forEach { preset -> CheckChip(state.reason == preset, { state.reason = preset }, preset) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Loads the screen underneath again after a change, so it does not show the state from before. */
private fun refreshBehind(model: AppModel) {
    when (val dest = model.top.dest) {
        is Dest.Page, is Dest.User, is Dest.Diff, is Dest.File -> model.replaceTop(dest)
        else -> {}
    }
}

@Composable
private fun Info(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Choice(label: String, options: List<Pair<String, String>>, chosen: String, onChoose: (String) -> Unit) {
    SectionLabel(label, Modifier.padding(start = 0.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, name) -> CheckChip(chosen == value, { onChoose(value) }, name) }
    }
}
