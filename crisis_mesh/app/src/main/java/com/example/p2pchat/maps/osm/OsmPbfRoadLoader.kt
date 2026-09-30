package com.example.p2pchat.maps.osm

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.Inflater

/**
 * Streams an OpenStreetMap `.osm.pbf` extract (the compact, protobuf-based export format used
 * by Geofabrik, BBBike and `osmium`/`osmconvert` - a fraction of the size of the equivalent
 * plain `.osm` XML) straight into a [RoadGraph], with no external protobuf library dependency.
 *
 * The PBF container format is intentionally simple and has been stable for well over a decade,
 * so it's practical to read by hand: the file is just a sequence of length-prefixed
 * `BlobHeader` + `Blob` pairs (see [readBlob]/[readBlobHeader]), each `Blob` holding a
 * zlib-compressed (or occasionally raw) `PrimitiveBlock` protobuf message. This class only
 * implements the handful of protobuf field numbers those messages actually use (per the public
 * `fileformat.proto` / `osmformat.proto` schemas) - it's a small, purpose-built reader for this
 * one format, not a general protobuf parser.
 *
 * Mirrors [OsmRoadLoader]'s exact two-pass strategy and output shape:
 *   Pass 1: decode every way, keep the ones tagged `highway=<routable type>`, and remember
 *           which node ids they reference.
 *   Pass 2: decode every node (from `DenseNodes` - by far the common case in real extracts -
 *           and the rarer plain `Node` form), keeping only the referenced ids' coordinates.
 *
 * Same scope note as [OsmRoadLoader]: fine for a city/region extract; a whole-country `.osm.pbf`
 * will still parse correctly, just slower and with more memory pressure, since like the XML
 * reader this favours simplicity/readability over raw throughput.
 */
object OsmPbfRoadLoader {

    private data class WayDef(val refs: LongArray, val oneway: Boolean)

