package org.nextcolor.wikimop.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nextcolor.wikimop.AppModel
import org.nextcolor.wikimop.Dest
import org.nextcolor.wikimop.Entry
import org.nextcolor.wikimop.Session
import org.nextcolor.wikimop.data.DiffKind
import org.nextcolor.wikimop.data.Draft
import org.nextcolor.wikimop.data.Preview
import org.nextcolor.wikimop.data.LocalDiff
import org.nextcolor.wikimop.data.PageText
import org.nextcolor.wikimop.data.PendingWrite
import org.nextcolor.wikimop.logic.Completion
import org.nextcolor.wikimop.logic.Edited
import org.nextcolor.wikimop.logic.EditorOps
import org.nextcolor.wikimop.logic.Wikitext
import org.nextcolor.wikimop.say

/** The text being edited, with its undo steps. Every change is written to the draft shortly after it is made. */
class EditorState(private val model: AppModel, private val session: Session, initial: Draft, private val scope: CoroutineScope) {
    var draft by mutableStateOf(initial)
        private set
    /**
     * What the text field shows and edits. Typing changes it directly, and the toolbar, undo and find change it
     * through [set]. This kind of field keeps a selection on the text it was made on when the keyboard opening
     * scrolls the text under a finger that is still down, where the older kind picked up text from much further on.
     */
    val content = TextFieldState(initial.text)
    val text: String get() = content.text.toString()
    private var lastText = initial.text
    var saved by mutableStateOf(true)
        private set
    private val undone = ArrayDeque<Edited>()
    private val past = ArrayDeque<Edited>()
    private var lastStep = 0L
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var saving: Job? = null

    var completion by mutableStateOf<Completion?>(null)
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    private var asking: Job? = null

    var finding by mutableStateOf(false)
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    val matches get() = EditorOps.matches(text, query, matchCase)

    private fun snapshot() = Edited(text, content.selection.min, content.selection.max)

