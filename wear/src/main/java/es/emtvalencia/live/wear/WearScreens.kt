package es.emtvalencia.live.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.delay

@Composable
fun StopsNearScreen(nearby: List<NearStop>, loading: Boolean, onOpen: (NearStop) -> Unit) {
    val state = rememberScalingLazyListState()
    ScreenScaffold(scrollState = state) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { ListHeader { Text("Nearby stops") } }
            if (loading) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            } else if (nearby.isEmpty()) {
                item {
                    Text(
                        "No stops nearby. Open the phone app once to grant location.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(nearby) { near ->
                    Card(
                        onClick = { onOpen(near) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                near.stop.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "${near.stop.lines.joinToString(" · ")} · ${near.meters.toInt()} m",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArrivalsScreen(near: NearStop, repository: WearRepository, onBack: () -> Unit) {
    val state = rememberScalingLazyListState()
    var arrivals by remember { mutableStateOf<List<Live>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(near) {
        while (true) {
            arrivals = repository.arrivals(near.stop.id)
            loading = false
            delay(20_000)
        }
    }
    ScreenScaffold(scrollState = state) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { ListHeader { Text(near.stop.name, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            if (loading) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            } else if (arrivals.isEmpty()) {
                item { Text("No arrivals", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(arrivals) { live ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("Line ${live.line}  ·  ${live.minutes}", style = MaterialTheme.typography.titleSmall)
                            if (live.destination.isNotBlank()) {
                                Text(
                                    live.destination,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            item {
                FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Place, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Back")
                }
            }
        }
    }
}
