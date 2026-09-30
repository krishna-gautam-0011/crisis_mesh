package com.example.p2pchat

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.p2pchat.databinding.ActivityMapsBinding
import com.example.p2pchat.maps.GeoMath
import com.example.p2pchat.maps.GeoPoint
import com.example.p2pchat.maps.TurnByTurn
import com.example.p2pchat.maps.osm.OsmDownloader
import com.example.p2pchat.maps.osm.OsmPbfRoadLoader
import com.example.p2pchat.maps.osm.OsmRoadLoader
import com.example.p2pchat.maps.osm.RoadGraph
import java.io.File
import java.util.Locale

/**
 * Fully offline map (no Google Maps SDK, no map tiles, no internet connection, and no handoff
 * to any other installed app) centred on India, built on [com.example.p2pchat.maps.IndiaMapView].
 *
 * "Navigate" draws (and keeps redrawing as you move) an actual road-following route from a road
 * network parsed out of an OpenStreetMap `.osm.pbf` or plain `.osm` XML extract (see
 * [OsmPbfRoadLoader] / [OsmRoadLoader] - the format is auto-detected from the file's contents) -
 * tap the road icon in the header to pick one. If no road data is loaded yet, or the two points
 * aren't near/connected by the loaded roads, the map falls back to a straight-line offline
 * bearing/distance estimate so there's always *some* route drawn - entirely on this screen,
 * with nothing ever handed off to Google Maps or any other app. A fixed-north compass in the
 * top-right corner always points from your current position toward the pinned destination.
 */
class MapsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapsBinding
    private lateinit var prefs: SharedPreferences

    private var pin: GeoPoint? = null
    private var myLocation: GeoPoint? = null
    private var sharedFrom: String? = null
    private var roadGraph: RoadGraph? = null
    private var loadingRoads = false
    private var isNavigating = false

    // ---- turn-by-turn voice guidance (on-device TextToSpeech, no internet involved) ----
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var muted = false
    /** De-dupe key for the last instruction spoken, so we don't repeat it on every GPS fix. */
    private var lastAnnouncedKey: String? = null

    private var locationManager: LocationManager? = null
    private val locationListener = LocationListener { location: Location -> onNewLocation(location) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { it }) {
                startLocationUpdates()
            } else {
                Toast.makeText(this, "Location permission is needed to show your position on the map", Toast.LENGTH_LONG).show()
            }
        }

    private val osmFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // Some providers don't support persistable permissions; the file still
                    // loads for this session, it just won't be remembered across app restarts.
                }
                prefs.edit().putString(PREF_OSM_URI, uri.toString()).apply()
                loadRoadsFromUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("maps_prefs", MODE_PRIVATE)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.getDefault()
        }

        binding.backButton.setOnClickListener { finish() }
        binding.resetViewButton.setOnClickListener { binding.indiaMapView.resetView() }
        binding.myLocationButton.setOnClickListener { focusOnMyLocation() }
        binding.navigateButton.setOnClickListener { toggleNavigation() }
        binding.clearPinButton.setOnClickListener { clearPin() }
        binding.loadRoadsButton.setOnClickListener { pickOsmFile() }
        binding.loadRoadsButton.setOnLongClickListener { promptDownloadRoads(); true }
        binding.muteButton.setOnClickListener { toggleMute() }

        binding.indiaMapView.tapListener = object : com.example.p2pchat.maps.IndiaMapView.OnMapTapListener {
            override fun onMapTapped(point: GeoPoint) {
                pin = point
                binding.indiaMapView.setPin(point)
                updatePinInfo()
            }
        }
        binding.indiaMapView.onRouteUpdated = { hasRoute, isRoadRoute, _ ->
            runOnUiThread {
                updateRouteInfo(hasRoute, isRoadRoute)
                updateTurnByTurn(hasRoute, isRoadRoute)
            }
        }

        // Opened from a location message shared by a peer?
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        sharedFrom = intent.getStringExtra(EXTRA_FROM)
        if (!lat.isNaN() && !lng.isNaN()) {
            val label = intent.getStringExtra(EXTRA_LABEL) ?: sharedFrom?.let { "From $it" }
            pin = GeoPoint(lat, lng, label)
            binding.mapTitleText.text = if (sharedFrom != null) "Location from $sharedFrom" else "Shared location"
            binding.mapSubtitleText.text = "Tap Navigate for directions"
        }
        binding.indiaMapView.setPin(pin)
        updatePinInfo()

        val existingPin = pin
        if (existingPin != null) {
            binding.indiaMapView.post { binding.indiaMapView.centerOn(existingPin, 6f) }
        }

        ensureLocationPermissionThenStart()
        restoreSavedRoadsIfAny()
        maybePromptFirstRunDownload()
    }

    /**
     * Shown once, the first time this screen opens with no road data loaded yet (not saved from
     * a previous pick/download, and not already mid-restore): offers to fetch a starter extract
     * with one tap, so the person doesn't have to already know a URL to paste. Declining, or
     * dismissing, never asks again this install - "Choose a different area" and the 🛣 icon
     * (pick a local file, or long-press to download a different URL) stay available regardless.
     */
    private fun maybePromptFirstRunDownload() {
        if (loadingRoads) return // a saved/downloaded file is already being restored
        if (File(filesDir, DOWNLOADED_ROADS_FILENAME).exists()) return
        if (prefs.getString(PREF_OSM_URI, null) != null) return
        if (prefs.getBoolean(PREF_FIRST_RUN_PROMPTED, false)) return
        prefs.edit().putBoolean(PREF_FIRST_RUN_PROMPTED, true).apply()

        AlertDialog.Builder(this)
            .setTitle("Download offline road data?")
            .setMessage(
                "Turn-by-turn navigation needs a road-network file loaded once. I can fetch a " +
                    "starter extract now - $DEFAULT_ROADS_LABEL ($DEFAULT_ROADS_SIZE_HINT, over " +
                    "the network one time only; everything after that, including all future " +
                    "navigation, stays fully offline).\n\n" +
                    "Wi-Fi is recommended for this. You can pick a different city/state extract, " +
                    "or a file already on your phone, any time via the 🛣 icon (tap = pick a " +
                    "local file, long-press = download a different URL)."
            )
            .setPositiveButton("Download now") { _, _ -> startRoadsDownload(DEFAULT_ROADS_URL) }
            .setNeutralButton("Choose a different area") { _, _ -> promptDownloadRoads() }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ---------------------------------------------------------------- road data (.osm)

    private fun pickOsmFile() {
        if (loadingRoads) {
            Toast.makeText(this, "Already loading roads…", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Pick an OSM road extract (.osm.pbf or .osm XML) • long-press to download one instead", Toast.LENGTH_LONG).show()
        osmFilePicker.launch(arrayOf("*/*"))
    }

    /** Long-press on the road icon: paste a direct URL and fetch it once, then stay offline. */
    private fun promptDownloadRoads() {
        if (loadingRoads) {
            Toast.makeText(this, "Already loading roads…", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "https://…/roads.osm.pbf, roads.osm or roads.osm.gz"
            setText(prefs.getString(PREF_LAST_DOWNLOAD_URL, DEFAULT_ROADS_URL))
        }
        AlertDialog.Builder(this)
            .setTitle("Download road data (once)")
            .setMessage(
                "Paste a direct link to an OSM extract - a compact \".osm.pbf\" (recommended, " +
                    "much smaller) or a plain \".osm\" XML file, or a \".osm.gz\" (gzip is handled " +
                    "automatically). A clipped city/state extract from extract.bbbike.org or a " +
                    "BBBike predefined-city export works well - avoid a whole-country file, it's " +
                    "usually multiple GB.\n\n" +
                    "This uses the internet once to fetch it. Everything after that - loading, " +
                    "routing, navigating - stays fully offline, same as picking a local file."
            )
            .setView(input)
            .setPositiveButton("Download") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) startRoadsDownload(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRoadsDownload(url: String) {
        if (loadingRoads) {
            Toast.makeText(this, "Already loading roads…", Toast.LENGTH_SHORT).show()
            return
        }
        loadingRoads = true
        prefs.edit().putString(PREF_LAST_DOWNLOAD_URL, url).apply()
        binding.roadLoadProgressText.visibility = View.VISIBLE
        binding.roadLoadProgressText.text = "Downloading roads… 0%"

        Thread {
            try {
                val destFile = File(filesDir, DOWNLOADED_ROADS_FILENAME) // filesDir, not cacheDir -
                // survives the system clearing the cache, so we never re-download on next launch.
                OsmDownloader.download(url, destFile) { progress ->
                    runOnUiThread {
                        val pct = if (progress.percent >= 0) {
                            "${progress.percent}%"
                        } else {
                            "${progress.bytesDownloaded / 1024} KB"
                        }
                        binding.roadLoadProgressText.text = "Downloading roads… $pct"
                    }
                }
                parseAndInstallRoads(destFile)
            } catch (e: Exception) {
                runOnUiThread {
                    loadingRoads = false
                    binding.roadLoadProgressText.visibility = View.GONE
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun restoreSavedRoadsIfAny() {
        // A previously-downloaded file (see startRoadsDownload) always wins - it's already local,
        // no content:// permission to worry about, and no network needed to use it again.
        val downloaded = File(filesDir, DOWNLOADED_ROADS_FILENAME)
        if (downloaded.exists() && downloaded.length() > 0L) {
            loadingRoads = true
            binding.roadLoadProgressText.visibility = View.VISIBLE
            binding.roadLoadProgressText.text = "Loading saved roads… 0%"
            Thread { parseAndInstallRoads(downloaded) }.start()
            return
        }
        val saved = prefs.getString(PREF_OSM_URI, null) ?: return
        try {
            loadRoadsFromUri(Uri.parse(saved))
        } catch (e: Exception) {
            // Saved URI no longer valid (file moved/deleted, permission revoked) - just ignore,
            // the user can pick a fresh one via the road icon.
        }
    }

    private fun loadRoadsFromUri(uri: Uri) {
        loadingRoads = true
        binding.roadLoadProgressText.visibility = View.VISIBLE
        binding.roadLoadProgressText.text = "Loading roads… 0%"

        Thread {
            try {
                // Both loaders stream from a plain File, so copy the picked document into our
                // cache first (it may be multiple hundred MB for a large XML extract - a .pbf of
                // the same area is typically 5-10x smaller - so this is a streamed copy, not
                // held in memory). The extension here doesn't matter - parseAndInstallRoads
                // sniffs the actual format from the file's contents, not its name.
                val cacheFile = File(cacheDir, "loaded_roads.dat")
                contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output, bufferSize = 256 * 1024) }
                } ?: throw IllegalStateException("Could not open the selected file")
                parseAndInstallRoads(cacheFile)
            } catch (e: Exception) {
                runOnUiThread {
                    loadingRoads = false
                    binding.roadLoadProgressText.visibility = View.GONE
                    Toast.makeText(this, "Couldn't load that road file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Shared tail for every road-loading path (local pick, downloaded file, restored-on-launch
     * file): sniffs whether [file] is a compact `.osm.pbf` or plain `.osm` XML extract *by its
     * content* (not its name/extension - a downloaded or picked file's name is unreliable, e.g.
     * content:// picks often lose the real extension), parses it with the matching loader, and
     * installs the resulting graph. Must be called from a background thread - both loaders
     * block - and posts its own UI updates.
     */
    private fun parseAndInstallRoads(file: File) {
        val isPbf = OsmPbfRoadLoader.looksLikePbf(file)
        val onProgress = { progress: OsmRoadLoader.ProgressUpdate ->
            runOnUiThread {
                binding.roadLoadProgressText.text =
                    "Loading roads… pass ${progress.pass}/2 ${progress.percent}%"
            }
        }
        val graph = if (isPbf) {
            OsmPbfRoadLoader.load(file, onProgress)
        } else {
            OsmRoadLoader.load(file, onProgress)
        }
        runOnUiThread {
            loadingRoads = false
            if (graph == null || graph.nodeCount == 0) {
                binding.roadLoadProgressText.text = "No routable roads found in that file"
                Toast.makeText(this, "That file had no usable road data (need highway=... ways)", Toast.LENGTH_LONG).show()
            } else {
                roadGraph = graph
                binding.indiaMapView.setRoadGraph(graph)
                val format = if (isPbf) "pbf" else "xml"
                binding.roadLoadProgressText.text = "Roads loaded • ${graph.nodeCount} nodes"
                Toast.makeText(this, "Road network loaded ($format, ${graph.nodeCount} nodes) - routes will follow roads now", Toast.LENGTH_LONG).show()
            }
            binding.roadLoadProgressText.postDelayed({ binding.roadLoadProgressText.visibility = View.GONE }, 3000)
        }
    }

    // ---------------------------------------------------------------- pin / route UI

    private fun updatePinInfo() {
        val p = pin
        if (p == null) {
            binding.pinInfoText.text = "Tap the map to drop a pin anywhere in India"
            binding.navInfoText.text = ""
            return
        }
        binding.pinInfoText.text = if (p.label != null) {
            String.format("%s — %.5f, %.5f", p.label, p.lat, p.lng)
        } else {
            String.format("Pin: %.5f, %.5f", p.lat, p.lng)
        }
        val me = myLocation
        binding.navInfoText.text = if (me != null) {
            val km = GeoMath.distanceKm(me, p)
            val bearing = GeoMath.bearingDegrees(me, p)
            "Head ${bearing.toInt()}° (${GeoMath.compassLabel(bearing)}) • ${GeoMath.formatDistance(km)} away"
        } else {
            "Waiting for your GPS fix…"
        }
    }

    // ---------------------------------------------------------------- turn-by-turn

    /**
     * Recomputes the current turn-by-turn instruction from the map's live route (which is
     * itself recomputed from wherever the user is right now, see [IndiaMapView.recomputeRoute])
     * and shows/speaks it. Because the route is always freshly computed from the current
     * position, we don't need to track progress across GPS fixes ourselves - each fix just
     * yields a new "next maneuver ahead of me" straight from [TurnByTurn.build]'s second step
     * (its first step is always "Head <dir>" from the current position, which isn't itself an
     * upcoming turn to announce).
     */
    private fun updateTurnByTurn(hasRoute: Boolean, isRoadRoute: Boolean) {
        if (!isNavigating || !hasRoute || !isRoadRoute) {
            binding.turnByTurnText.visibility = View.GONE
            lastAnnouncedKey = null
            return
        }
        val route = binding.indiaMapView.getRoutePoints()
        if (route == null || route.size < 2) {
            binding.turnByTurnText.visibility = View.GONE
            return
        }
        val steps = TurnByTurn.build(route)
        if (steps.size < 2) {
            binding.turnByTurnText.visibility = View.GONE
            return
        }

        val next = steps[1] // first real maneuver (or the arrival step, on a route with no turns)
        val distanceMeters = next.cumulativeMeters
        val phrase = next.instruction.replaceFirstChar { it.lowercase() }
        val text = "In ${GeoMath.formatDistance(distanceMeters / 1000.0)}, $phrase"

        binding.turnByTurnText.text = text
        binding.turnByTurnText.visibility = View.VISIBLE

        // Announce once per "distance bucket" per maneuver, so a fix every ~2-3s doesn't repeat
        // the same line over and over: once on approach, once again right before the turn.
        val bucket = when {
            distanceMeters > FAR_ANNOUNCE_METERS -> null
            distanceMeters > NEAR_ANNOUNCE_METERS -> "far"
            else -> "near"
        }
        if (!muted && bucket != null) {
            val key = "${next.instruction}|$bucket"
            if (key != lastAnnouncedKey) {
                lastAnnouncedKey = key
                speak(text)
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_instruction")
    }

    private fun toggleMute() {
        muted = !muted
        binding.muteButton.text = if (muted) "🔇" else "🔈"
        if (muted) tts?.stop()
    }

    private fun updateRouteInfo(hasRoute: Boolean, isRoadRoute: Boolean) {
        if (!hasRoute) return
        val suffix = if (isRoadRoute) " • following roads" else if (roadGraph != null) " • straight-line (no nearby road data)" else " • straight-line estimate"
        val current = binding.navInfoText.text.toString()
        if (current.isNotBlank() && !current.contains("following roads") && !current.contains("straight-line")) {
            binding.navInfoText.text = current + suffix
        }
    }

    /**
     * Starts/stops a self-contained "follow me" navigation mode entirely on our own offline
     * map: while active, the camera keeps re-centring on each new GPS fix (see [onNewLocation])
     * and the road-following (or straight-line fallback) route to the pin stays drawn. Nothing
     * here ever leaves this screen or this app - no Google Maps, no geo: intent, no other app.
     */
    private fun toggleNavigation() {
        val p = pin
        if (p == null) {
            Toast.makeText(this, "Drop a pin (or open a shared location) first", Toast.LENGTH_SHORT).show()
            return
        }
        isNavigating = !isNavigating
        binding.indiaMapView.setNavigating(isNavigating)
        binding.navigateButton.text = if (isNavigating) "Stop navigating" else "Navigate"
        lastAnnouncedKey = null
        if (isNavigating) {
            val me = myLocation
            if (me != null) {
                binding.indiaMapView.centerOn(me, 8f)
            } else {
                Toast.makeText(this, "Waiting for your GPS fix to start following…", Toast.LENGTH_SHORT).show()
            }
            // Render immediately from whatever route is already computed, rather than waiting
            // for the next GPS fix to trigger onRouteUpdated.
            updateTurnByTurn(
                binding.indiaMapView.getRoutePoints() != null,
                binding.indiaMapView.isRouteFollowingRoad()
            )
        } else {
            binding.turnByTurnText.visibility = View.GONE
            tts?.stop()
        }
    }

    private fun clearPin() {
        pin = null
        isNavigating = false
        binding.indiaMapView.setNavigating(false)
        binding.navigateButton.text = "Navigate"
        binding.indiaMapView.setPin(null)
        binding.turnByTurnText.visibility = View.GONE
        lastAnnouncedKey = null
        tts?.stop()
        updatePinInfo()
    }

    private fun focusOnMyLocation() {
        val me = myLocation
        if (me == null) {
            Toast.makeText(this, "Still finding your GPS location…", Toast.LENGTH_SHORT).show()
            return
        }
        binding.indiaMapView.centerOn(me, 7f)
    }

    private fun onNewLocation(location: Location) {
        myLocation = GeoPoint(location.latitude, location.longitude)
        binding.indiaMapView.setUserLocation(myLocation)
        updatePinInfo()
        if (isNavigating) {
            binding.indiaMapView.centerOn(myLocation!!, 8f)
        }
    }

    private fun ensureLocationPermissionThenStart() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun startLocationUpdates() {
        val lm = locationManager ?: return
        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (provider in providers) {
                if (lm.isProviderEnabled(provider)) {
                    lm.getLastKnownLocation(provider)?.let { onNewLocation(it) }
                    lm.requestLocationUpdates(provider, 2000L, 3f, locationListener)
                }
            }
            if (providers.none { lm.isProviderEnabled(it) }) {
                Toast.makeText(this, "Turn on GPS / Location to see yourself on the map", Toast.LENGTH_LONG).show()
            }
        } catch (e: SecurityException) {
            // Permission was revoked between the check and the call - safe to ignore, UI just
            // stays without a "you are here" dot until the user grants it again.
        }
    }

    override fun onResume() {
        super.onResume()
        lockOutIfRevoked()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (e: SecurityException) {
            // nothing to clean up if we never had permission
        }
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_FROM = "extra_from"
        private const val PREF_OSM_URI = "osm_uri"
        private const val PREF_LAST_DOWNLOAD_URL = "last_download_url"
        private const val PREF_FIRST_RUN_PROMPTED = "first_run_road_prompt_shown"
        private const val DOWNLOADED_ROADS_FILENAME = "downloaded_roads.dat" // name is format-agnostic; parseAndInstallRoads sniffs content

        // Starter extract offered on first run - New Delhi, gzip'd OSM XML, from BBBike's free
        // extract server. VERIFY THIS URL BEFORE SHIPPING: I found solid evidence this extract
        // exists (BBBike's NewDelhi extract page lists an "OSM XML gzip'd ~69M" download) but
        // couldn't fetch the page directly to confirm this exact filename, since BBBike blocks
        // automated fetches. If it 404s, open https://download.bbbike.org/osm/bbbike/NewDelhi/
        // in a browser, copy the real "OSM XML gzip'd" link, and paste it in here - or point it
        // at any other city/state's .osm/.osm.gz link (BBBike, or a custom extract.bbbike.org
        // export) instead.
        private const val DEFAULT_ROADS_URL = "https://download.bbbike.org/osm/bbbike/NewDelhi/NewDelhi.osm.gz"
        private const val DEFAULT_ROADS_LABEL = "New Delhi"
        private const val DEFAULT_ROADS_SIZE_HINT = "~69 MB"

        // Turn-by-turn announcement thresholds (metres from the upcoming maneuver).
        private const val FAR_ANNOUNCE_METERS = 250.0
        private const val NEAR_ANNOUNCE_METERS = 40.0
    }
}