    /** True if [file] looks like a `.osm.pbf` (checked by content, not by filename - see [looksLikePbf]). */
    fun looksLikePbf(file: File): Boolean {
        if (!file.exists() || file.length() < 8) return false
        return try {
            FileInputStream(file).use { input ->
                val headerLen = readUInt32BE(input) ?: return false
                // A real file starts with a small BlobHeader (well under any sane bound); an
                // OSM XML file's first 4 bytes are ASCII text ("<?xm" etc), which decodes to a
                // huge/garbage "length" here, so this alone is already a strong signal.
                if (headerLen <= 0 || headerLen > 64 * 1024) return false
                val headerBytes = ByteArray(headerLen)
                if (!readFully(input, headerBytes)) return false
                val (type, _) = readBlobHeader(headerBytes)
                type == "OSMHeader" || type == "OSMData"
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Parses [file] into a [RoadGraph], or null if the file has no usable road data. */
    fun load(file: File, onProgress: ((OsmRoadLoader.ProgressUpdate) -> Unit)? = null): RoadGraph? {
        if (!file.exists() || file.length() == 0L) return null
        val fileLength = file.length()

        // ---------------- pass 1: which ways to keep + which node ids they touch ----------------
        val referencedNodeIds = HashSet<Long>()
        val ways = ArrayList<WayDef>()

        forEachPrimitiveBlock(file, fileLength, 1, onProgress) { strings, group ->
            for (way in group.ways) {
                var isHighway = false
                var isOneway = false
                for (i in way.keys.indices) {
                    val k = strings.getOrNull(way.keys[i])
                    val v = strings.getOrNull(way.vals.getOrElse(i) { -1 })
                    if (k == "highway" && v != null && v in OsmRoadLoader.ROUTABLE_HIGHWAYS) isHighway = true
                    if (k == "oneway" && (v == "yes" || v == "1" || v == "true")) isOneway = true
                }
                if (isHighway && way.refs.size >= 2) {
                    ways.add(WayDef(way.refs, isOneway))
                    referencedNodeIds.addAll(way.refs.asList())
                }
            }
        }

        if (ways.isEmpty()) return null

        // ---------------- pass 2: coordinates for just the referenced nodes ----------------
        val idToIndex = HashMap<Long, Int>(referencedNodeIds.size * 2)
        val idList = ArrayList<Long>(referencedNodeIds.size)
        val latList = ArrayList<Double>(referencedNodeIds.size)
        val lngList = ArrayList<Double>(referencedNodeIds.size)

        forEachPrimitiveBlock(file, fileLength, 2, onProgress) { _, group ->
            for ((id, lat, lon) in group.nodes) {
                if (id in referencedNodeIds && !idToIndex.containsKey(id)) {
                    idToIndex[id] = idList.size
                    idList.add(id)
                    latList.add(lat)
                    lngList.add(lon)
                }
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

    // ============================================================== PrimitiveGroup contents

    private data class DecodedNode(val id: Long, val lat: Double, val lon: Double)
    private data class DecodedWay(val id: Long, val keys: IntArray, val vals: IntArray, val refs: LongArray)
    private class DecodedGroup {
        val nodes = ArrayList<DecodedNode>()
        val ways = ArrayList<DecodedWay>()
    }

    /**
     * Walks every `OSMData` blob in [file] (in file order, start to finish - a second full pass
     * re-opens the file from the start), decoding each `PrimitiveBlock`'s string table and
     * primitive groups, and invokes [onGroup] once per group with that block's string table and
     * the group's decoded nodes/ways. [onProgress] fires with `passNumber` and roughly how far
     * through the file (by bytes) this pass has gotten.
     */
    private inline fun forEachPrimitiveBlock(
        file: File,
        fileLength: Long,
        passNumber: Int,
        noinline onProgress: ((OsmRoadLoader.ProgressUpdate) -> Unit)?,
        onGroup: (strings: List<String>, group: DecodedGroup) -> Unit
    ) {
        var lastReported = -1
        val counting = CountingInputStream(FileInputStream(file)) { bytesRead ->
            if (onProgress != null && fileLength > 0) {
                val pct = ((bytesRead * 100) / fileLength).toInt().coerceIn(0, 100)
                if (pct != lastReported) {
                    lastReported = pct
                    onProgress(OsmRoadLoader.ProgressUpdate(passNumber, pct))
                }
            }
        }
        counting.use { input ->
            while (true) {
                val headerLen = readUInt32BE(input) ?: break // clean EOF between fileblocks
                val headerBytes = ByteArray(headerLen)
                if (!readFully(input, headerBytes)) break
                val (type, dataSize) = readBlobHeader(headerBytes)
                val blobBytes = ByteArray(dataSize)
                if (!readFully(input, blobBytes)) break

                if (type == "OSMData") {
                    val blockBytes = inflateBlob(blobBytes)
                    val (strings, groups) = parsePrimitiveBlock(blockBytes)
                    for (group in groups) onGroup(strings, group)
                }
                // "OSMHeader" (bounding box, required-feature list, etc.) has nothing this
                // loader needs - it's read past (via dataSize) but not decoded.
            }
        }
    }

    // ============================================================== protobuf primitives

    /** Minimal streaming protobuf reader: just enough varint/tag/length-delimited/skip support
     *  for the message shapes `fileformat.proto`/`osmformat.proto` actually define. */
    private class ProtoReader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
        fun eof(): Boolean = pos >= end

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data[pos].toInt() and 0xFF
                pos++
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }

        fun readTag(): Pair<Int, Int> {
            val t = readVarint()
            return Pair((t ushr 3).toInt(), (t and 0x7).toInt())
        }

        fun readLengthDelimited(): ByteArray {
            val len = readVarint().toInt()
            val slice = data.copyOfRange(pos, pos + len)
            pos += len
            return slice
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> { val len = readVarint().toInt(); pos += len }
                5 -> pos += 4
                else -> throw IllegalArgumentException("Unsupported PBF wire type $wireType")
            }
        }
    }

    /** A packed-varint field (e.g. `repeated sint64 id = 1 [packed=true]`) is just a
     *  length-delimited run of back-to-back varints with no tags in between. */
    private fun readPackedVarints(bytes: ByteArray): LongArray {
        val reader = ProtoReader(bytes)
        val out = ArrayList<Long>()
        while (!reader.eof()) out.add(reader.readVarint())
        return out.toLongArray()
    }

    private fun zigzagDecode(n: Long): Long = (n ushr 1) xor -(n and 1L)

    private fun parseStringTable(bytes: ByteArray): List<String> {
        val reader = ProtoReader(bytes)
        val strings = ArrayList<String>()
        while (!reader.eof()) {
            val (field, wireType) = reader.readTag()
            if (field == 1 && wireType == 2) {
                strings.add(String(reader.readLengthDelimited(), Charsets.UTF_8))
            } else {
                reader.skip(wireType)
            }
        }
        return strings
    }

    private fun parseDenseNodes(bytes: ByteArray, granularity: Long, latOffset: Long, lonOffset: Long): List<DecodedNode> {
        val reader = ProtoReader(bytes)
        var ids: LongArray? = null
        var lats: LongArray? = null
        var lons: LongArray? = null
        while (!reader.eof()) {
            val (field, wireType) = reader.readTag()
            when {
                field == 1 && wireType == 2 -> ids = readPackedVarints(reader.readLengthDelimited())
                field == 8 && wireType == 2 -> lats = readPackedVarints(reader.readLengthDelimited())
                field == 9 && wireType == 2 -> lons = readPackedVarints(reader.readLengthDelimited())
                else -> reader.skip(wireType)
            }
        }
        val result = ArrayList<DecodedNode>()
        val idArr = ids
        val latArr = lats
        val lonArr = lons
        if (idArr != null && latArr != null && lonArr != null) {
            var id = 0L
            var latAcc = 0L
            var lonAcc = 0L
            val count = minOf(idArr.size, latArr.size, lonArr.size)
            for (i in 0 until count) {
                id += zigzagDecode(idArr[i])
                latAcc += zigzagDecode(latArr[i])
                lonAcc += zigzagDecode(lonArr[i])
                val lat = (latOffset + granularity * latAcc) / 1_000_000_000.0
                val lon = (lonOffset + granularity * lonAcc) / 1_000_000_000.0
                result.add(DecodedNode(id, lat, lon))
            }
        }
        return result
    }

    /** The rarer non-dense `Node` message - some exporters (older Osmosis configs) use this
     *  instead of/alongside `DenseNodes`. Unlike `Way.id` (plain `int64`), `Node.id`/`lat`/`lon`
     *  are declared `sint64` in osmformat.proto, i.e. zigzag-encoded on the wire even though
     *  they're absolute values here, not deltas - so these three (and only these three, versus
     *  [parseWay]'s `id`) need [zigzagDecode] too. */
    private fun parsePlainNode(bytes: ByteArray, granularity: Long, latOffset: Long, lonOffset: Long): DecodedNode? {
        val reader = ProtoReader(bytes)
        var id: Long? = null
        var lat: Long? = null
        var lon: Long? = null
        while (!reader.eof()) {
            val (field, wireType) = reader.readTag()
            when {
                field == 1 && wireType == 0 -> id = zigzagDecode(reader.readVarint())
                field == 8 && wireType == 0 -> lat = zigzagDecode(reader.readVarint())
                field == 9 && wireType == 0 -> lon = zigzagDecode(reader.readVarint())
                else -> reader.skip(wireType)
            }
        }
        if (id == null || lat == null || lon == null) return null
        return DecodedNode(
            id,
            (latOffset + granularity * lat) / 1_000_000_000.0,
            (lonOffset + granularity * lon) / 1_000_000_000.0
        )
    }

    private fun parseWay(bytes: ByteArray): DecodedWay {
        val reader = ProtoReader(bytes)
        var id = 0L
        var keys: LongArray = LongArray(0)
        var vals: LongArray = LongArray(0)
        var refsDelta: LongArray = LongArray(0)
        while (!reader.eof()) {
            val (field, wireType) = reader.readTag()
            when {
                field == 1 && wireType == 0 -> id = reader.readVarint()
                field == 2 && wireType == 2 -> keys = readPackedVarints(reader.readLengthDelimited())
                field == 3 && wireType == 2 -> vals = readPackedVarints(reader.readLengthDelimited())
                field == 8 && wireType == 2 -> refsDelta = readPackedVarints(reader.readLengthDelimited())
                else -> reader.skip(wireType)
            }
        }
        val refs = LongArray(refsDelta.size)
        var acc = 0L
        for (i in refsDelta.indices) {
            acc += zigzagDecode(refsDelta[i])
            refs[i] = acc
        }
        return DecodedWay(
            id,
            IntArray(keys.size) { keys[it].toInt() },
            IntArray(vals.size) { vals[it].toInt() },
            refs
        )
    }

    private fun parsePrimitiveGroup(bytes: ByteArray, granularity: Long, latOffset: Long, lonOffset: Long): DecodedGroup {
        val reader = ProtoReader(bytes)
        val group = DecodedGroup()
        while (!reader.eof()) {
            val (field, wireType) = reader.readTag()
            when {
                field == 1 && wireType == 2 -> { // repeated Node nodes = 1 (the rare plain form)
                    parsePlainNode(reader.readLengthDelimited(), granularity, latOffset, lonOffset)?.let { group.nodes.add(it) }
                }
                field == 2 && wireType == 2 -> // optional DenseNodes dense = 2
                    group.nodes.addAll(parseDenseNodes(reader.readLengthDelimited(), granularity, latOffset, lonOffset))
                field == 3 && wireType == 2 -> // repeated Way ways = 3
                    group.ways.add(parseWay(reader.readLengthDelimited()))
                else -> reader.skip(wireType) // relations (field 4) and changesets: not roads, skipped
            }
        }
        return group
    }

    /** Returns this block's string table plus its decoded primitive groups. */
    private fun parsePrimitiveBlock(bytes: ByteArray): Pair<List<String>, List<DecodedGroup>> {
        val reader = ProtoReader(bytes)
        var strings: List<String> = emptyList()
        val groupBytes = ArrayList<ByteArray>()
        var granularity = 100L      // PrimitiveBlock.granularity default
        var latOffset = 0L          // PrimitiveBlock.lat_offset default
        var lonOffset = 0L          // PrimitiveBlock.lon_offset default
        while (!reader.eof()) {
            val (field, wireType) = reader.readTag()
            when {
                field == 1 && wireType == 2 -> strings = parseStringTable(reader.readLengthDelimited())
                field == 2 && wireType == 2 -> groupBytes.add(reader.readLengthDelimited())
                field == 17 && wireType == 0 -> granularity = reader.readVarint()
                field == 19 && wireType == 0 -> latOffset = reader.readVarint()
                field == 20 && wireType == 0 -> lonOffset = reader.readVarint()
                else -> reader.skip(wireType)
            }
        }
        val groups = groupBytes.map { parsePrimitiveGroup(it, granularity, latOffset, lonOffset) }
        return Pair(strings, groups)
    }

    // ============================================================== fileblock framing

    private fun inflateBlob(bytes: ByteArray): ByteArray {
        val reader = ProtoReader(bytes)
        var raw: ByteArray? = null
        var rawSize: Int? = null
        var zlibData: ByteArray? = null
        while (!reader.eof()) {
            val (field, wireType) = reader.readTag()
            when {
                field == 1 && wireType == 2 -> raw = reader.readLengthDelimited()
                field == 2 && wireType == 0 -> rawSize = reader.readVarint().toInt()
                field == 3 && wireType == 2 -> zlibData = reader.readLengthDelimited()
                else -> reader.skip(wireType) // lzma_data/OBSOLETE_bzip2_data/lz4_data/zstd_data: not produced by any common exporter
            }
        }
        raw?.let { return it }
        val compressed = zlibData ?: throw IllegalStateException("PBF blob has neither raw nor zlib_data")
        val inflater = Inflater() // PBF's zlib_data is standard zlib-wrapped DEFLATE - no nowrap needed
        inflater.setInput(compressed)
        val out = ByteArray(rawSize ?: (compressed.size * 4))
        val written = inflater.inflate(out)
        inflater.end()
        return if (written == out.size) out else out.copyOf(written)
    }

    private fun readBlobHeader(bytes: ByteArray): Pair<String, Int> {
        val reader = ProtoReader(bytes)
        var type: String? = null
        var dataSize: Int? = null
        while (!reader.eof()) {
            val (field, wireType) = reader.readTag()
            when {
                field == 1 && wireType == 2 -> type = String(reader.readLengthDelimited(), Charsets.UTF_8)
                field == 3 && wireType == 0 -> dataSize = reader.readVarint().toInt()
                else -> reader.skip(wireType)
            }
        }
        return Pair(type ?: "", dataSize ?: 0)
    }

    /** The 4-byte big-endian length prefix in front of every BlobHeader. Returns null on a
     *  clean end-of-file (0 bytes read before hitting EOF) - anything else short is corrupt. */
    private fun readUInt32BE(input: InputStream): Int? {
        val b = ByteArray(4)
        var total = 0
        while (total < 4) {
            val n = input.read(b, total, 4 - total)
            if (n < 0) {
                return if (total == 0) null else throw EOFException("Truncated PBF fileblock length prefix")
            }
            total += n
        }
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    private fun readFully(input: InputStream, dest: ByteArray): Boolean {
        var total = 0
        while (total < dest.size) {
            val n = input.read(dest, total, dest.size - total)
            if (n < 0) return false
            total += n
        }
        return true
    }
}
