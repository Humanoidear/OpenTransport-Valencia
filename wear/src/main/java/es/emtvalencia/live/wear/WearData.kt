package es.emtvalencia.live.wear

import android.content.Context
import org.json.JSONArray
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Stop(val id: Int, val name: String, val lines: List<String>, val lat: Double, val lon: Double)
data class NearStop(val stop: Stop, val meters: Double)
data class Live(val line: String, val destination: String, val minutes: String)

enum class Svc { Emt, Metro, Valenbisi, Metrobus, Rodalies }

data class MetroStation(val id: Int, val name: String, val lat: Double, val lon: Double, val lines: List<String>)

data class RodaliesStation(val name: String, val lat: Double, val lon: Double, val lines: List<String>)

data class VbStation(
    val number: Int,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val bikes: Int,
    val stands: Int,
    val mechanical: Int,
    val electrical: Int,
    val open: Boolean,
)

data class Bike(val number: Int, val stand: Int, val type: String, val rating: Double, val ratings: Int)

data class MbArrival(val line: String, val destination: String, val minutes: String)

data class NearItem(
    val key: String,
    val name: String,
    val detail: String,
    val lat: Double,
    val lon: Double,
    val service: Svc,
    val lines: List<String>,
    val stopId: Int = -1,
)

class WearData(val stops: List<Stop>) {
    companion object {
        fun load(context: Context): WearData {
            val array = JSONArray(context.assets.open("stops.json").bufferedReader().use { it.readText() })
            val stops = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val lines = item.optJSONArray("lineas")?.let { a ->
                        buildList { for (k in 0 until a.length()) add(a.getString(k)) }
                    }.orEmpty()
                    add(
                        Stop(
                            id = item.optInt("id"),
                            name = item.optString("nombre").replace(Regex("\\s*\\(\\d+\\)\\s*$"), "").trim(),
                            lines = lines,
                            lat = item.optDouble("lat"),
                            lon = item.optDouble("lon"),
                        ),
                    )
                }
            }
            return WearData(stops)
        }

        fun rodaliesStations(context: Context): List<RodaliesStation> {
            val root = org.json.JSONObject(context.assets.open("rodalies.geojson").bufferedReader().use { it.readText() })
            val features = root.optJSONArray("features") ?: return emptyList()
            val times = org.json.JSONObject(context.assets.open("rodalies_times.json").bufferedReader().use { it.readText() })
            return buildList {
                for (i in 0 until features.length()) {
                    val feature = features.optJSONObject(i) ?: continue
                    if (feature.optJSONObject("geometry")?.optString("type") != "Point") continue
                    val props = feature.optJSONObject("properties") ?: continue
                    val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                    val name = props.optString("stop_name")
                    if (name.isBlank()) continue
                    val lines = linkedSetOf<String>()
                    props.optJSONArray("lines")?.let { array ->
                        for (j in 0 until array.length()) {
                            val line = array.optString(j).trim()
                            if (line.isNotBlank()) lines += "C" + line.removePrefix("C")
                        }
                    }
                    val arr = times.optJSONArray(name)
                    if (lines.isEmpty() && arr != null) for (j in 0 until arr.length()) {
                        arr.optString(j).split("|").getOrNull(1)?.takeIf { it.isNotBlank() }?.let { lines += it }
                    }
                    add(RodaliesStation(name, coords.optDouble(1), coords.optDouble(0), lines.sorted()))
                }
            }
        }

        fun rodaliesTimes(context: Context, name: String): List<Live> {
            val root = rodaliesTimesCache ?: runCatching {
                org.json.JSONObject(context.assets.open("rodalies_times.json").bufferedReader().use { it.readText() })
            }.getOrNull()?.also { rodaliesTimesCache = it } ?: return emptyList()
            val list = root.optJSONArray(name) ?: return emptyList()
            val cal = java.util.Calendar.getInstance()
            val nowMinute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val out = mutableListOf<Pair<Int, Live>>()
            for (i in 0 until list.length()) {
                val parts = list.optString(i).split("|")
                if (parts.size < 2) continue
                val time = parts[0].split(":")
                val hh = time.getOrNull(0)?.toIntOrNull() ?: continue
                val mm = time.getOrNull(1)?.toIntOrNull() ?: continue
                val target = hh * 60 + mm
                val diff = if (target >= nowMinute) target - nowMinute else target + 1440 - nowMinute
                if (diff <= 240) out += diff to Live(parts[1], parts.getOrElse(2) { "" }, "$diff min")
            }
            return out.sortedBy { it.first }.take(8).map { it.second }
        }

        private var rodaliesTimesCache: org.json.JSONObject? = null

        private var routesCache: org.json.JSONObject? = null

        /** Shape polylines for an EMT line (routes.json, [lat, lon]) or Metro line (geojson, [lon, lat]). */
        fun routeShapes(context: Context, line: String, service: Svc): List<List<Pair<Double, Double>>> {
            return when (service) {
                Svc.Emt -> {
                    val root = routesCache ?: runCatching {
                        org.json.JSONObject(context.assets.open("routes.json").bufferedReader().use { it.readText() })
                    }.getOrNull()?.also { routesCache = it } ?: return emptyList()
                    val entry = root.optJSONObject(line) ?: return emptyList()
                    listOf("ida", "vuelta").flatMap { dir ->
                        val shapes = entry.optJSONObject(dir)?.optJSONArray("shapes") ?: return@flatMap emptyList()
                        buildList {
                            for (i in 0 until shapes.length()) {
                                val pts = shapes.optJSONArray(i) ?: continue
                                add(buildList {
                                    for (j in 0 until pts.length()) {
                                        val p = pts.optJSONArray(j) ?: continue
                                        add(p.optDouble(0) to p.optDouble(1))
                                    }
                                })
                            }
                        }
                    }
                }
                Svc.Metro -> {
                    val bare = line.removePrefix("M")
                    val root = metroRoutesCache ?: runCatching {
                        org.json.JSONObject(context.assets.open("metrovalencia.geojson").bufferedReader().use { it.readText() })
                    }.getOrNull()?.also { metroRoutesCache = it } ?: return emptyList()
                    val features = root.optJSONArray("features") ?: return emptyList()
                    buildList {
                        for (i in 0 until features.length()) {
                            val feature = features.optJSONObject(i) ?: continue
                            if (feature.optJSONObject("geometry")?.optString("type") != "LineString") continue
                            if (feature.optJSONObject("properties")?.opt("line")?.toString() != bare) continue
                            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                            add(buildList {
                                for (j in 0 until coords.length()) {
                                    val p = coords.optJSONArray(j) ?: continue
                                    add(p.optDouble(1) to p.optDouble(0))
                                }
                            })
                        }
                    }
                }
                Svc.Rodalies -> {
                    val root = rodaliesRoutesCache ?: runCatching {
                        org.json.JSONObject(context.assets.open("rodalies.geojson").bufferedReader().use { it.readText() })
                    }.getOrNull()?.also { rodaliesRoutesCache = it } ?: return emptyList()
                    val features = root.optJSONArray("features") ?: return emptyList()
                    buildList {
                        for (i in 0 until features.length()) {
                            val feature = features.optJSONObject(i) ?: continue
                            if (feature.optJSONObject("geometry")?.optString("type") != "LineString") continue
                            if (feature.optJSONObject("properties")?.optString("line") != line) continue
                            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                            add(buildList {
                                for (j in 0 until coords.length()) {
                                    val p = coords.optJSONArray(j) ?: continue
                                    add(p.optDouble(1) to p.optDouble(0))
                                }
                            })
                        }
                    }
                }
                else -> emptyList()
            }
        }

        private var metroRoutesCache: org.json.JSONObject? = null
        private var rodaliesRoutesCache: org.json.JSONObject? = null

        fun stopsOnLine(data: WearData, metro: List<MetroStation>, rodalies: List<RodaliesStation>, line: String, service: Svc): List<NearItem> {
            return when (service) {
                Svc.Emt -> data.stops.filter { line in it.lines }.map { stop ->
                    NearItem(
                        key = "emt-${stop.id}", name = stop.name, detail = stop.lines.joinToString(" · "),
                        lat = stop.lat, lon = stop.lon, service = Svc.Emt, lines = stop.lines, stopId = stop.id,
                    )
                }
                Svc.Metro -> {
                    val bare = line.removePrefix("M")
                    metro.filter { station -> station.lines.any { it.removePrefix("M") == bare } }.map { station ->
                        NearItem(
                            key = "metro-${station.id}", name = station.name, detail = station.lines.joinToString(" · "),
                            lat = station.lat, lon = station.lon, service = Svc.Metro, lines = station.lines, stopId = station.id,
                        )
                    }
                }
                Svc.Rodalies -> rodalies.filter { line in it.lines }.map { station ->
                    NearItem(
                        key = "rd-${station.name}", name = station.name, detail = station.lines.joinToString(" · "),
                        lat = station.lat, lon = station.lon, service = Svc.Rodalies, lines = station.lines,
                    )
                }
                else -> emptyList()
            }
        }

        fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(bLat - aLat)
            val dLon = Math.toRadians(bLon - aLon)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(min(1.0, h)))
        }
    }
}
