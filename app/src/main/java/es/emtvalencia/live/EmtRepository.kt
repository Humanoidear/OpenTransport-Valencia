package es.emtvalencia.live

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class EmtRepository(private val data: TransitData) {
    @Volatile private var incidentCache: Pair<Long, List<Incident>>? = null

    suspend fun buses(line: String): List<BusFix> = withContext(Dispatchers.IO) {
        val raw = request("/buses/linea.php", mapOf("usuario" to USER, "linea" to line))
        if (raw.isBlank()) return@withContext emptyList()
        val root = try { JSONObject(raw) } catch (_: Exception) { return@withContext emptyList() }
        val array = root.optJSONArray("buses") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(
                    BusFix(
                        number = item.optInt("num"),
                        line = item.optString("lin", line),
                        destination = item.optString("tra", ""),
                        lastStop = item.optInt("pUlt"),
                        nextStop = item.optInt("pSig"),
                        lon = item.optDouble("lon"),
                        lat = item.optDouble("lat"),
                        timestamp = item.optString("ts", ""),
                        direction = TransitMotion.direction(data.routes, line, item.optInt("pUlt"), item.optInt("pSig")),
                    ),
                )
            }
        }
    }

    suspend fun stop(id: Int): StopInfo? = coroutineScope {
        val stop = data.stopsById[id.toString()] ?: return@coroutineScope null
        val byLine = stop.lines.map { l -> async(Dispatchers.IO) { l to buses(l) } }.awaitAll().toMap()
        val arrivals = mutableListOf<Arrival>()
        val near = mutableListOf<NearBus>()

        for (line in stop.lines) {
            val seqCandidates = byLine[line].orEmpty().mapNotNull { bus ->
                val dir = bus.direction ?: return@mapNotNull null
                val seq = TransitMotion.itinerary(data.routes, line, dir)
                val iBus = seq.indexOf(bus.nextStop.toString())
                val iStop = seq.indexOf(id.toString())
                if (iBus < 0 || iStop < 0 || iBus >= iStop) return@mapNotNull null
                val dist = TransitMotion.distanceMeters(LonLat(bus.lon, bus.lat), LonLat(stop.lon, stop.lat))
                Triple(bus, dir, dist)
            }
            val best = seqCandidates.minByOrNull { it.third } ?: continue
            val (bus, dir, dist) = best
            val minutes = max(1, (dist / 1000.0 / 18.0 * 60.0).roundToInt())
            near += NearBus(line, bus.number, bus.lat, bus.lon, dir, minutes, (dist * 100).roundToInt() / 100.0)
            arrivals += Arrival(line, bus.destination, "$minutes min.", source = "computed")
        }

        val official = try {
            withContext(Dispatchers.IO) {
                parseEstimations(
                    request("/estimaciones/estimacion.php", mapOf(
                        "idioma" to "en", "parada" to id.toString(), "adaptados" to "false",
                    )),
                )
            }
        } catch (_: Exception) { emptyList() }
        val result = official.ifEmpty { arrivals }.sortedBy(::etaMinutes)
        StopInfo(stop, result, near.sortedBy { it.minutes })
    }

    suspend fun incidents(force: Boolean = false): List<Incident> = withContext(Dispatchers.IO) {        val now = System.currentTimeMillis()
        incidentCache?.let { if (!force && now - it.first < INCIDENT_CACHE_MS) return@withContext it.second }
        val html = try {
            requestExternal("https://www.emtvalencia.es/wp/estado-del-servicio/", wsse = false)
        } catch (_: Exception) {
            return@withContext incidentCache?.second.orEmpty()
        }
        parseIncidents(html).also { incidentCache = now to it }
    }

    /** (metres, seconds) walking on OSM streets, cached; straight-line fallback. */
    suspend fun walkMetrics(a: LonLat, b: LonLat): Pair<Double, Double> = withContext(Dispatchers.IO) {
        val key = "${a.lat},${a.lon}->${b.lat},${b.lon}"
        walkCache[key] ?: runCatching {
            val url = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/${a.lon},${a.lat};${b.lon},${b.lat}?overview=false"
            val route = JSONObject(requestExternal(url, wsse = false)).getJSONArray("routes").getJSONObject(0)
            route.getDouble("distance") to route.getDouble("duration")
        }.getOrElse {
            val straight = TransitMotion.distanceMeters(a, b) * 1.25
            straight to straight / 1.25
        }.also { walkCache[key] = it }
    }

    /** Minutes until the next [line] bus at [stopId], or null when unknown. */
    suspend fun nextBus(stopId: Int, line: String): Int? = withContext(Dispatchers.IO) {
        val key = stopId.toString()
        val perLine: Map<String, Int> = etaCache[key] ?: runCatching {
            parseEstimations(
                request("/estimaciones/estimacion.php", mapOf("idioma" to "en", "parada" to key, "adaptados" to "false")),
            ).filter { it.line.isNotBlank() }
                .groupBy { it.line }
                .mapValues { (_, rows) -> rows.minOf { etaMinutes(it) } }
        }.getOrDefault(emptyMap()).also { etaCache[key] = it }
        perLine[line]
    }

    /** Valenbisi stations with live bikes/docks, from JCDecaux's public API. */
    suspend fun valenbisi(): List<Place> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray(requestExternal(VALENBISI_URL, wsse = false))
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val position = item.optJSONObject("position") ?: continue
                    // v3 nests the counts under totalStands.availabilities.
                    val availability = item.optJSONObject("totalStands")?.optJSONObject("availabilities")
                    add(
                        Place(
                            id = "vb-" + item.optInt("number"),
                            network = Network.Valenbisi,
                            name = item.optString("name").replace('_', ' ').trim(),
                            lat = position.optDouble("latitude"),
                            lon = position.optDouble("longitude"),
                            detail = item.optString("address"),
                            available = availability?.optInt("bikes", -1) ?: -1,
                            free = availability?.optInt("stands", -1) ?: -1,
                            open = item.optString("status").equals("OPEN", ignoreCase = true),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Metrovalencia stations (name, lines, zone) from the open GTFS stop list. */
    suspend fun metroStations(): List<Place> = withContext(Dispatchers.IO) {
        runCatching {
            val stops = JSONObject(requestExternal(METRO_STOPS_URL, wsse = false)).optJSONArray("stops") ?: JSONArray()
            buildList {
                for (i in 0 until stops.length()) {
                    val item = stops.optJSONObject(i) ?: continue
                    val lines = item.optJSONArray("lines")?.let { a ->
                        buildList { for (k in 0 until a.length()) add(a.get(k).toString()) }
                    }.orEmpty()
                    add(
                        Place(
                            id = "mt-" + item.optInt("stop_id"),
                            network = Network.Metro,
                            name = item.optString("stop_name"),
                            lat = item.optDouble("stop_lat"),
                            lon = item.optDouble("stop_lon"),
                            lines = lines,
                            detail = item.optString("street_name"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Metrovalencia service alerts, straight from their own site endpoint. */
    suspend fun metroIncidents(): List<Incident> = withContext(Dispatchers.IO) {
        runCatching {
            // The alerts page sets a session cookie the ajax endpoint expects.
            if (java.net.CookieHandler.getDefault() == null) {
                java.net.CookieHandler.setDefault(java.net.CookieManager())
            }
            runCatching {
                (URL(METRO_ALERTS_PAGE).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", METRO_UA)
                    inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    disconnect()
                }
            }
            val connection = URL(METRO_ALERTS_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", METRO_UA)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            connection.setRequestProperty("Origin", "https://www.metrovalencia.es")
            connection.setRequestProperty("Referer", METRO_ALERTS_PAGE)
            connection.outputStream.use { it.write(METRO_ALERTS_BODY.toByteArray()) }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            parseMetroAlerts(text)
        }.getOrDefault(emptyList())
    }

    private fun parseMetroAlerts(json: String): List<Incident> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val html = root.optString("htmlAlertasPrioritarias") + root.optString("htmlAlertas")
        if (html.isBlank()) return emptyList()
        val result = mutableListOf<Incident>()
        // Each alert title; the lines listed just after it belong to it.
        Regex("<h2 class=\"aviso-title\"[^>]*>(.*?)</h2>", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { match ->
            val title = unescapeHtml(
                match.groupValues[1].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim(),
            )
            val chunk = html.substring(match.range.first, minOf(html.length, match.range.first + 4000))
            val lines = Regex("linea-(\\d+)").findAll(chunk).map { it.groupValues[1] }.toSet()
            if (title.isNotBlank()) result += Incident(title, "", lines, Network.Metro)
        }
        return result
    }

    private fun unescapeHtml(value: String) = value
        .replace("&amp;", "&")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    /** Live Metrovalencia arrivals at a station (metroapi wrapper, no auth). */
    suspend fun metroArrivals(stopId: Int): List<Arrival> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$METRO_API/prevision/$stopId/parse").openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", METRO_API_UA)
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            val array = JSONObject(text).optJSONArray("previsiones") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val seconds = item.optInt("seconds", -1)
                    val minutes = if (seconds >= 0) max(1, seconds / 60) else 0
                    add(
                        Arrival(
                            line = "L" + item.optInt("line"),
                            destination = item.optString("destino"),
                            minutes = if (seconds >= 0) "$minutes min." else item.optString("hora").take(5),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private val walkCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Double>>()
    private val etaCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Int>>()

    private fun request(path: String, query: Map<String, String>): String {
        val params = query.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return requestExternal("$HOST$path?$params", wsse = true)
    }

    private fun requestExternal(url: String, wsse: Boolean): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept-Charset", "UTF-8")
        connection.setRequestProperty("User-Agent", if (wsse) USER_AGENT else "Mozilla/5.0 (Android) EMT Valencia")
        if (wsse) {
            connection.setRequestProperty("X-WSSE", WSSE)
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseEstimations(xml: String): List<Arrival> {
        if (xml.isBlank()) return emptyList()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val out = mutableListOf<Arrival>()
        var inBus = false
        var line = ""
        var dest = ""
        var minutes = ""
        var arrivalTime = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "bus" -> { inBus = true; line = ""; dest = ""; minutes = ""; arrivalTime = "" }
                    "linea" -> if (inBus) line = parser.nextText().trim()
                    "destino" -> if (inBus) dest = parser.nextText().trim()
                    "minutos" -> if (inBus) minutes = parser.nextText().trim()
                    "horaLlegada" -> if (inBus) arrivalTime = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name == "bus" && inBus) {
                    if (line.isNotBlank() || dest.isNotBlank() || minutes.isNotBlank()) {
                        out += Arrival(line, dest, minutes, arrivalTime)
                    }
                    inBus = false
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun etaMinutes(a: Arrival): Int {
        val m = a.minutes.trim().lowercase(Locale.ROOT)
        if (m.startsWith("next")) return 0
        // Schedules may come as "hh:mm:ss" (in either field); convert to minutes.
        if (m.contains(":")) parseClock(m)?.let { return it }
        Regex("^(\\d+)").find(m)?.let { return it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE }
        parseClock(a.arrivalTime)?.let { return it }
        return Int.MAX_VALUE
    }

    /** Minutes from now to a "hh:mm[:ss]" clock time. */
    private fun parseClock(value: String): Int? {
        val parts = value.trim().split(":")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val cal = Calendar.getInstance()
        val current = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val target = hour * 60 + minute
        return if (target >= current) target - current else target + 1440 - current
    }

    /** What to show for an arrival: a minute count, never a raw timestamp. */
    fun etaLabel(a: Arrival): String {
        val minutes = etaMinutes(a)
        if (minutes != Int.MAX_VALUE && (a.minutes.isBlank() || a.minutes.contains(":"))) return "$minutes min"
        return a.minutes.ifBlank { a.arrivalTime.take(5).ifBlank { "—" } }
    }

    private fun parseIncidents(html: String): List<Incident> {
        val result = mutableListOf<Incident>()
        val cards = Regex("<section class=\\\"estado-servicio\\\">(.*?)</section>", RegexOption.DOT_MATCHES_ALL)
        for (match in cards.findAll(html)) {
            val section = match.groupValues[1]
            val text = section.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            val date = Regex("Desde:\\s*([0-9/]+)").find(text)?.groupValues?.get(1).orEmpty()
            val lines = Regex("<img[^>]*alt=\\\"L[ií]nea\\s+([A-Za-z0-9]+)")
                .findAll(section).map { normalizeLine(it.groupValues[1]) }.toSet()
            val title = Regex("<h2[^>]*>\\s*<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                .find(section)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim() ?: text.take(160)
            result += Incident(title, date, lines)
        }
        return result
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val HOST = "https://servicios.emtvalencia.es"
        private const val VALENBISI_URL =
            "https://api.jcdecaux.com/vls/v3/stations?apiKey=frifk0jbxfefqqniqez09tw4jvk37wyf823b5j1i&contract=valence"
        private const val METRO_STOPS_URL = "https://www.wiilink24.com/extras/locations.json"
        private const val METRO_ALERTS_URL = "https://www.metrovalencia.es/wp-content/themes/metrovalencia/functions/ajax-no-wp.php"
        private const val METRO_ALERTS_PAGE = "https://www.metrovalencia.es/ca/avisos-i-incidencies/"
        private const val METRO_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:157.0) Gecko/20100101 Firefox/157.0"
        private const val METRO_ALERTS_BODY = "action=formularios_ajax&data=action%3Dcomprobar-usuario%26lang%3Dca"
        private const val METRO_API = "https://metroapi.alexbadi.es"
        private const val METRO_API_UA = "EMT-RealTime/1.0 (api; contact=alex@example.com)"
        private const val USER = "7gH8m45w7A"
        private const val INCIDENT_CACHE_MS = 30 * 60 * 1000L
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 17; Pixel 8a Build/CP41.260831.007)"
        private const val WSSE = "UsernameToken Username=\"7gH8m45w7A\", PasswordDigest=\"ODdmMzU1OWU4ZDEwZGE0MDllM2E5NzhlZTg3Y2UxMmRjYTQ2N2VmYQ==\", Nonce=\"MjZlNTNjMWIxZmZhMmU4NjE5N2QyYjhkMjgyMGU2YjU=\", Created=\"1790762921\""
        private fun normalizeLine(line: String): String {
            val v = line.trim().uppercase(Locale.ROOT)
            return v.toIntOrNull()?.toString() ?: v
        }
    }
}
