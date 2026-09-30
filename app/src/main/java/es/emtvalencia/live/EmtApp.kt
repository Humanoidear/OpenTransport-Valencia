package es.emtvalencia.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

private val LINE_PALETTE = listOf(
    Color(0xFFF97316), Color(0xFF0EA5E9), Color(0xFFE11D48), Color(0xFF8B5CF6),
    Color(0xFF14B8A6), Color(0xFFF43F5E), Color(0xFF6366F1), Color(0xFF22C55E),
    Color(0xFFF59E0B), Color(0xFF06B6D4), Color(0xFFA855F7), Color(0xFF84CC16),
)
private val IDA = Color(0xFF3B82F6)
private val VUELTA = Color(0xFFEF4444)
private const val DARK_STYLE = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
private const val LIGHT_STYLE = "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"

/** Stable per-line colour used on both the map and the badges. */
fun stableLineColor(line: String): Color = LINE_PALETTE[abs(line.hashCode()) % LINE_PALETTE.size]

/**
 * Bus positions live outside the app's root state so a 30 fps update only
 * invalidates the map and the follow sheet, never the whole screen.
 */
class BusStore {
    var buses by mutableStateOf<List<BusPosition>>(emptyList(), neverEqualPolicy())
}

fun cleanStopName(nombre: String) = nombre.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "").trim()

private val preparedCache = mutableMapOf<List<LonLat>, PreparedRoute>()
private fun prepared(shape: List<LonLat>) = preparedCache.getOrPut(shape) { TransitMotion.prepare(shape) }

/** A line plus every line that shares a stop with it (transferable routes). */
private fun linesTouching(transit: TransitData, line: String): Set<String> {
    val touching = mutableSetOf(line)
    transit.stops.forEach { stop -> if (line in stop.lines) touching += stop.lines }
    return touching
}
private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/Madrid") }

/** Fallback camera target when location is unavailable. */
private val VALENCIA_CENTRE = LonLat(39.4699, -0.3763)

private fun loadFavorites(context: Context): Set<String> =
    context.getSharedPreferences("emt", Context.MODE_PRIVATE).getStringSet("favorites", emptySet()).orEmpty().toSet()

private fun saveFavorites(context: Context, ids: Set<String>) {
    context.getSharedPreferences("emt", Context.MODE_PRIVATE).edit()
        .putStringSet("favorites", ids).apply()
}

private fun stopKey(id: Int) = "emt-$id"

