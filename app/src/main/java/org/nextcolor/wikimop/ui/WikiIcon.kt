package org.nextcolor.wikimop.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nextcolor.wikimop.AppModel
import org.nextcolor.wikimop.data.USER_AGENT
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Icons already looked up, by address. A null entry is one that could not be loaded, so it is not asked for again. */
private val icons = mutableStateMapOf<String, LoadedIcon?>()

/** [ink] is how bright the drawn part of a see-through icon is, from 0 to 1. It is null for icons that fill their square. */
class LoadedIcon(val image: ImageBitmap, val ink: Float?)

/**
 * The average brightness of the visible pixels, when at least a tenth of the icon is
 * see-through. A logo like that takes on the color behind it, so it needs checking.
 */
internal fun inkLuminance(bitmap: Bitmap): Float? {
    val step = maxOf(1, maxOf(bitmap.width, bitmap.height) / 64)
    var clear = 0
    var total = 0
    var sum = 0.0
    var weight = 0.0
    for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
        val p = bitmap.getPixel(x, y)
        val alpha = (p ushr 24) / 255.0
        total++
        if (alpha < 0.5) clear++
        sum += alpha * Color(p).copy(alpha = 1f).luminance()
        weight += alpha
    }
    return if (total == 0 || clear * 10 < total || weight == 0.0) null else (sum / weight).toFloat()
}

/** How far apart two brightnesses are, as WCAG measures contrast, from 1 to 21. */
private fun contrast(a: Float, b: Float) = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

/** Favicons are small. Anything bigger than this is not one. */
private const val MAX_ICON_BYTES = 2 * 1024 * 1024

/** A wiki's own icon, or the first letter of its name until the icon is there or when it has none. */
@Composable
fun WikiIcon(model: AppModel, name: String, url: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val cache = File(LocalContext.current.cacheDir, "icons")
    if (url != null && url !in icons) {
        LaunchedEffect(url) { icons[url] = withContext(Dispatchers.IO) { fetchIcon(model.http, cache, url) } }
    }
    val shape = RoundedCornerShape(percent = 28)
    val icon = url?.let { icons[it] }
    val scheme = MaterialTheme.colorScheme
    if (icon != null && icon.ink != null && contrast(icon.ink, scheme.surface.luminance()) < 2f) {
        // A dark logo on a dark screen, or a light one on a light screen, sits on a tile of the opposite shade.
        Box(modifier.size(size).background(scheme.inverseSurface, shape), Alignment.Center) {
            Image(icon.image, null, Modifier.size(size * 0.72f), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
        }
    } else if (icon != null) {
        Image(icon.image, null, modifier.size(size).clip(shape), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
    } else {
        Box(modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, shape), Alignment.Center) {
            Text(
                name.trim().take(1).uppercase(), Modifier.clearAndSetSemantics {},
                color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = (size.value * 0.45f).sp,
            )
        }
    }
}

/** Reads an icon from the phone if it was fetched before, and from the wiki otherwise. */
internal fun fetchIcon(http: OkHttpClient, dir: File, url: String): LoadedIcon? = try {
    val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
    val file = File(dir, name)
    val bytes = if (file.isFile) file.readBytes() else {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null || body.contentLength() > MAX_ICON_BYTES) null
            else body.source().run { request(MAX_ICON_BYTES + 1L); if (buffer.size > MAX_ICON_BYTES) null else buffer.readByteArray() }
        }
    }
    val bitmap = bytes?.let(::decodeIcon)
    if (bitmap != null && !file.isFile) { dir.mkdirs(); file.writeBytes(bytes) }
    if (bitmap == null) file.delete()
    bitmap?.let { LoadedIcon(it.asImageBitmap(), inkLuminance(it)) }
} catch (e: Exception) {
    null
}

/** Decodes PNG, JPEG, WebP and GIF as Android does, and .ico files by picking the largest picture inside. */
internal fun decodeIcon(bytes: ByteArray): Bitmap? {
    if (bytes.size > 6 && bytes[0].toInt() == 0 && bytes[1].toInt() == 0 && bytes[2].toInt() == 1 && bytes[3].toInt() == 0) {
        decodeIco(bytes)?.let { return it }
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

/**
 * An .ico file is a list of pictures, each either a whole PNG or a Windows bitmap
 * without its file header. Only 32-bit bitmaps are read here, which is what
 * favicons with transparency use. Anything else is left to Android to try.
 */
private fun decodeIco(bytes: ByteArray): Bitmap? = try {
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val count = data.getShort(4).toInt() and 0xffff
    val best = (0 until count).map { 6 + it * 16 }.maxByOrNull { entry ->
        // A width of 0 stands for 256.
        val width = (data.get(entry).toInt() and 0xff).let { if (it == 0) 256 else it }
        width * 100 + (data.getShort(entry + 6).toInt() and 0xffff)
    }
    if (best == null) null else {
        val length = data.getInt(best + 8)
        val start = data.getInt(best + 12)
        if (data.getInt(start) == 0x474e5089) BitmapFactory.decodeByteArray(bytes, start, length)
        else {
            val width = data.getInt(start + 4)
            val height = data.getInt(start + 8) / 2 // The stored height also counts the transparency mask below the picture.
            val bits = data.getShort(start + 14).toInt()
            val compression = data.getInt(start + 16)
            if (data.getInt(start) != 40 || bits != 32 || compression != 0 || width !in 1..512 || height !in 1..512) null else {
                val pixels = IntArray(width * height)
                // Rows are stored bottom to top, as blue, green, red, alpha, which reads as one little-endian ARGB int.
                for (y in 0 until height) for (x in 0 until width) {
                    pixels[(height - 1 - y) * width + x] = data.getInt(start + 40 + (y * width + x) * 4)
                }
                // Some 32-bit icons leave alpha at zero everywhere and rely on the mask. Those are shown opaque.
                if (pixels.all { it ushr 24 == 0 }) for (i in pixels.indices) pixels[i] = pixels[i] or (0xff shl 24)
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            }
        }
    }
} catch (e: Exception) {
    null
}
