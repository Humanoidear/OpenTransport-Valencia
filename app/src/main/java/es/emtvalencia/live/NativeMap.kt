package es.emtvalencia.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.maps.Style
import kotlin.math.hypot

data class MapViewport(val zoom: Double, val west: Double, val south: Double, val east: Double, val north: Double)

/** Distinct colour per ride in a planned trip. */
private val JOURNEY_PALETTE = listOf("#2563EB", "#DB2777", "#16A34A", "#F59E0B", "#7C3AED", "#0891B2")

data class MapColors(
    val stopFill: Int,
    val stopStroke: Int,
    val building: Int,
    val routes: Map<String, Int>,
    val ida: Int,
    val vuelta: Int,
    val busText: Int,
    val busHalo: Int,
)

data class MapData(
    val visibleStops: List<Stop>,
    val contextLines: List<String>,
    val currentLine: String?,
    val buses: List<BusPosition>,
    val userLocation: LonLat?,
    val routes: Map<String, BusRoute>,
    val followBusNumber: Int? = null,
    val journey: List<JourneySegment> = emptyList(),
    val journeyStops: List<JourneyStop> = emptyList(),
    val places: List<Place> = emptyList(),
    val metroLines: List<String> = emptyList(),
)

/** One drawn stretch of a planned trip: a bus ride along the line, or a walk. */
data class JourneySegment(val walk: Boolean, val points: List<LonLat>, val colorIndex: Int = 0)

/** A boarding/alighting point of a planned trip, drawn as a big dot. */
data class JourneyStop(val point: LonLat, val colorIndex: Int = 0)

@Composable
fun TransitMap(
    modifier: Modifier,
    styleUrl: String,
    colors: MapColors,
    data: MapData,
    onStopTap: (Stop) -> Unit,
    onBusTap: (Int) -> Unit,
    onPlaceTap: (Place) -> Unit,
    onViewport: (MapViewport) -> Unit,
    onReady: (NativeMapController) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(context) { NativeMapController(context) }
    SideEffect {
        controller.onStopTap = onStopTap
        controller.onBusTap = onBusTap
        controller.onPlaceTap = onPlaceTap
        controller.onViewport = onViewport
        controller.update(styleUrl, colors, data)
        onReady(controller)
    }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event -> controller.onLifecycle(event) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.destroy()
        }
    }
    AndroidView(modifier = modifier, factory = { controller.view }, update = { controller.update(styleUrl, colors, data) })
}

class NativeMapController(context: Context) {
    val view: MapView = MapView(context).apply { onCreate(null) }

    var onStopTap: (Stop) -> Unit = {}
    var onBusTap: (Int) -> Unit = {}
    var onPlaceTap: (Place) -> Unit = {}
    var onViewport: (MapViewport) -> Unit = {}

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleUrl: String? = null
    private var styleReady = false
    private var colors = MapColors(0, 0, 0, emptyMap(), 0, 0, 0, 0)
    private var data = MapData(emptyList(), emptyList(), null, emptyList(), null, emptyMap())
    private var clickListener: MapLibreMap.OnMapClickListener? = null
    private var cameraListener: MapLibreMap.OnCameraIdleListener? = null
    private var lastContext: List<String>? = null
    private var lastMain: String? = null
    private var lastRoutes: Map<String, BusRoute>? = null
    private var lastStops: List<Stop>? = null
    private var lastJourney: List<JourneySegment>? = null
    private var lastTransfer: List<JourneyStop>? = null
    private var lastPlaces: List<Place>? = null
    private var lastUser: LonLat? = null
    private val appContext = context.applicationContext

    init {
        MapLibre.getInstance(context.applicationContext)
    }

