package org.nextcolor.wikimop.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nextcolor.wikimop.AppModel
import org.nextcolor.wikimop.Dest
import org.nextcolor.wikimop.Entry
import org.nextcolor.wikimop.FormKind
import org.nextcolor.wikimop.Session
import org.nextcolor.wikimop.data.FileInfo
import org.nextcolor.wikimop.data.FileVersion
import org.nextcolor.wikimop.data.PendingWrite
import org.nextcolor.wikimop.data.USER_AGENT
import org.nextcolor.wikimop.say

class FileState {
    var file by mutableStateOf<FileInfo?>(null)
    var error by mutableStateOf<String?>(null)
    var patrolled by mutableStateOf(false)
    /** The version picked from the file's history. Until one is, the upload the screen was opened from is shown. */
    var picked by mutableStateOf<FileVersion?>(null)
}

/**
 * An uploaded file as it is, not its description page: the picture, who uploaded it and when,
 * and its earlier versions. An upload that is waiting for patrol can be patrolled from here.
 */
@Composable
fun FileScreen(model: AppModel, entry: Entry, session: Session, dest: Dest.File) {
    val state = entry.hold("file") { FileState() }
    // Pictures are asked for as wide as the screen, so they are sharp without loading the whole file.
    val width = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }.coerceIn(320, 1600)
    fun load() {
        state.error = null
        entry.scope.launch {
            try { state.file = session.api.file(dest.title, width) } catch (e: Exception) { state.error = e.say() }
        }
    }
    LaunchedEffect(Unit) { if (state.file == null) load() }
    val change = dest.change
    val file = state.file
    val uploaded = file?.at(change?.timestamp)
    val shown = state.picked ?: uploaded
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val beside = entry === model.beside

    Scaffold(
        topBar = {
            BackBar(dest.title, { if (beside) model.closeBeside() else model.back() }, subtitle = "File", close = beside) {
                IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.wiki.pageUrl(dest.title)))) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser")
                }
            }
        },
        snackbarHost = { if (!beside) SnackbarHost(model.snackbar) },
        floatingActionButton = {
            if (file == null) return@Scaffold
            val user = change?.user ?: uploaded?.user.orEmpty()
            val actions = buildList<Triple<String, ImageVector, () -> Unit>> {
                if (file.versions.isNotEmpty() && session.can("delete")) add(Triple("Delete file", Icons.Rounded.Delete) { model.go(Dest.Form(FormKind.DELETE, dest.title)) })
                if (user.isNotEmpty() && session.can("block")) add(Triple("Block user", Icons.Rounded.Block) { model.go(Dest.Form(FormKind.BLOCK, user)) })
                add(Triple("Logs", Icons.AutoMirrored.Rounded.ListAlt) { model.go(Dest.Logs(title = dest.title)) })
                add(Triple("View history", Icons.Rounded.History) { model.go(Dest.Page(dest.title, tab = 1)) })
                add(Triple("Open page", Icons.Rounded.Description) { model.go(Dest.Page(dest.title)) })
                // An upload has no revision of its own, so it is patrolled by its entry in recent changes.
                if (change != null && change.patrolled == false && change.rcId > 0 && !state.patrolled && session.can("patrol")) {
                    add(Triple("Patrol", Icons.Rounded.Check) {
                        model.ask(PendingWrite.Patrol(session.wiki, dest.title, 0, change.user, change.rcId)) { state.patrolled = true }
                    })
                }
            }
            ActionsMenu(actions, menu) { menu = it }
        },
    ) { padding ->
        when {
            state.error != null -> Notice(state.error.orEmpty(), Modifier.padding(padding), "Try again", ::load)
            file == null -> Busy(Modifier.padding(padding))
            shown == null -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Notice("File not found", Modifier.heightIn(min = 200.dp))
                ToolRow(Icons.AutoMirrored.Rounded.ListAlt, "Logs", segment(0, 1)) { model.go(Dest.Logs(title = dest.title)) }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp)) {
                item(key = "preview") {
                    FilePreview(model, shown) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(shown.url))) }
                }
                item(key = "upload") {
                    val flags = listOfNotNull(
                        if (shown === file.versions.first()) "Current" else null,
                        if (shown === uploaded && change != null) {
                            if (state.patrolled || change.patrolled == true) "Patrolled" else if (change.patrolled == false) "Unpatrolled" else null
                        } else null,
                    )
                    VersionCard(shown, flags, Modifier.padding(top = 12.dp)) { model.go(Dest.User(shown.user)) }
                }
                if (file.versions.size > 1) {
                    item(key = "history") { SectionLabel("File history", Modifier.padding(start = 0.dp)) }
                    itemsIndexed(file.versions) { index, version ->
                        VersionRow(model, version, version === shown, segment(index, file.versions.size)) { state.picked = version }
                    }
                }
            }
        }
    }
}