@Composable
fun EmtApp() {
    val context = LocalContext.current
    val transit = remember { TransitDataLoader.load(context) }
    val repository = remember { EmtRepository(transit) }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var currentLine by remember { mutableStateOf<String?>(null) }
    val busStore = remember { BusStore() }
    var selectedStop by remember { mutableStateOf<Stop?>(null) }
    var stopInfo by remember { mutableStateOf<StopInfo?>(null) }
    var loadingStop by remember { mutableStateOf(false) }
    var incidents by remember { mutableStateOf<List<Incident>>(emptyList()) }
    var followedBus by remember { mutableStateOf<Int?>(null) }
    var followedLine by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(loadFavorites(context)) }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var mapController by remember { mutableStateOf<NativeMapController?>(null) }
    var userLocation by remember { mutableStateOf<LonLat?>(null) }
    var tilt by remember { mutableStateOf(false) }
    var planFrom by remember { mutableStateOf<Stop?>(null) }
    var planTo by remember { mutableStateOf<Stop?>(null) }
    var planOptions by remember { mutableStateOf<List<JourneyOption>>(emptyList()) }
    var planning by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    var plannedJourney by remember { mutableStateOf<List<JourneySegment>>(emptyList()) }
    var plannedJourneyStops by remember { mutableStateOf<List<JourneyStop>>(emptyList()) }
    var plannedStopIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var plannedOption by remember { mutableStateOf<JourneyOption?>(null) }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var enabledNetworks by remember { mutableStateOf(setOf(Network.Emt, Network.Metro, Network.Valenbisi)) }
    var selectedPlace by remember { mutableStateOf<Place?>(null) }
    var showLayers by remember { mutableStateOf(false) }
    var savedService by remember { mutableStateOf<Network?>(null) }
    var alertsService by remember { mutableStateOf<Network?>(null) }
    var planModes by remember { mutableStateOf(setOf(Network.Emt)) }
    var language by remember { mutableStateOf(loadLanguage(context)) }
    SideEffect { currentStrings.value = Strings(language) }

    val scope = rememberCoroutineScope()
    val planner = remember(transit, repository) {
        JourneyPlanner(transit, { a, b -> repository.walkMetrics(a, b) }, { id, line -> repository.nextBus(id, line) })
    }

    // Ask for location on launch and open the map there, zoomed in.
    var hasLocationPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLocationPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) runCatching { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }
    LaunchedEffect(mapController, hasLocationPermission) {
        if (hasLocationPermission) {
            lastKnownLocation(context)?.let { userLocation = it }
        }
    }

    // Keep the "you are here" dot fresh; the lookup stays off the main thread.
    LaunchedEffect(hasLocationPermission) {
        if (!hasLocationPermission) {
            userLocation = null
            return@LaunchedEffect
        }
        while (true) {
            withContext(Dispatchers.IO) { runCatching { lastKnownLocation(context) }.getOrNull() }
                ?.let { userLocation = it }
            delay(10_000)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            busStore.buses = busStore.buses.map { advance(it, now) }
            delay(33)
        }
    }

    LaunchedEffect(currentLine) {
        // Drop the previous line's buses (and their numbers) immediately, so a
        // stale marker can never be tapped while the new line loads.
        busStore.buses = emptyList()
        val line = currentLine ?: return@LaunchedEffect
        while (true) {
            val fixes = runCatching { repository.buses(line) }.getOrDefault(emptyList())
            busStore.buses = merge(fixes, busStore.buses, transit, System.currentTimeMillis())
            delay(1_000)
        }
    }

    LaunchedEffect(selectedStop) {
        val stop = selectedStop ?: return@LaunchedEffect
        while (true) {
            if (stopInfo == null) loadingStop = true
            stopInfo = runCatching { repository.stop(stop.id) }.getOrNull()
            loadingStop = false
            delay(10_000)
        }
    }

    LaunchedEffect(Unit) {
        val emt = runCatching { repository.incidents() }.getOrDefault(emptyList())
        val metro = runCatching { repository.metroIncidents() }.getOrDefault(emptyList())
        incidents = emt + metro
    }

    // Metrovalencia stations load once; Valenbisi availability refreshes on a timer.
    LaunchedEffect(Unit) {
        val stations = runCatching { repository.metroStations() }.getOrDefault(emptyList())
        if (stations.isNotEmpty()) places = places.filterNot { it.network == Network.Metro } + stations
    }
    LaunchedEffect(Unit) {
        while (true) {
            val bikes = runCatching { repository.valenbisi() }.getOrDefault(emptyList())
            if (bikes.isNotEmpty()) places = places.filterNot { it.network == Network.Valenbisi } + bikes
            delay(60_000)
        }
    }
    val visiblePlaces = remember(places, enabledNetworks) { places.filter { it.network in enabledNetworks } }

    val visibleStops = remember(viewport, transit, currentLine, selectedStop, followedLine) {
        val vp = viewport ?: return@remember emptyList()
        if (vp.zoom < 12.0) return@remember emptyList()
        // Focusing a line hides every stop that does not serve it; focusing a
        // bus hides everything off its line too.
        val line = currentLine ?: followedLine
        val stopId = selectedStop?.id
        transit.stops.asSequence()
            .filter { !it.metro }
            // A revealed line shows only its own stops; otherwise the chosen stop
            // or the whole network.
            .filter {
                when {
                    line != null -> it.lines.contains(line)
                    stopId != null -> it.id == stopId
                    else -> true
                }
            }
            .filter { it.lat in vp.south..vp.north && it.lon in vp.west..vp.east }
            .take(400).toList()
    }

    val scheme = MaterialTheme.colorScheme
    val contextLines = selectedStop?.lines.orEmpty()
    val mapColors = MapColors(
        stopFill = scheme.onSurfaceVariant.toArgb(),
        stopStroke = scheme.surface.toArgb(),
        building = scheme.surfaceVariant.toArgb(),
        // Distinct colour per line crossing the selected stop, not hash-based
        // (which could give two lines the same colour).
        routes = contextLines.distinct()
            .mapIndexed { index, line -> line to LINE_PALETTE[index % LINE_PALETTE.size].toArgb() }
            .toMap(),
        ida = IDA.toArgb(),
        vuelta = VUELTA.toArgb(),
        busText = Color.White.toArgb(),
        busHalo = scheme.scrim.toArgb(),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Map, contentDescription = null) },
                    label = { Text(currentStrings.value.map) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    label = { Text(currentStrings.value.saved) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = {
                        if (incidents.isEmpty()) {
                            Icon(Icons.Filled.Warning, contentDescription = null)
                        } else {
                            BadgedBox(badge = { Badge { Text(incidents.size.toString()) } }) {
                                Icon(Icons.Filled.Warning, contentDescription = null)
                            }
                        }
                    },
                    label = { Text(currentStrings.value.alerts) },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Route, contentDescription = null) },
                    label = { Text(currentStrings.value.plan) },
                )
                NavigationBarItem(
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(currentStrings.value.settings) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // The map is the base layer and stays composed for every tab, so its
            // camera position and the expensive MapView are never reloaded.
            MapTab(
                transit = transit,
                mapColors = mapColors,
                visibleStops = visibleStops,
                contextLines = if (currentLine == null) contextLines else emptyList(),
                currentLine = currentLine,
                busStore = busStore,
                followBusNumber = followedBus,
                userLocation = userLocation,
                journey = plannedJourney,
                journeyStops = plannedJourneyStops,
                journeyStopIds = plannedStopIds,
                // Focusing one dot hides every other: a stop leaves its own
                // marker only, a place leaves itself only.
                places = when {
                    currentLine != null || followedBus != null -> emptyList()
                    selectedPlace != null -> listOf(selectedPlace!!)
                    selectedStop != null -> emptyList()
                    else -> visiblePlaces
                },
                metroLines = selectedPlace?.takeIf { it.network == Network.Metro }?.lines.orEmpty(),
                enabledNetworks = enabledNetworks,
                onPlaceTap = { place ->
                    selectedPlace = place
                    selectedStop = null
                    stopInfo = null
                    followedBus = null
                    followedLine = null
                    currentLine = null
                },
                onZoomTo = { point -> mapController?.moveCamera(point, 16.5, duration = 700) },
                darkTheme = isSystemInDarkTheme(),
                query = query,
                onQueryChange = { value ->
                    query = value
                    if (value.isNotBlank()) searchActive = true
                },
                searchActive = searchActive,
                onSearchActiveChange = { searchActive = it },
                onStopTap = { stop ->
                    selectedStop = stop
                    selectedPlace = null
                    stopInfo = null
                    followedBus = null
                    followedLine = null
                    currentLine = null
                    searchActive = false
                },
                onBusTap = { number ->
                    followedBus = number
                    followedLine = busStore.buses.firstOrNull { it.number == number }?.line
                    selectedStop = null
                    stopInfo = null
                    selectedPlace = null
                },
                onViewport = { viewport = it },
                onController = { mapController = it },
                onToggleTilt = {
                    val next = !tilt
                    tilt = next
                    mapController?.setPitch(if (next) 55.0 else 0.0)
                },
                onResetView = {
                    val line = currentLine
                    mapController?.resetView(if (line != null) transit.routes[line]?.let { lineBounds(it) } else null)
                    tilt = false
                },
                onShowLayers = { showLayers = true },
                onLocated = { point ->
                    point?.let {
                        userLocation = it
                        mapController?.moveCamera(it, 15.0, duration = 800)
                    }
                },
                onLinePick = { line ->
                    selectedStop = null
                    stopInfo = null
                    selectedPlace = null
                    followedBus = null
                    followedLine = null
                    currentLine = line
                    tab = 0
                    transit.routes[line]?.let { route -> lineBounds(route)?.let { mapController?.fit(it, 240) } }
                },
            )

            if (tab != 0) {
                Surface(Modifier.fillMaxSize()) {
                    when (tab) {
                        1 -> SavedTab(
                            favorites = favorites,
                            transit = transit,
                            places = places,
                            service = savedService,
                            onService = { savedService = it },
                            onOpenStop = { stop ->
                                tab = 0
                                selectedStop = stop
                                stopInfo = null
                                currentLine = null
                                mapController?.moveCamera(LonLat(stop.lon, stop.lat), 16.0, duration = 700)
                            },
                            onOpenPlace = { place ->
                                tab = 0
                                selectedPlace = place
                                selectedStop = null
                                mapController?.moveCamera(LonLat(place.lon, place.lat), 16.0, duration = 700)
                            },
                            onRemove = { key -> favorites = favorites - key; saveFavorites(context, favorites) },
                        )
                        2 -> AlertsTab(incidents, alertsService, { alertsService = it })
                        4 -> SettingsTab(
                            language = language,
                            onLanguage = { language = it; saveLanguage(context, it) },
                        )
                        else -> PlanTab(
                            from = planFrom,
                            to = planTo,
                            options = planOptions,
                            planning = planning,
                            onPickFrom = { pickFor = "from" },
                            onPickTo = { pickFor = "to" },
                            onUseLocation = {
                                lastKnownLocation(context)?.let { located ->
                                    planFrom = Stop(-1, "My location", emptyList(), located.lat, located.lon)
                                }
                            },
                            onPlan = {
                                val start = planFrom
                                val end = planTo
                                if (start != null && end != null && !planning) {
                                    planning = true
                                    plannedJourney = emptyList()
                                    plannedJourneyStops = emptyList()
                                    plannedOption = null
                                    plannedStopIds = emptySet()
                                    scope.launch {
                                        planOptions = runCatching {
                                            planner.plan(LonLat(start.lon, start.lat), LonLat(end.lon, end.lat))
                                        }.getOrDefault(emptyList())
                                        planning = false
                                    }
                                }
                            },
                            onSelect = { option ->
                                val (segments, stops) = journeySegments(option, transit, planFrom, planTo)
                                plannedJourney = segments
                                plannedJourneyStops = stops
                                plannedOption = option
                                plannedStopIds = option.legs.filter { !it.walk }
                                    .flatMap { listOfNotNull(it.boardStopId, it.alightStopId) }.toSet()
                                tab = 0
                                journeyBounds(segments)?.let { mapController?.fit(it, 220) }
                            },
                            modes = planModes,
                            onToggleMode = { network ->
                                planModes = if (network in planModes) planModes - network else planModes + network
                            },
                        )
                    }
                }
            }

            selectedStop?.let { stop ->
                StopSheet(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    stop = stop,
                    info = stopInfo,
                    loading = loadingStop,
                    currentLine = currentLine,
                    incidents = incidents,
                    isFavorite = favorites.contains(stopKey(stop.id)),
                    onToggleFavorite = {
                        val key = stopKey(stop.id)
                        favorites = if (favorites.contains(key)) favorites - key else favorites + key
                        saveFavorites(context, favorites)
                    },
                    etaLabel = { a -> repository.etaLabel(a) },
                    onSelectLine = { line ->
                        currentLine = if (currentLine == line) null else line
                        selectedStop = null
                        stopInfo = null
                        selectedPlace = null
                        followedBus = null
                        followedLine = null
                        tab = 0
                    },
                    onDismiss = { selectedStop = null; stopInfo = null },
                )
            }

            if (followedBus != null) {
                FollowSheet(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    transit = transit,
                    busStore = busStore,
                    busNumber = followedBus!!,
                    onDismiss = { followedBus = null; followedLine = null },
                )
            }

            // The planned trip's own pane; sliding it away clears the plan and
            // brings the normal station view back.
            plannedOption?.let { option ->
                JourneySheet(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    option = option,
                    onDismiss = {
                        plannedOption = null
                        plannedJourney = emptyList()
                        plannedJourneyStops = emptyList()
                        plannedStopIds = emptySet()
                    },
                )
            }

            // The line pane yields to the bus pane while a bus is being followed.
            if (followedBus == null) currentLine?.let { line ->
                LineSheet(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    transit = transit,
                    busStore = busStore,
                    line = line,
                    onDismiss = { currentLine = null },
                )
            }

            selectedPlace?.let { place ->
                PlaceSheet(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    place = place,
                    arrivals = { id -> repository.metroArrivals(id) },
                    isFavorite = favorites.contains(place.id),
                    onToggleFavorite = {
                        favorites = if (favorites.contains(place.id)) favorites - place.id else favorites + place.id
                        saveFavorites(context, favorites)
                    },
                    onDismiss = { selectedPlace = null },
                )
            }
        }

        pickFor?.let { target ->
            StopPickerSheet(
                transit = transit,
                onPick = { stop ->
                    if (target == "from") planFrom = stop else planTo = stop
                    pickFor = null
                },
                onDismiss = { pickFor = null },
            )
        }

        if (showLayers) {
            LayersSheet(
                enabled = enabledNetworks,
                onToggle = { network ->
                    enabledNetworks = if (network in enabledNetworks) enabledNetworks - network else enabledNetworks + network
                },
                onDismiss = { showLayers = false },
            )
        }
    }
}

