package org.nextcolor.wikimop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nextcolor.wikimop.Request
import org.nextcolor.wikimop.data.Confirmation
import kotlin.math.roundToInt

/**
 * The last step before anything is changed on a wiki. The control has to be
 * dragged from one end to the other, which a stray tap or a phone in a pocket
 * does not do. Let go early and it goes back to the start.
 */
@Composable
fun SlideToConfirm(label: String, onConfirmed: () -> Unit, modifier: Modifier = Modifier, severe: Boolean = false, busy: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val thumbColor = if (severe) scheme.error else scheme.primary
    val onThumb = if (severe) scheme.onError else scheme.onPrimary
    val track = if (severe) scheme.errorContainer else scheme.primaryContainer
    val onTrack = if (severe) scheme.onErrorContainer else scheme.onPrimaryContainer
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(CircleShape)
            .background(track)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                // A screen reader has no drag, and its double tap is already a deliberate act.
                if (!busy) onClick(label.removePrefix("Slide to ").replaceFirstChar { it.uppercase() }) { onConfirmed(); true }
            },
    ) {
        val thumb = 56.dp
        val inset = 4.dp
        val travel = with(LocalDensity.current) { (maxWidth - thumb - inset * 2).toPx() }
        val drag = rememberDraggableState { delta ->
            scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, travel)) }
        }
        Text(
            if (busy) "Sending" else label,
            Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
            color = onTrack.copy(alpha = if (busy) 1f else 1f - 0.7f * (offset.value / travel.coerceAtLeast(1f))),
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
        )
        Box(
            Modifier
                .padding(inset)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(thumbColor)
                .draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    enabled = !busy,
                    onDragStopped = {
                        if (offset.value >= travel * 0.92f) {
                            onConfirmed()
                            offset.animateTo(0f)
                        } else {
                            offset.animateTo(0f)
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) LoadingIndicator(Modifier.size(36.dp), color = onThumb)
            else Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = onThumb)
        }
    }
}

/** Says exactly what is about to be changed, where and as whom, above the slide control. */
@Composable
fun ConfirmSheet(request: Request, wikiName: String, userName: String, onConfirm: (Confirmation) -> Unit, onCancel: () -> Unit) {
    val write = request.write
    val scheme = MaterialTheme.colorScheme
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !request.sending })
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(write.action, style = MaterialTheme.typography.headlineSmall, color = if (write.severe) scheme.error else scheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(write.target, style = MaterialTheme.typography.titleLarge)
            Text("$wikiName · as $userName", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Surface(color = scheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    write.facts.forEachIndexed { index, (name, value) ->
                        if (index > 0) Spacer(Modifier.height(10.dp))
                        Row {
                            Text(name, Modifier.weight(0.45f).padding(end = 16.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                            Text(value, Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            request.error?.let {
                Spacer(Modifier.height(12.dp))
                Alert(it)
            }
            Spacer(Modifier.height(20.dp))
            SlideToConfirm(write.slide, { onConfirm(Confirmation.bySlide(write)) }, severe = write.severe, busy = request.sending)
            TextButton(onClick = onCancel, enabled = !request.sending, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp)) {
                Text("Cancel")
            }
        }
    }
}