/** The picture itself, as wide as the screen. A file that is not a picture shows what kind it is, and opens elsewhere. */
@Composable
private fun FilePreview(model: AppModel, version: FileVersion, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val thumb = version.thumbUrl
    val ratio = if (version.width > 0 && version.height > 0) (version.width.toFloat() / version.height).coerceIn(0.4f, 3f) else 1.5f
    // At most about half the screen high, so who uploaded it and the earlier versions show below it.
    val tallest = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        if (thumb != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RemoteImage(model, thumb, version.mime, Modifier.heightIn(max = tallest).aspectRatio(ratio).clickable(onClick = onOpen))
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, Modifier.size(48.dp), tint = scheme.onSurfaceVariant)
                Text(version.mime.ifEmpty { "File" }, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpen) { Text("Open file") }
            }
        }
    }
}

@Composable
private fun VersionCard(version: FileVersion, flags: List<String>, modifier: Modifier = Modifier, onUser: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Uploaded", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                Text(version.timestamp?.full().orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                flags.forEach { if (it == "Unpatrolled") Flag(it, scheme.onErrorContainer, scheme.errorContainer) else Flag(it) }
            }
            Text(
                version.user, Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onUser).padding(vertical = 2.dp),
                style = MaterialTheme.typography.titleSmall, color = scheme.primary,
            )
            Text(fileFacts(version), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (version.comment.isNotBlank()) {
                Text(version.comment, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VersionRow(model: AppModel, version: FileVersion, selected: Boolean, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow, shape = shape,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(shape).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                val thumb = version.thumbUrl
                if (thumb != null) RemoteImage(model, thumb, null, Modifier.fillMaxSize(), ContentScale.Crop)
                else Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = scheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(version.timestamp?.full().orEmpty(), style = MaterialTheme.typography.bodyMedium)
                Text(version.user + " · " + fileFacts(version), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                if (version.comment.isNotBlank()) {
                    Text(version.comment, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}

/** Size on screen and on disk, such as "1920 × 1080 · 245 KB". */
internal fun fileFacts(version: FileVersion): String = listOfNotNull(
    if (version.width > 0 && version.height > 0) "${version.width} × ${version.height}" else null,
    bytes(version.size),
).joinToString(" · ")

internal fun bytes(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
    size >= 1024 -> "${size / 1024} KB"
    else -> "$size bytes"
}

/** Pictures already loaded, so going back to a file does not load it again. */
private val pictures = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Files bigger than this are not loaded into the app. The browser can still open them. */
private const val MAX_PICTURE_BYTES = 30 * 1024 * 1024

/** A picture from the wiki, with a spinner while it loads and a broken picture if it cannot be. */
@Composable
fun RemoteImage(model: AppModel, url: String, description: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Fit) {
    val loaded by produceState<Result<ImageBitmap>?>(pictures.get(url)?.let { Result.success(it) }, url) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { fetchPicture(model.http, url) } }
    }
    val image = loaded?.getOrNull()
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            image != null -> Image(image, description, Modifier.fillMaxSize(), contentScale = scale)
            loaded == null -> LoadingIndicator()
            else -> Icon(Icons.Rounded.BrokenImage, "Could not load the picture", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Loads and decodes a picture, scaled down on the way so a large one does not take up the memory of the whole file. */
private fun fetchPicture(http: OkHttpClient, url: String): ImageBitmap {
    pictures.get(url)?.let { return it }
    val bytes = http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
        val body = response.body
        check(response.isSuccessful && body != null) { "HTTP ${response.code}" }
        body.source().run {
            request(MAX_PICTURE_BYTES + 1L)
            check(buffer.size <= MAX_PICTURE_BYTES) { "Too big" }
            buffer.readByteArray()
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })) { "Not a picture" }
    return bitmap.asImageBitmap().also { pictures.put(url, it) }
}