    /** Keeps an undo step from before each change that is typed. Changes made through [set] keep their own. */
    val typing = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            if (!asCharSequence().contentEquals(originalText)) remember(Edited(originalText.toString(), originalSelection.min, originalSelection.max))
        }
    }

    init {
        // However the text changed, it is saved, and a moved cursor may be in a link that page names can be offered for.
        scope.launch {
            snapshotFlow { content.text.toString() to content.selection }.collect { (now, _) ->
                if (now != lastText) {
                    lastText = now
                    changed()
                }
                complete()
            }
        }
    }

    private fun set(e: Edited) = content.edit {
        if (!asCharSequence().contentEquals(e.text)) replace(0, length, e.text)
        selection = TextRange(e.start, e.end)
    }

    /** Applies one of the toolbar's changes to the text as a single undo step. */
    fun apply(change: (Edited) -> Edited) {
        lastStep = 0
        remember()
        lastStep = 0
        set(change(snapshot()))
    }

    private fun remember(before: Edited = snapshot()) {
        val now = System.currentTimeMillis()
        // Typing within a second of the last key is one undo step, not one per letter.
        if (now - lastStep > 1000 || past.isEmpty()) {
            past.addLast(before)
            if (past.size > 200) past.removeFirst()
        }
        lastStep = now
        undone.clear()
        canUndo = true
        canRedo = false
    }

    fun undo() {
        val step = past.removeLastOrNull() ?: return
        undone.addLast(snapshot())
        restore(step)
    }

    fun redo() {
        val step = undone.removeLastOrNull() ?: return
        past.addLast(snapshot())
        restore(step)
    }

    private fun restore(step: Edited) {
        set(step)
        lastStep = 0
        canUndo = past.isNotEmpty()
        canRedo = undone.isNotEmpty()
    }

    private fun changed() {
        saved = false
        saving?.cancel()
        saving = scope.launch {
            delay(700)
            flush()
        }
    }

    fun flush() {
        if (saved) return
        draft = draft.copy(text = text, updated = System.currentTimeMillis())
        model.saveDraft(draft)
        saved = true
    }

    private fun complete() {
        val found = if (content.selection.collapsed) EditorOps.completion(text, content.selection.start) else null
        if (found == completion) return
        completion = found
        asking?.cancel()
        if (found == null) {
            suggestions = emptyList()
            return
        }
        asking = scope.launch {
            delay(250)
            suggestions = try {
                session.api.titlesStarting(found.prefix, if (found.template && !found.prefix.contains(':')) 10 else null)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun pick(title: String) {
        val c = completion ?: return
        apply { EditorOps.complete(it, c, title) }
    }

    fun select(match: Int) {
        content.edit { selection = TextRange(match, match + query.length) }
    }
}

private data class Key(val label: String, val icon: ImageVector?, val describe: String, val change: (Edited) -> Edited)

private val formatKeys = listOf(
    Key("B", Icons.Rounded.FormatBold, "Bold") { EditorOps.wrap(it, "'''", "'''", "bold text") },
    Key("I", Icons.Rounded.FormatItalic, "Italic") { EditorOps.wrap(it, "''", "''", "italic text") },
    Key("[[", Icons.Rounded.Link, "Link") { EditorOps.wrap(it, "[[", "]]") },
    Key("{{", Icons.Rounded.DataObject, "Template") { EditorOps.wrap(it, "{{", "}}") },
    Key("H", Icons.Rounded.Title, "Heading") { EditorOps.heading(it, 2) },
    Key("*", Icons.AutoMirrored.Rounded.FormatListBulleted, "Bulleted list") { EditorOps.prefixLines(it, "* ") },
    Key("#", Icons.Rounded.FormatListNumbered, "Numbered list") { EditorOps.prefixLines(it, "# ") },
    Key("<ref>", null, "Reference") { EditorOps.wrap(it, "<ref>", "</ref>") },
    Key("<!--", null, "Comment") { EditorOps.wrap(it, "<!-- ", " -->") },
    Key("nowiki", null, "No wiki formatting") { EditorOps.wrap(it, "<nowiki>", "</nowiki>") },
) + listOf("|", "=", "[", "]", "{", "}", "<", ">", "/", "~~~~").map { c -> Key(c, null, "Insert $c") { EditorOps.insert(it, c) } }

@Composable
fun EditorScreen(model: AppModel, entry: Entry, session: Session, dest: Dest.Editor) {
    val draft = remember { model.draftStore.get(dest.draftId) }
    if (draft == null) {
        Scaffold(topBar = { BackBar("Draft", model::back) }) { Notice("Draft not found", Modifier.padding(it)) }
        return
    }
    val state = entry.hold("editor") { EditorState(model, session, draft, entry.scope) }
    DisposableEffect(Unit) { onDispose { state.flush() } }
    var menu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    val colors = syntaxColors()
    val highlight = remember(colors) { OutputTransformation { Wikitext.spans(asCharSequence(), colors, this::addStyle) } }
    val focus = remember { FocusRequester() }
    val changed = state.text != draft.baseText

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            BackBar(
                draft.title, model::back,
                subtitle = listOfNotNull(
                    draft.sectionHeading.ifEmpty { null }, if (draft.isNew) "New page" else null,
                    if (!changed) "No changes" else if (state.saved) "Draft saved" else "Saving",
                ).joinToString(" · "),
            ) {
                Button(onClick = { state.flush(); model.go(Dest.Review(draft.id)) }, enabled = changed) { Text("Show changes") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Find and replace") }, { menu = false; state.finding = true })
                        DropdownMenuItem({ Text("Show preview") }, { menu = false; preview = true })
                        DropdownMenuItem({ Text("Discard draft") }, { menu = false; discard = true })
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.navigationBarsPadding()) {
                    if (state.suggestions.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.suggestions.forEach { title -> SuggestionChip({ state.pick(title) }, { Text(title) }) }
                        }
                    }
                    if (state.finding) FindBar(state)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = state::undo, enabled = state.canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo typing") }
                        IconButton(onClick = state::redo, enabled = state.canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo typing") }
                        formatKeys.forEach { key ->
                            if (key.icon != null) {
                                IconButton(onClick = { state.apply(key.change) }) { Icon(key.icon, key.describe) }
                            } else {
                                TextButton(onClick = { state.apply(key.change) }, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.semantics { contentDescription = key.describe }) {
                                    Text(key.label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(model.snackbar) },
    ) { padding ->
        BasicTextField(
            state.content,
            inputTransformation = state.typing,
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp).focusRequester(focus),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            outputTransformation = highlight,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false),
        )
    }

    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Discard draft?") },
            text = { Text("Unpublished changes to ${draft.title} will be lost.") },
            confirmButton = { TextButton(onClick = { discard = false; model.discardDraft(draft.id); model.back() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep") } },
        )
    }
    if (preview) PreviewSheet(model, session, draft.title, state.text) { preview = false }
}

@Composable
private fun FindBar(state: EditorState) {
    val matches = state.matches
    var at by remember { mutableStateOf(0) }
    fun step(by: Int) {
        if (matches.isEmpty()) return
        at = (at + by).mod(matches.size)
        state.select(matches[at])
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                state.query, { state.query = it; at = 0 }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find") },
                suffix = { if (state.query.isNotEmpty()) Text(if (matches.isEmpty()) "0" else "${at.coerceAtMost(matches.size - 1) + 1}/${matches.size}") },
            )
            IconButton(onClick = { step(-1) }) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous match") }
            IconButton(onClick = { step(1) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Next match") }
            IconButton(onClick = { state.finding = false }) { Icon(Icons.Rounded.Close, "Close find") }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.replacement, { state.replacement = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Replace with") })
            FilterChip(state.matchCase, { state.matchCase = !state.matchCase }, { Text("Aa") }, modifier = Modifier.semantics { contentDescription = "Match case" })
            TextButton(onClick = {
                val index = matches.getOrNull(at.coerceAtMost(matches.size - 1)) ?: return@TextButton
                state.apply { Edited(it.text.replaceRange(index, index + state.query.length, state.replacement), index + state.replacement.length) }
            }, enabled = matches.isNotEmpty()) { Text("Replace") }
            TextButton(onClick = {
                state.apply { Edited(EditorOps.replaceAll(it.text, state.query, state.replacement, state.matchCase), 0) }
            }, enabled = matches.isNotEmpty()) { Text("All") }
        }
    }
}

@Composable
fun PreviewSheet(model: AppModel, session: Session, title: String, text: String, onClose: () -> Unit) {
    var preview by remember { mutableStateOf<Preview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) {
        try { preview = session.api.preview(title, text) } catch (e: Exception) { error = e.say() }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f)) {
            Text("Preview", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
                        when {
                error != null -> Notice(error.orEmpty())
                else -> preview?.let {
                    val settings = model.saved.settings
                    PreviewHtml(
                        it.html, it.styles, session.wiki.base, pageFrame(session.site, title), settings.desktopPages, settings.darkPages, settings.blockTrackers,
                    ) { page -> onClose(); model.openPage(page) }
                } ?: Busy()
            }
        }
    }
}

// Review

class ReviewState(initial: Draft, markBot: Boolean) {
    var draft by mutableStateOf(initial)
    var checking by mutableStateOf(true)
    var checkError by mutableStateOf<String?>(null)
    /** The page as it is on the wiki now, when that is not what the draft started from. */
    var newer by mutableStateOf<PageText?>(null)
    var newerBy by mutableStateOf("")
    var markBot by mutableStateOf(markBot)
}

/**
 * The step between writing and publishing. It shows the exact difference that
 * will be published and checks that the page has not moved on in the meantime.
 */
@Composable
fun ReviewScreen(model: AppModel, entry: Entry, session: Session, dest: Dest.Review) {
    val initial = remember { model.draftStore.get(dest.draftId) }
    if (initial == null) {
        Scaffold(topBar = { BackBar("Review", model::back) }) { Notice("Draft not found", Modifier.padding(it)) }
        return
    }
    val state = entry.hold("review") { ReviewState(initial, model.saved.settings.markBot) }
    val draft = state.draft
    fun save(changed: Draft) { state.draft = changed; model.saveDraft(changed) }
    fun check() {
        state.checking = true
        state.checkError = null
        entry.scope.launch {
            try {
                val now = session.api.pageText(draft.title, draft.section)
                val moved = if (state.draft.isNew) now.exists else now.revId != state.draft.baseRevId
                state.newer = if (moved) now else null
                if (moved && now.exists) state.newerBy = session.api.pageFacts(draft.title).lastUser
            } catch (e: Exception) {
                state.checkError = e.say()
            } finally {
                state.checking = false
            }
        }
    }
    LaunchedEffect(Unit) { check() }
    val lines = remember(draft.baseText, draft.text) { LocalDiff.lines(draft.baseText, draft.text) }
    val added = lines.count { it.kind == DiffKind.ADDED }
    val removed = lines.count { it.kind == DiffKind.REMOVED }
    var preview by remember { mutableStateOf(false) }
    val needSummary = model.saved.settings.requireSummary && draft.summary.isBlank()
    val newer = state.newer
    val ready = draft.changed && !state.checking && state.checkError == null && newer == null && !needSummary

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = { BackBar("Show changes", model::back, subtitle = listOfNotNull(draft.title, draft.sectionHeading.ifEmpty { null }).joinToString(" · ")) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            !draft.changed -> "No changes"
                            state.checking -> "Checking"
                            state.checkError != null -> "Check failed"
                            newer != null -> "Edit conflict"
                            needSummary -> "Summary required"
                            else -> ""
                        },
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = { preview = true }) { Text("Show preview") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = ready,
                        onClick = {
                            val write = PendingWrite.Edit(
                                session.wiki, draft.title, draft.text, draft.summary.trim(), draft.minor, draft.isNew, draft.section, draft.sectionHeading,
                                draft.baseRevId, draft.baseTimestamp, draft.startTimestamp, session.can("bot") && state.markBot, added, removed,
                            )
                            model.ask(write) {
                                model.discardDraft(draft.id)
                                // Back past the review and the editor, then load the page again if that is where they came from.
                                model.back()
                                model.back()
                                (model.top.dest as? Dest.Page)?.let { model.replaceTop(it) }
                            }
                        },
                    ) { Text(if (draft.isNew) "Create" else "Publish") }
                }
            }
        },
        snackbarHost = { SnackbarHost(model.snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (state.checkError != null) {
                        Alert(state.checkError.orEmpty()) { TextButton(onClick = ::check) { Text("Try again") } }
                    }
                    if (newer != null) {
                        Alert(
                            "Edit conflict",
                            text = if (draft.isNew) "This page was created after you started." else "${state.newerBy.ifEmpty { "Someone" }} edited this page after you started.",
                        ) {
                            if (!draft.isNew) TextButton(onClick = { model.go(Dest.Diff(draft.title, draft.baseRevId, newer.revId)) }) { Text("Show their edit") }
                            TextButton(onClick = {
                                save(draft.copy(isNew = false, baseText = newer.text, baseRevId = newer.revId, baseTimestamp = newer.revTimestamp, startTimestamp = newer.serverTime))
                                state.newer = null
                            }) { Text("Compare with latest") }
                        }
                    }
                    OutlinedTextField(
                        draft.summary, { if (it.length <= 500) save(draft.copy(summary = it)) },
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("Summary") }, maxLines = 4,
                        supportingText = { Text("${draft.summary.length}/500") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    SwitchRow("Minor edit", draft.minor) { save(draft.copy(minor = it)) }
                    if (session.can("bot")) SwitchRow("Bot edit", state.markBot) { state.markBot = it }
                    val colors = LocalDiffColors.current
                    Row(Modifier.padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Changes", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text("+$added", style = MaterialTheme.typography.labelLarge, color = colors.plus)
                        Text("−$removed", style = MaterialTheme.typography.labelLarge, color = colors.minus)
                    }
                }
            }
            if (lines.isEmpty()) item { Notice("No changes", Modifier.height(160.dp)) }
            items(lines) { DiffLineRow(it) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (preview) PreviewSheet(model, session, draft.title, draft.text) { preview = false }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, modifier: Modifier = Modifier, note: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
