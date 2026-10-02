package es.emtvalencia.live.wear

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Minimal EMT client for the watch: nearby stops + arrivals. */
class WearRepository(private val data: WearData) {

    fun stopsNear(lat: Double, lon: Double, radius: Double): List<NearStop> =
        data.stops.asSequence()
            .map { NearStop(it, WearData.distance(lat, lon, it.lat, it.lon)) }
            .filter { it.meters <= radius }
            .sortedBy { it.meters }
            .take(12)
            .toList()

    private var metroCache: List<MetroStation>? = null

    suspend fun metroStations(): List<MetroStation> = withContext(Dispatchers.IO) {
        metroCache ?: runCatching {
            val connection = URL(METRO_STOPS_URL).openConnection() as HttpURLConnection
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect()
            val stops = root.optJSONArray("stops") ?: JSONArray()
            buildList {
                for (i in 0 until stops.length()) {
                    val item = stops.optJSONObject(i) ?: continue
                    val lines = item.optJSONArray("lines")?.let { a ->
                        buildList { for (k in 0 until a.length()) add("M" + a.get(k).toString()) }
                    }.orEmpty()
                    add(
                        MetroStation(
                            id = item.optInt("stop_id"),
                            name = item.optString("stop_name"),
                            lat = item.optDouble("stop_lat"),
                            lon = item.optDouble("stop_lon"),
                            lines = lines,
                        ),
                    )
                }
            }.also { metroCache = it }
        }.getOrDefault(emptyList())
    }

