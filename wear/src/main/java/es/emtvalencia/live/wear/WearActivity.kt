package es.emtvalencia.live.wear

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme

internal const val PREFS = "wear"
internal const val KEY_COMP_STOP = "comp_stop"
internal const val KEY_FAVS = "wear_favs"
private const val KEY_SERVICES = "services"
private const val KEY_RADIUS = "radius"

private fun encodeFav(item: NearItem): String = listOf(
    item.key, item.name, item.detail, item.lat.toString(), item.lon.toString(),
    item.service.name, item.lines.joinToString(","), item.stopId.toString(),
).joinToString("\u001F")

internal fun decodeFav(raw: String): NearItem? {
    val parts = raw.split("\u001F")
    if (parts.size != 8) return null
    val service = runCatching { Svc.valueOf(parts[5]) }.getOrNull() ?: return null
    return NearItem(
        key = parts[0], name = parts[1], detail = parts[2],
        lat = parts[3].toDoubleOrNull() ?: return null,
        lon = parts[4].toDoubleOrNull() ?: return null,
        service = service,
        lines = if (parts[6].isBlank()) emptyList() else parts[6].split(","),
        stopId = parts[7].toIntOrNull() ?: -1,
    )
}

fun loadServices(context: Context): Set<Svc> {
    val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SERVICES, null)
    return saved?.mapNotNull { runCatching { Svc.valueOf(it) }.getOrNull() }?.toSet()
        ?: Svc.entries.toSet()
}

fun loadRadius(context: Context): Int =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_RADIUS, 800)

