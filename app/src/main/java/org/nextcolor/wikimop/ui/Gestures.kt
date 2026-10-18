package org.nextcolor.wikimop.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Something a pull past the edge of a screen does, such as going to the older edit. */
class PullAction(val label: String, val icon: ImageVector, val run: () -> Unit)

/** How far a pull goes for each bit the finger moves. Less than one, so a pull feels held back. */
private const val RESISTANCE = 0.5f

/**
 * Lets [content] be pulled past its edges: sideways from anywhere on it, and down once it is
 * scrolled to the top. A circle comes in from that edge and fills as it is pulled. Letting go
 * once it is full runs the action. Letting go before that puts it back and does nothing.
 *
 * [start] shows at the start edge and is pulled toward the end, the way a page is turned back.
 * [end] is the other way. An action that is null cannot be pulled for.
 */
@Composable
fun PullToGo(start: PullAction?, end: PullAction?, top: PullAction?, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val starts by rememberUpdatedState(start)
    val ends by rememberUpdatedState(end)
    val tops by rememberUpdatedState(top)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reach = with(LocalDensity.current) { 96.dp.toPx() }
    // Toward the end of the line is to the left in right-to-left writing.
    val toEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    // Positive is a pull for [start], negative for [end].
    var side by remember { mutableFloatStateOf(0f) }
    var down by remember { mutableFloatStateOf(0f) }

    fun pulled(before: Float, after: Float) {
        if (abs(before) < reach && abs(after) >= reach) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }

    fun letGo(amount: Float, action: PullAction?, set: (Float) -> Unit) {
        if (abs(amount) >= reach) action?.run?.invoke()
        scope.launch { animate(amount, 0f) { value, _ -> set(value) } }
    }

    val scroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Scrolling back up takes back the pull first.
                if (source != NestedScrollSource.UserInput || down <= 0f || available.y >= 0f) return Offset.Zero
                val used = maxOf(available.y, -down / RESISTANCE)
                down += used * RESISTANCE
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // What the list could not scroll, because it is already at the top, pulls the circle down.
                if (source != NestedScrollSource.UserInput || available.y <= 0f || tops == null) return Offset.Zero
                val before = down
                down = minOf(down + available.y * RESISTANCE, reach * 1.25f)
                pulled(before, down)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (down <= 0f) return Velocity.Zero
                letGo(down, tops) { down = it }
                return available
            }
        }
    }

    Box(
        modifier
            .nestedScroll(scroll)
            .pointerInput(toEnd) {
                detectHorizontalDragGestures(
                    onDragEnd = { letGo(side, if (side > 0) starts else ends) { side = it } },
                    onDragCancel = { letGo(0f, null) { side = it } },
                ) { change, amount ->
                    val low = if (ends == null) 0f else -reach * 1.25f
                    val high = if (starts == null) 0f else reach * 1.25f
                    if (low == high) return@detectHorizontalDragGestures
                    change.consume()
                    val before = side
                    side = (side + amount * toEnd * RESISTANCE).coerceIn(low, high)
                    pulled(before, side)
                }
            },
    ) {
        content()
        val size = with(LocalDensity.current) { 48.dp.toPx() }
        val margin = with(LocalDensity.current) { 16.dp.toPx() }
        fun travel(amount: Float) = (-size + (size + margin) * minOf(abs(amount) / reach, 1f)).roundToInt()
        if (side > 0f) starts?.let {
            PullCircle(it, side / reach, Edge.START, Modifier.align(Alignment.CenterStart).offset { IntOffset(travel(side), 0) })
        }
        if (side < 0f) ends?.let {
            PullCircle(it, -side / reach, Edge.END, Modifier.align(Alignment.CenterEnd).offset { IntOffset(-travel(side), 0) })
        }
        if (down > 0f) tops?.let {
            PullCircle(it, down / reach, Edge.TOP, Modifier.align(Alignment.TopCenter).offset { IntOffset(0, travel(down)) })
        }
    }
}

private enum class Edge { START, END, TOP }

/**
 * A circle that fills from the middle as [progress] goes to one. Once it is full, what letting go
 * will do is written beside it, on the side away from [edge], so the circle itself stays put.
 */
@Composable
private fun PullCircle(action: PullAction, progress: Float, edge: Edge, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val full = progress >= 1f
    val circle = @Composable {
        Surface(
            shape = CircleShape, color = scheme.surfaceContainerHighest, shadowElevation = 3.dp,
            modifier = Modifier.size(48.dp).graphicsLayer { scaleX = if (full) 1.1f else 1f; scaleY = scaleX },
        ) {
            Box(
                Modifier.drawBehind { drawCircle(scheme.primary, radius = size.minDimension / 2 * progress.coerceAtMost(1f)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(action.icon, null, tint = if (progress > 0.6f) scheme.onPrimary else scheme.primary)
            }
        }
    }
    val label = @Composable {
        if (full) {
            Surface(shape = CircleShape, color = scheme.inverseSurface, contentColor = scheme.inverseOnSurface) {
                Text(action.label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    val gap = Arrangement.spacedBy(8.dp)
    val quiet = modifier.clearAndSetSemantics {}
    when (edge) {
        Edge.START -> Row(quiet, gap, Alignment.CenterVertically) { circle(); label() }
        Edge.END -> Row(quiet, gap, Alignment.CenterVertically) { label(); circle() }
        Edge.TOP -> Column(quiet, gap, Alignment.CenterHorizontally) { circle(); label() }
    }
}
