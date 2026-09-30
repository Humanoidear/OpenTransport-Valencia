package es.emtvalencia.live

import kotlin.math.*
import java.util.Locale

data class LonLat(val lon: Double, val lat: Double)
data class PreparedRoute(val points: List<LonLat>, val cumulativeMeters: DoubleArray) {
    val totalMeters: Double get() = cumulativeMeters.lastOrNull() ?: 0.0
}
data class Projection(val distanceAlong: Double, val distanceFrom: Double)
data class StopHold(val id: String, val distanceAlong: Double)
data class UpcomingStop(val stop: Stop, val minutes: Int)

object TransitMotion {
    private const val EARTH_M = 6_371_000.0
    private const val METERS_PER_DEGREE = 111_320.0
    private fun rad(d: Double) = d * Math.PI / 180.0

    fun distanceMeters(a: LonLat, b: LonLat): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun prepare(points: List<LonLat>): PreparedRoute {
        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + distanceMeters(points[i - 1], points[i])
        return PreparedRoute(points, cumulative)
    }

    fun project(route: PreparedRoute, p: LonLat): Projection {
        if (route.points.size < 2) return Projection(0.0, Double.POSITIVE_INFINITY)
        val mLon = METERS_PER_DEGREE * cos(rad(p.lat)).coerceAtLeast(1e-6)
        var bestS = 0.0
        var bestD = Double.POSITIVE_INFINITY
        for (i in 1 until route.points.size) {
            val a = route.points[i - 1]
            val b = route.points[i]
            val ax = (a.lon - p.lon) * mLon
            val ay = (a.lat - p.lat) * METERS_PER_DEGREE
            val bx = (b.lon - p.lon) * mLon
            val by = (b.lat - p.lat) * METERS_PER_DEGREE
            val dx = bx - ax
            val dy = by - ay
            val denom = dx * dx + dy * dy
            val t = if (denom == 0.0) 0.0 else (-(ax * dx + ay * dy) / denom).coerceIn(0.0, 1.0)
            val d = hypot(ax + dx * t, ay + dy * t)
            if (d < bestD) {
                bestD = d
                bestS = route.cumulativeMeters[i - 1] + distanceMeters(a, b) * t
            }
        }
        return Projection(bestS, bestD)
    }

    fun pointAt(route: PreparedRoute, s: Double): LonLat {
        if (route.points.isEmpty()) return LonLat(0.0, 0.0)
        if (route.points.size == 1) return route.points.first()
        val value = s.coerceIn(0.0, route.totalMeters)
        var lo = 0
        var hi = route.cumulativeMeters.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (route.cumulativeMeters[mid] <= value) lo = mid else hi = mid
        }
        val segment = route.cumulativeMeters[hi] - route.cumulativeMeters[lo]
        val t = if (segment == 0.0) 0.0 else (value - route.cumulativeMeters[lo]) / segment
        val a = route.points[lo]
        val b = route.points[hi]
        return LonLat(a.lon + (b.lon - a.lon) * t, a.lat + (b.lat - a.lat) * t)
    }

    fun clampSpeed(mps: Double, max: Double = 15.0) = if (mps < 0.3) 0.0 else mps.coerceAtMost(max)
    fun predictArc(s: Double, speed: Double, seconds: Double, total: Double) = (s + speed * seconds).coerceIn(0.0, total)

    fun bearing(route: PreparedRoute, s: Double): Double {
        val step = 15.0
        var a = pointAt(route, s)
        var b = pointAt(route, min(route.totalMeters, s + step))
        if (s + step > route.totalMeters) {
            a = pointAt(route, max(0.0, s - step))
            b = pointAt(route, s)
        }
        val dx = (b.lon - a.lon) * cos(rad(a.lat))
        val dy = b.lat - a.lat
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    fun itinerary(routes: Map<String, BusRoute>, line: String, direction: String): List<String> {
        val route = routes[line] ?: return emptyList()
        return if (direction == "ida") route.ida.stops else route.vuelta.stops
    }

    fun direction(routes: Map<String, BusRoute>, line: String, last: Int, next: Int): String? {
        val l = last.toString()
        val n = next.toString()
        for (exact in listOf(true, false)) {
            for (dir in listOf("ida", "vuelta")) {
                val seq = itinerary(routes, line, dir)
                val ni = seq.indexOf(n)
                if (ni < 0) continue
                if (!exact) return dir
                val li = seq.indexOf(l)
                if (li >= 0 && ni == li + 1) return dir
            }
        }
        return null
    }

    fun stopHold(hold: StopHold?, s: Double, next: String, stopS: Double?): StopHold? {
        if (hold != null) {
            val left = s > hold.distanceAlong + 30 || (next != hold.id && s > hold.distanceAlong - 10)
            return if (left) null else hold
        }
        return if (stopS != null && abs(s - stopS) <= 35) StopHold(next, stopS) else null
    }

    fun upcoming(
        routes: Map<String, BusRoute>,
        stopsById: Map<String, Stop>,
        line: String,
        dir: String,
        next: Int,
        position: LonLat,
        limit: Int = 12,
    ): List<UpcomingStop> {
        val seq = itinerary(routes, line, dir)
        val first = seq.indexOf(next.toString())
        if (first < 0) return emptyList()
        var prev = position
        var distance = 0.0
        return seq.drop(first).take(limit).mapNotNull { id ->
            val stop = stopsById[id] ?: return@mapNotNull null
            val pos = LonLat(stop.lon, stop.lat)
            distance += distanceMeters(prev, pos)
            prev = pos
            UpcomingStop(stop, max(1, (distance / 1000.0 / 18.0 * 60.0).roundToInt()))
        }
    }

    fun formatDistance(meters: Double): String =
        if (meters < 950) "${(meters / 10).roundToInt() * 10} m" else "${String.format(Locale.US, "%.1f", meters / 1000)} km"
}
