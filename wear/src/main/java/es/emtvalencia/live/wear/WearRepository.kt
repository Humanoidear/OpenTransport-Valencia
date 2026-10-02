package es.emtvalencia.live.wear

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        private const val USER = "7gH8m45w7A"
        private const val USER_AGENT = "Dalvik/2.1.0 (Linux; U; Android 17; Pixel 8a Build/CP41.260831.007)"
        private const val WSSE = "UsernameToken Username=\"7gH8m45w7A\", PasswordDigest=\"ODdmMzU1OWU4ZDEwZGE0MDllM2E5NzhlZTg3Y2UxMmRjYTQ2N2VmYQ==\", Nonce=\"MjZlNTNjMWIxZmZhMmU4NjE5N2QyYjhkMjgyMGU2YjU=\", Created=\"1790762921\""
    }
}
