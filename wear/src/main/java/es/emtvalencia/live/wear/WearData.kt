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
