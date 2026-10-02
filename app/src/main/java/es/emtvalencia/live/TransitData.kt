package es.emtvalencia.live

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Stop(val id: Int, val name: String, val lines: List<String>, val lat: Double, val lon: Double, val metro: Boolean = false)data class RouteDirection(val shapes: List<List<LonLat>>, val stops: List<String>)
data class BusRoute(val ida: RouteDirection, val vuelta: RouteDirection)
data class TransitData(val stops: List<Stop>, val stopsById: Map<String, Stop>, val routes: Map<String, BusRoute>)

data class BusFix(
    val number: Int,
    val line: String,
    val destination: String,
    val lastStop: Int,
    val nextStop: Int,
    val lon: Double,
    val lat: Double,
    val timestamp: String,
    val direction: String?,
)

data class BusPosition(
    val number: Int,
    val line: String,
    val destination: String,
    var nextStop: Int,
    var direction: String?,
    var timestamp: String,
    var observed: LonLat,
    var observedAt: Long,
    var velocity: LonLat,
    var render: LonLat,
    var route: PreparedRoute?,
    var routeS: Double,
    var observedS: Double,
    var speedMps: Double,
    var hold: StopHold? = null,
    var bearing: Double = 0.0,
)

data class Arrival(val line: String, val destination: String, val minutes: String, val arrivalTime: String = "", val source: String = "emt")
data class NearBus(val line: String, val number: Int, val lat: Double, val lon: Double, val direction: String?, val minutes: Int, val distanceMeters: Double)
data class StopInfo(val stop: Stop, val arrivals: List<Arrival>, val buses: List<NearBus>)
data class Incident(
    val title: String,
    val date: String,
    val lines: Set<String>,
    val network: Network = Network.Emt,
    val detail: String = "",
    val url: String = "",
)

/** One docked Valenbisi bike: which stand, its type and its user rating. */
data class Bike(val number: Int, val stand: Int, val type: String, val rating: Double, val ratings: Int)

/** One scheduled Metrobús departure in the day's timetable. */
data class MetrobusTime(val hour: Int, val minute: Int, val line: String, val destination: String)

/** The networks the app can draw on the map. */
enum class Network(val label: String) {
    Emt("EMT"),
    Metro("Metro"),
    Valenbisi("Bici"),
    Metrobus("Metrobús"),
    Rodalies("Rodalies"),
}

/**
 * A point of interest from any network: an EMT stop, a Metrovalencia station
 * or a Valenbisi dock. One shape so the map can draw them uniformly.
 */
data class Place(
    val id: String,
    val network: Network,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lines: List<String> = emptyList(),
    val detail: String = "",
    val available: Int = -1,
    val free: Int = -1,
    val open: Boolean = true,
    val mechanical: Int = -1,
    val electric: Int = -1,
)

/** Screen-space friendly bounds used for fitting the camera to a stop's lines. */
data class LngLatBounds(val west: Double, val south: Double, val east: Double, val north: Double)

object TransitDataLoader {
    fun load(context: Context): TransitData {
        val stopsJson = JSONArray(context.assets.open("stops.json").bufferedReader().use { it.readText() })
        val stops = buildList {
            for (i in 0 until stopsJson.length()) {
                val item = stopsJson.getJSONObject(i)
                add(
                    Stop(
                        id = item.getInt("id"),
                        name = item.getString("nombre"),
                        lines = item.getJSONArray("lineas").strings(),
                        lat = item.getDouble("lat"),
                        lon = item.getDouble("lon"),
                    ),
                )
            }
        }
        val routesJson = JSONObject(context.assets.open("routes.json").bufferedReader().use { it.readText() })
        val routes = buildMap {
            val keys = routesJson.keys()
            while (keys.hasNext()) {
                val line = keys.next()
                val item = routesJson.getJSONObject(line)
                put(line, BusRoute(item.getJSONObject("ida").direction(), item.getJSONObject("vuelta").direction()))
            }
        }
        // Metrovalencia: merge its stations and route shapes into the same graph
        // so the planner can route over them like EMT stops.
        val (metroStops, metroRoutes) = loadMetro(context)
        // Some EMT stops are physically Metro stations (same name): flag them so
        // they're drawn/served as Metro, never as EMT points.
        val metroNames = metroStops.map { it.name.lowercase() }.toSet()
        val taggedStops = stops.map { stop ->
            if (stop.name.lowercase() in metroNames) stop.copy(metro = true) else stop
        }
        val allStops = taggedStops + metroStops
        return TransitData(allStops, allStops.associateBy { it.id.toString() }, routes + metroRoutes)
    }

    /** Metro stations and line shapes, built from the bundled geojson. */
    private fun loadMetro(context: Context): Pair<List<Stop>, Map<String, BusRoute>> {
        val empty = emptyList<Stop>() to emptyMap<String, BusRoute>()
        val text = runCatching { context.assets.open("metrovalencia.geojson").bufferedReader().use { it.readText() } }.getOrNull() ?: return empty
        val features = runCatching { JSONObject(text).optJSONArray("features") }.getOrNull() ?: return empty
        val stations = LinkedHashMap<String, Stop>()
        val lineStrings = mutableListOf<JSONObject>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            when (geometry.optString("type")) {
                "Point" -> {
                    val coords = geometry.optJSONArray("coordinates") ?: continue
                    val name = properties.optString("stop_name")
                    if (name.isBlank()) continue
                    val lines = properties.optJSONArray("lines")?.strings().orEmpty().map { "M$it" }
                    stations[name] = Stop(METRO_ID_BASE + stations.size, name, lines, coords.optDouble(1), coords.optDouble(0), metro = true)
                }
                "LineString" -> lineStrings += feature
            }
        }
        val routes = mutableMapOf<String, BusRoute>()
        lineStrings.groupBy { it.optJSONObject("properties")?.optInt("line") ?: 0 }
            .filterKeys { it != 0 }
            .forEach { (lineNumber, group) ->
                val directions = group.take(2).map { feature ->
                    val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                    val shape = buildList {
                        if (coords != null) {
                            for (j in 0 until coords.length()) {
                                val point = coords.optJSONArray(j) ?: continue
                                add(LonLat(point.optDouble(0), point.optDouble(1)))
                            }
                        }
                    }
                    val names = feature.optJSONObject("properties")?.optJSONArray("stops")?.strings().orEmpty()
                    RouteDirection(listOf(shape), names.mapNotNull { stations[it]?.id?.toString() })
                }
                if (directions.isNotEmpty()) {
                    routes["M$lineNumber"] = BusRoute(directions[0], directions.getOrElse(1) { directions[0] })
                }
            }
        return stations.values.toList() to routes
    }

    private const val METRO_ID_BASE = 1_000_000

    private fun JSONArray.strings() = buildList {
        for (i in 0 until length()) add(getString(i))
    }

    private fun JSONObject.direction(): RouteDirection {
        val shapesJson = getJSONArray("shapes")
        val shapes = buildList {
            for (i in 0 until shapesJson.length()) {
                val shape = shapesJson.getJSONArray(i)
                add(buildList {
                    for (j in 0 until shape.length()) {
                        val point = shape.getJSONArray(j) // source JSON is [lat, lon]
                        add(LonLat(point.getDouble(1), point.getDouble(0)))
                    }
                })
            }
        }
        val stops = getJSONArray("stops").strings()
        return RouteDirection(shapes, stops)
    }
}
