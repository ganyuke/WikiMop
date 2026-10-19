package org.nextcolor.wikimop.notify

import org.nextcolor.wikimop.data.Change
import org.nextcolor.wikimop.ui.logVerb
import java.text.NumberFormat

/** What an edit did, for the tags that say it better than "edited". [%s] is the page. Earlier ones win when an edit has several. */
private val tagged = listOf(
    "mw-rollback" to "rolled back edits on %s",
    "mw-undo" to "undid an edit on %s",
    "mw-blank" to "blanked %s",
    "mw-replace" to "replaced the content of %s",
    "mw-new-redirect" to "turned %s into a redirect",
    "mw-removed-redirect" to "turned %s back into a page",
    "mw-changed-redirect-target" to "changed where %s redirects",
)

/**
 * A change told as one sentence, such as "StardustReader edited Elaina (+102): "Added her relationships"".
 * A log entry reads as it does in the list of changes, without its summary.
 */
fun Change.sentence(): String {
    if (isLog) {
        val target = if (logType in setOf("block", "rights", "newusers")) title.substringAfter(':') else title
        return "$user ${logVerb(this)} $target"
    }
    val what = when {
        isNew -> "created %s"
        else -> tagged.firstOrNull { it.first in tags }?.second ?: "edited %s"
    }
    val size = delta?.let { " (${signed(it)})" }.orEmpty()
    val summary = comment.takeIf { it.isNotBlank() }?.let { ": \"$it\"" }.orEmpty()
    return "$user ${what.format(title + size)}$summary"
}

private fun signed(delta: Int): String {
    val n = NumberFormat.getIntegerInstance().format(kotlin.math.abs(delta))
    return when {
        delta > 0 -> "+$n"
        delta < 0 -> "−$n"
        else -> "0"
    }
}