    /** Meters from the closest live bus of [line] to a point, or null. */
    suspend fun busDistanceMeters(line: String, lat: Double, lon: Double): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val params = listOf("usuario" to USER, "linea" to line)
                .joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
            val connection = URL("$HOST/buses/linea.php?$params").openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("X-WSSE", WSSE)
            val array = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("buses")
                ?: return@runCatching null
            connection.disconnect()
            var best: Double? = null
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val d = WearData.distance(lat, lon, item.optDouble("lat"), item.optDouble("lon"))
                if (best == null || d < best) best = d
            }
            best
        }.getOrNull()
    }

    fun nearby(lat: Double, lon: Double, radius: Double, services: Set<Svc>, metro: List<MetroStation>): List<NearItem> {
        val out = mutableListOf<Pair<Double, NearItem>>()
        if (Svc.Emt in services) {
            data.stops.forEach { stop ->
                val d = WearData.distance(lat, lon, stop.lat, stop.lon)
                if (d <= radius) {
                    out += d to NearItem(
                        key = "emt-${stop.id}",
                        name = stop.name,
                        detail = stop.lines.joinToString(" · "),
                        lat = stop.lat, lon = stop.lon,
                        service = Svc.Emt, lines = stop.lines, stopId = stop.id,
                    )
                }
            }
        }
        if (Svc.Metro in services) {
            metro.forEach { station ->
                val d = WearData.distance(lat, lon, station.lat, station.lon)
                if (d <= radius) {
                    out += d to NearItem(
                        key = "metro-${station.id}",
                        name = station.name,
                        detail = station.lines.joinToString(" · "),
                        lat = station.lat, lon = station.lon,
                        service = Svc.Metro, lines = station.lines, stopId = station.id,
                    )
                }
            }
        }
        return out.sortedBy { it.first }.take(15).map { it.second }
    }

    fun nearbyAll(
        lat: Double,
        lon: Double,
        radius: Double,
        services: Set<Svc>,
        metro: List<MetroStation>,
        valenbisi: List<VbStation>,
        rodalies: List<RodaliesStation>,
        metrobus: List<MetroStation>,
    ): List<NearItem> {
        val out = nearby(lat, lon, radius, services, metro).toMutableList()
        if (Svc.Valenbisi in services) {
            valenbisi.forEach { station ->
                val d = WearData.distance(lat, lon, station.lat, station.lon)
                if (d <= radius) {
                    out += NearItem(
                        key = "vb-${station.number}",
                        name = station.name,
                        detail = "${station.bikes} bikes · ${station.stands} docks",
                        lat = station.lat, lon = station.lon,
                        service = Svc.Valenbisi, lines = emptyList(), stopId = station.number,
                    )
                }
            }
        }
        if (Svc.Rodalies in services) {
            rodalies.forEach { station ->
                val d = WearData.distance(lat, lon, station.lat, station.lon)
                if (d <= radius) {
                    out += NearItem(
                        key = "rd-${station.name}",
                        name = station.name,
                        detail = station.lines.joinToString(" · "),
                        lat = station.lat, lon = station.lon,
                        service = Svc.Rodalies, lines = station.lines,
                    )
                }
            }
        }
        if (Svc.Metrobus in services) {
            metro.forEach { _ -> }
        }
        return out.sortedBy { WearData.distance(lat, lon, it.lat, it.lon) }.take(15)
    }

    suspend fun metrobusNear(lat: Double, lon: Double, radius: Int = 1500): List<NearItem> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("lat", lat).put("lon", lon).put("radius", radius).toString()
            val connection = URL("$METROBUS_API/stops/near").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            metrobusHeaders(connection)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        NearItem(
                            key = "mb-" + item.optString("stop_code"),
                            name = item.optString("stop_name"),
                            detail = item.optString("stop_desc").ifBlank { "Metrobús" },
                            lat = item.optDouble("stop_lat"),
                            lon = item.optDouble("stop_lon"),
                            service = Svc.Metrobus, lines = emptyList(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Cached (short, route_id, concesion) for Metrobús lines ("MB"+short). */
    private var mbRoutesCache: List<Triple<String, String, String>>? = null

    suspend fun metrobusLines(): List<String> = withContext(Dispatchers.IO) {
        (mbRoutesCache ?: runCatching {
            val routes = JSONArray(metrobusGet("$METROBUS_API/old/routes"))
            buildList {
                for (i in 0 until routes.length()) {
                    val route = routes.optJSONObject(i) ?: continue
                    val short = route.optString("route_short_name")
                    if (short.isBlank()) continue
                    add(Triple(short, route.optString("route_id"), route.optString("concesion")))
                }
            }.also { mbRoutesCache = it }
        }.getOrDefault(emptyList())).map { "MB" + it.first }
    }

    private suspend fun metrobusRoute(line: String): Triple<String, String, String>? = withContext(Dispatchers.IO) {
        if (mbRoutesCache == null) metrobusLines()
        mbRoutesCache?.firstOrNull { "MB" + it.first == line }
    }

    suspend fun metrobusShapes(line: String): List<List<Pair<Double, Double>>> = withContext(Dispatchers.IO) {
        val (_, id, concesion) = runCatching {
            val route = metrobusRoute(line) ?: return@runCatching null
            route
        }.getOrNull() ?: return@withContext emptyList()
        buildList {
            for (direction in 0..1) {
                val list = runCatching {
                    JSONObject(metrobusGet("$METROBUS_API/routes/$id/shapes?direction=$direction&concesion=$concesion"))
                        .optJSONArray("features")
                }.getOrNull() ?: continue
                for (k in 0 until list.length()) {
                    val coords = list.optJSONObject(k)?.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                    add(buildList {
                        for (c in 0 until coords.length()) {
                            val point = coords.optJSONArray(c) ?: continue
                            add(point.optDouble(1) to point.optDouble(0))
                        }
                    })
                }
            }
        }
    }

    private fun metrobusHeaders(connection: HttpURLConnection) {        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 17; Pixel 8a)")
        connection.setRequestProperty("x-requested-with", "com.softoursistemas.metgovalencia")
        connection.setRequestProperty("Origin", "https://localhost")
        connection.setRequestProperty("Referer", "https://localhost/")
    }

    suspend fun arrivals(stopId: Int): List<Live> = withContext(Dispatchers.IO) {
        runCatching {
            val params = listOf(
                "usuario" to USER, "idioma" to "en", "parada" to stopId.toString(), "adaptados" to "false",
            ).joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
            val url = "$HOST/estimaciones/estimacion.php?$params"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("X-WSSE", WSSE)
            val xml = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            parse(xml)
        }.getOrDefault(emptyList())
    }

    private var vbCache: List<VbStation>? = null

    suspend fun valenbisiStations(): List<VbStation> = withContext(Dispatchers.IO) {
        vbCache ?: runCatching {
            val connection = URL(VALENBISI_URL).openConnection() as HttpURLConnection
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val position = item.optJSONObject("position") ?: continue
                    val availability = item.optJSONObject("totalStands")?.optJSONObject("availabilities")
                    add(
                        VbStation(
                            number = item.optInt("number"),
                            name = item.optString("name").replace('_', ' ').trim(),
                            address = item.optString("address"),
                            lat = position.optDouble("latitude"),
                            lon = position.optDouble("longitude"),
                            bikes = availability?.optInt("bikes", 0) ?: 0,
                            stands = availability?.optInt("stands", 0) ?: 0,
                            mechanical = availability?.optInt("mechanicalBikes", 0) ?: 0,
                            electrical = availability?.optInt("electricalBikes", 0) ?: 0,
                            open = item.optString("status").equals("OPEN", ignoreCase = true),
                        ),
                    )
                }
            }.also { vbCache = it }
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

    suspend fun valenbisiBikes(stationNumber: Int): List<Bike> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$CYCLOCITY_API/contracts/valence/bikes?stationNumber=$stationNumber").openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.setRequestProperty("Accept", "application/vnd.bikes.v4+json")
            connection.setRequestProperty("Content-Type", "application/vnd.bikes.v4+json")
            connection.setRequestProperty("Authorization", "Taknv1 ${cyclocityToken()}")
            connection.setRequestProperty("Origin", "https://www.valenbisi.es")
            connection.setRequestProperty("Referer", "https://www.valenbisi.es/")
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect()
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

    suspend fun metroArrivals(stopId: Int): List<Live> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$METRO_API/prevision/$stopId/parse").openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", METRO_API_UA)
            val array = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("previsiones")
                ?: return@runCatching emptyList()
            connection.disconnect()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val seconds = item.optInt("seconds", -1)
                    val minutes = if (seconds >= 0) maxOf(1, seconds / 60) else 0
                    add(
                        Live(
                            line = "L" + item.optInt("line"),
                            destination = item.optString("destino"),
                            minutes = if (seconds >= 0) "$minutes min." else item.optString("hora").take(5),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun metrobusOccupancy(stopCode: String): List<Live> = withContext(Dispatchers.IO) {
        runCatching {
            val array = JSONArray(metrobusGet("$METROBUS_API/estimacion/ocupacion/$stopCode"))
            val out = mutableListOf<Pair<Int, Live>>()
            for (i in 0 until array.length()) {
                val lineObj = array.optJSONObject(i) ?: continue
                val estimation = lineObj.optJSONArray("estimations")?.optJSONObject(0) ?: continue
                val minutes = estimation.optInt("minutesToArrival", -1)
                if (minutes < 0) continue
                val occupancy = estimation.optString("ocupacion")
                out += minutes to Live(
                    "MB" + lineObj.optString("line"),
                    lineObj.optString("route"),
                    if (occupancy.isBlank()) "$minutes min" else "$minutes min · $occupancy",
                )
            }
            out.sortedBy { it.first }.map { it.second }
        }.getOrDefault(emptyList())
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

    private fun parse(xml: String): List<Live> {
        if (xml.isBlank()) return emptyList()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val out = mutableListOf<Live>()
        var line = ""; var dest = ""; var minutes = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "bus" -> { line = ""; dest = ""; minutes = "" }
                    "linea" -> line = parser.nextText().trim()
                    "destino" -> dest = parser.nextText().trim()
                    "minutos" -> minutes = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name == "bus" && (line.isNotBlank() || minutes.isNotBlank())) {
                    out += Live(line, dest, if (minutes.lowercase(Locale.ROOT).startsWith("next")) "now" else minutes)
                }
            }
            event = parser.next()
        }
        return out
    }

    companion object {
        private const val HOST = "https://servicios.emtvalencia.es"
        private const val METRO_STOPS_URL = "https://www.wiilink24.com/extras/locations.json"
        private const val VALENBISI_URL =
            "https://api.jcdecaux.com/vls/v3/stations?apiKey=frifk0jbxfefqqniqez09tw4jvk37wyf823b5j1i&contract=valence"
        private const val METRO_API = "https://metroapi.alexbadi.es"
        private const val METRO_API_UA = "EMT-RealTime/1.0 (api; contact=alex@example.com)"
        private const val METROBUS_API = "https://api.softoursistemas.com/metrobus"
        private const val CYCLOCITY_API = "https://api.cyclocity.fr"
        private const val USER = "7gH8m45w7A"
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 17; Pixel 8a Build/CP41.260831.007)"
        private const val WSSE = "UsernameToken Username=\"7gH8m45w7A\", PasswordDigest=\"ODdmMzU1OWU4ZDEwZGE0MDllM2E5NzhlZTg3Y2UxMmRjYTQ2N2VmYQ==\", Nonce=\"MjZlNTNjMWIxZmZhMmU4NjE5N2QyYjhkMjgyMGU2YjU=\", Created=\"1790762921\""
    }
}
