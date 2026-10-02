package es.emtvalencia.live

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Home-screen widget: next arrivals at a chosen stop, rounded-rect card with a scrolling list. */
class StopWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        scheduleRefresh(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { updateOne(context, manager, it) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: Bundle) {
        onUpdate(context, manager, intArrayOf(widgetId))
    }

    override fun onEnabled(context: Context) = scheduleRefresh(context)
    override fun onDisabled(context: Context) = cancelRefresh(context)

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
        val p = WidgetShared.palette(context)

        val opts = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
        val minW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val maxW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
        val isWide = maxOf(minW, maxW) >= 200

        val views = RemoteViews(context.packageName, if (isWide) R.layout.widget_stop_wide else R.layout.widget_stop)
        views.setImageViewBitmap(R.id.widget_bg, roundedCard(context, manager, widgetId, p.container))
        views.setInt(R.id.widget_refresh, "setColorFilter", p.accent)
        views.setTextViewText(R.id.widget_title, name ?: context.getString(R.string.widget_choose_stop))
        views.setTextColor(R.id.widget_title, p.onContainer)
        views.setTextColor(R.id.widget_row1, p.onContainer)
        views.setTextColor(R.id.widget_row1_dest, p.variant)
        views.setOnClickPendingIntent(R.id.widget_root, openStop(context, stopId))
        views.setOnClickPendingIntent(R.id.widget_refresh, refresh(context))
        WidgetShared.serviceLogo(context, network)?.let { views.setImageViewBitmap(R.id.widget_logo, it) }

        val arrivals = WidgetShared.arrivals(context, stopId, network)
        WidgetShared.cachedStopId = stopId
        WidgetShared.cachedArrivals = arrivals
        val hero = if (stopId <= 0) {
            "–" to context.getString(R.string.widget_tap_to_configure)
        } else if (network != Network.Emt) {
            "···" to context.getString(R.string.widget_feed_soon, network.label)
        } else arrivals.firstOrNull()?.let {
            (it.minutes.ifBlank { it.arrivalTime.take(5) }) to it.destination
        } ?: ("–" to context.getString(R.string.widget_no_buses))

        WidgetShared.lineBadge(context, arrivals.firstOrNull()?.line.orEmpty())?.let {
            views.setImageViewBitmap(R.id.widget_row1_img, it)
            views.setViewVisibility(R.id.widget_row1_img, View.VISIBLE)
        } ?: views.setViewVisibility(R.id.widget_row1_img, View.GONE)
        views.setTextViewText(R.id.widget_row1, hero.first)
        views.setTextViewText(R.id.widget_row1_dest, hero.second)

        if (isWide) {
            val serviceIntent = Intent(context, WidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            serviceIntent.data = Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME))
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)
        }
        manager.updateAppWidget(widgetId, views)
        if (isWide) manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
    }

    /** Plain rounded-rect card at the widget's exact size (no blobs, no asymmetry). */
    private fun roundedCard(context: Context, manager: AppWidgetManager, widgetId: Int, container: Int): Bitmap {
        val dm = context.resources.displayMetrics
        val opts = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
        val wDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260) ?: 260
        val hDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 260) ?: 260
        val w = ((wDp * dm.density).toInt()).coerceIn(200, 1200)
        val h = ((hDp * dm.density).toInt()).coerceIn(200, 1200)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val radius = (28 * dm.density)
        val path = Path().apply { addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW) }
        Canvas(bmp).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = container })
        return bmp
    }

    private fun openStop(context: Context, stopId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("widget_stop", stopId)
        return PendingIntent.getActivity(
            context, stopId.coerceAtLeast(0), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun refresh(context: Context): PendingIntent {
        val intent = Intent(context, StopWidgetProvider::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun scheduleRefresh(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 99, Intent(context, StopWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 60_000L,
            60_000L, pi,
        )
    }

    private fun cancelRefresh(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 99, Intent(context, StopWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.cancel(pi)
    }

    companion object {
        const val ACTION_REFRESH = "es.emtvalencia.live.WIDGET_REFRESH"
    }
}
