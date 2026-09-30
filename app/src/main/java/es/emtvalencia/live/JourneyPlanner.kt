package es.emtvalencia.live

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class JourneyLeg(
    val walk: Boolean,
    val minutes: Int,
    val meters: Int,
    val line: String? = null,
    val direction: String? = null,
    val towards: String? = null,
    val boardStop: String? = null,
    val alightStop: String? = null,
    val stops: Int = 0,
    val waitMinutes: Int = 0,
    val toStop: String? = null,
    val boardStopId: Int? = null,
    val alightStopId: Int? = null,
)

data class JourneyOption(
    val totalMinutes: Int,
    val walkMeters: Int,
    val transfers: Int,
    val legs: List<JourneyLeg>,
)

/**
 * Schedule-aware journey planner: rides along EMT itineraries, walking on OSM
 * streets, scored by the live waiting times supplied through [nextBus].
 * Pure logic — no platform or network code lives here.
 */
class JourneyPlanner(
    private val transit: TransitData,
    private val streetWalk: suspend (LonLat, LonLat) -> Pair<Double, Double>, // metres, seconds
    private val nextBus: suspend (Int, String) -> Int?,                      // minutes
) {
    private val sequence = HashMap<Pair<String, String>, List<String>>()
    private val position = HashMap<Pair<String, String>, Map<String, Int>>()

    init {
        transit.routes.forEach { (line, route) ->
            listOf("ida" to route.ida, "vuelta" to route.vuelta).forEach { (dir, r) ->
                val stops = r.stops
                sequence[line to dir] = stops
                position[line to dir] = stops.withIndex().associate { it.value to it.index }
            }
        }
    }

    private fun linesAt(stopId: Int): Set<String> = transit.stopsById[stopId.toString()]?.lines.orEmpty().toSet()

    private fun stopsNear(point: LonLat, maxWalkMeters: Double, limit: Int = 10): List<Stop> =
        transit.stops.asSequence()
            .map { it to straightMeters(point, LonLat(it.lon, it.lat)) }
            .filter { it.second <= maxWalkMeters * DETOUR }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()

    private fun straightMeters(a: LonLat, b: LonLat): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(min(1.0, h)))
    }

    private fun ride(line: String, dir: String, from: Int, to: Int): Pair<Double, Double> {
        val seq = sequence[line to dir].orEmpty()
        var meters = 0.0
        for (k in from + 1..to) {
            val a = transit.stopsById[seq.getOrNull(k - 1)]
            val b = transit.stopsById[seq.getOrNull(k)]
            if (a != null && b != null) meters += straightMeters(LonLat(a.lon, a.lat), LonLat(b.lon, b.lat)) * DETOUR
        }
        val minutes = if (meters > 0) meters / RIDE_MPS / 60 else (to - from) * STOP_MINUTES
        return meters to minutes
    }

    private fun terminus(line: String, dir: String): String {
        val seq = sequence[line to dir].orEmpty()
        return seq.lastOrNull()?.let { transit.stopsById[it]?.name } ?: line
    }

    suspend fun plan(origin: LonLat, destination: LonLat, maxWalkMeters: Double = MAX_WALK_METRES): List<JourneyOption> {
        val originStops = stopsNear(origin, maxWalkMeters)
        val destStops = stopsNear(destination, maxWalkMeters)
        val destLines = destStops.flatMap { linesAt(it.id) }.toSet()
        val options = mutableListOf<JourneyOption>()
        val waitCache = HashMap<String, Int?>()

        suspend fun wait(stopId: Int, line: String): Int {
            val key = "$stopId:$line"
            if (waitCache.containsKey(key)) return waitCache[key] ?: DEFAULT_WAIT
            val value = nextBus(stopId, line)
            waitCache[key] = value
            return value ?: DEFAULT_WAIT
        }

        for (originStop in originStops) {
            val (walkODist, walkOSecs) = streetWalk(origin, LonLat(originStop.lon, originStop.lat))
            if (walkODist > maxWalkMeters) continue

            for (line in linesAt(originStop.id)) {
                for (dir in listOf("ida", "vuelta")) {
                    val i = position[line to dir]?.get(originStop.id.toString()) ?: continue

                    // --- direct ride ---
                    for (destStop in destStops) {
                        if (line !in destStop.lines) continue
                        val j = position[line to dir]?.get(destStop.id.toString()) ?: continue
                        if (j <= i) continue
                        val (walkDDist, walkDSecs) =
                            streetWalk(LonLat(destStop.lon, destStop.lat), destination)
                        if (walkDDist > maxWalkMeters) continue
                        val rideMinutes = ride(line, dir, i, j).second
                        val waitMinutes = wait(originStop.id, line)
                        val total = walkOSecs / 60 + waitMinutes + rideMinutes + walkDSecs / 60
                        options += JourneyOption(
                            totalMinutes = total.roundToInt(),
                            walkMeters = (walkODist + walkDDist).roundToInt(),
                            transfers = 0,
                            legs = listOf(
                                JourneyLeg(true, max(1, (walkOSecs / 60).roundToInt()), walkODist.roundToInt(), toStop = originStop.name),
                                JourneyLeg(
                                    false, max(1, rideMinutes.roundToInt()), 0, line, dir, terminus(line, dir),
                                    originStop.name, destStop.name, j - i, waitMinutes,
                                    boardStopId = originStop.id, alightStopId = destStop.id,
                                ),
                                JourneyLeg(true, max(1, (walkDSecs / 60).roundToInt()), walkDDist.roundToInt(), toStop = "destination"),
                            ),
                        )
                    }

                    // --- one transfer ---
                    val seq = sequence[line to dir].orEmpty()
                    val last = min(i + MAX_RIDE_STOPS, seq.lastIndex)
                    for (k in i + 1..last) {
                        val transfer = transit.stopsById[seq[k]] ?: continue
                        if (transfer.id == originStop.id) continue
                        for (line2 in linesAt(transfer.id) - line) {
                            if (line2 !in destLines) continue
                            for (dir2 in listOf("ida", "vuelta")) {
                                val ix = position[line2 to dir2]?.get(transfer.id.toString()) ?: continue
                                for (destStop in destStops) {
                                    if (line2 !in destStop.lines) continue
                                    val j2 = position[line2 to dir2]?.get(destStop.id.toString()) ?: continue
                                    if (j2 <= ix) continue
                                    val (walkDDist, walkDSecs) =
                                        streetWalk(LonLat(destStop.lon, destStop.lat), destination)
                                    if (walkDDist > maxWalkMeters) continue
                                    val ride1 = ride(line, dir, i, k).second
                                    val ride2 = ride(line2, dir2, ix, j2).second
                                    val wait1 = wait(originStop.id, line)
                                    val wait2 = wait(transfer.id, line2)
                                    val total = walkOSecs / 60 + wait1 + ride1 + TRANSFER_PENALTY +
                                        wait2 + ride2 + walkDSecs / 60
                                    options += JourneyOption(
                                        totalMinutes = total.roundToInt(),
                                        walkMeters = (walkODist + walkDDist).roundToInt(),
                                        transfers = 1,
                                        legs = listOf(
                                            JourneyLeg(true, max(1, (walkOSecs / 60).roundToInt()), walkODist.roundToInt(), toStop = originStop.name),
                                            JourneyLeg(false, max(1, ride1.roundToInt()), 0, line, dir, terminus(line, dir), originStop.name, transfer.name, k - i, wait1, boardStopId = originStop.id, alightStopId = transfer.id),
                                            JourneyLeg(false, max(1, ride2.roundToInt()), 0, line2, dir2, terminus(line2, dir2), transfer.name, destStop.name, j2 - ix, wait2, boardStopId = transfer.id, alightStopId = destStop.id),
                                            JourneyLeg(true, max(1, (walkDSecs / 60).roundToInt()), walkDDist.roundToInt(), toStop = "destination"),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val seen = mutableSetOf<String>()
        val ranked = options.sortedBy { it.totalMinutes }.filter { option ->
            val signature = option.legs.filter { !it.walk }.joinToString("|") { "${it.line}:${it.boardStop}" }
            seen.add(signature)
        }
        // If a direct ride is at least as fast, transfer options are noise.
        val fastestDirect = ranked.filter { it.transfers == 0 }.minOfOrNull { it.totalMinutes }
        val useful = if (fastestDirect == null) {
            ranked
        } else {
            ranked.filter { it.transfers == 0 || it.totalMinutes < fastestDirect }
        }
        return useful.take(MAX_OPTIONS)
    }

    companion object {
        private const val DETOUR = 1.25
        private const val RIDE_MPS = 5.0
        private const val STOP_MINUTES = 1.6
        private const val MAX_WALK_METRES = 1000.0
        private const val MAX_RIDE_STOPS = 30
        private const val TRANSFER_PENALTY = 3.0
        private const val DEFAULT_WAIT = 6
        private const val MAX_OPTIONS = 4
    }
}