    fun onLifecycle(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> view.onStart()
            Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause()
            Lifecycle.Event.ON_STOP -> view.onStop()
            else -> Unit
        }
    }

    fun destroy() {
        clickListener?.let { map?.removeOnMapClickListener(it) }
        cameraListener?.let { map?.removeOnCameraIdleListener(it) }
        view.onDestroy()
        map = null
        style = null
    }

    fun update(url: String, mapColors: MapColors, mapData: MapData) {
        colors = mapColors
        data = mapData
        if (map == null) {
            if (styleUrl == null) {
                styleUrl = url
                view.getMapAsync { readyMap ->
                    map = readyMap
                    // Keep the view inside the Valencian Community scale.
                    readyMap.setMinZoomPreference(8.0)
                    installListeners(readyMap)
                    loadStyle(url)
                }
            }
            return
        }
        if (styleUrl != url) loadStyle(url) else if (styleReady) updateSources()
    }

    fun moveCamera(point: LonLat, zoom: Double, bearing: Double = 0.0, pitch: Double = 0.0, duration: Int = 600) {
        val current = map?.cameraPosition ?: return
        val camera = CameraPosition.Builder(current)
            .target(LatLng(point.lat, point.lon))
            .zoom(zoom)
            .bearing(bearing)
            .tilt(pitch)
            .build()
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(camera), duration)
    }

    /** Camera trails just behind the vehicle, looking along its heading. */
    fun follow(point: LonLat, bearing: Double, pitch: Double = 58.0) {
        val current = map?.cameraPosition ?: return
        map?.cameraPosition = CameraPosition.Builder(current)
            .target(LatLng(point.lat, point.lon))
            .bearing(bearing)
            .tilt(pitch)
            .build()
    }

    fun fit(bounds: LngLatBounds, bottomPadding: Int = 100) {
        map?.animateCamera(
            CameraUpdateFactory.newLatLngBounds(
                org.maplibre.android.geometry.LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west),
                72,
                72,
                72,
                bottomPadding,
            ),
            700,
        )
    }

    /** North-up, flat, and framed on [bounds] when a line/stop is focused. */
    fun resetView(bounds: LngLatBounds?) {
        val m = map ?: return
        m.cameraPosition = CameraPosition.Builder(m.cameraPosition).bearing(0.0).tilt(0.0).build()
        if (bounds != null) fit(bounds, 160)
    }

    fun setPitch(pitch: Double) {        val m = map ?: return
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(m.cameraPosition).tilt(pitch).build()),
            400,
        )
    }

    private fun loadStyle(url: String) {
        val m = map ?: return
        val camera = m.cameraPosition
        styleUrl = url
        styleReady = false
        m.setStyle(url) { loaded ->
            style = loaded
            addSourcesAndLayers(loaded)
            styleReady = true
            m.cameraPosition = camera
            updateSources()
            emitViewport()
        }
    }

    /** Marker images: the network logo on its brand-coloured disc. */
    private fun addServiceIcons(s: Style) {
        serviceIcon("emt.png", 0xFFFFFFFF.toInt())?.let { s.addImage("emt-icon", it) }
        serviceIcon("metrovalencia.png", 0xFFFF8A80.toInt())?.let { s.addImage("metro-icon", it) }
        serviceIcon("valenbisi.png", 0xFF90CAF9.toInt())?.let { s.addImage("valenbisi-icon", it) }
        s.addImage("vb-pill", pillImage())
    }

    /** A rounded Material-style pill behind the Valenbisi icon + count. */
    private fun pillImage(): Bitmap {
        val width = 248
        val height = 120
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = height / 2f
        canvas.drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.building },
        )
        return bitmap
    }

    private fun serviceIcon(asset: String, color: Int): Bitmap? {
        val logo = runCatching { appContext.assets.open(asset).use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: return null
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val pad = size * 0.24f
        canvas.drawBitmap(logo, null, RectF(pad, pad, size - pad, size - pad), Paint(Paint.ANTI_ALIAS_FLAG))
        return bitmap
    }

    /** Metrovalencia route lines, bundled as GeoJSON in assets, filtered by line. */
    private var metroRoutesCache: Pair<List<String>, String>? = null

    private fun metroRoutesJson(lines: List<String>): String {
        if (lines.isEmpty()) return EMPTY
        metroRoutesCache?.let { if (it.first == lines) return it.second }
        val text = runCatching {
            appContext.assets.open("metrovalencia.geojson").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return EMPTY
        val features = runCatching { JSONObject(text).optJSONArray("features") }.getOrNull() ?: return EMPTY
        val kept = JSONArray()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            if (feature.optJSONObject("geometry")?.optString("type") != "LineString") continue
            if (feature.optJSONObject("properties")?.opt("line")?.toString() in lines) kept.put(feature)
        }
        val json = JSONObject().put("type", "FeatureCollection").put("features", kept).toString()
        metroRoutesCache = lines to json
        return json
    }

    private fun addSourcesAndLayers(s: Style) {
        addServiceIcons(s)
        s.addSource(GeoJsonSource(SRC_CONTEXT, EMPTY))
        s.addSource(GeoJsonSource(SRC_MAIN, EMPTY))
        s.addSource(GeoJsonSource(SRC_STOPS, EMPTY))
        s.addSource(GeoJsonSource(SRC_BUSES, EMPTY))
        s.addSource(GeoJsonSource(SRC_USER, EMPTY))
        s.addSource(GeoJsonSource(SRC_JOURNEY, EMPTY))
        s.addSource(
            GeoJsonSource(
                SRC_PLACES,
                GeoJsonOptions().withCluster(true).withClusterRadius(50).withClusterMaxZoom(14),
            ),
        )
        s.addSource(GeoJsonSource(SRC_TRANSFER, EMPTY))
        // OSM building footprints are extruded into simple 3D city blocks.
        val buildingSource = when {
            s.getSource("openmaptiles") != null -> "openmaptiles"
            s.getSource("carto") != null -> "carto"
            else -> null
        }
        if (buildingSource != null) {
            try {
                val height = Expression.coalesce(Expression.get("render_height"), Expression.get("height"), Expression.literal(12))
                val base = Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0))
                val extrusion = FillExtrusionLayer("emt-buildings-3d", buildingSource)
                    .withSourceLayer("building")
                    .withProperties(
                        PropertyFactory.fillExtrusionColor(colors.building),
                        PropertyFactory.fillExtrusionHeight(height),
                        PropertyFactory.fillExtrusionBase(base),
                        PropertyFactory.fillExtrusionOpacity(0.78f),
                    )
                extrusion.setMinZoom(14f)
                val label = s.layers.firstOrNull { it is SymbolLayer }
                if (label != null) s.addLayerBelow(extrusion, label.id) else s.addLayer(extrusion)
            } catch (_: Exception) {
                // If a provider changes its source schema, keep the street map usable.
            }
        }

        // Stop-serving routes: per-line layers give each route a distinct stable colour.
        s.addLayer(
            LineLayer("emt-context-routes", SRC_CONTEXT)
                .withProperties(
                    PropertyFactory.lineColor(colors.routes.values.firstOrNull() ?: colors.ida),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineOpacity(0.9f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        addContextLineLayers(s)

        s.addLayer(
            LineLayer("emt-ida", SRC_MAIN)
                .withFilter(Expression.eq(Expression.get("dir"), Expression.literal("ida")))
                .withProperties(
                    PropertyFactory.lineColor(colors.ida), PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineOpacity(0.96f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        s.addLayer(
            LineLayer("emt-vuelta", SRC_MAIN)
                .withFilter(Expression.eq(Expression.get("dir"), Expression.literal("vuelta")))
                .withProperties(
                    PropertyFactory.lineColor(colors.vuelta), PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineOpacity(0.96f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        val stopsLayer = SymbolLayer("emt-stops", SRC_STOPS)
            .withProperties(
                PropertyFactory.iconImage("emt-icon"),
                PropertyFactory.iconSize(0.42f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            )
        stopsLayer.setMinZoom(12f)
        s.addLayer(stopsLayer)
        s.addLayer(
            CircleLayer("emt-buses-ida", SRC_BUSES)
                .withFilter(Expression.eq(Expression.get("dir"), Expression.literal("ida")))
                .withProperties(
                    PropertyFactory.circleRadius(12f), PropertyFactory.circleColor(colors.ida),
                    PropertyFactory.circleStrokeColor(colors.busHalo), PropertyFactory.circleStrokeWidth(2f),
                ),
        )
        s.addLayer(
            CircleLayer("emt-buses-vuelta", SRC_BUSES)
                .withFilter(Expression.eq(Expression.get("dir"), Expression.literal("vuelta")))
                .withProperties(
                    PropertyFactory.circleRadius(12f), PropertyFactory.circleColor(colors.vuelta),
                    PropertyFactory.circleStrokeColor(colors.busHalo), PropertyFactory.circleStrokeWidth(2f),
                ),
        )
        s.addLayer(
            CircleLayer("emt-buses-unknown", SRC_BUSES)
                .withFilter(Expression.eq(Expression.get("dir"), Expression.literal("")))
                .withProperties(
                    PropertyFactory.circleRadius(12f), PropertyFactory.circleColor(colors.ida),
                    PropertyFactory.circleStrokeColor(colors.busHalo), PropertyFactory.circleStrokeWidth(2f),
                ),
        )
        s.addLayer(
            SymbolLayer("emt-bus-numbers", SRC_BUSES)
                .withProperties(
                    PropertyFactory.textField(Expression.get("line")),
                    PropertyFactory.textSize(11f), PropertyFactory.textColor(colors.busText),
                    PropertyFactory.textHaloColor(colors.busHalo), PropertyFactory.textHaloWidth(1.2f),
                    PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
                ),
        )
        // Planned trip: solid for the ride, dashed for the walking stretches.
        s.addLayer(
            LineLayer("emt-journey-bus", SRC_JOURNEY)
                .withFilter(Expression.eq(Expression.get("walk"), Expression.literal(false)))
                .withProperties(
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))), PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineOpacity(0.95f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        s.addLayer(
            LineLayer("emt-journey-walk", SRC_JOURNEY)
                .withFilter(Expression.eq(Expression.get("walk"), Expression.literal(true)))
                .withProperties(
                    PropertyFactory.lineColor(colors.stopFill), PropertyFactory.lineWidth(3.5f),
                    PropertyFactory.lineOpacity(0.95f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineDasharray(arrayOf(1.5f, 1.5f)),
                ),
        )
        // Transfers and trip ends: big dots coloured to match their ride.
        s.addLayer(
            CircleLayer("emt-journey-stops", SRC_TRANSFER)
                .withProperties(
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleStrokeColor(colors.busText),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
        )
        // Metrovalencia route lines, each in its own brand colour.
        s.addSource(GeoJsonSource(SRC_METRO, EMPTY))
        s.addLayer(
            LineLayer("emt-metro-routes", SRC_METRO)
                .withProperties(
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        // Other networks: Metrovalencia, Valenbisi and Metrobús points.
        // Dense at city scale, so they are clustered; taps use the raw data.
        s.addLayer(
            CircleLayer("emt-places-clusters", SRC_PLACES)
                .withFilter(Expression.has("point_count"))
                .withProperties(
                    PropertyFactory.circleColor(colors.ida),
                    PropertyFactory.circleOpacity(0.9f),
                    PropertyFactory.circleStrokeColor(colors.stopStroke),
                    PropertyFactory.circleStrokeWidth(1.5f),
                    PropertyFactory.circleRadius(
                        Expression.step(
                            Expression.get("point_count"),
                            Expression.literal(15f),
                            Expression.stop(10, 20f),
                            Expression.stop(50, 26f),
                        ),
                    ),
                ),
        )
        s.addLayer(
            SymbolLayer("emt-places-cluster-count", SRC_PLACES)
                .withFilter(Expression.has("point_count"))
                .withProperties(
                    PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textColor(colors.busText),
                ),
        )
        val placesLayer = SymbolLayer("emt-places", SRC_PLACES)
            .withFilter(
                Expression.all(
                    Expression.not(Expression.has("point_count")),
                    Expression.neq(Expression.get("network"), Expression.literal("Valenbisi")),
                ),
            )
            .withProperties(
                PropertyFactory.iconImage(
                    Expression.match(
                        Expression.get("network"),
                        Expression.literal("Metro"), Expression.literal("metro-icon"),
                        Expression.literal("Valenbisi"), Expression.literal("valenbisi-icon"),
                        Expression.literal("emt-icon"),
                    ),
                ),
                PropertyFactory.iconSize(0.5f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            )
        placesLayer.setMinZoom(11f)
        s.addLayer(placesLayer)
        // Valenbisi: a Material-style pill holding the dock icon and the bike count.
        val vbFilter = Expression.all(
            Expression.not(Expression.has("point_count")),
            Expression.eq(Expression.get("network"), Expression.literal("Valenbisi")),
        )
        s.addLayer(
            SymbolLayer("emt-vb-pill", SRC_PLACES)
                .withFilter(vbFilter)
                .withProperties(
                    PropertyFactory.iconImage("vb-pill"),
                    PropertyFactory.iconSize(0.55f),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
                .also { it.setMinZoom(12f) },
        )
        s.addLayer(
            SymbolLayer("emt-vb-icon", SRC_PLACES)
                .withFilter(vbFilter)
                .withProperties(
                    PropertyFactory.iconImage("valenbisi-icon"),
                    PropertyFactory.iconSize(0.45f),
                    PropertyFactory.iconOffset(arrayOf(-30f, 0f)),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
                .also { it.setMinZoom(12f) },
        )
        s.addLayer(
            SymbolLayer("emt-vb-count", SRC_PLACES)
                .withFilter(vbFilter)
                .withProperties(
                    PropertyFactory.textField(Expression.toString(Expression.get("available"))),
                    PropertyFactory.textSize(13f),
                    PropertyFactory.textColor(colors.busText),
                    PropertyFactory.textOffset(arrayOf(0.7f, 0f)),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                )
                .also { it.setMinZoom(12f) },
        )
        val placeLabels = SymbolLayer("emt-places-labels", SRC_PLACES)
            .withFilter(
                Expression.all(
                    Expression.not(Expression.has("point_count")),
                    Expression.neq(Expression.get("network"), Expression.literal("Valenbisi")),
                ),
            )
            .withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(colors.busText),
                PropertyFactory.textHaloColor(colors.busHalo),
                PropertyFactory.textHaloWidth(1.2f),
                PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                PropertyFactory.textAllowOverlap(false),
            )
        placeLabels.setMinZoom(14.5f)
        s.addLayer(placeLabels)
        // "You are here": a soft halo under a solid dot, on top of everything else.
        s.addLayer(
            CircleLayer("emt-user-halo", SRC_USER)
                .withProperties(
                    PropertyFactory.circleRadius(20f),
                    PropertyFactory.circleColor(USER_COLOR),
                    PropertyFactory.circleOpacity(0.18f),
                    PropertyFactory.circleBlur(0.6f),
                ),
        )
        s.addLayer(
            CircleLayer("emt-user-dot", SRC_USER)
                .withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(USER_COLOR),
                    PropertyFactory.circleStrokeColor(colors.busText),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
        )
    }

    private fun addContextLineLayers(s: Style) {
        data.contextLines.forEachIndexed { i, line ->
            val layer = LineLayer("emt-context-$i", SRC_CONTEXT)
                .withFilter(Expression.eq(Expression.get("linea"), Expression.literal(line)))
                .withProperties(
                    PropertyFactory.lineColor(colors.routes[line] ?: colors.ida),
                    PropertyFactory.lineWidth(4.2f), PropertyFactory.lineOpacity(0.95f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            s.addLayer(layer)
        }
    }

    private fun updateSources() {
        val s = style ?: return
        // Only push a source when its data actually changed: the bus layer moves
        // every frame, but stops/places/routes do not.
        if (lastContext != data.contextLines) {
            lastContext = data.contextLines
            s.getSourceAs<GeoJsonSource>(SRC_CONTEXT)?.setGeoJson(contextJson(data.contextLines, data.routes))
            data.contextLines.forEachIndexed { i, line ->
                s.getLayerAs<LineLayer>("emt-context-$i")?.setProperties(PropertyFactory.lineColor(colors.routes[line] ?: colors.ida))
            }
        }
        if (lastMain != data.currentLine || lastRoutes !== data.routes) {
            lastMain = data.currentLine
            lastRoutes = data.routes
            s.getSourceAs<GeoJsonSource>(SRC_MAIN)?.setGeoJson(mainJson(data.currentLine, data.routes))
        }
        if (lastStops !== data.visibleStops) {
            lastStops = data.visibleStops
            s.getSourceAs<GeoJsonSource>(SRC_STOPS)?.setGeoJson(stopsJson(data.visibleStops))
        }
        s.getSourceAs<GeoJsonSource>(SRC_BUSES)?.setGeoJson(busesJson(data.buses))
        if (lastJourney !== data.journey) {
            lastJourney = data.journey
            s.getSourceAs<GeoJsonSource>(SRC_JOURNEY)?.setGeoJson(journeyJson(data.journey))
        }
        if (lastTransfer !== data.journeyStops) {
            lastTransfer = data.journeyStops
            s.getSourceAs<GeoJsonSource>(SRC_TRANSFER)?.setGeoJson(transfersJson(data.journeyStops))
        }
        if (lastPlaces !== data.places) {
            lastPlaces = data.places
            s.getSourceAs<GeoJsonSource>(SRC_PLACES)?.setGeoJson(placesJson(data.places))
        s.getSourceAs<GeoJsonSource>(SRC_METRO)?.setGeoJson(metroRoutesJson(data.metroLines))
        }
        if (lastUser != data.userLocation) {
            lastUser = data.userLocation
            s.getSourceAs<GeoJsonSource>(SRC_USER)?.setGeoJson(userJson(data.userLocation))
        }
        data.followBusNumber?.let { number ->
            data.buses.firstOrNull { it.number == number }?.let { follow(it.render, it.bearing) }
        }
    }

    private fun installListeners(m: MapLibreMap) {
        clickListener?.let { m.removeOnMapClickListener(it) }
        clickListener = MapLibreMap.OnMapClickListener { tapped ->
            val screen = m.projection.toScreenLocation(tapped)
            val density = view.resources.displayMetrics.density
            val bus = data.buses.minByOrNull { b ->
                val p = m.projection.toScreenLocation(LatLng(b.render.lat, b.render.lon))
                hypot((p.x - screen.x).toDouble(), (p.y - screen.y).toDouble())
            }
            if (bus != null) {
                val p = m.projection.toScreenLocation(LatLng(bus.render.lat, bus.render.lon))
                if (hypot((p.x - screen.x).toDouble(), (p.y - screen.y).toDouble()) <= 36 * density) {
                    onBusTap(bus.number)
                    return@OnMapClickListener true
                }
            }
            val stop = data.visibleStops.minByOrNull { st ->
                val p = m.projection.toScreenLocation(LatLng(st.lat, st.lon))
                hypot((p.x - screen.x).toDouble(), (p.y - screen.y).toDouble())
            }
            if (stop != null) {
                val p = m.projection.toScreenLocation(LatLng(stop.lat, stop.lon))
                if (hypot((p.x - screen.x).toDouble(), (p.y - screen.y).toDouble()) <= 30 * density) {
                    onStopTap(stop)
                    return@OnMapClickListener true
                }
            }
            val place = data.places.minByOrNull { pl ->
                val p = m.projection.toScreenLocation(LatLng(pl.lat, pl.lon))
                hypot((p.x - screen.x).toDouble(), (p.y - screen.y).toDouble())
            }
            if (place != null) {
                val p = m.projection.toScreenLocation(LatLng(place.lat, place.lon))
                if (hypot((p.x - screen.x).toDouble(), (p.y - screen.y).toDouble()) <= 34 * density) {
                    onPlaceTap(place)
                    return@OnMapClickListener true
                }
            }
            false
        }
        m.addOnMapClickListener(clickListener!!)
        cameraListener?.let { m.removeOnCameraIdleListener(it) }
        cameraListener = MapLibreMap.OnCameraIdleListener { emitViewport() }
        m.addOnCameraIdleListener(cameraListener!!)
    }

    private fun emitViewport() {
        val m = map ?: return
        val b = m.projection.visibleRegion.latLngBounds
        onViewport(MapViewport(m.cameraPosition.zoom.toDouble(), b.longitudeWest, b.latitudeSouth, b.longitudeEast, b.latitudeNorth))
    }

    private fun contextJson(lines: List<String>, routes: Map<String, BusRoute>): String {
        val features = JSONArray()
        lines.forEach { line ->
            val route = routes[line] ?: return@forEach
            listOf(route.ida, route.vuelta).forEach { direction ->
                direction.shapes.forEach { shape ->
                    val coords = JSONArray().apply { shape.forEach { put(JSONArray().put(it.lon).put(it.lat)) } }
                    features.put(feature("LineString", coords, JSONObject().put("linea", line)))
                }
            }
        }
        return collection(features)
    }

    private fun mainJson(line: String?, routes: Map<String, BusRoute>): String {
        val features = JSONArray()
        if (line != null) {
            val route = routes[line]
            route?.ida?.shapes?.forEach { shape ->
                val coords = JSONArray().apply { shape.forEach { put(JSONArray().put(it.lon).put(it.lat)) } }
                features.put(feature("LineString", coords, JSONObject().put("dir", "ida")))
            }
            route?.vuelta?.shapes?.forEach { shape ->
                val coords = JSONArray().apply { shape.forEach { put(JSONArray().put(it.lon).put(it.lat)) } }
                features.put(feature("LineString", coords, JSONObject().put("dir", "vuelta")))
            }
        }
        return collection(features)
    }

    private fun placesJson(places: List<Place>): String {
        val features = JSONArray()
        places.forEach { place ->
            val properties = JSONObject()
                .put("id", place.id)
                .put("network", place.network.name)
                .put("name", place.name)
                .put("lines", place.lines.joinToString(" · "))
                .put("detail", place.detail)
                .put("available", place.available)
                .put("free", place.free)
                .put("color", placeColor(place))
            features.put(feature("Point", JSONArray().put(place.lon).put(place.lat), properties))
        }
        return collection(features)
    }

    private fun placeColor(place: Place): String = when (place.network) {
        Network.Metro -> "#E4002B"
        Network.Metrobus -> "#0EA5E9"
        Network.Emt -> "#F97316"
        Network.Valenbisi -> when {
            !place.open -> "#9CA3AF"
            place.available <= 0 -> "#EF4444"
            place.free <= 0 -> "#F59E0B"
            else -> "#22C55E"
        }
    }

    private fun journeyJson(segments: List<JourneySegment>): String {
        val features = JSONArray()
        segments.forEach { segment ->
            if (segment.points.size < 2) return@forEach
            val coords = JSONArray().apply { segment.points.forEach { put(JSONArray().put(it.lon).put(it.lat)) } }
            features.put(
                feature(
                    "LineString", coords,
                    JSONObject().put("walk", segment.walk).put("color", JOURNEY_PALETTE[segment.colorIndex.mod(JOURNEY_PALETTE.size)]),
                ),
            )
        }
        return collection(features)
    }

    private fun transfersJson(stops: List<JourneyStop>): String {
        val features = JSONArray()
        stops.forEach { stop ->
            features.put(
                feature(
                    "Point", JSONArray().put(stop.point.lon).put(stop.point.lat),
                    JSONObject().put("color", JOURNEY_PALETTE[stop.colorIndex.mod(JOURNEY_PALETTE.size)]),
                ),
            )
        }
        return collection(features)
    }

    private fun userJson(point: LonLat?): String {
        val features = JSONArray()
        if (point != null) {
            features.put(feature("Point", JSONArray().put(point.lon).put(point.lat), JSONObject()))
        }
        return collection(features)
    }

        private fun stopsJson(stops: List<Stop>): String {        val features = JSONArray()
        stops.forEach { features.put(feature("Point", JSONArray().put(it.lon).put(it.lat), JSONObject().put("id", it.id))) }
        return collection(features)
    }

    private fun busesJson(buses: List<BusPosition>): String {
        val features = JSONArray()
        buses.forEach {
            features.put(
                feature(
                    "Point", JSONArray().put(it.render.lon).put(it.render.lat),
                    JSONObject().put("line", it.line).put("dir", it.direction ?: "").put("num", it.number),
                ),
            )
        }
        return collection(features)
    }

    private fun feature(type: String, coordinates: JSONArray, properties: JSONObject) =
        JSONObject()
            .put("type", "Feature")
            .put("properties", properties)
            .put("geometry", JSONObject().put("type", type).put("coordinates", coordinates))

    private fun collection(features: JSONArray) =
        JSONObject().put("type", "FeatureCollection").put("features", features).toString()

    companion object {
        private const val SRC_CONTEXT = "emt-context"
        private const val SRC_MAIN = "emt-main"
        private const val SRC_STOPS = "emt-stops"
        private const val SRC_BUSES = "emt-buses"
        private const val SRC_USER = "emt-user"
        private const val SRC_JOURNEY = "emt-journey"
        private const val SRC_PLACES = "emt-places"
        private const val SRC_METRO = "emt-metro"
        private const val SRC_TRANSFER = "emt-transfer"
        private val USER_COLOR = 0xFF1A73E8.toInt()
        private const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}

