package es.emtvalencia.live

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Picks the stop a widget instance shows, and remembers it per widget id. */
class WidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val transit = TransitDataLoader.load(this)

        setContent {
            EmtTheme {
                var query by remember { mutableStateOf("") }
                val results = remember(query) {
                    if (query.isBlank()) {
                        emptyList()
                    } else {
                        transit.stops.filter {
                            it.name.contains(query, ignoreCase = true) || it.id.toString() == query.trim()
                        }.take(30)
                    }
                }
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Widget stop", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search a stop") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.weight(1f)) {
                        items(results, key = { it.id }) { stop ->
                            ListItem(
                                headlineContent = { Text(cleanStopName(stop.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(stop.lines.joinToString(" · ")) },
                                modifier = Modifier.clickable {
                                    getSharedPreferences("emt", MODE_PRIVATE).edit()
                                        .putInt("widget_${widgetId}_stop", stop.id)
                                        .putString("widget_${widgetId}_name", cleanStopName(stop.name))
                                        .putString("widget_${widgetId}_network", Network.Emt.name)
                                        .putFloat("widget_${widgetId}_lat", stop.lat.toFloat())
                                        .putFloat("widget_${widgetId}_lon", stop.lon.toFloat())
                                        .apply()
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                    finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
