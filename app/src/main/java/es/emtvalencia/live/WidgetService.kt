package es.emtvalencia.live

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID)
        return WidgetViewsFactory(applicationContext, widgetId)
    }
}

class WidgetViewsFactory(private val context: android.content.Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private var items = listOf<Arrival>()

    override fun onCreate() {}
    override fun onDestroy() {}

    override fun onDataSetChanged() {
        val prefs = context.getSharedPreferences("emt", android.content.Context.MODE_PRIVATE)
        val stopId = prefs.getInt("widget_${widgetId}_stop", -1)
        val network = Network.valueOf(prefs.getString("widget_${widgetId}_network", Network.Emt.name) ?: Network.Emt.name)
        val cached = if (WidgetShared.cachedStopId == stopId) WidgetShared.cachedArrivals else emptyList()
        items = cached.ifEmpty { WidgetShared.arrivals(context, stopId, network) }.drop(1)
    }

    override fun getCount() = items.size
    override fun getViewTypeCount() = 1
    override fun hasStableIds() = false
    override fun getItemId(position: Int) = position.toLong()

    override fun getViewAt(position: Int): RemoteViews {
        val a = items[position]
        val p = WidgetShared.palette(context)
        val views = RemoteViews(context.packageName, R.layout.widget_row_item)
        val badge = WidgetShared.lineBadge(context, a.line)
        if (badge != null) {
            views.setImageViewBitmap(R.id.widget_row_img, badge)
            views.setViewVisibility(R.id.widget_row_img, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.widget_row_line, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_row_img, android.view.View.GONE)
            views.setTextViewText(R.id.widget_row_line, a.line)
            views.setTextColor(R.id.widget_row_line, p.accent)
            views.setViewVisibility(R.id.widget_row_line, android.view.View.VISIBLE)
        }
        views.setTextViewText(R.id.widget_row_minutes, a.minutes.ifBlank { a.arrivalTime.take(5) })
        views.setTextViewText(R.id.widget_row_dest, a.destination)
        views.setTextColor(R.id.widget_row_minutes, p.onContainer)
        views.setTextColor(R.id.widget_row_dest, p.variant)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null
}
