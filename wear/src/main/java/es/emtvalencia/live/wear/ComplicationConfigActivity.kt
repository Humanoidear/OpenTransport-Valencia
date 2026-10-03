package es.emtvalencia.live.wear

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Check
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

/** Picks which stop the complication shows: nearest to you, or one of your saved stops. */
class ComplicationConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activity = this
        setContent {
            MaterialTheme {
                AppScaffold {
                    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                    var current by remember { mutableStateOf(prefs.getString(KEY_COMP_STOP, null)) }
                    val favs = remember {
                        prefs.getStringSet(KEY_FAVS, emptySet()).orEmpty().mapNotNull { decodeFav(it) }
                    }
                    fun pick(key: String?) {
                        prefs.edit().putString(KEY_COMP_STOP, key).apply()
                        current = key
                        ComplicationDataSourceUpdateRequester.create(
                            activity, ComponentName(activity, StopComplicationService::class.java),
                        ).requestUpdateAll()
                        setResult(RESULT_OK)
                        finish()
                    }
                    val state = rememberScalingLazyListState()
                    ScreenScaffold(scrollState = state) {
                        ScalingLazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize().crownScrollable(state),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item { TitleHeader(Icons.Filled.Tune, "Complication") }
                            item {
                                ConfigRow(
                                    title = "Nearest stop",
                                    subtitle = "Follows your location",
                                    selected = current == null || current == COMP_NEAREST,
                                ) { pick(COMP_NEAREST) }
                            }
                            items(favs, key = { it.key }) { item ->
                                ConfigRow(
                                    title = item.name,
                                    subtitle = item.detail,
                                    selected = current == item.key,
                                ) { pick(item.key) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Spacer(Modifier.size(6.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
