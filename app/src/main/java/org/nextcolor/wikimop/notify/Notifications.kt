package org.nextcolor.wikimop.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nextcolor.wikimop.MainActivity
import org.nextcolor.wikimop.R
import org.nextcolor.wikimop.WikiMopApp
import org.nextcolor.wikimop.data.Saved

/** Puts up and takes down the notifications that [Checker] asks for. */
object Notifier {
    const val EXTRA_WIKI = "wiki"
    const val EXTRA_NOTICE = "notice"

    private val names = mapOf(
        Notice.CHANGES to "New changes",
        Notice.WATCHLIST to "Watchlist",
        Notice.STREAK to "Streak reminder",
        Notice.SIGNED_OUT to "Sign-in",
    )

    /** False on Android 13 and later until notifications have been allowed for the app. */
    fun allowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, post: Post) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val text = post.text
        if (text == null) {
            manager.cancel(post.tag, post.notice.id)
            if (post.notice.grouped) tidy(manager, post.wikiId)
            return
        }
        if (!allowed(context)) return
        val channel = post.notice.name.lowercase()
        manager.createNotificationChannel(NotificationChannel(channel, names.getValue(post.notice), NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_WIKI, post.wikiId).putExtra(EXTRA_NOTICE, post.notice.name)
        // Each notification needs a request code of its own, or they would all open the same thing.
        val tap = PendingIntent.getActivity(
            context, (post.tag + post.notice.name).hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // A single change is said in full. Several are counted, and listed once the notification is opened up.
        val single = post.lines.singleOrNull()
        val built = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(post.title)
            .setContentText(single ?: text)
            .setSubText(post.wikiName)
            .setContentIntent(tap)
            .setAutoCancel(true)
            // A count that goes up while the notification is still there changes quietly.
            .setOnlyAlertOnce(true)
        when {
            single != null -> built.setStyle(Notification.BigTextStyle().bigText(single))
            // The count stays in the header once the lines take its place.
            post.lines.isNotEmpty() -> built.setStyle(Notification.InboxStyle().setSummaryText(text).also { style -> post.lines.forEach(style::addLine) })
        }
        // The streak reminder is about the person, not news from the wiki, so it looks different and stays out of the wiki's group.
        if (post.notice == Notice.STREAK) built.setSmallIcon(R.drawable.ic_streak).setColor(STREAK_COLOR)
        if (post.notice.grouped) built.setGroup(group(post.wikiId))
        post.until?.let { built.setTimeoutAfter(maxOf(1, it.toEpochMilli() - System.currentTimeMillis())) }
        manager.notify(post.tag, post.notice.id, built.build())
        if (post.notice.grouped) summary(context, manager, channel, post, tap)
    }

    /**
     * The header that holds a wiki's changes and watchlist together, so each wiki is a stack of its own.
     * It does not alert by itself. The notifications in it do.
     */
    private fun summary(context: Context, manager: NotificationManager, channel: String, post: Post, tap: PendingIntent) {
        val name = post.wikiName ?: post.title
        val built = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setSubText(name)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setGroup(group(post.wikiId))
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
        manager.notify(post.wikiId, SUMMARY_ID, built.build())
    }

    /** Takes a wiki's header down once nothing is left under it. */
    private fun tidy(manager: NotificationManager, wikiId: String) {
        val left = manager.activeNotifications.any { it.notification.group == group(wikiId) && it.id != SUMMARY_ID }
        if (!left) manager.cancel(wikiId, SUMMARY_ID)
    }

    fun dismiss(context: Context, notice: Notice, tag: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(tag, notice.id)
        if (notice.grouped) tidy(manager, tag)
    }

    private val Notice.id get() = ordinal + 1
    /** Changes and the watchlist are news from a wiki, and are kept together by wiki. */
    private val Notice.grouped get() = this == Notice.CHANGES || this == Notice.WATCHLIST
    private fun group(wikiId: String) = "wiki:$wikiId"
    private const val SUMMARY_ID = 100
    /** Warm, like a flame, and unlike the app's teal. */
    private const val STREAK_COLOR = 0xFFE8710A.toInt()
}

/** Runs the checks every so often while any wiki has notifications on, also after the phone restarts. */
class CheckJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as WikiMopApp
        running = scope.launch {
            try {
                val posts = Checker(app.store, app.http).run()
                posts.forEach { Notifier.show(app, it) }
                withContext(Dispatchers.IO) { app.store.flush() }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }

    companion object {
        private const val ID = 1
        val INTERVALS = listOf(15, 30, 60, 120)

        /** Brings the schedule in line with the settings. Safe to call at any time. */
        fun schedule(context: Context, saved: Saved) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val wanted = saved.wikis.any { it.notifying }
            val every = saved.settings.checkMinutes * 60_000L
            val pending = scheduler.getPendingJob(ID)
            if (!wanted) {
                if (pending != null) scheduler.cancel(ID)
                return
            }
            // Scheduling again starts the wait over, so a schedule that is already right is left alone.
            if (pending != null && pending.intervalMillis == every) return
            scheduler.schedule(
                JobInfo.Builder(ID, ComponentName(context, CheckJob::class.java))
                    .setPeriodic(every)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
