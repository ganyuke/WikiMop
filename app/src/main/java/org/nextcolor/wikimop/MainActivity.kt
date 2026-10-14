package org.nextcolor.wikimop

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.nextcolor.wikimop.data.KeystoreVault
import org.nextcolor.wikimop.data.SavedStore
import org.nextcolor.wikimop.notify.CheckJob
import org.nextcolor.wikimop.notify.Notice
import org.nextcolor.wikimop.notify.Notifier
import org.nextcolor.wikimop.ui.App
import org.nextcolor.wikimop.ui.WikiMopTheme

/** Holds what the screens and the checks for notifications share. */
class WikiMopApp : Application() {
    val store by lazy { SavedStore(KeystoreVault(filesDir.resolve("accounts.bin"))) }
    val http by lazy { OkHttpClient() }
}

/** Keeps the app's state through screen rotation. */
class Holder(app: Application) : AndroidViewModel(app) {
    val model = AppModel((app as WikiMopApp).store, app.filesDir.resolve("drafts"), app.http).apply {
        dismiss = { notice, tag -> Notifier.dismiss(app, notice, tag) }
    }
    override fun onCleared() { model.scope.cancel() }
}

class MainActivity : ComponentActivity() {
    private val holder: Holder by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            val saved = holder.model.saved
            LaunchedEffect(saved.wikis, saved.settings.checkMinutes) { CheckJob.schedule(applicationContext, saved) }
            WikiMopTheme(dynamicColor = saved.settings.dynamicColor) { App(holder.model) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** Goes to what a tapped notification was about. */
    private fun openFrom(intent: Intent?) {
        val wikiId = intent?.getStringExtra(Notifier.EXTRA_WIKI) ?: return
        val notice = Notice.entries.firstOrNull { it.name == intent.getStringExtra(Notifier.EXTRA_NOTICE) } ?: return
        holder.model.openNotice(wikiId, notice)
    }
}
