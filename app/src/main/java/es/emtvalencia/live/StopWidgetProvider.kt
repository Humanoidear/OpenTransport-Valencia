package es.emtvalencia.live

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Home-screen widget showing the next arrivals at a chosen stop. The stop is
 * stored per widget id, and the network is stored alongside it so other
 * operators can be wired in without changing the layout.
 */
class StopWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { updateOne(context, manager, it) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StopWidgetProvider::class.java))
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ids.forEach { updateOne(context, manager, it) }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private suspend fun updateOne(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val prefs = context.getSharedPreferences("emt", Context.MODE_PRIVATE)
        val stopId = prefs.getInt("widget_${widgetId}_stop", -1)
        val name = prefs.getString("widget_${widgetId}_name", null)
        val network = Network.valueOf(prefs.getString("widget_${widgetId}_network", Network.Emt.name) ?: Network.Emt.name)

        val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val accent = if (android.os.Build.VERSION.SDK_INT >= 31) {
            runCatching { context.getColor(android.R.color.system_accent1_600) }.getOrElse { 0xFF0B57D0.toInt() }
        } else {
            0xFF0B57D0.toInt()
        }
        val onSurface = if (dark) 0xFFF2F4F7.toInt() else 0xFF1A1C1E.toInt()
        val onSurfaceVariant = if (dark) 0xFFB6C2CF.toInt() else 0xFF43474E.toInt()

        val views = RemoteViews(context.packageName, R.layout.widget_stop)
        // System accent as the card colour, with the map only faintly behind it.
        val accentSoft = (accent and 0x00FFFFFF) or (0xE6 shl 24)
        views.setInt(R.id.widget_root, "setBackgroundColor", accentSoft)
        views.setInt(R.id.widget_map, "setImageAlpha", if (dark) 40 else 55)
        views.setTextViewText(R.id.widget_title, name ?: context.getString(R.string.widget_choose_stop))
        views.setTextViewText(R.id.widget_subtitle, network.label)
        views.setTextColor(R.id.widget_title, onSurface)
        views.setTextColor(R.id.widget_subtitle, onSurfaceVariant)
        views.setTextColor(R.id.widget_lines, accent)
        val crossing = if (stopId > 0 && network == Network.Emt) {
            runCatching { TransitDataLoader.load(context) }.getOrNull()?.stopsById?.get(stopId.toString())?.lines?.joinToString(" · ")
        } else {
            null
        }
        views.setTextViewText(R.id.widget_lines, crossing.orEmpty())
        for (rowId in listOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3)) {
            views.setTextColor(rowId, onSurface)
        }
        views.setOnClickPendingIntent(R.id.widget_title, openApp(context))
        views.setOnClickPendingIntent(R.id.widget_root, openStop(context, stopId))

        // Static map behind the card, plus the service logo.
        val lat = prefs.getFloat("widget_${widgetId}_lat", 0f).toDouble()
        val lon = prefs.getFloat("widget_${widgetId}_lon", 0f).toDouble()
        if (lat != 0.0 && lon != 0.0) staticMapTile(lat, lon, dark)?.let {
            views.setImageViewBitmap(R.id.widget_map, it)
        }
        serviceLogo(context, network)?.let { views.setImageViewBitmap(R.id.widget_logo, it) }

        val rows = listOf(
            R.id.widget_row1_img to R.id.widget_row1,
            R.id.widget_row2_img to R.id.widget_row2,
            R.id.widget_row3_img to R.id.widget_row3,
        )
        val lines = mutableListOf<String>()
        val rowLines = mutableListOf<String?>()
        if (stopId <= 0) {
            lines += context.getString(R.string.widget_tap_to_configure)
            rowLines += null
        } else if (network == Network.Emt) {
            val transit = runCatching { TransitDataLoader.load(context) }.getOrNull()
            val info = if (transit != null) runCatching { EmtRepository(transit).stop(stopId) }.getOrNull() else null
            info?.arrivals.orEmpty().take(rows.size).forEach { arrival ->
                lines += arrival.minutes.ifBlank { arrival.arrivalTime.take(5) }
                rowLines += arrival.line
            }
            if (lines.isEmpty()) { lines += context.getString(R.string.widget_no_buses); rowLines += null }
        } else {
            lines += context.getString(R.string.widget_feed_soon, network.label)
            rowLines += null
        }

        rows.forEachIndexed { index, (imageId, textId) ->
            if (index < lines.size) {
                views.setViewVisibility(textId, View.VISIBLE)
                val badge = rowLines.getOrNull(index)?.let { lineBadge(context, it) }
                if (badge != null) {
                    views.setImageViewBitmap(imageId, badge)
                    views.setViewVisibility(imageId, View.VISIBLE)
                    views.setTextViewText(textId, lines[index])
                } else {
                    views.setViewVisibility(imageId, View.GONE)
                    views.setTextViewText(textId, rowLines.getOrNull(index)?.let { "$it   ${lines[index]}" } ?: lines[index])
                }
            } else {
                views.setViewVisibility(imageId, View.GONE)
                views.setViewVisibility(textId, View.GONE)
            }
        }
        manager.updateAppWidget(widgetId, views)
    }

    /** Line image for a widget row: geometry from the generator/PNG assets. */
    private fun lineBadge(context: Context, line: String): android.graphics.Bitmap? {
        if (line.startsWith("MB")) return null
        if (line.startsWith("M") && line.length > 1 && line.drop(1).all { it.isDigit() }) {
            return runCatching {
                context.assets.open("metrovalencia/${line.drop(1)}.png").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        return runCatching {
            val url = "https://geoportal.emtvalencia.es/ciudadano/icongenerator/create-line-image.php" +
                "?size=50&type=normal&lineNumber=${java.net.URLEncoder.encode(line, "UTF-8")}" +
                "&showBorder=false&borderColor=white"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    private fun staticMapTile(lat: Double, lon: Double, dark: Boolean): android.graphics.Bitmap? = runCatching {
        val zoom = 15
        val tiles = 1 shl zoom
        val x = ((lon + 180.0) / 360.0 * tiles).toInt()
        val latRad = Math.toRadians(lat)
        val y = ((1 - ln(tan(latRad) + 1 / cos(latRad)) / Math.PI) / 2 * tiles).toInt()
        // OSM is keyless; darken it in night mode instead of pulling CARTO (which
        // now needs an API key).
        val connection = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png").openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "OpenTransportValencia/1.0")
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        val tile = connection.inputStream.use { BitmapFactory.decodeStream(it) }
        if (!dark || tile == null) {
            tile
        } else {
            val out = android.graphics.Bitmap.createBitmap(tile.width, tile.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            canvas.drawBitmap(tile, 0f, 0f, null)
            canvas.drawColor(0xB8121A22.toInt())
            out
        }
    }.getOrNull()

    private fun serviceLogo(context: Context, network: Network): android.graphics.Bitmap? = runCatching {
        val asset = when (network) {
            Network.Emt -> "emt.png"
            Network.Metro -> "metrovalencia.png"
            Network.Valenbisi -> "valenbisi.png"
            Network.Metrobus -> "metrobus.png"
            Network.Rodalies -> "rodalies.png"
        }
        context.assets.open(asset).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun openStop(context: Context, stopId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("widget_stop", stopId)
        return PendingIntent.getActivity(
            context, stopId.coerceAtLeast(0), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_REFRESH = "es.emtvalencia.live.WIDGET_REFRESH"
    }
}