class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RefreshScheduler.schedule(this)
        setContent {
            MaterialTheme {
                AppScaffold {
                    WearApp()
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun WearApp() {
        val context = this
        val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
        val data = remember { WearData.load(context) }
        val repository = remember { WearRepository(data) }

        var services by remember { mutableStateOf(loadServices(context)) }
        var radius by remember { mutableStateOf(loadRadius(context)) }
        var location by remember { mutableStateOf<Pair<Double, Double>?>(null) }
        var items by remember { mutableStateOf<List<NearItem>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var selected by remember { mutableStateOf<NearItem?>(null) }
        var query by remember { mutableStateOf("") }
        var linesMode by remember { mutableStateOf(false) }
        var svcFilter by remember { mutableStateOf<Svc?>(null) }
        var filterOpen by remember { mutableStateOf(false) }
        var mapOpen by remember { mutableStateOf(false) }
        var mapLine by remember { mutableStateOf<Pair<String, Svc>?>(null) }
        var lineMapItems by remember { mutableStateOf<List<NearItem>>(emptyList()) }
        var lineMapShapes by remember { mutableStateOf<List<List<Pair<Double, Double>>>>(emptyList()) }
        var metroCache by remember { mutableStateOf<List<MetroStation>>(emptyList()) }
        var vbCache by remember { mutableStateOf<List<VbStation>>(emptyList()) }
        var rodaliesCache by remember { mutableStateOf<List<RodaliesStation>>(emptyList()) }
        var mbLines by remember { mutableStateOf<List<String>>(emptyList()) }
        var mbCache by remember { mutableStateOf<List<NearItem>>(emptyList()) }
        var mapBus by remember { mutableStateOf<List<NearItem>>(emptyList()) }
        var favs by remember {
            mutableStateOf(
                prefs.getStringSet(KEY_FAVS, emptySet()).orEmpty()
                    .mapNotNull { decodeFav(it) }.toSet(),
            )
        }

        fun toggleFav(item: NearItem) {
            favs = if (favs.any { it.key == item.key }) {
                favs.filterNot { it.key == item.key }.toSet()
            } else {
                favs + item
            }
            prefs.edit().putStringSet(KEY_FAVS, favs.map { encodeFav(it) }.toSet()).apply()
        }

        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }

        LaunchedEffect(Unit) {
            runCatching { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
            metroCache = repository.metroStations()
            vbCache = repository.valenbisiStations()
            rodaliesCache = WearData.rodaliesStations(context)
            mbLines = repository.metrobusLines()
        }

        LaunchedEffect(location, services) {
            val loc = location ?: return@LaunchedEffect
            mbCache = if (Svc.Metrobus in services) {
                repository.metrobusNear(loc.first, loc.second, 5000)
            } else {
                emptyList()
            }
        }

        LaunchedEffect(services, radius, metroCache, vbCache, rodaliesCache, mbCache) {
            val loc = rememberLocation(context)
            location = loc
            if (loc == null) {
                items = emptyList()
                loading = false
                return@LaunchedEffect
            }
            val (lat, lon) = loc
            val withBus = repository.nearbyAll(
                lat, lon, radius.toDouble(), services, metroCache, vbCache, rodaliesCache, mbCache,
            )
            val withLines = withBus.map { item ->
                if (item.service == Svc.Metrobus && item.lines.isEmpty()) {
                    val lines = repository.metrobusOccupancy(item.key.removePrefix("mb-")).map { it.line }.distinct()
                    if (lines.isNotEmpty()) item.copy(lines = lines) else item
                } else item
            }
            items = withLines.sortedBy { WearData.distance(lat, lon, it.lat, it.lon) }.take(15)
            loading = false
        }

        LaunchedEffect(mapLine) {
            val focus = mapLine
            if (focus == null) {
                lineMapItems = emptyList()
                lineMapShapes = emptyList()
            } else if (focus.second == Svc.Metrobus) {
                // Metrobús line view: shape only, no stop dots.
                lineMapItems = emptyList()
                lineMapShapes = repository.metrobusShapes(focus.first)
            } else {
                lineMapItems = WearData.stopsOnLine(data, metroCache, rodaliesCache, focus.first, focus.second)
                lineMapShapes = WearData.routeShapes(context, focus.first, focus.second)
            }
        }

        // Wide Metrobús fetch for the map so dots show well beyond the nearby-list radius.
        LaunchedEffect(mapOpen, location, services, mbCache) {
            val loc = location
            mapBus = if (mapOpen && loc != null && Svc.Metrobus in services) {
                mbCache
            } else {
                emptyList()
            }
        }

        // Opened from the complication: jump to that stop (or the map for "nearest").
        var compConsumed by remember { mutableStateOf(false) }
        LaunchedEffect(favs) {
            if (compConsumed) return@LaunchedEffect
            val compKey = (context as? android.app.Activity)?.intent?.getStringExtra(KEY_COMP_STOP)
            if (compKey == null) {
                compConsumed = true
                return@LaunchedEffect
            }
            compConsumed = true
            if (compKey == COMP_NEAREST) {
                mapOpen = true
            } else {
                favs.firstOrNull { it.key == compKey }?.let { selected = it }
            }
        }

        var backProgress by remember { mutableStateOf(0f) }
        PredictiveBackHandler(enabled = selected != null || filterOpen || mapOpen) { progress ->
            var completed = false
            try {
                progress.collect { backProgress = it.progress }
                completed = true
            } finally {
                if (completed) {
                    when {
                        selected != null -> selected = null
                        filterOpen -> filterOpen = false
                        mapOpen -> {
                            mapOpen = false
                            mapLine = null
                        }
                    }
                }
                backProgress = 0f
            }
        }

        val pagerState = rememberPagerState(pageCount = { 4 })
        val current = selected
        if (current != null) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = size.width * backProgress
                    alpha = 1f - backProgress * 0.15f
                },
            ) {
                DetailScreen(
                    item = current,
                    repository = repository,
                    context = context,
                    rodaliesTimes = { name -> WearData.rodaliesTimes(context, name) },
                    distanceM = location?.let { WearData.distance(it.first, it.second, current.lat, current.lon) },
                    isFav = favs.any { it.key == current.key },
                    onToggleFav = { toggleFav(current) },
                    onBack = { selected = null },
                )
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = !mapOpen) { page ->
                    when (page) {
                        0 -> NearbyScreen(
                            items,
                            loading,
                            repository,
                            { name -> WearData.rodaliesTimes(context, name) },
                            favKeys = favs.map { it.key }.toSet(),
                            onOpen = { selected = it },
                            onOpenMap = { mapOpen = true },
                            active = pagerState.currentPage == 0 && !filterOpen && !mapOpen,
                        )
                        1 -> SearchScreen(
                            query = query,
                            onQuery = { query = it },
                            linesMode = linesMode,
                            onLinesMode = { linesMode = it },
                            svcFilter = svcFilter,
                            onSvcFilter = { svcFilter = it },
                            filterOpen = filterOpen,
                            onFilterOpen = { filterOpen = it },
                        results = searchResults(query, data, metroCache, vbCache, rodaliesCache, mbLines, mbCache, items, services, linesMode, svcFilter),
                        onOpen = { item ->
                            val parts = item.key.split("-")
                            val lineKey = when {
                                linesMode && parts.size == 3 -> parts[2]
                                linesMode && parts.size == 2 && (parts[0] == "rdl" || parts[0] == "mbl") -> parts[1]
                                else -> null
                            }
                            if (lineKey != null) {
                                mapLine = lineKey to item.service
                                mapOpen = true
                            } else {
                                selected = item
                            }
                        },
                        active = pagerState.currentPage == 1 && !filterOpen && !mapOpen,
                        )
                        2 -> FavoritesScreen(
                            favs = favs.toList(),
                            repository = repository,
                            rodaliesFn = { name -> WearData.rodaliesTimes(context, name) },
                            onOpen = { selected = it },
                            active = pagerState.currentPage == 2 && !filterOpen && !mapOpen,
                        )
                        else -> SettingsScreen(
                            services = services,
                            onToggleService = { svc ->
                                services = if (svc in services) services - svc else services + svc
                                prefs.edit().putStringSet(KEY_SERVICES, services.map { it.name }.toSet()).apply()
                            },
                            radius = radius,
                            onRadius = { meters ->
                                radius = meters
                                prefs.edit().putInt(KEY_RADIUS, meters).apply()
                            },
                            active = pagerState.currentPage == 3 && !filterOpen && !mapOpen,
                        )
                    }
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(4) { index ->
                        Box(
                            Modifier
                                .size(if (pagerState.currentPage == index) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                ),
                        )
                    }
                }
                if (filterOpen) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            translationX = size.width * backProgress
                            alpha = 1f - backProgress * 0.15f
                        },
                    ) {
                        FilterScreen(
                            linesMode = linesMode,
                            onLinesMode = { linesMode = it },
                            svcFilter = svcFilter,
                            onSvcFilter = { svcFilter = it },
                            onClose = { filterOpen = false },
                        )
                    }
                }
                if (mapOpen) {
                    val loc = location
                    if (loc != null) {
                        val focus = mapLine
                        val mapItems = if (focus != null) lineMapItems else {
                            allMapItems(data, metroCache, vbCache, rodaliesCache, mapBus, services)
                        }
                        val shapes = if (focus != null) lineMapShapes else emptyList()
                        Box(
                            Modifier.fillMaxSize().graphicsLayer {
                                translationX = size.width * backProgress
                                alpha = 1f - backProgress * 0.15f
                            },
                        ) {
                            MapScreen(
                                loc.first, loc.second, mapItems, shapes,
                                onOpenStop = { key ->
                                    (mapItems + items).firstOrNull { it.key == key }?.let {
                                        selected = it
                                        mapOpen = false
                                        mapLine = null
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun allMapItems(
        data: WearData,
        metro: List<MetroStation>,
        vb: List<VbStation>,
        rodalies: List<RodaliesStation>,
        mapBus: List<NearItem>,
        services: Set<Svc>,
    ): List<NearItem> {
        val out = mutableListOf<NearItem>()
        if (Svc.Emt in services) {
            data.stops.forEach { stop ->
                out += NearItem(
                    key = "emt-${stop.id}", name = stop.name, detail = stop.lines.joinToString(" · "),
                    lat = stop.lat, lon = stop.lon, service = Svc.Emt, lines = stop.lines, stopId = stop.id,
                )
            }
        }
        if (Svc.Metro in services) {
            metro.forEach { station ->
                out += NearItem(
                    key = "metro-${station.id}", name = station.name, detail = station.lines.joinToString(" · "),
                    lat = station.lat, lon = station.lon, service = Svc.Metro, lines = station.lines, stopId = station.id,
                )
            }
        }
        if (Svc.Valenbisi in services) {
            vb.forEach { station ->
                out += NearItem(
                    key = "vb-${station.number}", name = station.name,
                    detail = "${station.bikes} bikes · ${station.stands} docks",
                    lat = station.lat, lon = station.lon, service = Svc.Valenbisi, lines = emptyList(), stopId = station.number,
                )
            }
        }
        if (Svc.Rodalies in services) {
            rodalies.forEach { station ->
                out += NearItem(
                    key = "rd-${station.name}", name = station.name, detail = station.lines.joinToString(" · "),
                    lat = station.lat, lon = station.lon, service = Svc.Rodalies, lines = station.lines,
                )
            }
        }
        if (Svc.Metrobus in services) {
            mapBus.forEach { out += it }
        }
        return out
    }

    private fun searchResults(
        query: String,
        data: WearData,
        metro: List<MetroStation>,
        vb: List<VbStation>,
        rodalies: List<RodaliesStation>,
        mbLines: List<String>,
        mbCache: List<NearItem>,
        nearby: List<NearItem>,
        services: Set<Svc>,
        linesMode: Boolean,
        svcFilter: Svc?,
    ): List<NearItem> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val inFilter: (Svc) -> Boolean = { svc -> svcFilter == null || svcFilter == svc }
        val out = mutableListOf<NearItem>()
        if (!linesMode) {
            if (Svc.Emt in services && inFilter(Svc.Emt)) {
                data.stops.filter { it.name.lowercase().contains(q) }.take(10).forEach { stop ->
                    out += NearItem(
                        key = "emt-${stop.id}", name = stop.name, detail = stop.lines.joinToString(" · "),
                        lat = stop.lat, lon = stop.lon, service = Svc.Emt, lines = stop.lines, stopId = stop.id,
                    )
                }
            }
            if (Svc.Metro in services && inFilter(Svc.Metro)) {
                metro.filter { it.name.lowercase().contains(q) }.take(6).forEach { station ->
                    out += NearItem(
                        key = "metro-${station.id}", name = station.name, detail = station.lines.joinToString(" · "),
                        lat = station.lat, lon = station.lon, service = Svc.Metro, lines = station.lines, stopId = station.id,
                    )
                }
            }
            if (Svc.Valenbisi in services && inFilter(Svc.Valenbisi)) {
                vb.filter { it.name.lowercase().contains(q) }.take(6).forEach { station ->
                    out += NearItem(
                        key = "vb-${station.number}", name = station.name,
                        detail = "${station.bikes} bikes · ${station.stands} docks",
                        lat = station.lat, lon = station.lon, service = Svc.Valenbisi, lines = emptyList(), stopId = station.number,
                    )
                }
            }
            if (Svc.Rodalies in services && inFilter(Svc.Rodalies)) {
                rodalies.filter { it.name.lowercase().contains(q) }.take(6).forEach { station ->
                    out += NearItem(
                        key = "rd-${station.name}", name = station.name, detail = station.lines.joinToString(" · "),
                        lat = station.lat, lon = station.lon, service = Svc.Rodalies, lines = station.lines,
                    )
                }
            }
            if (Svc.Metrobus in services && inFilter(Svc.Metrobus)) {
                mbCache.filter { it.name.lowercase().contains(q) }.take(6).forEach { out += it }
            }
        } else {
            if (Svc.Emt in services && inFilter(Svc.Emt)) {
                data.stops.flatMap { stop -> stop.lines.map { line -> line to stop } }
                    .filter { (line, _) -> line.lowercase().contains(q) }
                    .take(10).forEach { (line, stop) ->
                        out += NearItem(
                            key = "emt-${stop.id}-$line", name = "Line $line · ${stop.name}",
                            detail = stop.lines.joinToString(" · "),
                            lat = stop.lat, lon = stop.lon, service = Svc.Emt, lines = stop.lines, stopId = stop.id,
                        )
                    }
            }
            if (Svc.Metro in services && inFilter(Svc.Metro)) {
                metro.flatMap { station -> station.lines.map { line -> line to station } }
                    .filter { (line, _) -> line.lowercase().contains(q) }
                    .take(6).forEach { (line, station) ->
                        out += NearItem(
                            key = "metro-${station.id}-$line", name = "Line $line · ${station.name}",
                            detail = station.lines.joinToString(" · "),
                            lat = station.lat, lon = station.lon, service = Svc.Metro, lines = station.lines, stopId = station.id,
                        )
                    }
            }
            if (Svc.Rodalies in services && inFilter(Svc.Rodalies)) {
                rodalies.flatMap { it.lines }.distinct()
                    .filter { it.lowercase().contains(q) }
                    .take(6).forEach { line ->
                        out += NearItem(
                            key = "rdl-$line", name = "Line $line",
                            detail = "Rodalies", lat = 0.0, lon = 0.0,
                            service = Svc.Rodalies, lines = listOf(line),
                        )
                    }
            }
            if (Svc.Metrobus in services && inFilter(Svc.Metrobus)) {
                mbLines.filter { it.lowercase().contains(q) }
                    .take(6).forEach { line ->
                        out += NearItem(
                            key = "mbl-$line", name = "Line $line",
                            detail = "Metrobús", lat = 0.0, lon = 0.0,
                            service = Svc.Metrobus, lines = listOf(line),
                        )
                    }
            }
        }
        return out
    }

}
