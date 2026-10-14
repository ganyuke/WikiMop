package org.nextcolor.wikimop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.nextcolor.wikimop.AppModel
import org.nextcolor.wikimop.Dest

@Composable
fun App(model: AppModel) {
    BackHandler(enabled = model.stack.size > 1) { model.back() }
    val session = model.session
    val entry = model.top
    val depth = remember { mutableIntStateOf(model.stack.size) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(
            targetState = entry,
            contentKey = { it.id },
            transitionSpec = {
                val forward = model.stack.size >= depth.intValue
                depth.intValue = model.stack.size
                (fadeIn() + slideInHorizontally { if (forward) it / 8 else -it / 8 }) togetherWith fadeOut()
            },
            label = "screen",
        ) { shown ->
            val dest = shown.dest
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
                dest is Dest.Editor -> EditorScreen(model, shown, session, dest)
                dest is Dest.Review -> ReviewScreen(model, shown, session, dest)
                dest is Dest.User -> UserScreen(model, shown, session, dest)
                dest is Dest.Logs -> LogsScreen(model, shown, session, dest)
                dest is Dest.NewPages -> NewPagesScreen(model, shown, session)
                dest is Dest.Blocks -> BlocksScreen(model, shown, session)
                dest is Dest.Form -> FormScreen(model, shown, session, dest)
            }
        }
    }

    val request = model.request
    if (request != null && session != null) {
        ConfirmSheet(request, session.wiki.name, session.account?.userName.orEmpty(), model::confirm, model::cancelRequest)
    }
}
