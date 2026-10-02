package es.emtvalencia.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking

data class WidgetPalette(
    val container: Int,
    val onContainer: Int,
    val accent: Int,
    val variant: Int,
    val dark: Boolean,
)

object WidgetShared {
    @Volatile var cachedStopId: Int = -1
    @Volatile var cachedArrivals: List<Arrival> = emptyList()

    fun palette(context: Context): WidgetPalette {
        val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        fun sys(res: Int, fallback: Int) =
            if (android.os.Build.VERSION.SDK_INT >= 31) runCatching { context.getColor(res) }.getOrElse { fallback } else fallback
        val container = if (dark) sys(android.R.color.system_accent1_900, 0xFF062E6F.toInt()) else sys(android.R.color.system_accent1_100, 0xFFD3E3FD.toInt())
        val onContainer = if (dark) sys(android.R.color.system_accent1_100, 0xFFD3E3FD.toInt()) else sys(android.R.color.system_accent1_900, 0xFF041E49.toInt())
        val accent = sys(android.R.color.system_accent1_600, 0xFF0B57D0.toInt())
        val variant = if (dark) 0xFFB6C2CF.toInt() else sys(android.R.color.system_accent1_700, 0xFF0842A0.toInt())
        return WidgetPalette(container, onContainer, accent, variant, dark)
    }

    fun arrivals(context: Context, stopId: Int, network: Network): List<Arrival> {
        if (stopId <= 0 || network != Network.Emt) return emptyList()
        val transit = runCatching { TransitDataLoader.load(context) }.getOrNull() ?: return emptyList()
        return runBlocking { runCatching { EmtRepository(transit).stop(stopId) }.getOrNull()?.arrivals.orEmpty() }
    }

    private val badgeCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    fun lineBadge(context: Context, line: String): Bitmap? {
        badgeCache[line]?.let { return it }
        val fetched = if (line.startsWith("MB")) {
            null
        } else if (line.startsWith("M") && line.length > 1 && line.drop(1).all { it.isDigit() }) {
            runCatching {
                context.assets.open("metrovalencia/${line.drop(1)}.png").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        } else {
            runCatching {
                val url = "https://geoportal.emtvalencia.es/ciudadano/icongenerator/create-line-image.php" +
                    "?size=50&type=normal&lineNumber=${java.net.URLEncoder.encode(line, "UTF-8")}" +
                    "&showBorder=false&borderColor=white"
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        fetched?.let { badgeCache[line] = it }
        return fetched
    }

    fun serviceLogo(context: Context, network: Network): Bitmap? = runCatching {
        val asset = when (network) {
            Network.Emt -> "emt.png"
            Network.Metro -> "metrovalencia.png"
            Network.Valenbisi -> "valenbisi.png"
            Network.Metrobus -> "metrobus.png"
            Network.Rodalies -> "rodalies.png"
        }
        context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
