package com.example.p2pchat.maps

/**
 * A deliberately simplified, hand-plotted outline of India's border/coastline plus a handful of
 * reference cities. Because the map has to work with zero network access (no tile server, no
 * downloaded map data), it is drawn entirely from these coordinates baked into the app instead
 * of raster/vector tiles fetched from the internet. It is a schematic reference shape good
 * enough to see where a pin sits relative to the country and to your own position - it is NOT a
 * survey-accurate political boundary and shouldn't be used for anything beyond visual reference.
 *
 * lat = index 0, lng = index 1, going roughly clockwise from the Rann of Kutch.
 */
object IndiaBoundary {

    val OUTLINE: List<DoubleArray> = listOf(
        doubleArrayOf(23.9, 68.2),   // Rann of Kutch
        doubleArrayOf(22.8, 69.1),
        doubleArrayOf(21.6, 69.6),   // Saurashtra
        doubleArrayOf(20.7, 70.5),   // Diu
        doubleArrayOf(20.0, 72.7),
        doubleArrayOf(18.9, 72.8),   // Mumbai
        doubleArrayOf(17.0, 73.3),   // Ratnagiri
        doubleArrayOf(15.5, 73.8),   // Goa
        doubleArrayOf(13.0, 74.8),   // Mangaluru
        doubleArrayOf(11.9, 75.4),   // Kozhikode
        doubleArrayOf(10.0, 76.3),   // Kochi
        doubleArrayOf(8.4, 77.0),    // Kanyakumari - southern tip
        doubleArrayOf(9.3, 78.1),    // Tuticorin - turning north on the east coast
        doubleArrayOf(10.8, 79.8),   // Nagapattinam
        doubleArrayOf(13.1, 80.3),   // Chennai
        doubleArrayOf(15.9, 80.4),
        doubleArrayOf(17.7, 83.3),   // Visakhapatnam
        doubleArrayOf(19.8, 85.8),   // Puri
        doubleArrayOf(21.6, 87.5),
        doubleArrayOf(21.9, 88.1),   // Sundarbans
        doubleArrayOf(22.5, 89.0),
        doubleArrayOf(24.0, 88.5),   // Malda
        doubleArrayOf(25.3, 89.0),
        doubleArrayOf(26.0, 89.9),   // Siliguri corridor
        doubleArrayOf(26.4, 90.4),   // Assam
        doubleArrayOf(25.2, 91.7),   // Meghalaya
        doubleArrayOf(24.1, 92.0),
        doubleArrayOf(23.0, 93.2),   // Mizoram
        doubleArrayOf(24.2, 94.1),   // Manipur
        doubleArrayOf(25.2, 95.2),   // Nagaland
        doubleArrayOf(27.5, 97.4),   // Arunachal - eastern tip
        doubleArrayOf(28.3, 96.2),
        doubleArrayOf(29.0, 94.0),
        doubleArrayOf(28.2, 91.5),   // Bhutan border
        doubleArrayOf(27.0, 88.9),   // Sikkim
        doubleArrayOf(28.0, 86.9),   // Nepal border
        doubleArrayOf(29.3, 82.3),
        doubleArrayOf(30.4, 80.2),   // Uttarakhand
        doubleArrayOf(32.5, 79.0),   // Himachal / Ladakh
        doubleArrayOf(34.5, 78.0),   // Ladakh
        doubleArrayOf(35.5, 76.0),
        doubleArrayOf(36.9, 75.2),   // northernmost point
        doubleArrayOf(35.0, 74.0),
        doubleArrayOf(34.0, 73.9),   // Kashmir
        doubleArrayOf(33.0, 74.3),   // Jammu
        doubleArrayOf(32.0, 74.8),   // Punjab border
        doubleArrayOf(30.9, 74.4),
        doubleArrayOf(29.9, 73.5),   // Rajasthan north
        doubleArrayOf(28.0, 70.3),   // Thar desert
        doubleArrayOf(26.9, 70.2),
        doubleArrayOf(24.4, 69.9),
        doubleArrayOf(23.9, 68.2)    // back to Kutch, closing the loop
    )

    /** Reference cities, purely for on-screen orientation. */
    val CITIES: List<GeoPoint> = listOf(
        GeoPoint(28.6139, 77.2090, "New Delhi"),
        GeoPoint(19.0760, 72.8777, "Mumbai"),
        GeoPoint(22.5726, 88.3639, "Kolkata"),
        GeoPoint(13.0827, 80.2707, "Chennai"),
        GeoPoint(12.9716, 77.5946, "Bengaluru"),
        GeoPoint(17.3850, 78.4867, "Hyderabad"),
        GeoPoint(23.0225, 72.5714, "Ahmedabad"),
        GeoPoint(26.9124, 75.7873, "Jaipur"),
        GeoPoint(26.8467, 80.9462, "Lucknow"),
        GeoPoint(25.5941, 85.1376, "Patna"),
        GeoPoint(21.1458, 79.0882, "Nagpur"),
        GeoPoint(30.7333, 76.7794, "Chandigarh"),
        GeoPoint(9.9312, 76.2673, "Kochi"),
        GeoPoint(26.1445, 91.7362, "Guwahati")
    )

    const val LAT_MIN = 6.5
    const val LAT_MAX = 37.5
    const val LON_MIN = 67.5
    const val LON_MAX = 98.0
}
