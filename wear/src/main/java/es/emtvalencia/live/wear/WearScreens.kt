package es.emtvalencia.live.wear

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AirportShuttle
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsRailway
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.withContext

@Composable
fun Modifier.crownScrollable(state: androidx.wear.compose.foundation.lazy.ScalingLazyListState, active: Boolean = true): Modifier {
    val focusRequester = remember { FocusRequester() }
    val behavior = RotaryScrollableDefaults.behavior(state)
    LaunchedEffect(active) {
        if (active) focusRequester.requestFocus()
    }
    return this
        .focusProperties { canFocus = active }
        .focusRequester(focusRequester)
        .focusable()
        .rotaryScrollable(behavior, focusRequester)
}

fun svcIcon(service: Svc): ImageVector = when (service) {
    Svc.Emt -> Icons.Filled.DirectionsBus
    Svc.Metro -> Icons.Filled.Train
    Svc.Valenbisi -> Icons.Filled.PedalBike
    Svc.Metrobus -> Icons.Filled.AirportShuttle
    Svc.Rodalies -> Icons.Filled.DirectionsRailway
}

fun svcAsset(service: Svc): String = when (service) {
    Svc.Emt -> "emt.png"
    Svc.Metro -> "metrovalencia.png"
    Svc.Valenbisi -> "valenbisi.png"
    Svc.Metrobus -> "metrobus.png"
    Svc.Rodalies -> "rodalies.png"
}

@Composable
fun ServiceLogo(service: Svc, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(service) {
        runCatching {
            context.assets.open(svcAsset(service)).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.size(22.dp))
    } else {
        Icon(svcIcon(service), contentDescription = null, modifier = modifier.size(22.dp))
    }
}

private val lineBadgeCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

/** Line badge image: geoportal generator for EMT, bundled PNG for Metrovalencia. */
@Composable
fun LineBadgeImage(line: String, service: Svc, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(line, service) { mutableStateOf(lineBadgeCache[line]) }
    LaunchedEffect(line, service) {
        if (!lineBadgeCache.containsKey(line)) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (service == Svc.Metro) {
                        val num = line.removePrefix("M").removePrefix("L")
                        context.assets.open("metrovalencia/$num.png").use {
                            BitmapFactory.decodeStream(it)
                        }
                    } else {
                        val url = "https://geoportal.emtvalencia.es/ciudadano/icongenerator/create-line-image.php" +
                            "?size=50&type=normal&lineNumber=${URLEncoder.encode(line, "UTF-8")}" +
                            "&showBorder=false&borderColor=white"
                        (URL(url).openConnection() as HttpURLConnection).run {
                            connectTimeout = 8000
                            readTimeout = 8000
                            try {
                                inputStream.use { BitmapFactory.decodeStream(it) }
                            } finally {
                                disconnect()
                            }
                        }
                    }
                }.getOrNull()
            }
            if (loaded != null) lineBadgeCache[line] = loaded
            bitmap = loaded
        }
    }
    val current: android.graphics.Bitmap = bitmap ?: run {
        Box(Modifier.width(26.dp).height(20.dp))
        return
    }
    Image(current.asImageBitmap(), contentDescription = null, modifier = modifier.height(20.dp))
}

/** Small text pill for lines without an image (Metrobús MB…, Rodalies C…). */
@Composable
fun LinePill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun SmallLineBadge(line: String, service: Svc, modifier: Modifier = Modifier) {
    if (service == Svc.Metrobus || service == Svc.Rodalies || line.startsWith("MB") || (line.startsWith("C") && line.length > 1)) {
        LinePill(line.removePrefix("MB"), modifier)
    } else {
        LineBadgeImage(line, service, modifier)
    }
}

