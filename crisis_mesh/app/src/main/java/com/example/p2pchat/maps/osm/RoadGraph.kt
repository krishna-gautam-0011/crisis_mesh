package com.example.p2pchat.maps.osm

import com.example.p2pchat.maps.GeoPoint
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A lightweight, in-memory road graph built from an OpenStreetMap extract - either a plain
 * `.osm` XML file (via [OsmRoadLoader]) or a compact `.osm.pbf` file (via [OsmPbfRoadLoader]).
 * Nodes are kept as plain parallel arrays (not objects) to keep memory reasonable for a large
 * extract; edges reference nodes by index into those arrays.
 */
class RoadGraph(
    val nodeIds: LongArray,                    // original OSM node id, indexed by node index
    val lat: DoubleArray,                       // parallel to nodeIds
    val lng: DoubleArray,                       // parallel to nodeIds
    val adjacency: Array<MutableList<Edge>>     // adjacency[nodeIndex] -> outgoing edges
) {
    data class Edge(val to: Int, val meters: Double)

    val nodeCount: Int get() = nodeIds.size

    // ---- uniform grid spatial index, used only for "nearest road node to this GPS fix" ----
    private val cellSizeDeg = 0.02 // ~2.2km cells at the equator - plenty fine for this
    private val grid: HashMap<Long, MutableList<Int>> by lazy { buildGrid() }

    private fun cellKeyFromCell(cx: Long, cy: Long): Long = (cx + 200_000L) * 1_000_000L + (cy + 200_000L)

    private fun cellKey(latVal: Double, lngVal: Double): Long {
        val cx = floor(lngVal / cellSizeDeg).toLong()
        val cy = floor(latVal / cellSizeDeg).toLong()
        return cellKeyFromCell(cx, cy)
    }

    private fun buildGrid(): HashMap<Long, MutableList<Int>> {
        val g = HashMap<Long, MutableList<Int>>()
        for (i in nodeIds.indices) {
            g.getOrPut(cellKey(lat[i], lng[i])) { mutableListOf() }.add(i)
        }
        return g
    }

    /**
     * Nearest graph node to [point] within [maxMeters] (default 3km - a GPS fix or pin
     * further than that from any known road is treated as "no route data here").
     * Returns -1 if nothing close enough was found.
     */
    fun nearestNode(point: GeoPoint, maxMeters: Double = 3000.0): Int {
        if (nodeCount == 0) return -1
        var best = -1
        var bestDist = Double.MAX_VALUE
        val cx = floor(point.lng / cellSizeDeg).toLong()
        val cy = floor(point.lat / cellSizeDeg).toLong()
        val maxRing = ceil((maxMeters / 111_000.0) / cellSizeDeg).toInt().coerceAtLeast(1) + 1
        var ringsSinceFound = -1

        for (ring in 0..maxRing) {
            for (dx in -ring..ring) {
                for (dy in -ring..ring) {
                    if (max(abs(dx), abs(dy)) != ring) continue // only this ring's outline
                    val bucket = grid[cellKeyFromCell(cx + dx, cy + dy)] ?: continue
                    for (idx in bucket) {
                        val d = haversine(point.lat, point.lng, lat[idx], lng[idx])
                        if (d < bestDist) {
                            bestDist = d
                            best = idx
                        }
                    }
                }
            }
            if (best != -1) {
                if (ringsSinceFound == -1) ringsSinceFound = 0 else ringsSinceFound++
                // search one extra ring past the first hit, since the true nearest point
                // can sit just across a cell boundary from where it was first seen
                if (ringsSinceFound >= 1) break
            }
        }
        return if (best != -1 && bestDist <= maxMeters) best else -1
    }

    /**
     * Shortest road path from node [fromIdx] to node [toIdx] by total edge distance (metres),
     * via a plain Dijkstra search over [adjacency]. Returns the list of node indices from start
     * to end (inclusive), or null if the two nodes aren't connected within the loaded extract
     * (common at the edge of a clipped region) or either index is invalid.
     */
    fun shortestPath(fromIdx: Int, toIdx: Int): List<Int>? {
        if (fromIdx < 0 || toIdx < 0 || fromIdx >= nodeCount || toIdx >= nodeCount) return null
        if (fromIdx == toIdx) return listOf(fromIdx)

        val dist = DoubleArray(nodeCount) { Double.POSITIVE_INFINITY }
        val prev = IntArray(nodeCount) { -1 }
        val visited = BooleanArray(nodeCount)
        dist[fromIdx] = 0.0

        // (distance, nodeIndex) min-heap
        val queue = java.util.PriorityQueue<DoubleArray>(compareBy { it[0] })
        queue.add(doubleArrayOf(0.0, fromIdx.toDouble()))

        while (queue.isNotEmpty()) {
            val (d, nf) = queue.poll()
            val node = nf.toInt()
            if (visited[node]) continue
            visited[node] = true
            if (node == toIdx) break
            if (d > dist[node]) continue

            for (edge in adjacency[node]) {
                if (visited[edge.to]) continue
                val newDist = d + edge.meters
                if (newDist < dist[edge.to]) {
                    dist[edge.to] = newDist
                    prev[edge.to] = node
                    queue.add(doubleArrayOf(newDist, edge.to.toDouble()))
                }
            }
        }

        if (dist[toIdx] == Double.POSITIVE_INFINITY) return null

        val path = ArrayList<Int>()
        var cur = toIdx
        while (cur != -1) {
            path.add(cur)
            if (cur == fromIdx) break
            cur = prev[cur]
        }
        path.reverse()
        return if (path.firstOrNull() == fromIdx) path else null
    }

    /** Total length in metres of a node-index path, e.g. one returned by [shortestPath]. */
    fun pathLengthMeters(path: List<Int>): Double {
        var total = 0.0
        for (i in 0 until path.size - 1) {
            total += haversine(lat[path[i]], lng[path[i]], lat[path[i + 1]], lng[path[i + 1]])
        }
        return total
    }

    companion object {
        /** Great-circle distance in metres. */
        fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
