package com.example.p2pchat.maps.osm

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Downloads an OpenStreetMap extract from a direct URL - a single, one-time use of the network
 * so that every navigation afterwards (loading, routing, the map itself) stays fully offline,
 * same as when the file is picked from local storage.
 *
 * Accepts a compact `.osm.pbf` (already compressed internally - stored as-is), a plain `.osm`
 * XML file, or a gzip-compressed `.osm.gz` (common for BBBike/Geofabrik XML extracts - gzip is
 * decompressed on the fly while streaming to disk). Either resulting [destFile] format is ready
 * for [OsmPbfRoadLoader.load] or [OsmRoadLoader.load] respectively - MapsActivity sniffs which
 * one it got by content, not by URL/filename, and picks the matching loader automatically.
 */
object OsmDownloader {

    data class Progress(val bytesDownloaded: Long, val totalBytes: Long) {
        /** -1 when the server didn't send a Content-Length (can't show a percentage then). */
        val percent: Int get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else -1
    }

    /**
     * Downloads [url] into [destFile]. Writes to a sibling ".part" file first and only renames
     * it into place on success, so a failed/cancelled/interrupted download never leaves a
     * corrupt file where [destFile] is expected to be. Throws [IOException] on any network
     * error or non-2xx response - the caller decides how to surface that to the user.
     */
    fun download(url: String, destFile: File, onProgress: ((Progress) -> Unit)? = null) {
        val partFile = File(destFile.parentFile, destFile.name + ".part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "P2PChat-OfflineMaps/1.0")
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Server returned HTTP $code for $url")
            }
            // getContentLengthLong() needs API 24; this project's minSdk is 23, so use the
            // plain int form instead (fine here since we only expect clipped regional extracts,
            // not multi-GB files, in scope for a one-time download).
            val totalBytes = connection.contentLength.toLong() // compressed size if gzipped; -1 if unknown
            val isGzip = url.endsWith(".gz", ignoreCase = true) ||
                connection.contentType?.contains("gzip", ignoreCase = true) == true

            val counting = CountingInputStream(connection.inputStream) { downloaded ->
                onProgress?.invoke(Progress(downloaded, totalBytes))
            }
            val source = if (isGzip) GZIPInputStream(counting) else counting

            source.use { input ->
                partFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 256 * 1024)
                }
            }

            if (destFile.exists()) destFile.delete()
            if (!partFile.renameTo(destFile)) {
                // Cross-filesystem rename can fail on some devices - fall back to a copy.
                partFile.copyTo(destFile, overwrite = true)
                partFile.delete()
            }
        } finally {
            partFile.delete() // no-op if already renamed away
            connection.disconnect()
        }
    }
}
