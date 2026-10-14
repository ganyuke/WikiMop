package org.nextcolor.wikimop.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import org.nextcolor.wikimop.logic.SyntaxColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Seed = Color(0xFF00696E)

/** Colors for added and removed text. They mean the same thing whatever the wallpaper colors are. */
data class DiffColors(
    val added: Color, val addedMark: Color, val onAdded: Color,
    val removed: Color, val removedMark: Color, val onRemoved: Color,
    val plus: Color, val minus: Color,
)

private val LightDiff = DiffColors(
    added = Color(0xFFE3F6E5), addedMark = Color(0xFFA5E3AE), onAdded = Color(0xFF0B2A12),
    removed = Color(0xFFFFE9E6), removedMark = Color(0xFFFFB3AA), onRemoved = Color(0xFF3B0A06),
    plus = Color(0xFF1B6B2C), minus = Color(0xFFB3261E),
)
private val DarkDiff = DiffColors(
    added = Color(0xFF14301B), addedMark = Color(0xFF2B6B3A), onAdded = Color(0xFFD5F2D9),
    removed = Color(0xFF3A1614), removedMark = Color(0xFF8A2C25), onRemoved = Color(0xFFFFDAD6),
    plus = Color(0xFF86D992), minus = Color(0xFFFFB4AB),
)

val LocalDiffColors = staticCompositionLocalOf { LightDiff }

/**
 * Material 3 Expressive theme. On Android 12 and later the colors follow the
 * wallpaper like the rest of the system, unless [dynamicColor] is off.
 */
@Composable
fun WikiMopTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        rememberDynamicColorScheme(seedColor = Seed, isDark = dark, style = PaletteStyle.TonalSpot)
    }
    CompositionLocalProvider(LocalDiffColors provides if (dark) DarkDiff else LightDiff) {
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
    }
}

@Composable
fun syntaxColors(): SyntaxColors {
    val c = MaterialTheme.colorScheme
    return SyntaxColors(template = c.tertiary, link = c.primary, tag = c.secondary, comment = c.outline, mark = c.outline, heading = c.onSurface)
}

private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val day = DateTimeFormatter.ofPattern("d MMMM")
private val dayYear = DateTimeFormatter.ofPattern("d MMMM yyyy")
private val full = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

fun Instant.clock(zone: ZoneId = ZoneId.systemDefault()): String = atZone(zone).format(clock)
fun Instant.full(zone: ZoneId = ZoneId.systemDefault()): String = atZone(zone).format(full)

fun Instant.dayName(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val date = atZone(zone).toLocalDate()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(day)
        else -> date.format(dayYear)
    }
}

fun timeLeft(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}

/** A wiki's own timestamp for an expiry, or the words it uses for no end. */
fun expiryText(expiry: String): String = when (expiry) {
    "", "infinity", "infinite", "indefinite", "never" -> "Indefinite"
    else -> try { Instant.parse(expiry).full() } catch (e: Exception) { expiry }
}
