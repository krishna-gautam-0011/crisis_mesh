package com.example.p2pchat.maps.osm

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream

/**
 * Streams an OpenStreetMap XML extract (a plain `.osm` file - the same format Geofabrik /
 * BBBike / Overpass exports use, *not* the compressed `.osm.pbf`) into a [RoadGraph],
 * keeping only the "highway" ways a vehicle/pedestrian could actually route along.
 *
 * Runs in two passes over the file so it never has to hold every node in the extract in
 * memory at once - only the ones that end up part of a kept road:
 *   Pass 1: read every `<way>`, keep the ones tagged `highway=<routable type>`, and remember
 *           which `<nd ref="...">` node ids they reference.
 *   Pass 2: read every `<node>`, keep only the lat/lon of ids collected in pass 1.
 *
 * This is a small, educational parser, not a production-grade routing engine - a whole
 * country `.osm` XML export can be multiple GB, which is more than a phone can comfortably
 * parse and hold in memory. For a smooth in-app experience, feed it a regional/city extract
 * (e.g. clipped from Geofabrik or exported from https://extract.bbbike.org) rather than the
 * full country file; it will still work with a full country file given enough time/RAM, just
 * slower to load and to route over.
 */
object OsmRoadLoader {

    // highway values that represent something routable by road, in rough order of size
    // (internal, not private: OsmPbfRoadLoader shares this exact same list so a road counted as
    // "routable" means the same thing regardless of which file format it was loaded from).
    internal val ROUTABLE_HIGHWAYS = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
        "residential", "living_street", "service", "road",
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"
    )

    data class ProgressUpdate(val pass: Int, val percent: Int)

    private data class WayDef(val refs: LongArray, val oneway: Boolean)

    /** Parses [file] into a [RoadGraph], or null if the file has no usable road data. */
    fun load(file: File, onProgress: ((ProgressUpdate) -> Unit)? = null): RoadGraph? {
        if (!file.exists() || file.length() == 0L) return null
        val fileLength = file.length()

        // ---------------- pass 1: which ways to keep + which node ids they touch ----------------
        val referencedNodeIds = HashSet<Long>()
        val ways = ArrayList<WayDef>()

        parseWithProgress(file, fileLength, 1, onProgress) { parser ->
            var eventType = parser.eventType
            var inWay = false
            var currentRefs: ArrayList<Long>? = null
            var isHighway = false
            var isOneway = false
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "way" -> {
                            inWay = true
                            currentRefs = ArrayList()
                            isHighway = false
                            isOneway = false
                        }
                        "nd" -> if (inWay) {
                            parser.getAttributeValue(null, "ref")?.toLongOrNull()?.let { currentRefs?.add(it) }
                        }
                        "tag" -> if (inWay) {
                            val k = parser.getAttributeValue(null, "k")
                            val v = parser.getAttributeValue(null, "v")
                            if (k == "highway" && v != null && v in ROUTABLE_HIGHWAYS) isHighway = true
                            if (k == "oneway" && (v == "yes" || v == "1" || v == "true")) isOneway = true
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "way") {
                        val refs = currentRefs
                        if (isHighway && refs != null && refs.size >= 2) {
                            ways.add(WayDef(refs.toLongArray(), isOneway))
                            referencedNodeIds.addAll(refs)
                        }
                        inWay = false
                        currentRefs = null
                    }
                }
                eventType = parser.next()
            }
        }

        if (ways.isEmpty()) return null

        // ---------------- pass 2: coordinates for just the referenced nodes ----------------
        val idToIndex = HashMap<Long, Int>(referencedNodeIds.size * 2)
        val idList = ArrayList<Long>(referencedNodeIds.size)
        val latList = ArrayList<Double>(referencedNodeIds.size)
        val lngList = ArrayList<Double>(referencedNodeIds.size)

        parseWithProgress(file, fileLength, 2, onProgress) { parser ->
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "node") {
                    val id = parser.getAttributeValue(null, "id")?.toLongOrNull()
                    if (id != null && id in referencedNodeIds && !idToIndex.containsKey(id)) {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            idToIndex[id] = idList.size
                            idList.add(id)
                            latList.add(lat)
                            lngList.add(lon)
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        if (idList.isEmpty()) return null

        // ---------------- build adjacency from the ways, skipping any node the extract clipped out ----------------
        val adjacency = Array(idList.size) { mutableListOf<RoadGraph.Edge>() }
        for (way in ways) {
            for (i in 0 until way.refs.size - 1) {
                val fromIdx = idToIndex[way.refs[i]] ?: continue
                val toIdx = idToIndex[way.refs[i + 1]] ?: continue
                if (fromIdx == toIdx) continue
                val meters = RoadGraph.haversine(latList[fromIdx], lngList[fromIdx], latList[toIdx], lngList[toIdx])
                adjacency[fromIdx].add(RoadGraph.Edge(toIdx, meters))
                if (!way.oneway) {
                    adjacency[toIdx].add(RoadGraph.Edge(fromIdx, meters))
                }
            }
        }

        return RoadGraph(
            nodeIds = idList.toLongArray(),
            lat = latList.toDoubleArray(),
            lng = lngList.toDoubleArray(),
            adjacency = adjacency
        )
    }

    private fun parseWithProgress(
        file: File,
        fileLength: Long,
        passNumber: Int,
        onProgress: ((ProgressUpdate) -> Unit)?,
        body: (XmlPullParser) -> Unit
    ) {
        var lastReported = -1
        val counting = CountingInputStream(FileInputStream(file)) { bytesRead ->
            if (onProgress != null && fileLength > 0) {
                val pct = ((bytesRead * 100) / fileLength).toInt().coerceIn(0, 100)
                if (pct != lastReported) {
                    lastReported = pct
                    onProgress(ProgressUpdate(passNumber, pct))
                }
            }
        }
        counting.use { stream ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)
            body(parser)
        }
    }
}