@Composable
fun ItemCard(
    title: String,
    subtitle: String,
    service: Svc,
    trailing: String? = null,
    lines: List<String> = emptyList(),
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ServiceLogo(service)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (lines.isNotEmpty()) {
                    Spacer(Modifier.size(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        lines.take(4).forEach { line -> SmallLineBadge(line, service) }
                    }
                }
            }
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun StatNumberCard(value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Card(
        onClick = {},
        modifier = modifier,
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RowCard(title: String, subtitle: String, service: Svc? = null, trailing: String? = null, badgeLine: String? = null, badgeService: Svc? = null) {
    Card(onClick = {}, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            when {
                badgeLine != null && (badgeService == Svc.Emt || badgeService == Svc.Metro) ->
                    LineBadgeImage(badgeLine, badgeService ?: Svc.Emt)
                badgeLine != null -> LinePill(badgeLine)
                service != null -> ServiceLogo(service)
            }
            if (badgeLine != null || service != null) Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun TitleHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    ListHeader {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NearbyRowCard(
    item: NearItem,
    repository: WearRepository,
    rodaliesFn: (String) -> List<Live>,
    isFav: Boolean,
    onOpen: () -> Unit,
) {
    var vbMap by remember { mutableStateOf<Map<Int, VbStation>>(emptyMap()) }
    LaunchedEffect(Unit) {
        if (item.service == Svc.Valenbisi) {
            vbMap = repository.valenbisiStations().associateBy { it.number }
        }
    }
    var arrival by remember(item.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.key) {
        arrival = when (item.service) {
            Svc.Emt -> repository.arrivals(item.stopId).firstOrNull()?.let { "${it.line} · ${it.minutes}" }
            Svc.Metro -> repository.metroArrivals(item.stopId).firstOrNull()?.let { "${it.line} · ${it.minutes}" }
            Svc.Metrobus -> repository.metrobusOccupancy(item.key.removePrefix("mb-")).firstOrNull()?.let { "${it.line} · ${it.minutes}" }
            Svc.Rodalies -> rodaliesFn(item.name).firstOrNull()?.let { "${it.line} · ${it.minutes}" }
            Svc.Valenbisi -> null
        }
    }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ServiceLogo(item.service, Modifier.size(20.dp))
                Spacer(Modifier.size(6.dp))
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (isFav) {
                    Spacer(Modifier.size(4.dp))
                    Icon(Icons.Filled.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.size(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.lines.take(4).forEach { line -> SmallLineBadge(line, item.service) }
                }
                if (item.service == Svc.Valenbisi) {
                    val station = vbMap[item.stopId]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PedalBike, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(2.dp))
                        Text(
                            station?.bikes?.toString() ?: "–",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(6.dp))
                        Icon(Icons.Filled.LocalParking, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(2.dp))
                        Text(
                            station?.stands?.toString() ?: "–",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (arrival != null) {
                    Text(arrival!!, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun NearbyScreen(
    items: List<NearItem>,
    loading: Boolean,
    repository: WearRepository,
    rodaliesFn: (String) -> List<Live>,
    favKeys: Set<String>,
    onOpen: (NearItem) -> Unit,
    onOpenMap: () -> Unit,
    active: Boolean = true,
) {
    val state = rememberScalingLazyListState()
    ScreenScaffold(scrollState = state) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().crownScrollable(state, active),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { TitleHeader(Icons.Filled.Place, "Nearby") }
            item {
                Card(
                    onClick = onOpenMap,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Map, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "Map",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
            if (loading) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            } else if (items.isEmpty()) {
                item {
                    Text(
                        "Nothing nearby. Check services and distance in Settings.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(items, key = { it.key }) { item ->
                    NearbyRowCard(
                        item, repository, rodaliesFn,
                        isFav = favKeys.contains(item.key),
                    ) { onOpen(item) }
                }
            }
        }
    }
}

data class DetailRow(
    val title: String,
    val subtitle: String,
    val trailing: String? = null,
    val badgeLine: String? = null,
    val badgeService: Svc? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    item: NearItem,
    repository: WearRepository,
    context: Context,
    rodaliesTimes: (String) -> List<Live>,
    distanceM: Double?,
    isFav: Boolean,
    onToggleFav: () -> Unit,
    onBack: () -> Unit,
    active: Boolean = true,
) {
    val state = rememberScalingLazyListState()
    var rows by remember(item) { mutableStateOf<List<DetailRow>>(emptyList()) }
    var loading by remember(item) { mutableStateOf(true) }
    var vbStation by remember(item) { mutableStateOf<VbStation?>(null) }
    LaunchedEffect(item) {
        while (true) {
            rows = when (item.service) {
                Svc.Emt -> {
                    val live = repository.arrivals(item.stopId)
                    val dist = mutableMapOf<String, Double?>()
                    live.map { it.line }.distinct().forEach { line ->
                        dist[line] = repository.busDistanceMeters(line, item.lat, item.lon)
                    }
                    live.map {
                        val meters = dist[it.line]
                        val where = when {
                            meters == null -> ""
                            meters < 25 -> "at the stop"
                            meters < 1000 -> "${meters.toInt()} m away"
                            else -> "%.1f km away".format(java.util.Locale.US, meters / 1000)
                        }
                        DetailRow(it.destination.ifBlank { "Line ${it.line}" }, where, it.minutes, it.line, Svc.Emt)
                    }
                }
                Svc.Metro -> repository.metroArrivals(item.stopId).map { DetailRow(it.destination.ifBlank { "Line ${it.line}" }, "Line ${it.line}", it.minutes, it.line, Svc.Metro) }
                Svc.Valenbisi -> {
                    val bikes = repository.valenbisiBikes(item.stopId)
                    vbStation = repository.valenbisiStations().firstOrNull { it.number == item.stopId }
                    bikes.map { bike ->
                        val rating = if (bike.ratings > 0) "★ ${(bike.rating / 20).toInt()} (${bike.ratings})" else "no rating"
                        DetailRow("Stand ${bike.stand} · ${bike.type.lowercase()}", "Bike ${bike.number}", rating)
                    }
                }
                Svc.Metrobus -> repository.metrobusOccupancy(item.key.removePrefix("mb-")).map {
                    DetailRow(it.destination.ifBlank { "Line ${it.line}" }, "Line ${it.line}", it.minutes, it.line, Svc.Metrobus)
                }
                Svc.Rodalies -> rodaliesTimes(item.name).map { DetailRow(it.destination.ifBlank { "Line ${it.line}" }, "Line ${it.line}", it.minutes, it.line, Svc.Rodalies) }
            }
            loading = false
            delay(30_000)
        }
    }
    ScreenScaffold(scrollState = state) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().crownScrollable(state, active),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ServiceLogo(item.service, Modifier.size(40.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        item.name,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.basicMarquee(),
                    )
                    if (item.lines.isNotEmpty()) {
                        Spacer(Modifier.size(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            item.lines.take(5).forEach { line -> SmallLineBadge(line, item.service) }
                        }
                    }
                    if (distanceM != null) {
                        Text(
                            "${distanceM.toInt()} m away",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (loading) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            } else if (rows.isEmpty()) {
                item { Text("No data right now", style = MaterialTheme.typography.bodySmall) }
            } else {
                if (item.service == Svc.Valenbisi && vbStation != null) {
                    val station = vbStation!!
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatNumberCard(
                                value = station.bikes.toString(),
                                label = "bikes",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            StatNumberCard(
                                value = station.stands.toString(),
                                label = "docks",
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                items(rows) { row ->
                    RowCard(row.title, row.subtitle, item.service, row.trailing, row.badgeLine, row.badgeService)
                }
            }
            item {
                FilledTonalButton(
                    onClick = onToggleFav,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (isFav) "Saved" else "Save stop")
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        val uri = android.net.Uri.parse("google.navigation:q=${item.lat},${item.lon}&mode=w")
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Navigation, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Walk there")
                }
            }
            item {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Back")
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    linesMode: Boolean,
    onLinesMode: (Boolean) -> Unit,
    svcFilter: Svc?,
    onSvcFilter: (Svc?) -> Unit,
    filterOpen: Boolean,
    onFilterOpen: (Boolean) -> Unit,
    results: List<NearItem>,
    onOpen: (NearItem) -> Unit,
    active: Boolean = true,
) {
    val state = rememberScalingLazyListState()
    ScreenScaffold(scrollState = state) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().crownScrollable(state, active),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { TitleHeader(Icons.Filled.Search, "Search") }
            item {
                SurfaceCard {
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        decorationBox = { inner ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(8.dp))
                                Box(Modifier.weight(1f)) {
                                    if (query.isEmpty()) Text("Stop, station or line", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    inner()
                                }
                            }
                        },
                    )
                }
            }
            item {
                val summary = buildList {
                    add(if (linesMode) "Lines" else "Stops")
                    add(svcFilter?.name ?: "All services")
                }.joinToString(" · ")
                Card(
                    onClick = { onFilterOpen(true) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Filters", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (query.trim().length >= 2 && results.isEmpty()) {
                item {
                    Text(
                        "No matches — try another name or line",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(results, key = { it.key }) { item ->
                ItemCard(item.name, item.detail, item.service, lines = item.lines) { onOpen(item) }
            }
        }
    }
}

@Composable
fun FilterScreen(
    linesMode: Boolean,
    onLinesMode: (Boolean) -> Unit,
    svcFilter: Svc?,
    onSvcFilter: (Svc?) -> Unit,
    onClose: () -> Unit,
    active: Boolean = true,
) {
    val state = rememberScalingLazyListState()
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
    ScreenScaffold(scrollState = state) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().crownScrollable(state, active),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(6.dp))
                    Text("Filters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            item { ListHeader { Text("Show") } }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterPill(selected = !linesMode, label = "Stops", onClick = { onLinesMode(false) }, modifier = Modifier.weight(1f))
                    FilterPill(selected = linesMode, label = "Lines", onClick = { onLinesMode(true) }, modifier = Modifier.weight(1f))
                }
            }
            item { ListHeader { Text("Service") } }
            item {
                FilterPill(selected = svcFilter == null, label = "All services", onClick = { onSvcFilter(null) }, modifier = Modifier.fillMaxWidth())
            }
            items(Svc.entries.toList()) { svc ->
                val selected = svcFilter == svc
                Card(
                    onClick = { onSvcFilter(if (selected) null else svc) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ServiceLogo(svc, Modifier.size(24.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            svc.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                        if (selected) {
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
            item {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            }
        }
    }
    }
}

@Composable
fun FilterPill(selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LazyServiceRow(selected: Svc?, onSelect: (Svc?) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ServiceDot(selected == null, "All", onClick = { onSelect(null) }, service = null, modifier = Modifier.weight(1f))
        Svc.entries.take(3).forEach { svc ->
            ServiceDot(svc == selected, svc.name, onClick = { onSelect(if (selected == svc) null else svc) }, service = svc, modifier = Modifier.weight(1f))
        }
    }
    Spacer(Modifier.size(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Svc.entries.drop(3).forEach { svc ->
            ServiceDot(svc == selected, svc.name, onClick = { onSelect(if (selected == svc) null else svc) }, service = svc, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ServiceDot(selected: Boolean, label: String, onClick: () -> Unit, service: Svc?, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (service != null) ServiceLogo(service, Modifier.size(20.dp)) else Icon(Icons.Filled.Map, contentDescription = null)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun SurfaceCard(content: @Composable () -> Unit) {
    Card(onClick = {}, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
    }
}

@Composable
fun MapScreen(
    lat: Double,
    lon: Double,
    items: List<NearItem>,
    lineShapes: List<List<Pair<Double, Double>>> = emptyList(),
    onOpenStop: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = true
    val mapView = remember {
        MapLibre.getInstance(context)
        // Focusable so the crown (ACTION_SCROLL) reaches the map instead of the list behind.
        MapView(context).apply {
            onCreate(null)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnGenericMotionListener { _, event ->
                if (event.action != android.view.MotionEvent.ACTION_SCROLL) return@setOnGenericMotionListener false
                val scroll = event.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL)
                if (scroll == 0f) return@setOnGenericMotionListener true
                getMapAsync { map ->
                    val target = (map.cameraPosition.zoom + scroll * 1.5).coerceIn(8.0, 18.0)
                    map.animateCamera(CameraUpdateFactory.zoomTo(target), 120)
                }
                true
            }
        }
    }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var lastJson by remember { mutableStateOf("") }
    var lastLineJson by remember { mutableStateOf("") }
    var lastCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var styleRequested by remember { mutableStateOf(false) }
    val addedImages = remember { mutableSetOf<String>() }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun applyData(map: MapLibreMap, json: String, lineJson: String) {
        val style = map.style ?: return
        style.getSourceAs<GeoJsonSource>("wear-stops")?.setGeoJson(json)
        style.getSourceAs<GeoJsonSource>("wear-line")?.setGeoJson(lineJson)
    }
    var setupStyle: ((MapLibreMap, Style) -> Unit)? = null
    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize().zIndex(1f),
        update = {
            it.requestFocus()
            it.getMapAsync { map ->
                mapRef = map
                map.uiSettings.setAllGesturesEnabled(true)
                // Style is set once; re-setting it wipes the dot images and blanks the map.
                if (!styleRequested) {
                    styleRequested = true
                    val styleUrl = if (dark) STYLE_DARK else STYLE_LIGHT
                    map.setStyle(styleUrl) { style -> setupStyle?.invoke(map, style) }
                } else {
                    applyData(map, stopsJson(items), lineJson(lineShapes))
                    lastJson = stopsJson(items)
                    lastLineJson = lineJson(lineShapes)
                    map.style?.getSourceAs<GeoJsonSource>("wear-user")?.setGeoJson(userJson(lat, lon))
                }
            }
        },
    )
    setupStyle = { map, style ->
                    if (style.getSource("wear-stops") == null) {
                        style.addSource(GeoJsonSource("wear-stops", emptyStopsJson()))
                    }
                    if (style.getLayer("wear-stops") == null) {
                        // One dot image per provider: logo contained in a colored circle.
                        val services = mapOf(
                            "Emt" to ("emt.png" to "#F97316"),
                            "Metro" to ("metrovalencia.png" to "#E4002B"),
                            "Valenbisi" to ("valenbisi.png" to "#22C55E"),
                            "Metrobus" to ("metrobus.png" to "#0EA5E9"),
                            "Rodalies" to ("rodalies.png" to "#7C3AED"),
                        )
                        val stops = mutableListOf<Expression.Stop>()
                        services.forEach { (service, pair) ->
                            val imageId = "wear-dot-$service"
                            if (addedImages.add(imageId)) {
                                val logo = runCatching {
                                    context.assets.open(pair.first).use { BitmapFactory.decodeStream(it) }
                                }.getOrNull()
                                style.addImage(imageId, wearDotBitmap(logo, android.graphics.Color.parseColor(pair.second)))
                            }
                            stops += Expression.stop(service, imageId)
                        }
                        style.addLayer(
                            SymbolLayer("wear-stops", "wear-stops").withProperties(
                                PropertyFactory.iconImage(
                                    Expression.match(
                                        Expression.get("service"),
                                        Expression.literal("wear-dot-Emt"),
                                        *stops.toTypedArray(),
                                    ),
                                ),
                                PropertyFactory.iconSize(0.5f),
                                PropertyFactory.iconAllowOverlap(true),
                                PropertyFactory.iconIgnorePlacement(true),
                            ).also { it.minZoom = 14f },
                        )
                    }
                    if (style.getSource("wear-line") == null) {
                        style.addSource(GeoJsonSource("wear-line", emptyStopsJson()))
                    }
                    if (style.getLayer("wear-line") == null) {
                        var lineLayer = style.getLayer("wear-stops")
                        val line = LineLayer("wear-line", "wear-line").withProperties(
                            PropertyFactory.lineColor("#38BDF8"),
                            PropertyFactory.lineWidth(4f),
                        )
                        if (lineLayer != null) style.addLayerBelow(line, lineLayer.id) else style.addLayer(line)
                    }
                    if (style.getSource("wear-user") == null) {
                        style.addSource(GeoJsonSource("wear-user", emptyStopsJson()))
                    }
                    if (style.getLayer("wear-user") == null) {
                        style.addLayer(
                            CircleLayer("wear-user", "wear-user").withProperties(
                                PropertyFactory.circleRadius(5f),
                                PropertyFactory.circleColor("#1A73E8"),
                                PropertyFactory.circleStrokeColor("#FFFFFF"),
                                PropertyFactory.circleStrokeWidth(2f),
                            ),
                        )
                    }
                    style.getSourceAs<GeoJsonSource>("wear-user")?.setGeoJson(userJson(lat, lon))
                    applyData(map, stopsJson(items), lineJson(lineShapes))
                    lastJson = stopsJson(items)
                    lastLineJson = lineJson(lineShapes)
                    if (lastCenter == null) {
                        lastCenter = VALENCIA // one-shot: mark centered
                        if (lineShapes.isNotEmpty()) {
                            val bounds = org.maplibre.android.geometry.LatLngBounds.Builder().also { b ->
                                lineShapes.flatten().forEach { (shapeLat, shapeLon) ->
                                    b.include(LatLng(shapeLat, shapeLon))
                                }
                            }.build()
                            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60), 600)
                        } else {
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16.0), 600)
                        }
                    }
                    map.addOnMapClickListener { point ->
                        val key = map.queryRenderedFeatures(
                            map.projection.toScreenLocation(point), "wear-stops",
                        ).firstOrNull()?.getStringProperty("stopKey")
                        if (key != null) {
                            onOpenStop(key)
                            true
                        } else {
                            false
                        }
                    }
    }
    LaunchedEffect(items, lineShapes, mapRef) {
        val map = mapRef ?: return@LaunchedEffect
        if (map.style == null) return@LaunchedEffect
        val json = stopsJson(items)
        if (json != lastJson) {
            lastJson = json
            map.style?.getSourceAs<GeoJsonSource>("wear-stops")?.setGeoJson(json)
        }
        val lj = lineJson(lineShapes)
        if (lj != lastLineJson) {
            lastLineJson = lj
            map.style?.getSourceAs<GeoJsonSource>("wear-line")?.setGeoJson(lj)
        }
    }
}

private val VALENCIA = 39.4699 to -0.3763

private const val STYLE_DARK = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
private const val STYLE_LIGHT = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"

private fun emptyStopsJson(): String =
    JSONObject().put("type", "FeatureCollection").put("features", JSONArray()).toString()

// Provider logo aspect-fitted inside a colored circle with a white ring.
private fun wearDotBitmap(logo: android.graphics.Bitmap?, color: Int, size: Int = 72): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val r = size / 2f
    canvas.drawCircle(r, r, r, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    canvas.drawCircle(
        r, r, r - 3f,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 5f
            this.color = android.graphics.Color.WHITE
        },
    )
    if (logo != null) {
        val s = size * 0.52f
        val scale = minOf(s / logo.width, s / logo.height)
        val w = logo.width * scale
        val h = logo.height * scale
        canvas.drawBitmap(
            logo, null,
            android.graphics.RectF(r - w / 2, r - h / 2, r + w / 2, r + h / 2),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
    }
    return bmp
}

private fun stopsJson(items: List<NearItem>): String {
    val features = JSONArray()
    items.forEach { item ->
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("properties", JSONObject().put("service", item.service.name).put("name", item.name).put("stopKey", item.key))
                .put(
                    "geometry",
                    JSONObject().put("type", "Point")
                        .put("coordinates", JSONArray().put(item.lon).put(item.lat)),
                ),
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

private fun lineJson(shapes: List<List<Pair<Double, Double>>>): String {
    val features = JSONArray()
    shapes.forEach { shape ->
        if (shape.size < 2) return@forEach
        val coords = JSONArray()
        shape.forEach { (lat, lon) -> coords.put(JSONArray().put(lon).put(lat)) }
        features.put(
            JSONObject()
                .put("type", "Feature")
                .put("properties", JSONObject())
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords)),
        )
    }
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

private fun userJson(lat: Double, lon: Double): String {    val features = JSONArray().put(
        JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put(
                "geometry",
                JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat)),
            ),
    )
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    favs: List<NearItem>,
    repository: WearRepository,
    rodaliesFn: (String) -> List<Live>,
    onOpen: (NearItem) -> Unit,
    active: Boolean = true,
) {
    val state = rememberScalingLazyListState()
    ScreenScaffold(scrollState = state) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().crownScrollable(state, active),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { TitleHeader(Icons.Filled.Star, "Saved") }
            if (favs.isEmpty()) {
                item {
                    Text(
                        "No saved stops yet. Open a stop and tap Save stop to keep it here.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(favs, key = { it.key }) { item ->
                    NearbyRowCard(item, repository, rodaliesFn, isFav = true) { onOpen(item) }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    services: Set<Svc>,
    onToggleService: (Svc) -> Unit,
    radius: Int,
    onRadius: (Int) -> Unit,
    active: Boolean = true,
) {
    val state = rememberScalingLazyListState()
    ScreenScaffold(scrollState = state) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().crownScrollable(state, active),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { TitleHeader(Icons.Filled.Settings, "Services") }
            item {
                Text(
                    "Choose which networks appear in Nearby, Search and Map.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(Svc.entries.toList()) { service ->
                val on = service in services
                Card(
                    onClick = { onToggleService(service) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ServiceLogo(service)
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(service.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                when (service) {
                                    Svc.Emt -> "City buses, live arrivals"
                                    Svc.Metro -> "Metro & tram stations"
                                    Svc.Valenbisi -> "Bike docks & availability"
                                    Svc.Metrobus -> "Metropolitan buses"
                                    Svc.Rodalies -> "Commuter trains"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            if (on) "ON" else "OFF",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                TitleHeader(Icons.Filled.Place, "Distance")
            }
            item {
                Text(
                    "How far away stops can be to show up around you.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(listOf(200, 500, 1000, 2000)) { meters ->
                Card(
                    onClick = { onRadius(meters) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Place, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (meters < 1000) "$meters m" else "${meters / 1000} km",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (radius == meters) {
                            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                Text(
                    "Unofficial app. Data: EMT València, Metrovalencia, Valenbisi, Metrobús, Renfe. Times may vary.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
