package es.emtvalencia.live

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * The official EMT line badge, straight from the geoportal icon generator the
 * web app uses, cached in memory. Null while loading or when offline, so
 * callers fall back to a plain coloured chip.
 */
private val badgeCache = ConcurrentHashMap<String, Bitmap>()
private val metroBadgeCache = ConcurrentHashMap<String, Bitmap>()

/** Bundled Metrovalencia line badge (`assets/metrovalencia/<line>.png`). */
@Composable
fun rememberMetroBadge(line: String): Bitmap? {
    val context = LocalContext.current
    return remember(line) {
        metroBadgeCache[line] ?: runCatching {
            context.assets.open("metrovalencia/$line.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.also { metroBadgeCache[line] = it }
    }
}

@Composable
fun rememberLineBadge(line: String): Bitmap? {
    var bitmap by remember(line) { mutableStateOf(badgeCache[line]) }
    LaunchedEffect(line) {
        if (badgeCache[line] == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "https://geoportal.emtvalencia.es/ciudadano/icongenerator/create-line-image.php" +
                        "?size=50&type=normal&lineNumber=${URLEncoder.encode(line, "UTF-8")}" +
                        "&showBorder=false&borderColor=white"
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    try {
                        connection.inputStream.use { BitmapFactory.decodeStream(it) }
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
            if (loaded != null) badgeCache[line] = loaded
            bitmap = loaded
        }
    }
    return bitmap
}
