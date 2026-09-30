package com.example.p2pchat.maps

import kotlin.math.abs

/**
 * One turn-by-turn maneuver anchored to a point along a route.
 *
 * @param cumulativeMeters distance from the start of the route to [point], in metres - used to
 *   work out "in X m, turn left" as new GPS fixes come in.
 */
data class NavStep(
    val point: GeoPoint,
    val cumulativeMeters: Double,
    val instruction: String,
    val isArrival: Boolean = false
)

/** Maneuver kinds, each carrying the phrase spoken/shown for it. */
enum class Maneuver(val phrase: String) {
    SLIGHT_LEFT("Bear left"),
    LEFT("Turn left"),
    SHARP_LEFT("Turn sharp left"),
    SLIGHT_RIGHT("Bear right"),
    RIGHT("Turn right"),
    SHARP_RIGHT("Turn sharp right"),
    U_TURN("Make a U-turn")
}

/**
 * Turns a raw route polyline (as returned by [IndiaMapView.getRoutePoints], which in turn comes
 * from [com.example.p2pchat.maps.osm.RoadGraph.shortestPath]) into a short list of turn-by-turn
 * instructions.
 *
 * An OSM way is made of many almost-collinear nodes (that's how curves are represented), so most
 * points along the route are NOT maneuvers - only points where the bearing changes sharply enough
 * to be an actual turn are kept. This is a lightweight geometric heuristic (bearing-delta between
 * consecutive segments), not a full OSM turn-restriction/lane-guidance engine.
 */
object TurnByTurn {

    /** Bearing changes smaller than this are road curvature, not a maneuver worth announcing. */
    private const val MIN_TURN_DEGREES = 25.0

    /** Builds the instruction list for [route]. Always has >= 2 entries when [route] has >= 2 points. */
    fun build(route: List<GeoPoint>): List<NavStep> {
        if (route.size < 2) return emptyList()

        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cumulative[i] = cumulative[i - 1] + GeoMath.distanceKm(route[i - 1], route[i]) * 1000.0
        }

        val steps = ArrayList<NavStep>()
        val startBearing = GeoMath.bearingDegrees(route[0], route[1])
        steps.add(NavStep(route[0], 0.0, "Head ${GeoMath.compassLabel(startBearing)}"))

        for (i in 1 until route.size - 1) {
            val inBearing = GeoMath.bearingDegrees(route[i - 1], route[i])
            val outBearing = GeoMath.bearingDegrees(route[i], route[i + 1])
            var delta = outBearing - inBearing
            while (delta > 180) delta -= 360
            while (delta < -180) delta += 360
            val magnitude = abs(delta)
            if (magnitude < MIN_TURN_DEGREES) continue // gentle curve, not a maneuver

            val maneuver = when {
                magnitude >= 150.0 -> Maneuver.U_TURN
                magnitude >= 100.0 -> if (delta > 0) Maneuver.SHARP_RIGHT else Maneuver.SHARP_LEFT
                magnitude >= 45.0 -> if (delta > 0) Maneuver.RIGHT else Maneuver.LEFT
                else -> if (delta > 0) Maneuver.SLIGHT_RIGHT else Maneuver.SLIGHT_LEFT
            }
            steps.add(NavStep(route[i], cumulative[i], maneuver.phrase))
        }

        steps.add(NavStep(route.last(), cumulative.last(), "Arrive at your destination", isArrival = true))
        return steps
    }
}
