package es.emtvalencia.live.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val VALENCIA_CENTER = 39.4699 to -0.3763
private val lineBadgeCache = ConcurrentHashMap<String, Bitmap>()

object StopData {
    private fun repository(context: Context) = WearRepository(WearData.load(context))

    suspend fun resolve(context: Context): NearItem? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_COMP_STOP, null)
        if (key != null && key != COMP_NEAREST) {
            prefs.getStringSet(KEY_FAVS, emptySet()).orEmpty()
                .mapNotNull { decodeFav(it) }
                .firstOrNull { it.key == key }?.let { return it }
        }
        // Background services can't get a fresh GPS fix; fall back to the city centre.
        val loc = rememberLocation(context) ?: VALENCIA_CENTER
        val data = WearData.load(context)
        val stop = WearRepository(data).stopsNear(loc.first, loc.second, 2000.0).firstOrNull()?.stop
            ?: data.stops.minByOrNull { WearData.distance(loc.first, loc.second, it.lat, it.lon) }
            ?: return null
        return NearItem(
            key = "emt-${stop.id}", name = stop.name, detail = stop.lines.joinToString(" · "),
            lat = stop.lat, lon = stop.lon, service = Svc.Emt, lines = stop.lines, stopId = stop.id,
        )
    }

    suspend fun arrivals(context: Context, item: NearItem): List<Live> {
        val repo = repository(context)
        return when (item.service) {
            Svc.Emt -> runCatching { repo.arrivals(item.stopId) }.getOrDefault(emptyList())
            Svc.Metro -> runCatching { repo.metroArrivals(item.stopId) }.getOrDefault(emptyList())
            Svc.Metrobus -> runCatching { repo.metrobusOccupancy(item.key.removePrefix("mb-")) }.getOrDefault(emptyList())
            Svc.Rodalies -> runCatching { WearData.rodaliesTimes(context, item.name) }.getOrDefault(emptyList())
            Svc.Valenbisi -> emptyList()
        }
    }

    /** Line badge: geoportal generator for EMT, bundled PNG for Metrovalencia. */
    suspend fun lineBadge(context: Context, line: String, service: Svc): Bitmap? {
        lineBadgeCache[line]?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                if (service == Svc.Metro) {
                    val num = line.removePrefix("M").removePrefix("L")
                    context.assets.open("metrovalencia/$num.png").use { BitmapFactory.decodeStream(it) }
                } else {
                    val url = "https://geoportal.emtvalencia.es/ciudadano/icongenerator/create-line-image.php" +
                        "?size=50&type=normal&lineNumber=${URLEncoder.encode(line, "UTF-8")}" +
                        "&showBorder=false&borderColor=white"
                    (URL(url).openConnection() as HttpURLConnection).run {
                        connectTimeout = 8000
                        readTimeout = 8000
                        try {
                            inputStream.use { BitmapFactory.decodeStream(it) }
                        } finally {
                            disconnect()
                        }
                    }
                }
            }.getOrNull()
        }
        loaded?.let { lineBadgeCache[line] = it }
        return loaded
    }
}

