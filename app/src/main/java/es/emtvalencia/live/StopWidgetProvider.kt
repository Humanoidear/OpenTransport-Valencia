package es.emtvalencia.live

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        val views = RemoteViews(context.packageName, R.layout.widget_stop)
        views.setTextViewText(R.id.widget_title, name ?: context.getString(R.string.widget_choose_stop))
        views.setTextViewText(R.id.widget_subtitle, network.label)
        views.setOnClickPendingIntent(R.id.widget_title, openApp(context))

        val rows = listOf(R.id.widget_row1, R.id.widget_row2, R.id.widget_row3, R.id.widget_row4)
        val lines = mutableListOf<String>()
        if (stopId <= 0) {
            lines += context.getString(R.string.widget_tap_to_configure)
        } else if (network == Network.Emt) {
            val transit = runCatching { TransitDataLoader.load(context) }.getOrNull()
            val info = if (transit != null) runCatching { EmtRepository(transit).stop(stopId) }.getOrNull() else null
            info?.arrivals.orEmpty().take(rows.size).forEach { arrival ->
                lines += "${arrival.line}   ${arrival.minutes.ifBlank { arrival.arrivalTime.take(5) }}"
            }
            if (lines.isEmpty()) lines += context.getString(R.string.widget_no_buses)
        } else {
            lines += context.getString(R.string.widget_feed_soon, network.label)
        }

        rows.forEachIndexed { index, id ->
            if (index < lines.size) {
                views.setTextViewText(id, lines[index])
                views.setViewVisibility(id, View.VISIBLE)
            } else {
                views.setViewVisibility(id, View.GONE)
            }
        }
        manager.updateAppWidget(widgetId, views)
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
