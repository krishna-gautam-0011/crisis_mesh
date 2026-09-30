package com.example.p2pchat.maps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.example.p2pchat.R
import com.example.p2pchat.maps.osm.RoadGraph
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.toRadians

/**
 * Fully offline vector map of India. There are no map tiles involved anywhere - the coastline,
 * grid and city dots are all drawn from [IndiaBoundary], so nothing here ever needs a network
 * connection, matching the rest of the (Bluetooth/Wi-Fi Direct, no-internet) app.
 *
 * Supports: pinch-to-zoom, drag-to-pan, tap-to-drop-a-pin anywhere, a live "you are here" dot,
 * a road-following route to the pin drawn from a [RoadGraph] parsed out of an OSM `.osm`
 * extract (see [setRoadGraph]/[com.example.p2pchat.maps.osm.OsmRoadLoader]) with an automatic
 * fallback to a straight offline bearing/distance line when no road data covers the two points,
 * and a fixed-north compass overlay (top-right corner) whose needle always points from the
 * user (or, before a GPS fix, the current map centre) toward the pinned destination.
 */
class IndiaMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface OnMapTapListener {
        fun onMapTapped(point: GeoPoint)
    }

    var tapListener: OnMapTapListener? = null

    private var userLocation: GeoPoint? = null
    private var pin: GeoPoint? = null
    var navigating: Boolean = false
        private set

    // ---- OSM road routing ----
    private var roadGraph: RoadGraph? = null
    /** Current route as a sequence of lat/lng points, road-shaped when [routeIsRoad] is true. */
    private var routePoints: List<GeoPoint>? = null
    private var routeIsRoad: Boolean = false
    private var routeMeters: Double = 0.0
    /** Notified whenever the route is (re)computed, so the host Activity can update its UI text. */
    var onRouteUpdated: ((hasRoute: Boolean, isRoadRoute: Boolean, meters: Double) -> Unit)? = null

    // ---- projection: equirectangular (lon*cos(meanLat), -lat) then fit-to-view via Matrix ----
    private val meanLatRad = Math.toRadians((IndiaBoundary.LAT_MIN + IndiaBoundary.LAT_MAX) / 2.0)
    private val lonScale = cos(meanLatRad)

    private val rawOutline: FloatArray = FloatArray(IndiaBoundary.OUTLINE.size * 2).also { arr ->
        IndiaBoundary.OUTLINE.forEachIndexed { i, p ->
            arr[i * 2] = (p[1] * lonScale).toFloat()      // x from lng
            arr[i * 2 + 1] = (-p[0]).toFloat()             // y from lat (flip so north is up)
        }
    }

    private val outlinePath = Path().apply {
        for (i in IndiaBoundary.OUTLINE.indices) {
            val x = rawOutline[i * 2]
            val y = rawOutline[i * 2 + 1]
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun toRaw(p: GeoPoint) = floatArrayOf((p.lng * lonScale).toFloat(), (-p.lat).toFloat())

    private val baseMatrix = Matrix()
    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var currentZoom = 1f
    private val minZoom = 1f
    private val maxZoom = 14f
    // Guards against ever drawing with an un-fitted (identity) matrix, which would place the
    // whole outline off-canvas (raw lat/lng-derived coordinates are nowhere near screen pixel
    // scale) and look like a totally blank map. onSizeChanged normally sets this up, but onDraw
    // double-checks and computes it itself if that callback hasn't fired yet for any reason.
    private var isFitted = false

    // ---------------------------------------------------------------- paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_fill)
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_outline)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_grid)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val gridLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        textSize = 20f
    }
    private val cityDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_city_dot)
        style = Paint.Style.FILL
    }
    private val cityLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 22f
    }
    private val userDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_user_dot)
        style = Paint.Style.FILL
    }
    private val userHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_user_dot)
        alpha = 60
        style = Paint.Style.FILL
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_pin)
        style = Paint.Style.FILL
    }
    private val pinLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 1f, Color.parseColor("#33000000"))
    }
    private val pinLabelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 22f
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_route)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val routeLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_route)
        style = Paint.Style.FILL
    }
    private val routeLabelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        isFakeBoldText = true
    }
    // A straight-line fallback route (no road data covering these two points) is drawn thinner
    // and dashed, so it reads visually as "estimate" rather than an actual road.
    private val fallbackRoutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_route)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        alpha = 180
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(16f, 14f), 0f)
    }

    // ---------------------------------------------------------------- compass paints
    private val compassBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 235
        style = Paint.Style.FILL
        setShadowLayer(6f, 0f, 2f, Color.parseColor("#40000000"))
    }
    private val compassRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val compassNLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 20f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val compassTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_grid)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val compassNeedlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.map_pin)
        style = Paint.Style.FILL
    }
    private val compassNeedleTailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        style = Paint.Style.FILL
    }
    private val compassCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        style = Paint.Style.FILL
    }
    private val compassLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textSize = 18f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private var compassRadiusPx = 0f
    private var compassMarginPx = 0f
    /** Screen-space centre of the compass, exposed so touches on it can be ignored by callers if desired. */
    private var compassCx = 0f
    private var compassCy = 0f

    // ---------------------------------------------------------------- gestures
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var factor = detector.scaleFactor
            val newZoom = (currentZoom * factor).coerceIn(minZoom, maxZoom)
            factor = newZoom / currentZoom
            currentZoom = newZoom
            viewMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            viewMatrix.postTranslate(-dx, -dy)
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val pts = floatArrayOf(e.x, e.y)
            viewMatrix.invert(inverseMatrix)
            inverseMatrix.mapPoints(pts)
            val lng = pts[0] / lonScale
            val lat = -pts[1].toDouble()
            tapListener?.onMapTapped(GeoPoint(lat, lng))
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToSize(w, h)
    }

    /** Computes [baseMatrix] so the India outline fills the view, and resets [viewMatrix] to it. */
    private fun fitToSize(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val bounds = RectF()
        outlinePath.computeBounds(bounds, true)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        val padding = 0.12f
        val scaleX = w / (bounds.width() * (1 + padding))
        val scaleY = h / (bounds.height() * (1 + padding))
        val fitScale = min(scaleX, scaleY)

        baseMatrix.reset()
        baseMatrix.postTranslate(-bounds.centerX(), -bounds.centerY())
        baseMatrix.postScale(fitScale, fitScale)
        baseMatrix.postTranslate(w / 2f, h / 2f)

        viewMatrix.set(baseMatrix)
        currentZoom = 1f
        isFitted = true
    }

    fun resetView() {
        if (!isFitted) fitToSize(width, height)
        viewMatrix.set(baseMatrix)
        currentZoom = 1f
        invalidate()
    }

    fun setUserLocation(point: GeoPoint?) {
        userLocation = point
        recomputeRoute()
        invalidate()
    }

    fun setPin(point: GeoPoint?) {
        pin = point
        recomputeRoute()
        invalidate()
    }

    fun getPin(): GeoPoint? = pin

    fun setNavigating(value: Boolean) {
        navigating = value
        invalidate()
    }

    /** Installs (or clears, with null) the road network parsed from an OSM `.osm` extract. */
    fun setRoadGraph(graph: RoadGraph?) {
        roadGraph = graph
        recomputeRoute()
        invalidate()
    }

    fun hasRoadGraph(): Boolean = roadGraph != null

    /** Current route polyline (road-following or straight-line fallback), or null if none. */
    fun getRoutePoints(): List<GeoPoint>? = routePoints

    /** True if the current [getRoutePoints] follows real road geometry (not the straight fallback). */
    fun isRouteFollowingRoad(): Boolean = routeIsRoad

    /**
     * Recomputes [routePoints]: a real road-following path via [RoadGraph.shortestPath] when a
     * road graph is loaded and both the user and the pin sit near a known road, otherwise a
     * straight-line offline estimate between the two points, otherwise no route at all.
     */
    private fun recomputeRoute() {
        val user = userLocation
        val target = pin
        if (user == null || target == null) {
            routePoints = null
            routeIsRoad = false
            routeMeters = 0.0
            onRouteUpdated?.invoke(false, false, 0.0)
            return
        }

        val graph = roadGraph
        if (graph != null) {
            val fromIdx = graph.nearestNode(user)
            val toIdx = graph.nearestNode(target)
            if (fromIdx != -1 && toIdx != -1) {
                val path = graph.shortestPath(fromIdx, toIdx)
                if (path != null && path.size >= 2) {
                    routePoints = path.map { GeoPoint(graph.lat[it], graph.lng[it]) }
                    routeIsRoad = true
                    routeMeters = graph.pathLengthMeters(path)
                    onRouteUpdated?.invoke(true, true, routeMeters)
                    return
                }
            }
        }

        // Fallback: no road graph loaded, or these two points aren't close enough to (or
        // connected within) the loaded road extract - fall back to a straight-line estimate.
        routePoints = listOf(user, target)
        routeIsRoad = false
        routeMeters = GeoMath.distanceKm(user, target) * 1000.0
        onRouteUpdated?.invoke(true, false, routeMeters)
    }

    /** Pan+zoom so [point] is centred, keeping current zoom (or a minimum useful zoom). */
    fun centerOn(point: GeoPoint, zoom: Float = 4f) {
        if (!isFitted) fitToSize(width, height)
        if (!isFitted || width == 0 || height == 0) return
        val raw = toRaw(point)
        currentZoom = max(currentZoom, zoom).coerceAtMost(maxZoom)
        viewMatrix.set(baseMatrix)
        viewMatrix.postScale(currentZoom, currentZoom, raw[0], raw[1])
        // re-center that raw point to the view center after scaling about itself
        val mapped = floatArrayOf(raw[0], raw[1])
        viewMatrix.mapPoints(mapped)
        viewMatrix.postTranslate(width / 2f - mapped[0], height / 2f - mapped[1])
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isFitted) {
            // onSizeChanged hasn't run yet (or ran before we had real dimensions) - fit now
            // rather than draw with a useless identity matrix, which is what a blank map is.
            fitToSize(width, height)
            if (!isFitted) return // still no usable size this frame; try again next draw
        }

        canvas.save()
        canvas.concat(viewMatrix)

        canvas.drawPath(outlinePath, fillPaint)
        canvas.drawPath(outlinePath, outlinePaint)

        // 5-degree graticule, clipped loosely to the India bounding box
        var lat = (IndiaBoundary.LAT_MIN.toInt() / 5) * 5.0
        while (lat <= IndiaBoundary.LAT_MAX) {
            val y = -lat.toFloat()
            canvas.drawLine(
                (IndiaBoundary.LON_MIN * lonScale).toFloat(), y,
                (IndiaBoundary.LON_MAX * lonScale).toFloat(), y,
                gridPaint
            )
            lat += 5.0
        }
        var lng = (IndiaBoundary.LON_MIN.toInt() / 5) * 5.0
        while (lng <= IndiaBoundary.LON_MAX) {
            val x = (lng * lonScale).toFloat()
            canvas.drawLine(
                x, (-IndiaBoundary.LAT_MIN).toFloat(),
                x, (-IndiaBoundary.LAT_MAX).toFloat(),
                gridPaint
            )
            lng += 5.0
        }

        // city dots (always; labels are drawn later in screen-space so text doesn't distort)
        for (city in IndiaBoundary.CITIES) {
            val raw = toRaw(city)
            canvas.drawCircle(raw[0], raw[1], 4.5f / currentZoom, cityDotPaint)
        }

        canvas.restore()

        // ---- screen-space overlays (constant size regardless of zoom) ----
        if (currentZoom > 1.6f) {
            for (city in IndiaBoundary.CITIES) {
                val pts = toRaw(city)
                viewMatrix.mapPoints(pts)
                if (pts[0] in 0f..width.toFloat() && pts[1] in 0f..height.toFloat()) {
                    canvas.drawText(city.label ?: "", pts[0] + 8f, pts[1] + 6f, cityLabelPaint)
                }
            }
        }

        val user = userLocation
        val target = pin
        val route = routePoints

        if (route != null && route.size >= 2) {
            val screenPts = route.map { toRaw(it).also { p -> viewMatrix.mapPoints(p) } }
            val path = Path().apply {
                moveTo(screenPts[0][0], screenPts[0][1])
                for (i in 1 until screenPts.size) lineTo(screenPts[i][0], screenPts[i][1])
            }
            canvas.drawPath(path, if (routeIsRoad) routePaint else fallbackRoutePaint)

            val km = routeMeters / 1000.0
            val bearing = if (user != null && target != null) GeoMath.bearingDegrees(user, target) else 0.0
            val kind = if (routeIsRoad) "Road" else "Direct"
            val label = "$kind \u2022 ${GeoMath.formatDistance(km)} \u2022 ${GeoMath.compassLabel(bearing)}"
            val mid = screenPts[screenPts.size / 2]
            val textWidth = routeLabelText.measureText(label)
            val rect = RectF(mid[0] - textWidth / 2f - 12f, mid[1] - 20f, mid[0] + textWidth / 2f + 12f, mid[1] + 18f)
            canvas.drawRoundRect(rect, 12f, 12f, routeLabelBg)
            canvas.drawText(label, rect.left + 12f, rect.bottom - 12f, routeLabelText)
        }

        if (user != null) {
            val up = toRaw(user).also { viewMatrix.mapPoints(it) }
            canvas.drawCircle(up[0], up[1], 22f, userHaloPaint)
            canvas.drawCircle(up[0], up[1], 9f, userDotPaint)
        }

        if (target != null) {
            drawPinMarker(canvas, target)
        }

        drawCompass(canvas)
    }

    /**
     * Fixed-north compass rose in the top-right corner. The needle always points from the
     * user's current location (or, before a GPS fix, the point currently centred in view) to
     * the pinned destination - a pure offline bearing readout, no magnetometer needed since the
     * map itself is always drawn north-up.
     */
    private fun drawCompass(canvas: Canvas) {
        if (width == 0 || height == 0) return

        val density = resources.displayMetrics.density
        val radius = 34f * density
        val margin = 16f * density
        compassRadiusPx = radius
        compassMarginPx = margin
        compassCx = width - margin - radius
        compassCy = margin + radius

        // background disc + ring
        canvas.drawCircle(compassCx, compassCy, radius, compassBgPaint)
        canvas.drawCircle(compassCx, compassCy, radius, compassRingPaint)

        // N/E/S/W tick marks (map is always drawn north-up, so these never rotate)
        val dirs = listOf(0.0 to "N", 90.0 to "E", 180.0 to "S", 270.0 to "W")
        for ((deg, label) in dirs) {
            val rad = toRadians(deg - 90.0) // 0deg (N) points up on screen
            val innerR = radius - 9f * density
            val outerR = radius - 3f * density
            val x1 = compassCx + (innerR * cos(rad)).toFloat()
            val y1 = compassCy + (innerR * sin(rad)).toFloat()
            val x2 = compassCx + (outerR * cos(rad)).toFloat()
            val y2 = compassCy + (outerR * sin(rad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, compassTickPaint)
            if (label == "N") {
                val lx = compassCx + ((radius - 20f * density) * cos(rad)).toFloat()
                val ly = compassCy + ((radius - 20f * density) * sin(rad)).toFloat() + 7f
                canvas.drawText(label, lx, ly, compassNLabelPaint)
            }
        }

        val target = pin
        if (target == null) {
            // Nothing pinned yet - show a neutral compass with just the fixed N marker.
            canvas.drawCircle(compassCx, compassCy, 4f * density, compassCenterPaint)
            return
        }

        // Reference point: live GPS fix if we have one, otherwise whatever the map is currently
        // centred on, so the needle is always meaningful even before location comes in.
        val reference = compassReferencePoint()

        val bearing = GeoMath.bearingDegrees(reference, target)
        val rad = toRadians(bearing - 90.0)
        val needleLen = radius - 10f * density
        val tipX = compassCx + (needleLen * cos(rad)).toFloat()
        val tipY = compassCy + (needleLen * sin(rad)).toFloat()
        val backRad = rad + Math.PI
        val tailX = compassCx + (needleLen * 0.4f * cos(backRad)).toFloat()
        val tailY = compassCy + (needleLen * 0.4f * sin(backRad)).toFloat()
        val perpRad = rad + Math.PI / 2
        val wingHalf = 5f * density
        val wx = (wingHalf * cos(perpRad)).toFloat()
        val wy = (wingHalf * sin(perpRad)).toFloat()

        val needlePath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(compassCx + wx, compassCy + wy)
            lineTo(compassCx - wx, compassCy - wy)
            close()
        }
        canvas.drawPath(needlePath, compassNeedlePaint)
        canvas.drawLine(compassCx, compassCy, tailX, tailY, compassNeedleTailPaint)
        canvas.drawCircle(compassCx, compassCy, 4f * density, compassCenterPaint)

        // distance readout under the compass
        val label = GeoMath.formatDistance(GeoMath.distanceKm(reference, target))
        canvas.drawText(label, compassCx, compassCy + radius + 16f * density, compassLabelPaint)
    }

    /** Live GPS fix if we have one, otherwise the lat/lng currently centred in the view. */
    private fun compassReferencePoint(): GeoPoint {
        val u = userLocation
        if (u != null) return u
        val inv = Matrix()
        viewMatrix.invert(inv)
        val pts = floatArrayOf(width / 2f, height / 2f)
        inv.mapPoints(pts)
        return GeoPoint(-pts[1].toDouble(), (pts[0] / lonScale))
    }

    private fun drawPinMarker(canvas: Canvas, point: GeoPoint) {
        val p = toRaw(point).also { viewMatrix.mapPoints(it) }
        val cx = p[0]
        val cy = p[1] - 26f // pin tip sits at the actual coordinate

        val path = Path().apply {
            moveTo(cx, cy + 26f) // tip
            addCircle(cx, cy, 16f, Path.Direction.CW)
        }
        canvas.drawPath(path, pinPaint)
        canvas.drawCircle(cx, cy, 6f, pinLabelBg)

        val label = point.label ?: String.format("%.4f, %.4f", point.lat, point.lng)
        val textWidth = pinLabelText.measureText(label)
        val bg = RectF(cx - textWidth / 2f - 10f, cy - 58f, cx + textWidth / 2f + 10f, cy - 32f)
        canvas.drawRoundRect(bg, 8f, 8f, pinLabelBg)
        canvas.drawText(label, bg.left + 10f, bg.bottom - 8f, pinLabelText)
    }
}
