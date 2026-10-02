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
                            mechanical = availability?.optInt("mechanicalBikes", -1) ?: -1,
                            electric = availability?.optInt("electricalBikes", -1) ?: -1,
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
                        buildList { for (k in 0 until a.length()) add("M" + a.get(k).toString()) }
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

    /** Official FGV incident feeds, joined with their translations for detail. */
    suspend fun fgvIncidents(): List<Incident> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Incident>()
        // lineId -> line number, and stationId -> station name (from the geojson).
        val lineNames = fgvLineNames()
        val stationNames = metroStationNames()
        runCatching {
            val root = JSONObject(getJson("$FGV_API/incidencias"))
            val translations = translationsByIncident(root.optJSONArray("incidencias_translations"))
            val array = root.optJSONArray("incidencias")
            val seen = mutableSetOf<String>()
            for (i in 0 until (array?.length() ?: 0)) {
                val item = array!!.optJSONObject(i) ?: continue
                val lineId = item.optInt("linea_id")
                val text = translations[item.optInt("id")].orEmpty()
                val line = lineNames[lineId] ?: lineId.toString()
                if (seen.add("$line:$text")) {
                    out += Incident(
                        title = text.ifBlank { "Metrovalencia service incident" },
                        date = "",
                        lines = setOf(line),
                        network = Network.Metro,
                        detail = if (text.isBlank()) "" else "Line $line",
                    )
                }
            }
        }
        runCatching {
            val root = JSONObject(getJson("$FGV_API/incidencias_accesibilidad"))
            val translations = translationsByIncident(root.optJSONArray("incidencias_accesibilidad_translations"))
            val array = root.optJSONArray("incidencias_accesibilidad")
            val seen = mutableSetOf<Int>()
            for (i in 0 until (array?.length() ?: 0)) {
                val item = array!!.optJSONObject(i) ?: continue
                val stationId = item.optInt("estacion_id")
                val text = translations[item.optInt("id")]
                val station = stationNames[stationId] ?: "Station $stationId"
                if (seen.add(stationId)) {
                    out += Incident(
                        title = text?.substringBefore("||").orEmpty().ifBlank { "Accessibility issue" },
                        date = "",
                        lines = emptySet(),
                        network = Network.Metro,
                        detail = listOfNotNull(text?.substringAfter("||", "")?.ifBlank { null }, station).joinToString(" · "),
                    )
                }
            }
        }
        out
    }

    /** EN translation per incident id; "title||description". */
    private fun translationsByIncident(array: org.json.JSONArray?): Map<Int, String> {
        if (array == null) return emptyMap()
        val out = mutableMapOf<Int, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (!item.optString("locale").equals("EN", ignoreCase = true)) continue
            val title = item.optString("titulo").trim()
            val description = item.optString("descripcion").trim()
            out[item.optInt("incidencia_id")] = "$title||$description"
        }
        return out
    }

    private var fgvLineCache: Map<Int, String>? = null

    /** FGV line_id -> public line number, from the bundled metro geojson. */
    private fun fgvLineNames(): Map<Int, String> {
        fgvLineCache?.let { return it }
        // Best effort: FGV line ids 42..51 map to metro lines 1..10 (sede V).
        val map = (42..51).mapIndexed { index, id -> id to (index + 1).toString() }.toMap()
        fgvLineCache = map
        return map
    }

    private var metroNamesCache: Map<Int, String>? = null

    /** Metro station names keyed by their GTFS stop_id, from assets/metrovalencia.geojson. */
    private fun metroStationNames(): Map<Int, String> {
        metroNamesCache?.let { return it }
        val text = runCatching { transitAsset("metrovalencia.geojson") }.getOrNull() ?: return emptyMap()
        val features = runCatching { JSONObject(text).optJSONArray("features") }.getOrNull() ?: return emptyMap()
        val map = mutableMapOf<Int, String>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            if (feature.optJSONObject("geometry")?.optString("type") != "Point") continue
            val props = feature.optJSONObject("properties") ?: continue
            props.optString("stop_id").toIntOrNull()?.let { map[it] = props.optString("stop_name") }
        }
        metroNamesCache = map
        return map
    }

    private var assetContext: android.content.Context? = null
    fun attach(context: android.content.Context) { assetContext = context.applicationContext }
    private fun transitAsset(name: String) = assetContext!!.assets.open(name).bufferedReader().use { it.readText() }

    /** Upcoming Rodalies departures, keyed by station name (bundled GTFS table). */
    fun rodaliesTimes(stopName: String): List<Arrival> {
        val text = runCatching { transitAsset("rodalies_times.json") }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val list = root.optJSONArray(stopName) ?: return emptyList()
        val now = java.util.Calendar.getInstance()
        val nowMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val out = mutableListOf<Pair<Int, Arrival>>()
        for (i in 0 until list.length()) {
            val parts = list.optString(i).split("|")
            if (parts.size < 2) continue
            val time = parts[0].split(":")
            val minutes = (time.getOrNull(0)?.toIntOrNull() ?: continue) * 60 + (time.getOrNull(1)?.toIntOrNull() ?: 0)
            val diff = if (minutes >= nowMinute) minutes - nowMinute else minutes + 1440 - nowMinute
            if (diff <= 180) out += diff to Arrival(parts[1], parts.getOrElse(2) { "" }, "$diff min")
        }
        return out.sortedBy { it.first }.take(8).map { it.second }
    }

    /** Metrobús service notices, scraped from metgovalencia.com (title + link). */
    suspend fun metrobusIncidents(): List<Incident> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://metgovalencia.com/avisos-de-lineas/").openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            val out = mutableListOf<Incident>()
            val seen = mutableSetOf<String>()
            Regex("<a [^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(html).forEach { match ->
                    val href = match.groupValues[1]
                    val text = match.groupValues[2].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
                    val looksLikeNotice = href.contains("/wp-content/uploads/") && text.length > 4
                    if (looksLikeNotice && seen.add(text)) {
                        out += Incident(title = text, date = "", lines = emptySet(), network = Network.Metrobus, url = href)
                    }
                }
            out
        }.getOrDefault(emptyList())
    }

    /** Renfe Cercanías / Rodalies service alerts (GTFS-RT JSON). */
    suspend fun rodaliesIncidents(): List<Incident> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(getJson("https://gtfsrt.renfe.com/alerts.json"))
            val entities = root.optJSONArray("entity") ?: return@runCatching emptyList()
            val out = mutableListOf<Incident>()
            for (i in 0 until entities.length()) {
                val alert = entities.optJSONObject(i)?.optJSONObject("alert") ?: continue
                // Keep only Valencian Community Cercanías (route ids like 40T…).
                val informed = alert.optJSONArray("informedEntity")
                val inVC = informed != null && (0 until informed.length()).any { k ->
                    informed.optJSONObject(k)?.optString("routeId").orEmpty().startsWith("40T")
                }
                if (!inVC) continue
                val translations = alert.optJSONObject("descriptionText")?.optJSONArray("translation")
                var text = ""
                if (translations != null) {
                    for (t in 0 until translations.length()) {
                        val tr = translations.optJSONObject(t) ?: continue
                        val value = tr.optString("text")
                        if (tr.optString("language").startsWith("es") || text.isBlank()) text = value
                    }
                }
                val header = alert.optJSONObject("headerText")?.optJSONArray("translation")?.optJSONObject(0)?.optString("text").orEmpty()
                val title = header.ifBlank { text.take(120) }
                if (title.isNotBlank()) {
                    out += Incident(title = title, date = "", lines = emptySet(), network = Network.Rodalies, detail = text.take(240))
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    /** Rodalies stations + route shapes bundled in assets/rodalies.geojson. */
    suspend fun rodaliesNetwork(baseId: Int): Pair<List<Stop>, Map<String, BusRoute>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = transitAsset("rodalies.geojson")
            val features = JSONObject(text).optJSONArray("features") ?: JSONArray()
            val stations = LinkedHashMap<String, Stop>()
            val lineStrings = mutableListOf<JSONObject>()
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val geometry = feature.optJSONObject("geometry") ?: continue
                val props = feature.optJSONObject("properties") ?: JSONObject()
                when (geometry.optString("type")) {
                    "Point" -> {
                        val coords = geometry.optJSONArray("coordinates") ?: continue
                        val name = props.optString("stop_name")
                        if (name.isBlank()) continue
                        val lines = props.optJSONArray("lines")?.let { a ->
                            buildList { for (k in 0 until a.length()) add("C" + a.getString(k).removePrefix("C")) }
                        }.orEmpty()
                        stations[name] = Stop(baseId + stations.size, name, lines, coords.optDouble(1), coords.optDouble(0), metro = true)
                    }
                    "LineString" -> lineStrings += feature
                }
            }
            val lineStations = HashMap<String, MutableList<String>>()
            lineStrings.forEach { feature ->
                val props = feature.optJSONObject("properties") ?: return@forEach
                val line = "C" + props.optString("line").removePrefix("C")
                val names = props.optJSONArray("stops")?.let { a -> buildList { for (k in 0 until a.length()) add(a.getString(k)) } }.orEmpty()
                lineStations.getOrPut(line) { mutableListOf() } += names
            }
            val routes = mutableMapOf<String, BusRoute>()
            lineStrings.groupBy { "C" + (it.optJSONObject("properties")?.optString("line") ?: "").removePrefix("C") }
                .forEach { (line, group) ->
                    val dirs = group.take(2).map { feature ->
                        val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                        val shape = buildList {
                            if (coords != null) for (j in 0 until coords.length()) {
                                val p = coords.optJSONArray(j) ?: continue
                                add(LonLat(p.optDouble(0), p.optDouble(1)))
                            }
                        }
                        val names = feature.optJSONObject("properties")?.optJSONArray("stops")?.let { a ->
                            buildList { for (k in 0 until a.length()) add(a.getString(k)) }
                        }.orEmpty()
                        RouteDirection(listOf(shape), names.mapNotNull { stations[it]?.id?.toString() })
                    }
                    if (dirs.isNotEmpty()) routes[line] = BusRoute(dirs[0], dirs.getOrElse(1) { dirs[0] })
                }
            stations.values.toList() to routes
        }.getOrElse { emptyList<Stop>() to emptyMap<String, BusRoute>() }
    }

    private fun getJson(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "okhttp/4.10.0")
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Metrobús stops within [radius] metres of a point (Softour API). */
    suspend fun metrobusStops(lat: Double, lon: Double, radius: Int = 7000): List<Place> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$METROBUS_API/stops/near").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            metrobusHeaders(connection)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"radius":$radius,"lat":$lat,"lon":$lon}""".toByteArray()) }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            val array = JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val name = item.optString("stop_name")
                    if (name.isBlank()) continue
                    add(
                        Place(
                            id = "mb-" + item.optString("stop_code"),
                            network = Network.Metrobus,
                            name = name,
                            lat = item.optDouble("stop_lat"),
                            lon = item.optDouble("stop_lon"),
                            detail = item.optString("concesion"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Metrobús next arrivals at a stop code, from the day's timetable. */
    suspend fun metrobusTimes(stopCode: String): List<Arrival> = withContext(Dispatchers.IO) {
        runCatching {
            val date = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
            val text = metrobusGet("$METROBUS_API/stops/code/$stopCode/times?date=$date")
            val root = JSONObject(text)
            val now = java.util.Calendar.getInstance()
            val nowMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
            val out = mutableListOf<Pair<Int, Arrival>>()
            val hours = root.keys()
            while (hours.hasNext()) {
                val key = hours.next()
                val hour = key.toIntOrNull() ?: continue
                val list = root.optJSONArray(key) ?: continue
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val minute = item.optString("minute").toIntOrNull() ?: continue
                    val diff = hour * 60 + minute - nowMinute
                    if (diff < 0) continue
                    out += diff to Arrival("MB" + item.optString("route_short_name"), item.optString("direction"), "$diff min.")
                }
            }
            out.sortedBy { it.first }.take(8).map { it.second }
        }.getOrDefault(emptyList())
    }

    /** Metrobús live occupancy + next arrival per line at a stop code. */
    suspend fun metrobusOccupancy(stopCode: String): List<Arrival> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray(metrobusGet("$METROBUS_API/estimacion/ocupacion/$stopCode"))
            val out = mutableListOf<Pair<Int, Arrival>>()
            for (i in 0 until array.length()) {
                val lineObj = array.optJSONObject(i) ?: continue
                val estimation = lineObj.optJSONArray("estimations")?.optJSONObject(0) ?: continue
                val minutes = estimation.optInt("minutesToArrival", -1)
                if (minutes < 0) continue
                val occupancy = estimation.optString("ocupacion")
                out += minutes to Arrival(
                    "MB" + lineObj.optString("line"),
                    lineObj.optString("route"),
                    if (occupancy.isBlank()) "$minutes min" else "$minutes min · $occupancy",
                )
            }
            out.sortedBy { it.first }.map { it.second }
        }.getOrDefault(emptyList())
    }

    /** The whole day's Metrobús timetable for a stop code. */
    suspend fun metrobusSchedule(stopCode: String): List<MetrobusTime> = withContext(Dispatchers.IO) {
        runCatching {
            val date = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
            val root = JSONObject(metrobusGet("$METROBUS_API/stops/code/$stopCode/times?date=$date"))
            val out = mutableListOf<MetrobusTime>()
            val hours = root.keys()
            while (hours.hasNext()) {
                val key = hours.next()
                val hour = key.toIntOrNull() ?: continue
                val list = root.optJSONArray(key) ?: continue
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val minute = item.optString("minute").toIntOrNull() ?: continue
                    out += MetrobusTime(hour, minute, "MB" + item.optString("route_short_name"), item.optString("direction"))
                }
            }
            out.sortedWith(compareBy({ it.hour }, { it.minute }))
        }.getOrDefault(emptyList())
    }

    /** All Metrobús route shapes as one GeoJSON string, coloured by route. */
    suspend fun metrobusRoutesGeoJson(): String = withContext(Dispatchers.IO) {
        runCatching {
            val routes = JSONArray(metrobusGet("$METROBUS_API/old/routes"))
            val features = JSONArray()
            for (i in 0 until routes.length()) {
                val route = routes.optJSONObject(i) ?: continue
                val id = route.optString("route_id")
                val concesion = route.optString("concesion")
                val color = "#" + route.optString("route_color").ifBlank { "0EA5E9" }
                for (direction in 0..1) {
                    val collection = runCatching {
                        JSONObject(metrobusGet("$METROBUS_API/routes/$id/shapes?direction=$direction&concesion=$concesion"))
                    }.getOrNull() ?: continue
                    val list = collection.optJSONArray("features") ?: continue
                    for (k in 0 until list.length()) {
                        val feature = list.optJSONObject(k) ?: continue
                        feature.put("properties", (feature.optJSONObject("properties") ?: JSONObject()).put("color", color).put("line", "MB" + route.optString("route_short_name")))
                        features.put(feature)
                    }
                }
            }
            JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        }.getOrElse { EMPTY_COLLECTION }
    }

    /** Metrobús stops + route sequences (both directions), for the planner. */
    suspend fun metrobusNetwork(baseId: Int): Pair<List<Stop>, Map<String, BusRoute>> = withContext(Dispatchers.IO) {
        runCatching {
            val routes = JSONArray(metrobusGet("$METROBUS_API/old/routes"))
            val meta = LinkedHashMap<Int, Triple<String, Double, Double>>()
            val linesAt = HashMap<Int, MutableSet<String>>()
            val codeToId = HashMap<String, Int>()
            var nextId = baseId
            fun stopId(code: String, name: String, lat: Double, lon: Double): Int =
                codeToId.getOrPut(code) {
                    val id = nextId++
                    meta[id] = Triple(name, lat, lon)
                    id
                }
            val routesMap = mutableMapOf<String, BusRoute>()
            for (i in 0 until routes.length()) {
                val route = routes.optJSONObject(i) ?: continue
                val id = route.optString("route_id")
                val concesion = route.optString("concesion")
                val short = route.optString("route_short_name")
                if (short.isBlank()) continue
                val lineKey = "MB$short"
                val directions = listOf(0, 1).map { dir ->
                    val stopsArray = runCatching {
                        JSONObject(metrobusGet("$METROBUS_API/routes/$id/stops?direction=$dir&concesion=$concesion")).optJSONArray("stops")
                    }.getOrNull() ?: JSONArray()
                    val ids = mutableListOf<String>()
                    for (k in 0 until stopsArray.length()) {
                        val item = stopsArray.optJSONObject(k) ?: continue
                        val sid = stopId(
                            item.optString("stop_code"), item.optString("stop_name"),
                            item.optDouble("stop_lat"), item.optDouble("stop_lon"),
                        )
                        linesAt.getOrPut(sid) { mutableSetOf() } += lineKey
                        ids += sid.toString()
                    }
                    val shapes = runCatching {
                        JSONObject(metrobusGet("$METROBUS_API/routes/$id/shapes?direction=$dir&concesion=$concesion"))
                            .optJSONArray("features")
                    }.getOrNull()?.let { list ->
                        buildList {
                            for (s in 0 until list.length()) {
                                val coords = list.optJSONObject(s)?.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                                add(buildList {
                                    for (c in 0 until coords.length()) {
                                        val point = coords.optJSONArray(c) ?: continue
                                        add(LonLat(point.optDouble(0), point.optDouble(1)))
                                    }
                                })
                            }
                        }
                    }.orEmpty()
                    RouteDirection(shapes, ids)
                }
                routesMap[lineKey] = BusRoute(directions[0], directions.getOrElse(1) { directions[0] })
            }
            val stops = meta.map { (sid, info) ->
                Stop(sid, info.first, linesAt[sid]?.toList().orEmpty(), info.second, info.third, metro = true)
            }
            stops to routesMap
        }.getOrElse { emptyList<Stop>() to emptyMap<String, BusRoute>() }
    }

    /** Shapes for just the given Metrobús lines (fast, used per selected stop). */
    suspend fun metrobusRoutesForLines(lines: List<String>): String = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext EMPTY_COLLECTION
        runCatching {
            val routes = JSONArray(metrobusGet("$METROBUS_API/old/routes"))
            val features = JSONArray()
            for (i in 0 until routes.length()) {
                val route = routes.optJSONObject(i) ?: continue
                val lineKey = "MB" + route.optString("route_short_name")
                if (lineKey !in lines) continue
                val id = route.optString("route_id")
                val concesion = route.optString("concesion")
                val color = "#" + route.optString("route_color").ifBlank { "0EA5E9" }
                for (direction in 0..1) {
                    val list = runCatching {
                        JSONObject(metrobusGet("$METROBUS_API/routes/$id/shapes?direction=$direction&concesion=$concesion")).optJSONArray("features")
                    }.getOrNull() ?: continue
                    for (k in 0 until list.length()) {
                        val feature = list.optJSONObject(k) ?: continue
                        feature.put("properties", (feature.optJSONObject("properties") ?: JSONObject()).put("color", color).put("line", lineKey))
                        features.put(feature)
                    }
                }
            }
            JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        }.getOrElse { EMPTY_COLLECTION }
    }

    /** Docked bikes at a Valenbisi station: stand, type and user rating. */
    suspend fun valenbisiBikes(stationNumber: Int): List<Bike> = withContext(Dispatchers.IO) {
        runCatching {
            val token = cyclocityToken()
            val connection = URL("$CYCLOCITY_API/contracts/valence/bikes?stationNumber=$stationNumber").openConnection() as HttpURLConnection
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", "application/vnd.bikes.v4+json")
            connection.setRequestProperty("Content-Type", "application/vnd.bikes.v4+json")
            connection.setRequestProperty("Authorization", "Taknv1 $token")
            connection.setRequestProperty("Origin", "https://www.valenbisi.es")
            connection.setRequestProperty("Referer", "https://www.valenbisi.es/")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            val array = JSONArray(text)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val rating = item.optJSONObject("rating") ?: JSONObject()
                    add(
                        Bike(
                            number = item.optInt("number"),
                            stand = item.optInt("standNumber"),
                            type = item.optString("type"),
                            rating = rating.optDouble("value", 0.0),
                            ratings = rating.optInt("count", 0),
                        ),
                    )
                }
            }.sortedBy { it.stand }
        }.getOrDefault(emptyList())
    }

    private var cyclocityCached: Pair<Long, String>? = null

    private fun cyclocityToken(): String {
        cyclocityCached?.let { if (System.currentTimeMillis() - it.first < 10 * 60_000) return it.second }
        val connection = URL("https://api.cyclocity.fr/auth/environments/PRD/client_tokens").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write("""{"code":"vls.web.valence:PRD","key":"5baec26027069c8ff4358f7f8faf43e0ce2c1e32f6d919cc6006a4ee6bfdf5ac"}""".toByteArray()) }
        val token = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).optString("accessToken")
        connection.disconnect()
        cyclocityCached = System.currentTimeMillis() to token
        return token
    }

    private fun metrobusHeaders(connection: HttpURLConnection) {
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 17; Pixel 8a)")
        connection.setRequestProperty("x-requested-with", "com.softoursistemas.metgovalencia")
        connection.setRequestProperty("Origin", "https://localhost")
        connection.setRequestProperty("Referer", "https://localhost/")
    }

    private fun metrobusGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        metrobusHeaders(connection)
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
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
        private const val FGV_API = "https://www.fgv.es/fgv/app/en/api/v1/V"
        private const val METROBUS_API = "https://api.softoursistemas.com/metrobus"
        private const val CYCLOCITY_API = "https://api.cyclocity.fr"
        private const val EMPTY_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"
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
