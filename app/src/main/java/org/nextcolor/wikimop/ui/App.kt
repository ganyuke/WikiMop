package org.nextcolor.wikimop.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import org.nextcolor.wikimop.Session
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import org.nextcolor.wikimop.AppModel
import org.nextcolor.wikimop.Dest
import org.nextcolor.wikimop.Entry
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun App(model: AppModel) {
    // Wide is Material's expanded width, where a list and what was opened from it fit side by side.
    val wide = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH
    LaunchedEffect(wide) {
        model.wide = wide
        if (!wide) model.narrow()
    }
    val session = model.session
    val entry = model.top
    val screens = remember { SeekableTransitionState(entry) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(entry) { screens.animateTo(entry) }
    // The back gesture shows the screen underneath while it is being made, and follows the finger.
    // Letting go goes back. Pulling the finger back in stays.
    PredictiveBackHandler(enabled = model.stack.size > 1) { progress ->
        val under = model.stack[model.stack.size - 2]
        try {
            progress.collect { screens.seekTo(it.progress, under) }
            model.back()
        } catch (e: CancellationException) {
            // The gesture's own coroutine is cancelled with it, so the screen is put back from outside it.
            scope.launch { screens.animateTo(screens.currentState) }
            throw e
        }
    }
    BackHandler(enabled = model.stack.size == 1 && model.beside != null) { model.closeBeside() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        rememberTransition(screens, label = "screen").AnimatedContent(
            contentKey = { it.id },
            transitionSpec = {
                if (goesBack(model, initialState, targetState)) {
                    // The screen being left shrinks and slides toward the edge, over the one underneath.
                    // It stays solid until the end, so the one underneath shows around it rather than through it.
                    val late = Easing { if (it < 0.85f) 0f else (it - 0.85f) / 0.15f }
                    (slideInHorizontally { -it / 8 } togetherWith (scaleOut(targetScale = 0.88f) + slideOutHorizontally { it / 3 } + fadeOut(tween(easing = late))))
                        .apply { targetContentZIndex = -1f }
                } else {
                    (fadeIn() + slideInHorizontally { it / 8 }) togetherWith fadeOut()
                }.using(SizeTransform(clip = false))
            },
        ) { shown ->
            val dest = shown.dest
            // A screen on its way out becomes a card with rounded corners and a shadow, so it stands apart from the one under it.
            val corner by transition.animateDp(label = "corner") { if (it == EnterExitState.PostExit) 32.dp else 0.dp }
            Box(Modifier.fillMaxSize().graphicsLayer {
                shape = RoundedCornerShape(corner)
                clip = corner > 0.dp
                shadowElevation = corner.toPx() / 2
            }) { Screen(model, shown, dest, session) }
        }
    }

    val request = model.request
    if (request != null && session != null) {
        ConfirmSheet(request, session.wiki.name, accountName(session.account), model::confirm, model::cancelRequest)
    }
}

@Composable
private fun Screen(model: AppModel, shown: Entry, dest: Dest, session: Session?) {
    when {
        dest is Dest.AddWiki -> AddWikiScreen(model, shown)
        dest is Dest.SignIn -> SignInScreen(model, dest)
        dest is Dest.WikiAccount -> WikiAccountScreen(model, shown, dest)
        dest is Dest.Settings -> SettingsScreen(model)
        dest is Dest.Notifications -> NotificationsScreen(model)
        session == null -> Welcome(model)
        dest is Dest.Home -> HomeScreen(model, shown, session)
        dest is Dest.Diff -> DiffScreen(model, shown, session, dest)
        dest is Dest.Page -> PageScreen(model, shown, session, dest)
        dest is Dest.File -> FileScreen(model, shown, session, dest)
        dest is Dest.Editor -> EditorScreen(model, shown, session, dest)
        dest is Dest.Review -> ReviewScreen(model, shown, session, dest)
        dest is Dest.User -> UserScreen(model, shown, session, dest)
        dest is Dest.Logs -> LogsScreen(model, shown, session, dest)
        dest is Dest.NewPages -> NewPagesScreen(model, shown, session)
        dest is Dest.Blocks -> BlocksScreen(model, shown, session)
        dest is Dest.Form -> FormScreen(model, shown, session, dest)
    }
}

/** Whether going from [from] to [to] is going back: [from] was closed, or [to] is under it on the stack while the back gesture is made. */
private fun goesBack(model: AppModel, from: Entry, to: Entry): Boolean {
    if (from.closed) return true
    val below = model.stack.indexOf(to)
    return below >= 0 && below < model.stack.indexOf(from)
}

/** Material's window widths in dp. From medium up, a navigation rail replaces the bottom bar. From expanded up, two panes fit. */
const val MEDIUM_WIDTH = 600
const val EXPANDED_WIDTH = 840
