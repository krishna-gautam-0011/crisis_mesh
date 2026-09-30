package com.example.p2pchat.maps

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A plain lat/lng coordinate, optionally with a human label. */
data class GeoPoint(val lat: Double, val lng: Double, val label: String? = null)

/**
 * Lets a [com.example.p2pchat.ChatMessage] carry a location instead of free text, without
 * touching the ChatMessage schema (and therefore without touching NearbyManager/CryptoManager -
 * a location "IS" the message content, so it is synced/encrypted/relayed exactly like any
 * other text already is).
 */
object GeoCodec {
    private const val PREFIX = "GEO::"

    fun encode(point: GeoPoint): String {
        val label = (point.label ?: "").replace("::", "-").replace("\n", " ")
        return "$PREFIX${point.lat}::${point.lng}::$label"
    }

    fun decode(content: String): GeoPoint? {
        if (!content.startsWith(PREFIX)) return null
        val parts = content.removePrefix(PREFIX).split("::")
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        val label = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        return GeoPoint(lat, lng, label)
    }

    fun isGeo(content: String) = content.startsWith(PREFIX)
}

object GeoMath {
    private const val EARTH_RADIUS_KM = 6371.0

    /** Great-circle distance between two points, in kilometres. */
    fun distanceKm(from: GeoPoint, to: GeoPoint): Double {
        val dLat = Math.toRadians(to.lat - from.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(from.lat)) * cos(Math.toRadians(to.lat)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    /** Initial compass bearing (0-360, 0 = North) from [from] to [to]. */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }

    /** 8-point compass label for a bearing in degrees. */
    fun compassLabel(bearing: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = (((bearing + 22.5) / 45.0).toInt()) % 8
        return dirs[index]
    }

    fun formatDistance(km: Double): String = when {
        km < 1.0 -> "${(km * 1000).toInt()} m"
        km < 10.0 -> String.format("%.2f km", km)
        else -> String.format("%.1f km", km)
    }
}