@Composable
private fun MapTab(
    transit: TransitData,
    mapColors: MapColors,
    visibleStops: List<Stop>,
    contextLines: List<String>,
    currentLine: String?,
    busStore: BusStore,
    followBusNumber: Int?,
    userLocation: LonLat?,
    journey: List<JourneySegment>,
    journeyStops: List<JourneyStop>,
    journeyStopIds: Set<Int>,
    places: List<Place>,
    metroLines: List<String>,
    enabledNetworks: Set<Network>,
    onPlaceTap: (Place) -> Unit,
    onZoomTo: (LonLat) -> Unit,
    darkTheme: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    searchActive: Boolean,
    onSearchActiveChange: (Boolean) -> Unit,
    onStopTap: (Stop) -> Unit,
    onBusTap: (Int) -> Unit,
    onViewport: (MapViewport) -> Unit,
    onController: (NativeMapController) -> Unit,
    onToggleTilt: () -> Unit,
    onLocated: (LonLat?) -> Unit,
    onResetView: () -> Unit,
    onShowLayers: () -> Unit,
    onLinePick: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasLocationPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLocationPermission = granted
        if (granted) onLocated(lastKnownLocation(context))
    }
    val stopResults = remember(query, transit) {
        if (query.isBlank()) emptyList() else searchStops(transit.stops, query)
    }
    var byLine by remember { mutableStateOf(false) }
    var searchMedium by remember { mutableStateOf<Network?>(null) }
    val lineResults = remember(query, transit) {
        if (query.isBlank()) emptyList() else searchLines(transit, query)
    }

    Box(Modifier.fillMaxSize()) {
        // Reading the bus store here keeps the 30 fps invalidation scoped to this
        // composable, so the rest of the app never recomposes while buses move.
        val mapData = MapData(
            // While a trip is shown, only its own stops stay on the map.
            visibleStops = when {
                journey.isNotEmpty() -> visibleStops.filter { it.id in journeyStopIds }
                Network.Emt in enabledNetworks -> visibleStops
                else -> emptyList()
            },
            // A focused line or bus strips the map down to itself.
            contextLines = if (currentLine != null || followBusNumber != null) emptyList() else contextLines,
            currentLine = currentLine,
            buses = busStore.buses,
            userLocation = userLocation,
            routes = transit.routes,
            followBusNumber = followBusNumber,
            journey = journey,
            journeyStops = journeyStops,
            places = if (journey.isNotEmpty()) emptyList() else places,
            metroLines = metroLines,
        )
        TransitMap(
            modifier = Modifier.fillMaxSize(),
            styleUrl = if (darkTheme) DARK_STYLE else LIGHT_STYLE,
            colors = mapColors,
            data = mapData,
            onStopTap = onStopTap,
            onBusTap = onBusTap,
            onPlaceTap = onPlaceTap,
            onViewport = onViewport,
            onReady = onController,
        )

        if (searchActive) {
            // Dismiss the expanded search when tapping the map behind it. Drawn
            // below the search UI so its chips and results stay tappable.
            Box(Modifier.fillMaxSize().clickable { onSearchActiveChange(false) })
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 12.dp)) {
            StopSearchField(
                query = query,
                onQueryChange = onQueryChange,
                stopResults = stopResults,
                lineResults = lineResults,
                byLine = byLine,
                onByLineChange = { byLine = it },
                expanded = searchActive,
                onExpandedChange = onSearchActiveChange,
                onPick = { stop ->
                    onSearchActiveChange(false)
                    onQueryChange("")
                    onStopTap(stop)
                },
                onPickLine = { line ->
                    onSearchActiveChange(false)
                    onQueryChange("")
                    onLinePick(line)
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalIconButton(onClick = onResetView) {
                    Icon(Icons.Filled.Explore, contentDescription = "Reset view")
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .offset(y = -(LocalConfiguration.current.screenHeightDp * popoverVisibleFraction.floatValue).dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalIconButton(onClick = onShowLayers) {
                Icon(Icons.Filled.Layers, contentDescription = "Map layers")
            }
            FilledTonalIconButton(onClick = onToggleTilt) {
                Icon(Icons.Filled.ViewInAr, contentDescription = "3D perspective")
            }
            SmallFloatingActionButton(onClick = {
                if (hasLocationPermission) onLocated(lastKnownLocation(context)) else launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "My location")
            }
        }

        AnimatedVisibility(
            visible = searchActive,
            enter = fadeIn(tween(180)) + expandVertically(tween(240), expandFrom = Alignment.Top),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Top),
        ) {
            // Full-screen search: pick a medium, then a stop or station.
            val placeHits = remember(places, query, searchMedium, byLine) {
                val term = query.trim().lowercase(Locale.ROOT)
                if (byLine || term.isEmpty()) {
                    emptyList()
                } else {
                    places.filter {
                        (searchMedium == null || it.network == searchMedium) &&
                            it.name.lowercase(Locale.ROOT).contains(term)
                    }.take(40)
                }
            }
            val stopHits = if (!byLine && (searchMedium == null || searchMedium == Network.Emt)) stopResults else emptyList()
            val lineHits = if (byLine) lineResults else emptyList()
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = { Text(currentStrings.value.searchStop) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { onSearchActiveChange(false); onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close")
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                    LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { FilterChip(selected = !byLine, onClick = { byLine = false }, label = { Text(currentStrings.value.stops) }) }
                        item { FilterChip(selected = byLine, onClick = { byLine = true }, label = { Text(currentStrings.value.lines) }) }
                        item {
                            FilterChip(selected = searchMedium == null, onClick = { searchMedium = null }, label = { Text("All") })
                        }
                        items(Network.entries.toList()) { network ->
                            FilterChip(
                                selected = searchMedium == network,
                                onClick = { searchMedium = if (searchMedium == network) null else network },
                                label = { Text(network.label) },
                                leadingIcon = { ServiceLogo(network, 16.dp) },
                            )
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(lineHits, key = { "l-$it" }) { line ->
                            ListItem(
                                headlineContent = { Text("Line $line") },
                                leadingContent = { LineBadge(line) },
                                modifier = Modifier.clickable {
                                    onSearchActiveChange(false)
                                    onQueryChange("")
                                    onLinePick(line)
                                },
                            )
                        }
                        items(stopHits, key = { "s-${it.id}" }) { stop ->
                            ListItem(
                                headlineContent = { Text(cleanStopName(stop.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(stop.lines.joinToString(" · ")) },
                                leadingContent = { ServiceLogo(Network.Emt, 22.dp) },
                                modifier = Modifier.clickable {
                                    onSearchActiveChange(false)
                                    onQueryChange("")
                                    onZoomTo(LonLat(stop.lon, stop.lat))
                                    onStopTap(stop)
                                },
                            )
                        }
                        items(placeHits, key = { it.id }) { place ->
                            ListItem(
                                headlineContent = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(placeSubtitle(place)) },
                                leadingContent = { ServiceLogo(place.network, 22.dp) },
                                modifier = Modifier.clickable {
                                    onSearchActiveChange(false)
                                    onQueryChange("")
                                    onZoomTo(LonLat(place.lon, place.lat))
                                    onPlaceTap(place)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun lastKnownLocation(context: Context): LonLat? {
    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) return null
    return runCatching {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        location?.let { LonLat(it.longitude, it.latitude) }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    stopResults: List<Stop>,
    lineResults: List<String>,
    byLine: Boolean,
    onByLineChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (Stop) -> Unit,
    onPickLine: (String) -> Unit,
) {
    SearchBar(
        modifier = Modifier.fillMaxWidth(),
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        shape = RoundedCornerShape(28.dp),
        // The Scaffold already applies the status-bar inset; without this the
        // SearchBar adds it a second time, leaving a large gap at the top.
        windowInsets = WindowInsets(0, 0, 0, 0),
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { onExpandedChange(false) },
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                placeholder = { Text(if (byLine) currentStrings.value.searchLine else currentStrings.value.searchStop) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = !byLine, onClick = { onByLineChange(false) }, label = { Text(currentStrings.value.stops) })
                FilterChip(selected = byLine, onClick = { onByLineChange(true) }, label = { Text(currentStrings.value.lines) })
            }
            val empty = if (byLine) lineResults.isEmpty() else stopResults.isEmpty()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (empty) {
                    item {
                        ListItem(
                            headlineContent = { Text(if (query.isBlank()) "Type to search" else "No matches") },
                            leadingContent = { Icon(Icons.Filled.SearchOff, contentDescription = null) },
                        )
                    }
                }
                if (byLine) {
                    items(lineResults, key = { it }) { line ->
                        ListItem(
                            headlineContent = { Text("Line $line") },
                            leadingContent = { LineBadge(line) },
                            modifier = Modifier.clickable { onPickLine(line) },
                        )
                    }
                } else {
                    items(stopResults, key = { it.id }) { stop ->
                        ListItem(
                            headlineContent = { Text(cleanStopName(stop.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(stop.lines.joinToString(" · ")) },
                            leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
                            modifier = Modifier.clickable { onPick(stop) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StopSheet(
    modifier: Modifier,
    stop: Stop,
    info: StopInfo?,
    loading: Boolean,
    currentLine: String?,
    incidents: List<Incident>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    etaLabel: (Arrival) -> String,
    onSelectLine: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var updatesOpen by remember { mutableStateOf(true) }
    val arrivals = info?.arrivals.orEmpty()
    val relevant = stop.lines.mapNotNull { line -> incidents.firstOrNull { line.uppercase() in it.lines } }

    DraggableSheet(
        modifier = modifier.fillMaxHeight(),
        onDismiss = onDismiss,
        peek = {},
    ) { expanded, dismiss ->
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceLogo(Network.Emt, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(cleanStopName(stop.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stop.id.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(stop.lines) { line -> SmallLineBadge(line) }
                        }
                    }
                }
                // Star sits level with the stop name; toggles the favourite.
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (isFavorite) "Remove favourite" else "Add favourite",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (relevant.isNotEmpty()) {
                Surface(
                    tonalElevation = 3.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(8.dp))
                            Text("Service updates", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            AssistChip(
                                onClick = { updatesOpen = !updatesOpen },
                                label = { Text(if (updatesOpen) "Hide" else "Show") },
                            )
                        }
                        if (updatesOpen) {
                            relevant.forEach { incident ->
                                Text(incident.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                arrivals.isEmpty() -> Text("No buses coming right now", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(arrivals) { arrival ->
                            val selected = currentLine == arrival.line
                            Surface(
                                onClick = { onSelectLine(arrival.line) },
                                shape = RoundedCornerShape(50),
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LineBadge(arrival.line)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        etaLabel(arrival),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(arrivals) { arrival ->
                            val selectedRow = currentLine == arrival.line
                            val near = info?.buses?.firstOrNull { it.line == arrival.line }
                            Surface(
                                onClick = { onSelectLine(arrival.line) },
                                color = if (selectedRow) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LineBadge(arrival.line)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            arrival.destination.ifBlank { arrival.line },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        if (near != null) {
                                            Text(
                                                "Bus ${near.number} · ${TransitMotion.formatDistance(near.distanceMeters * 1000)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        etaLabel(arrival),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniLineBadge(line: String, minutes: String) {
    val badge = rememberLineBadge(line)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (badge != null) {
            Image(
                bitmap = badge.asImageBitmap(),
                contentDescription = "Line $line",
                modifier = Modifier.height(20.dp),
            )
        } else {
            Box(
                Modifier
                    .width(30.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
            )
        }
        if (minutes.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            Text(
                minutes,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun FollowSheet(
    modifier: Modifier,
    transit: TransitData,
    busStore: BusStore,
    busNumber: Int,
    onDismiss: () -> Unit,
) {
    val bus = busStore.buses.firstOrNull { it.number == busNumber }
    // Recompute the stop list as the bus advances, not on every animation frame.
    val upcoming = remember(bus?.nextStop, bus?.routeS?.div(200.0)?.toInt(), bus?.line, transit) {
        val current = bus
        if (current?.route == null) emptyList() else TransitMotion.upcoming(
            transit.routes, transit.stopsById, current.line, current.direction ?: "ida", current.nextStop, current.render,
        )
    }
    val next = upcoming.firstOrNull()

    DraggableSheet(
        modifier = modifier.fillMaxHeight(),
        peekHeight = 104.dp,
        onDismiss = onDismiss,
        peek = {},
    ) { expanded, dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 16.dp)) {
            if (bus == null) {
                Text("The bus left the map", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bus ${bus.number}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${bus.direction ?: "?"} · ${bus.destination}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.heightIn(max = if (expanded) 480.dp else 280.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(upcoming) { item ->
                    ListItem(
                        headlineContent = { Text(cleanStopName(item.stop.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
                        trailingContent = { Text("${item.minutes} min", color = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedTab(
    favorites: Set<String>,
    transit: TransitData,
    places: List<Place>,
    service: Network?,
    onService: (Network?) -> Unit,
    onOpenStop: (Stop) -> Unit,
    onOpenPlace: (Place) -> Unit,
    onRemove: (String) -> Unit,
) {
    val stops = remember(favorites, transit) {
        favorites.filter { it.startsWith("emt-") }.mapNotNull { transit.stopsById[it.removePrefix("emt-")] }
    }
    val savedPlaces = remember(favorites, places) {
        favorites.filterNot { it.startsWith("emt-") }.mapNotNull { id -> places.firstOrNull { it.id == id } }
    }
    Column(Modifier.fillMaxSize()) {
        ServiceFilterRow(service, onService, Modifier.padding(horizontal = 12.dp))
        if (stops.isEmpty() && savedPlaces.isEmpty()) {
            EmptyState(Icons.Filled.StarBorder, "Star a stop to save it here")
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(stops.filter { service == null || service == Network.Emt }, key = { "s-${it.id}" }) { stop ->
                    ListItem(
                        headlineContent = { Text(cleanStopName(stop.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stop.lines.joinToString(" · ")) },
                        leadingContent = { ServiceLogo(Network.Emt, 22.dp) },
                        trailingContent = {
                            IconButton(onClick = { onRemove(stopKey(stop.id)) }) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
                        },
                        modifier = Modifier.clickable { onOpenStop(stop) },
                    )
                }
                items(savedPlaces.filter { service == null || it.network == service }, key = { it.id }) { place ->
                    ListItem(
                        headlineContent = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(placeSubtitle(place)) },
                        leadingContent = { ServiceLogo(place.network, 22.dp) },
                        trailingContent = {
                            IconButton(onClick = { onRemove(place.id) }) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
                        },
                        modifier = Modifier.clickable { onOpenPlace(place) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertsTab(
    incidents: List<Incident>,
    service: Network?,
    onService: (Network?) -> Unit,
) {
    // Valenbisi has no alert feed; only operators that publish one appear.
    val shown = incidents.filter { service == null || it.network == service }
    Column(Modifier.fillMaxSize()) {
        ServiceFilterRow(service, onService, Modifier.padding(horizontal = 12.dp), exclude = setOf(Network.Valenbisi))
        if (shown.isEmpty()) {
            EmptyState(Icons.Filled.Warning, "No service updates right now")
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(shown) { incident ->
                    ListItem(
                        headlineContent = { Text(incident.title) },
                        supportingContent = {
                            Text(
                                buildString {
                                    if (incident.lines.isNotEmpty()) append("Lines ${incident.lines.joinToString(", ")}")
                                    if (incident.date.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append("since ${incident.date}")
                                    }
                                },
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LineBadge(line: String) {
    if (line.startsWith("M") && line.drop(1).toIntOrNull() != null) {
        MetroBadge(line.drop(1))
        return
    }
    val badge = rememberLineBadge(line)
    if (badge != null) {
        Image(
            bitmap = badge.asImageBitmap(),
            contentDescription = "Line $line",
            modifier = Modifier.height(24.dp),
        )
    } else {
        Box(
            Modifier
                .width(34.dp)
                .height(24.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
        )
    }
}

private fun searchStops(stops: List<Stop>, query: String, includeMetro: Boolean = false): List<Stop> {
    val candidates = if (includeMetro) stops else stops.filter { !it.metro }
    val normalized = query.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return emptyList()
    if (normalized.all { it.isDigit() }) return candidates.filter { it.id.toString().contains(normalized) }.take(30)
    val words = normalized.split(Regex("\\s+"))
    return candidates.filter { stop ->
        val name = stop.name.lowercase(Locale.ROOT)
        words.all { name.contains(it) }
    }.take(30)
}

/** Line numbers containing [query], numeric lines first. */
private fun searchLines(transit: TransitData, query: String): List<String> {
    val normalized = query.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return emptyList()
    return transit.routes.keys
        .filter { it.lowercase(Locale.ROOT).contains(normalized) }
        .sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
        .take(30)
}

/** One-line summary shown when a network point is tapped. */
private fun placeSubtitle(place: Place): String = when (place.network) {
    Network.Valenbisi -> when {
        !place.open -> "Closed"
        place.available <= 0 -> "No bikes · ${place.free} free docks"
        place.free <= 0 -> "${place.available} bikes · no free docks"
        else -> "${place.available} bikes · ${place.free} free docks"
    }
    else -> listOf(place.network.label, place.lines.joinToString(" · "), place.detail)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/** Bounding box of every shape of a line, for framing it on the map. */
private fun lineBounds(route: BusRoute): LngLatBounds? {    var west = Double.MAX_VALUE
    var south = Double.MAX_VALUE
    var east = -Double.MAX_VALUE
    var north = -Double.MAX_VALUE
    var any = false
    listOf(route.ida, route.vuelta).forEach { direction ->
        direction.shapes.forEach { shape ->
            shape.forEach { point ->
                west = minOf(west, point.lon); east = maxOf(east, point.lon)
                south = minOf(south, point.lat); north = maxOf(north, point.lat)
                any = true
            }
        }
    }
    return if (any) LngLatBounds(west, south, east, north) else null
}

/** Turns a planned option into drawable stretches: walks plus the line shapes. */
private fun journeySegments(
    option: JourneyOption,
    transit: TransitData,
    origin: Stop?,
    destination: Stop?,
): Pair<List<JourneySegment>, List<JourneyStop>> {
    val busLegs = option.legs.filter { !it.walk }
    if (busLegs.isEmpty()) return emptyList<JourneySegment>() to emptyList()
    val segments = mutableListOf<JourneySegment>()
    // One colour per ride; a transfer is drawn once, in the colour of the ride it starts.
    val dots = LinkedHashMap<String, JourneyStop>()
    fun dot(point: LonLat, colorIndex: Int) {
        dots["${(point.lat * 1e5).roundToInt()},${(point.lon * 1e5).roundToInt()}"] = JourneyStop(point, colorIndex)
    }
    val start = origin?.let { LonLat(it.lon, it.lat) }
    val end = destination?.let { LonLat(it.lon, it.lat) }
    busLegs.first().boardStopId?.let { id ->
        transit.stopsById[id.toString()]?.let { stop ->
            start?.let { segments += JourneySegment(true, listOf(it, LonLat(stop.lon, stop.lat))) }
        }
    }
    busLegs.forEachIndexed { index, leg ->
        val route = leg.line?.let { transit.routes[it] }
        val board = leg.boardStopId?.let { transit.stopsById[it.toString()] }
        val alight = leg.alightStopId?.let { transit.stopsById[it.toString()] }
        if (route != null && board != null && alight != null) {
            val points = legShape(route, leg.direction, board, alight)
            if (points.size >= 2) segments += JourneySegment(false, points, index)
            dot(LonLat(board.lon, board.lat), index)
            dot(LonLat(alight.lon, alight.lat), index)
        }
    }
    busLegs.last().alightStopId?.let { id ->
        transit.stopsById[id.toString()]?.let { stop ->
            end?.let { segments += JourneySegment(true, listOf(LonLat(stop.lon, stop.lat), it)) }
        }
    }
    return segments to dots.values.toList()
}

/** The stretch of an itinerary's shape between two stops, in travel order. */
private fun legShape(route: BusRoute, direction: String?, board: Stop, alight: Stop): List<LonLat> {
    val shapes = if (direction == "vuelta") route.vuelta.shapes else route.ida.shapes
    var best = emptyList<LonLat>()
    var bestScore = Double.MAX_VALUE
    shapes.forEach { shape ->
        if (shape.size < 2) return@forEach
        val from = nearestIndex(shape, board)
        val to = nearestIndex(shape, alight)
        val score = squaredDistance(shape[from], board) + squaredDistance(shape[to], alight)
        if (score < bestScore) {
            bestScore = score
            best = if (from <= to) shape.subList(from, to + 1).toList() else shape.subList(to, from + 1).toList().reversed()
        }
    }
    return best
}

private fun nearestIndex(shape: List<LonLat>, stop: Stop): Int {
    var index = 0
    var best = Double.MAX_VALUE
    shape.forEachIndexed { i, point ->
        val d = squaredDistance(point, stop)
        if (d < best) { best = d; index = i }
    }
    return index
}

private fun squaredDistance(point: LonLat, stop: Stop): Double {
    val dx = point.lon - stop.lon
    val dy = point.lat - stop.lat
    return dx * dx + dy * dy
}

private fun journeyBounds(segments: List<JourneySegment>): LngLatBounds? {
    var west = Double.MAX_VALUE
    var south = Double.MAX_VALUE
    var east = -Double.MAX_VALUE
    var north = -Double.MAX_VALUE
    var any = false
    segments.forEach { segment ->
        segment.points.forEach { point ->
            west = minOf(west, point.lon); east = maxOf(east, point.lon)
            south = minOf(south, point.lat); north = maxOf(north, point.lat)
            any = true
        }
    }
    return if (any) LngLatBounds(west, south, east, north) else null
}

private fun advance(bus: BusPosition, now: Long): BusPosition {
    val k = 1 - exp(-16.0 / 400.0)
    val route = bus.route
    if (route != null) {
        val age = now - bus.observedAt
        val speed = if (age > 25_000) 0.0 else bus.speedMps
        val target = bus.hold?.distanceAlong ?: TransitMotion.predictArc(bus.observedS, speed, age / 1000.0, route.totalMeters)
        val error = abs(target - bus.routeS)
        val kk = if (error > 25) 1 - exp(-16.0 / 2500.0) else k
        bus.routeS += (target - bus.routeS) * kk
        bus.render = TransitMotion.pointAt(route, bus.routeS)
        bus.bearing = TransitMotion.bearing(route, bus.routeS)
    } else {
        val seconds = (now - bus.observedAt) / 1000.0
        val px = bus.observed.lon + bus.velocity.lon * seconds
        val py = bus.observed.lat + bus.velocity.lat * seconds
        val kk = 1 - exp(-16.0 / 2500.0)
        bus.render = LonLat(bus.render.lon + (px - bus.render.lon) * kk, bus.render.lat + (py - bus.render.lat) * kk)
    }
    return bus
}

private fun merge(fixes: List<BusFix>, existing: List<BusPosition>, transit: TransitData, now: Long): List<BusPosition> {
    val map = existing.associateByTo(LinkedHashMap()) { it.number }
    val seen = mutableSetOf<Int>()
    for (fix in fixes) {
        seen += fix.number
        val position = LonLat(fix.lon, fix.lat)
        val current = map[fix.number]
        if (current == null) {
            val candidate = nearestRoute(transit, fix.line, fix.direction, position)
            val onRoute = candidate != null && candidate.second.distanceFrom <= 150
            map[fix.number] = BusPosition(
                number = fix.number,
                line = fix.line,
                destination = fix.destination,
                nextStop = fix.nextStop,
                direction = fix.direction,
                timestamp = fix.timestamp,
                observed = position,
                observedAt = now,
                velocity = LonLat(0.0, 0.0),
                render = if (onRoute) TransitMotion.pointAt(candidate!!.first, candidate.second.distanceAlong) else position,
                route = if (onRoute) candidate!!.first else null,
                routeS = if (onRoute) candidate!!.second.distanceAlong else 0.0,
                observedS = if (onRoute) candidate!!.second.distanceAlong else 0.0,
                speedMps = 0.0,
            )
            continue
        }
        val fresh = fix.timestamp != current.timestamp
        val moved = abs(position.lon - current.observed.lon) > 1e-6 || abs(position.lat - current.observed.lat) > 1e-6
        if (fresh) {
            if (moved) {
                val seconds = secondsBetween(current.timestamp, fix.timestamp)
                val candidate = nearestRoute(transit, fix.line, fix.direction, position)
                if (candidate != null && candidate.second.distanceFrom <= 150) {
                    if (current.route == null || current.route !== candidate.first) {
                        current.route = candidate.first
                        current.routeS = candidate.second.distanceAlong
                        current.observedS = candidate.second.distanceAlong
                    } else {
                        if (seconds > 0.5) {
                            current.speedMps =
                                (current.speedMps + TransitMotion.clampSpeed((candidate.second.distanceAlong - current.observedS) / seconds)) / 2
                        }
                        current.observedS = candidate.second.distanceAlong
                    }
                } else {
                    current.route = null
                    if (seconds > 0.5) {
                        current.velocity = LonLat(
                            (position.lon - current.observed.lon) / seconds,
                            (position.lat - current.observed.lat) / seconds,
                        )
                    }
                }
                current.observed = position
                current.observedAt = now
            } else {
                current.speedMps *= 0.5
                current.observedAt = now
            }
            current.timestamp = fix.timestamp
            current.nextStop = fix.nextStop
            current.direction = fix.direction
            current.route?.let { route ->
                val stop = transit.stopsById[fix.nextStop.toString()]
                val stopS = stop?.let { TransitMotion.project(route, LonLat(it.lon, it.lat)).distanceAlong }
                current.hold = TransitMotion.stopHold(current.hold, current.observedS, fix.nextStop.toString(), stopS)
            }
        }
        map[fix.number] = current
    }
    map.keys.retainAll(seen)
    return map.values.toList()
}

private fun nearestRoute(transit: TransitData, line: String, direction: String?, position: LonLat): Pair<PreparedRoute, Projection>? {
    if (direction == null) return null
    val route = transit.routes[line] ?: return null
    val shapes = if (direction == "ida") route.ida.shapes else route.vuelta.shapes
    var best: Pair<PreparedRoute, Projection>? = null
    shapes.forEach { shape ->
        val preparedRoute = prepared(shape)
        val projection = TransitMotion.project(preparedRoute, position)
        if (best == null || projection.distanceFrom < best!!.second.distanceFrom) best = preparedRoute to projection
    }
    return best
}

private fun secondsBetween(from: String, to: String): Double = runCatching {
    (timeFormat.parse(to)!!.time - timeFormat.parse(from)!!.time) / 1000.0
}.getOrDefault(0.0)

@Composable
private fun PlanTab(
    from: Stop?,
    to: Stop?,
    options: List<JourneyOption>,
    planning: Boolean,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit,
    onUseLocation: () -> Unit,
    onPlan: () -> Unit,
    onSelect: (JourneyOption) -> Unit,
    modes: Set<Network>,
    onToggleMode: (Network) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Plan a trip", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        // Which mediums may be used (everything but the bike share).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Network.Emt, Network.Metro, Network.Metrobus).forEach { network ->
                FilterChip(
                    selected = network in modes,
                    onClick = { onToggleMode(network) },
                    label = { Text(network.label) },
                    leadingIcon = { ServiceLogo(network, 16.dp) },
                )
            }
        }
        ListItem(
            headlineContent = { Text(from?.let { cleanStopName(it.name) } ?: "Choose a start") },
            leadingContent = { Icon(Icons.Filled.TripOrigin, contentDescription = null) },
            trailingContent = {
                IconButton(onClick = onUseLocation) { Icon(Icons.Filled.MyLocation, contentDescription = "My location") }
            },
            modifier = Modifier.clickable { onPickFrom() },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(to?.let { cleanStopName(it.name) } ?: "Choose a destination") },
            leadingContent = { Icon(Icons.Filled.Flag, contentDescription = null) },
            modifier = Modifier.clickable { onPickTo() },
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onPlan,
            enabled = from != null && to != null && !planning && Network.Emt in modes,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (planning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Navigation, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (planning) "Planning…" else "Plan")
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(options) { option -> JourneyOptionCard(option, onClick = { onSelect(option) }) }
        }
    }
}

@Composable
private fun JourneyOptionCard(option: JourneyOption, onClick: () -> Unit) {
    Surface(onClick = onClick, tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${option.totalMinutes} min",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                AssistChip(
                    onClick = {},
                    label = { Text(if (option.transfers == 0) "direct" else "${option.transfers} transfer") },
                )
            }
            Text(
                "walk ${option.walkMeters} m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            option.legs.forEach { leg ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (leg.walk) {
                        Icon(Icons.Filled.DirectionsWalk, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Walk ${leg.minutes} min · ${leg.meters} m",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        LineBadge(leg.line ?: "")
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "towards ${leg.towards}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "board ${leg.boardStop} · ${leg.stops} stops · ride ${leg.minutes} min",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("${leg.waitMinutes} min", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** Big number + icon + progress bar, Material You style. */
@Composable
private fun StatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Int,
    label: String,
    color: Color,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = color.copy(alpha = 0.14f)) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.height(8.dp))
            Text("$value", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = color,
                trackColor = color.copy(alpha = 0.25f),
            )
        }
    }
}

@Composable
private fun PlaceSheet(
    modifier: Modifier,
    place: Place,
    arrivals: suspend (Int) -> List<Arrival>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
) {
    DraggableSheet(
        modifier = modifier.fillMaxHeight(),
        onDismiss = onDismiss,
        peek = {},
    ) { _, dismiss ->
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceLogo(place.network, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (place.detail.isNotBlank()) {
                            Text(
                                place.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (place.network == Network.Metro) {
                            place.lines.forEach { line ->
                                MetroBadge(line, height = 64.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = "Favourite",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            when (place.network) {
                Network.Metro -> {
                    val stationId = place.id.removePrefix("mt-").toIntOrNull()
                    var times by remember(place) { mutableStateOf<List<Arrival>>(emptyList()) }
                    LaunchedEffect(place) {
                        times = if (stationId != null) arrivals(stationId) else emptyList()
                    }
                    // Every line serving this station is shown next to its address.
                    Spacer(Modifier.height(12.dp))
                    if (times.isEmpty()) {
                        Text("No upcoming trains", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(times) { arrival ->
                                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        MetroBadge(arrival.line.removePrefix("L"))
                                        Spacer(Modifier.width(8.dp))
                                        Text(arrival.minutes, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(times) { arrival ->
                                ListItem(
                                    leadingContent = { MetroBadge(arrival.line.removePrefix("L"), height = 34.dp) },
                                    headlineContent = { Text(arrival.destination.ifBlank { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = { Text(arrival.minutes, color = MaterialTheme.colorScheme.primary) },
                                )
                            }
                        }
                    }
                }
                Network.Valenbisi -> {
                    val total = (place.available.coerceAtLeast(0) + place.free.coerceAtLeast(0)).coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatCard(
                            icon = Icons.Filled.DirectionsBike,
                            value = place.available.coerceAtLeast(0),
                            label = "bikes",
                            color = Color(0xFF22C55E),
                            fraction = place.available.coerceAtLeast(0) / total.toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                        StatCard(
                            icon = Icons.Filled.LocalParking,
                            value = place.free.coerceAtLeast(0),
                            label = "free docks",
                            color = Color(0xFF1E88E5),
                            fraction = place.free.coerceAtLeast(0) / total.toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(if (place.open) "Open" else "Closed") },
                        leadingIcon = {
                            Icon(
                                if (place.open) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
                else -> Text(placeSubtitle(place), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The planned trip, in its own dismissible pane. */
@Composable
private fun JourneySheet(modifier: Modifier, option: JourneyOption, onDismiss: () -> Unit) {
    DraggableSheet(
        modifier = modifier.fillMaxHeight(),
        onDismiss = onDismiss,
        peek = {},
    ) { _, _ ->
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text("Trip", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "${option.totalMinutes} min · walk ${option.walkMeters} m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            JourneyOptionCard(option, onClick = {})
        }
    }
}

@Composable
private fun LineSheet(
    modifier: Modifier,
    transit: TransitData,
    busStore: BusStore,
    line: String,
    onDismiss: () -> Unit,
) {
    val buses = busStore.buses.filter { it.line == line }
    val direction = buses.firstOrNull()?.direction ?: "ida"
    val stops = remember(transit, line, direction) {
        transit.routes[line]
            ?.let { route -> (if (direction == "vuelta") route.vuelta else route.ida).stops }
            .orEmpty()
            .mapNotNull { transit.stopsById[it] }
    }
    // Minutes for each stop along the line, from the live buses on it.
    val etas = remember(buses.size, stops) {
        val map = HashMap<Int, Int>()
        buses.forEach { bus ->
            if (bus.route == null) return@forEach
            TransitMotion.upcoming(transit.routes, transit.stopsById, bus.line, bus.direction ?: "ida", bus.nextStop, bus.render)
                .forEach { item -> map[item.stop.id] = minOf(map[item.stop.id] ?: Int.MAX_VALUE, item.minutes) }
        }
        map
    }
    DraggableSheet(
        modifier = modifier.fillMaxHeight(),
        onDismiss = onDismiss,
        peek = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(line)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Line $line", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${buses.size} ${if (buses.size == 1) "bus" else "buses"} live · $direction",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { _, dismiss ->
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(
                "Stops",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(stops, key = { it.id }) { stop ->
                    val eta = etas[stop.id]
                    ListItem(
                        headlineContent = { Text(cleanStopName(stop.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
                        trailingContent = {
                            Text(
                                if (eta != null) "$eta min" else "—",
                                color = if (eta != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(language: Language, onLanguage: (Language) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(currentStrings.value.settings, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(currentStrings.value.languageTitle, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { entry ->
                FilterChip(
                    selected = entry == language,
                    onClick = { onLanguage(entry) },
                    label = { Text(entry.label) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("About & data sources", style = MaterialTheme.typography.titleMedium)
        Text(
            "Unofficial app. Not affiliated with, endorsed by, or connected to " +
                "EMT València, Metrovalencia / FGV, ATMV / Met GO, Valenbisi / JCDecaux, " +
                "the Ajuntament de València or any other operator or company. " +
                "All trademarks and logos belong to their respective owners.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Live data and maps come from public/open sources and community tools, including " +
                "EMT València (servicios.emtvalencia.es), Metrovalencia (metrovalencia.es, metroapi.alexbadi.es), " +
                "Valenbisi via JCDecaux's public Cyclocity v3 API, València open data " +
                "(opendata.vlci.valencia.es, CC BY 4.0), OpenStreetMap contributors (ODbL), " +
                "CARTO basemaps and MapLibre. FOSSGIS provides the walking routes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Provided “as is”, without warranty; times and positions may be delayed or inaccurate. " +
                "Use the official operators' information for travel decisions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("© 2026 EMT-RealTime contributors", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayersSheet(enabled: Set<Network>, onToggle: (Network) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Show on the map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Network.entries.forEach { network ->
                ListItem(
                    headlineContent = { Text(network.label) },
                    leadingContent = { ServiceLogo(network, 24.dp) },
                    trailingContent = {
                        Switch(checked = network in enabled, onCheckedChange = { onToggle(network) })
                    },
                    modifier = Modifier.clickable { onToggle(network) },
                )
            }
        }
    }
}

/** Small line badge for the stop header strip. */
@Composable
private fun SmallLineBadge(line: String) {
    val badge = rememberLineBadge(line)
    if (badge != null) {
        Image(
            bitmap = badge.asImageBitmap(),
            contentDescription = "Line $line",
            modifier = Modifier.height(16.dp),
        )
    } else {
        Box(
            Modifier
                .width(22.dp)
                .height(16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
        )
    }
}

/** Metrovalencia line badge from the bundled PNG assets. */
@Composable
private fun MetroBadge(line: String, height: Dp = 22.dp) {
    val badge = rememberMetroBadge(line)
    if (badge != null) {
        Image(
            bitmap = badge.asImageBitmap(),
            contentDescription = "Metro line $line",
            modifier = Modifier.height(height),
        )
    } else {
        Box(
            Modifier
                .width(height * 1.4f)
                .height(height)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
        )
    }
}

@Composable
private fun ServiceLogo(network: Network, size: Dp = 22.dp) {
    val context = LocalContext.current
    val bitmap = remember(network) {
        runCatching { context.assets.open(serviceAsset(network)).use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = network.label,
            modifier = Modifier.size(size),
        )
    } else {
        Box(
            Modifier.size(size).background(networkDot(network), RoundedCornerShape(size / 3)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                networkMark(network),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun serviceAsset(network: Network): String = when (network) {
    Network.Emt -> "emt.png"
    Network.Metro -> "metrovalencia.png"
    Network.Valenbisi -> "valenbisi.png"
    Network.Metrobus -> "metrobus.png"
}

private fun networkMark(network: Network): String = when (network) {
    Network.Emt -> "EMT"
    Network.Metro -> "MV"
    Network.Valenbisi -> "VB"
    Network.Metrobus -> "MB"
}

@Composable
private fun ServiceFilterRow(
    selected: Network?,
    onSelect: (Network?) -> Unit,
    modifier: Modifier = Modifier,
    exclude: Set<Network> = emptySet(),
) {
    LazyRow(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") }) }
        items(Network.entries.filterNot { it in exclude }) { network ->
            FilterChip(
                selected = selected == network,
                onClick = { onSelect(if (selected == network) null else network) },
                label = { Text(network.label) },
                leadingIcon = { ServiceLogo(network, 16.dp) },
            )
        }
    }
}

private fun networkDot(network: Network): Color = when (network) {
    Network.Emt -> Color(0xFFF97316)
    Network.Metro -> Color(0xFFE4002B)
    Network.Valenbisi -> Color(0xFF22C55E)
    Network.Metrobus -> Color(0xFF0EA5E9)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopPickerSheet(transit: TransitData, onPick: (Stop) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val results = remember(query, transit) {
        if (query.isBlank()) emptyList() else searchStops(transit.stops, query, includeMetro = true)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search a stop") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(results, key = { it.id }) { stop ->
                    ListItem(
                        headlineContent = { Text(cleanStopName(stop.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stop.lines.joinToString(" · ")) },
                        leadingContent = { ServiceLogo(if (stop.metro) Network.Metro else Network.Emt, 24.dp) },
                        modifier = Modifier.clickable { onPick(stop) },
                    )
                }
            }
        }
    }
}
