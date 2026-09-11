package com.malik.aegisdrive

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.gms.location.*
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*

class NavigateFragment : Fragment(), SensorEventListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = 250L }
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = 250L }
    }

    private lateinit var webView: WebView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    
    private var currentLat = 0.0
    private var currentLng = 0.0
    private var currentHeading = 0f
    private var lastGpsBearingTime = 0L   // when we last got a real GPS travel bearing

    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    // 🚀 FREE geocoding: native Android Geocoder + coroutine debounce (no paid Places API)
    private val geocodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var geocodeJob: Job? = null
    private val SUGGESTION_DEBOUNCE_MS = 500L

    // 🚀 TASK 1: Break the Permission Loop
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            setupLocationUpdates()
        }
    }

    private val sharedPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "LAST_SCORE") syncPassiveSafetyStatus()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_navigate, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView = view.findViewById(R.id.webView)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
        
        // 🚀 TASK 1: Instant, High-Quality Map Rendering
        webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                @Suppress("DEPRECATION")
                setRenderPriority(WebSettings.RenderPriority.HIGH)
            }
        }

        // Initialize Sensors
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                syncPassiveSafetyStatus()
                getLastKnownLocation()
                // 🛰️ Intercept map load: if the device's Location/GPS is physically off, prompt safely.
                if (!isLocationServiceEnabled()) {
                    showMapToast("Please turn on Location Services (GPS) for navigation")
                }
            }
        }

        webView.addJavascriptInterface(WebAppInterface(requireContext()), "Android")
        webView.loadUrl("file:///android_asset/navigate/index.html")

        checkPermissionsAndStart()
        setupInternalSpeech()

        requireActivity().getSharedPreferences("AegisData", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    private fun checkPermissionsAndStart() {
        val fineLocation = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        if (fineLocation != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            setupLocationUpdates()
        }
    }

    private fun setupInternalSpeech() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    val jsText = JSONObject.quote(text)   // safely escaped (handles quotes/apostrophes)
                    // Pipe the spoken text into the search bar and show live suggestions.
                    handler.post {
                        if (isAdded && ::webView.isInitialized) {
                            webView.evaluateJavascript("window.onVoiceResult($jsText);", null)
                        }
                    }
                }
            }
            override fun onReadyForSpeech(p0: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p0: Float) {}
            override fun onBufferReceived(p0: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(errorCode: Int) {
                // ERROR_NO_MATCH / SPEECH_TIMEOUT are benign; only surface real failures.
                if (errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    handler.post { showMapToast("Didn't catch that — please try again") }
                } else {
                    handler.post { showMapToast("Voice search unavailable") }
                }
            }
            override fun onPartialResults(p0: Bundle?) {}
            override fun onEvent(p0: Int, p1: Bundle?) {}
        })
    }

    private fun setupLocationUpdates() {
        // 🚀 TASK 2: High-Accuracy Continuous GPS
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).apply {
            setMinUpdateIntervalMillis(1000)
            setWaitForAccurateLocation(true)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLat = location.latitude
                    currentLng = location.longitude
                    // 🧭 DIRECTION OF TRAVEL: while actually moving, the GPS bearing is the true
                    // heading. It's far more stable than the raw compass, so it wins here and the
                    // compass is only used when stationary (see onSensorChanged).
                    if (location.hasBearing() && location.hasSpeed() && location.speed >= 0.7f) {
                        currentHeading = smoothHeading(currentHeading, location.bearing)
                        lastGpsBearingTime = System.currentTimeMillis()
                    }
                    updateMapMarker()
                }
            }
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
        }
    }

    // 🚀 TASK 2.3: Bridge to JS (updateRealTimeTracking)
    private var lastUpdateTimestamp = 0L
    private fun updateMapMarker() {
        if (!::webView.isInitialized || !isAdded) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateTimestamp < 100) return // Throttle to 10Hz to prevent JS thread saturation
        lastUpdateTimestamp = now
        handler.post { 
            if (isAdded) webView.evaluateJavascript("javascript:updateRealTimeTracking($currentLat, $currentLng, $currentHeading);", null) 
        }
    }

    // 🚀 TASK 2.2: Device Compass (Rotation) — fallback heading only when NOT moving.
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        // While driving, the GPS travel bearing owns the heading. Ignore the compass for a few
        // seconds after the last GPS bearing to prevent the arrow from fighting/jumping erratically.
        if (System.currentTimeMillis() - lastGpsBearingTime < 3000) return

        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val orientationAngles = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        val compass = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360) % 360).toFloat()
        currentHeading = smoothHeading(currentHeading, compass)   // low-pass to kill jitter
        updateMapMarker()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Low-pass angular filter that handles the 0°/360° wrap so headings never jump erratically. */
    private fun smoothHeading(current: Float, target: Float, factor: Float = 0.25f): Float {
        var delta = target - current
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return ((current + delta * factor) + 360f) % 360f
    }

    private fun syncPassiveSafetyStatus() {
        if (!::webView.isInitialized || !isAdded) return
        val score = requireActivity().getSharedPreferences("AegisData", Context.MODE_PRIVATE).getInt("LAST_SCORE", 100)
        handler.post { if (isAdded) webView.evaluateJavascript("window.updateSafetyScore($score);", null) }
    }

    private fun getLastKnownLocation() {
        if (!isAdded || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let { 
                currentLat = it.latitude
                currentLng = it.longitude
                // 🚀 TASK 1: Eradicate "Blue Screen" with Instant Snap
                handler.post { 
                    if (isAdded) webView.evaluateJavascript("javascript:initializeMapCenter(${it.latitude}, ${it.longitude});", null) 
                }
                updateMapMarker()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 🚀 FREE native geocoding (Step 1 & 2): Geocoder on Dispatchers.IO, debounced.
    // Called from the WebView JS bridge on every keystroke (isSuggestion=true) and on
    // submit/enter/voice (isSuggestion=false). No paid API, no custom-XML toasts.
    // ─────────────────────────────────────────────────────────────────────────
    private fun runNativeGeocode(rawQuery: String, isSuggestion: Boolean) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return

        // Debounce: cancel any in-flight lookup and start fresh.
        geocodeJob?.cancel()
        geocodeJob = geocodeScope.launch {
            if (isSuggestion) delay(SUGGESTION_DEBOUNCE_MS)   // 500ms as-you-type debounce
            val ctx = context ?: return@launch

            // 🎯 HYPER-LOCAL BIAS: anchor ambiguous queries to the twin cities AND constrain the
            // Play Services backend to a bounding box around the user (or Rawalpindi/Islamabad).
            // This is what makes "Raja Bazar", "Bakra Mandi", "Sultan Khoo" resolve locally & free.
            //
            // 🔁 EXPANDED-RADIUS RETRY LADDER (all free, all native):
            //   1. biased query + tight ~50km box around the user
            //   2. biased query + widened ~150km box
            //   3. raw query, unconstrained (worldwide) — catches out-of-region destinations
            //   4. simplified query (over-specific keywords stripped), unconstrained
            val attempts = mutableListOf<Pair<String, DoubleArray?>>(
                applyRegionalBias(query) to regionBoundingBox(1.0),
                applyRegionalBias(query) to regionBoundingBox(3.0),
                query to null
            )
            simplifyQuery(query)?.let { attempts.add(it to null) }

            var results: List<Address>? = null
            var networkFailed = false
            for ((q, box) in attempts) {
                val r: List<Address>? = withContext(Dispatchers.IO) { geocodeBlocking(ctx, q, box) }
                if (r == null) { networkFailed = true; break }   // IO error — retries won't help
                if (r.isNotEmpty()) { results = r; break }
                results = r   // empty — try the next, wider attempt
            }

            if (!isAdded || !::webView.isInitialized) return@launch
            if (results.isNullOrEmpty()) {
                // 💾 OFFLINE / ZERO-RESULT FALLBACK: serve previously found places from the
                // local Aegis cache so the user is never left with a blank screen.
                val cached = localCacheMatches(query)
                when {
                    isSuggestion && cached.isNotEmpty() -> pushCachedSuggestionsToJs(cached)
                    isSuggestion -> pushSuggestionsToJs(emptyList())   // JS renders the empty state
                    cached.isNotEmpty() -> {
                        val (name, lat, lon) = cached[0]
                        showMapToast("Offline match from local cache")
                        pushCachedDestinationToJs(name, lat, lon)
                    }
                    networkFailed -> showMapToast("Location not found or network error")
                    else -> showMapToast("Location not found — try a simpler search")
                }
                return@launch
            }
            if (isSuggestion) pushSuggestionsToJs(results) else pushDestinationToJs(results[0])
        }
    }

    /** Native Toast only — never AegisNotify — to avoid Material inflation crashes on map errors. */
    private fun showMapToast(msg: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Anchors ambiguous local queries to the Rawalpindi/Islamabad region. If the query already
     * names a major Pakistani city we trust it as-is; otherwise we strip any generic "Pakistan"
     * suffix added upstream and re-anchor to the twin cities for hyper-local accuracy.
     */
    private fun applyRegionalBias(q: String): String {
        val lower = q.lowercase(Locale.US)
        val cities = listOf(
            "rawalpindi", "islamabad", "lahore", "karachi", "peshawar",
            "murree", "multan", "faisalabad", "quetta", "sialkot", "gujranwala"
        )
        if (cities.any { lower.contains(it) }) return q
        val base = q.trim().trimEnd(',')
            .removeSuffix(" Pakistan").removeSuffix(" pakistan").trim()
        return "$base, Rawalpindi, Islamabad, Pakistan"
    }

    /**
     * Bounding box hint for the Geocoder: the user's vicinity when we have a GPS fix, otherwise the
     * Rawalpindi + Islamabad metro area. [scale] widens the box for retry attempts (1.0 ≈ 50 km).
     * Returned as [lowerLeftLat, lowerLeftLon, upperRightLat, upperRightLon].
     */
    private fun regionBoundingBox(scale: Double = 1.0): DoubleArray {
        return if (currentLat != 0.0 && currentLng != 0.0) {
            val d = 0.45 * scale
            doubleArrayOf(
                (currentLat - d).coerceIn(-90.0, 90.0), (currentLng - d).coerceIn(-180.0, 180.0),
                (currentLat + d).coerceIn(-90.0, 90.0), (currentLng + d).coerceIn(-180.0, 180.0)
            )
        } else {
            val d = 0.25 * (scale - 1.0)   // widen the twin-cities box on retries
            doubleArrayOf(
                (33.40 - d).coerceIn(-90.0, 90.0), (72.80 - d).coerceIn(-180.0, 180.0),
                (33.90 + d).coerceIn(-90.0, 90.0), (73.35 + d).coerceIn(-180.0, 180.0)
            )
        }
    }

    /**
     * One blocking Geocoder call. Returns an empty list for "no matches", or null on IOException
     * (no network / backend unavailable) so the caller can distinguish and fall back safely.
     */
    @Suppress("DEPRECATION")
    private fun geocodeBlocking(ctx: Context, query: String, box: DoubleArray?): List<Address>? {
        return try {
            val geocoder = Geocoder(ctx, Locale.getDefault())
            val r = if (box != null) {
                geocoder.getFromLocationName(query, 5, box[0], box[1], box[2], box[3])
            } else {
                geocoder.getFromLocationName(query, 5)
            }
            r ?: emptyList()
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Strips overly specific tokens ("near", "shop", house numbers, extra descriptors) so a failed
     * precise search can retry with the core place name — e.g.
     * "small tea shop near Raja Bazar gate 3" → "Raja Bazar".
     */
    private fun simplifyQuery(q: String): String? {
        val noise = setOf(
            "near", "nearby", "opposite", "behind", "beside", "front", "of", "the", "a",
            "shop", "store", "gate", "street", "st", "house", "no", "number", "close", "to"
        )
        val tokens = q.split(Regex("[\\s,]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase(Locale.US) !in noise && !it.all(Char::isDigit) }
        if (tokens.size < 1) return null
        val simplified = tokens.take(3).joinToString(" ")
        return if (simplified.equals(q.trim(), ignoreCase = true)) null else simplified
    }

    /** Case-insensitive lookup in the persistent Aegis search cache: (name, lat, lon) triples. */
    private fun localCacheMatches(query: String): List<Triple<String, Double, Double>> {
        val ctx = context ?: return emptyList()
        val history = ctx.getSharedPreferences("AegisSearchCache", Context.MODE_PRIVATE)
            .getString("history", "") ?: ""
        val q = query.lowercase(Locale.US)
        return history.split(";").mapNotNull { entry ->
            val p = entry.split("|")
            if (p.size < 3 || !p[0].lowercase(Locale.US).contains(q)) return@mapNotNull null
            val lat = p[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = p[2].toDoubleOrNull() ?: return@mapNotNull null
            Triple(p[0], lat, lon)
        }.take(6)
    }

    /** Push cached (offline) matches to the JS suggestion list, flagged with the 💾 icon. */
    private fun pushCachedSuggestionsToJs(matches: List<Triple<String, Double, Double>>) {
        val arr = JSONArray()
        for ((name, lat, lon) in matches) {
            arr.put(JSONObject().apply {
                put("display_name", name)
                put("lat", lat)
                put("lon", lon)
                put("isOffline", true)
            })
        }
        val json = arr.toString()
        handler.post {
            if (isAdded && ::webView.isInitialized) {
                webView.evaluateJavascript("window.renderNativeSuggestions($json);", null)
            }
        }
    }

    /** Pinpoint a cached destination on the map (offline path). */
    private fun pushCachedDestinationToJs(name: String, lat: Double, lon: Double) {
        val jsName = JSONObject.quote(name)
        handler.post {
            if (isAdded && ::webView.isInitialized) {
                webView.evaluateJavascript("handleDestinationSelected([$lon, $lat], $jsName);", null)
            }
        }
    }

    /** True if the device has GPS or network location physically enabled. */
    private fun isLocationServiceEnabled(): Boolean {
        val lm = context?.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return false
        return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
               lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    /** Builds a human-readable label from a Geocoder Address. */
    private fun buildAddressLabel(a: Address): String? {
        if (a.maxAddressLineIndex >= 0) {
            val line = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ")
            if (line.isNotBlank()) return line
        }
        val parts = listOfNotNull(a.featureName, a.locality, a.subAdminArea, a.adminArea, a.countryName)
            .distinct()
        return if (parts.isNotEmpty()) parts.joinToString(", ") else null
    }

    /** Push the top-5 native results to the JS suggestion list. */
    private fun pushSuggestionsToJs(addresses: List<Address>) {
        val arr = JSONArray()
        for (a in addresses) {
            val name = buildAddressLabel(a) ?: continue
            arr.put(JSONObject().apply {
                put("display_name", name)
                put("lat", a.latitude)
                put("lon", a.longitude)
            })
        }
        val json = arr.toString()
        handler.post {
            if (isAdded && ::webView.isInitialized) {
                webView.evaluateJavascript("window.renderNativeSuggestions($json);", null)
            }
        }
    }

    /** Pinpoint the single best native result on the map (exact LatLng). */
    private fun pushDestinationToJs(a: Address) {
        val name = buildAddressLabel(a) ?: "Destination"
        val lat = a.latitude
        val lon = a.longitude
        val jsName = JSONObject.quote(name)   // safely escaped JS string literal
        handler.post {
            if (isAdded && ::webView.isInitialized) {
                webView.evaluateJavascript("handleDestinationSelected([$lon, $lat], $jsName);", null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncPassiveSafetyStatus()
        checkAndExecuteCommands()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun checkAndExecuteCommands() {
        if (!isAdded) return
        val prefs = requireActivity().getSharedPreferences("AegisData", Context.MODE_PRIVATE)
        val cmd = prefs.getString("NAV_CMD", null)
        if (cmd == "START") {
            val lat = prefs.getFloat("NAV_LAT", 0f)
            val lon = prefs.getFloat("NAV_LON", 0f)
            val name = prefs.getString("NAV_NAME", "Destination")
            
            prefs.edit().remove("NAV_CMD").apply()
            
            if (lat != 0f) {
                // JSONObject.quote() → safely escaped JS string (apostrophes in place names used
                // to break this injection, e.g. "Faizan-e-Madina Qadria Jama'at").
                val jsName = JSONObject.quote(name ?: "Destination")
                handler.postDelayed({
                    if (isAdded && ::webView.isInitialized) {
                        webView.evaluateJavascript("window.handleDestinationSelected([$lon, $lat], $jsName);", null)
                        handler.postDelayed({
                            if (isAdded && ::webView.isInitialized) webView.evaluateJavascript("document.getElementById('btnStartDrive').click();", null)
                        }, 1500)
                    }
                }, 1000)
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        geocodeJob?.cancel()
        handler.removeCallbacksAndMessages(null)   // drop queued JS bridge posts to the dead WebView
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        try {
            webView.removeJavascriptInterface("Android")
            webView.stopLoading()
            webView.destroy()
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        geocodeScope.cancel()
        speechRecognizer?.setRecognitionListener(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        requireActivity().getSharedPreferences("AegisData", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    inner class WebAppInterface(private val mContext: Context) {

        // 🚀 FREE autocomplete: as-you-type suggestions via native Geocoder (debounced 500ms).
        @JavascriptInterface
        fun geocodeSuggest(query: String) {
            runNativeGeocode(query, isSuggestion = true)
        }

        // 🚀 FREE exact search: best single result -> pinpointed on the map by the fragment.
        @JavascriptInterface
        fun geocodeSearch(query: String) {
            runNativeGeocode(query, isSuggestion = false)
        }

        // 🛡️ Plain native Android Toast (never Material/AegisNotify) — used by JS for the
        // offline-routing message so it can never crash the map screen.
        @JavascriptInterface
        fun showNativeToast(msg: String) {
            handler.post { showMapToast(msg) }
        }

        @JavascriptInterface
        fun showToast(toast: String) {
            handler.post { 
                val type = if (toast.contains("Success", true) || toast.contains("Saved", true)) AegisNotify.Type.SUCCESS 
                           else if (toast.contains("Failed", true) || toast.contains("Error", true)) AegisNotify.Type.ERROR
                           else AegisNotify.Type.INFO
                AegisNotify.show(mContext, toast, type) 
            } 
        }

        @JavascriptInterface
        fun startVoiceRecognition() {
            handler.post {
                val permission = Manifest.permission.RECORD_AUDIO
                if (ContextCompat.checkSelfPermission(mContext, permission) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissionLauncher.launch(arrayOf(permission))
                    return@post
                }

                try {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak destination...")
                    }
                    speechRecognizer?.startListening(intent)
                    showMapToast("Listening…")
                } catch (e: Exception) {
                    showMapToast("Voice search unavailable")
                }
            }
        }

        // 🔋 Keep the screen awake while a route/navigation is active (domain 5).
        @JavascriptInterface
        fun setKeepScreenOn(enable: Boolean) {
            handler.post {
                if (!isAdded) return@post
                val w = activity?.window ?: return@post
                if (enable) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // NOTE: @JavascriptInterface methods run on a WebView worker thread — mContext is used
        // for prefs instead of requireActivity(), which throws if the fragment is detaching.
        @JavascriptInterface
        fun saveHomeLocation(lat: Double, lon: Double) {
            val prefs = mContext.getSharedPreferences("AegisData", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putFloat("HOME_LAT", lat.toFloat())
                putFloat("HOME_LON", lon.toFloat())
                apply()
            }
        }

        @JavascriptInterface
        fun triggerNavigateHome() {
            val prefs = mContext.getSharedPreferences("AegisData", Context.MODE_PRIVATE)
            val lat = prefs.getFloat("HOME_LAT", 0f)
            val lon = prefs.getFloat("HOME_LON", 0f)
            if (lat != 0f) {
                handler.post {
                    if (isAdded && ::webView.isInitialized) {
                        webView.evaluateJavascript("window.handleDestinationSelected([$lon, $lat], 'Home');", null)
                    }
                }
            } else {
                handler.post { AegisNotify.show(mContext, "Please set your Home location first.", AegisNotify.Type.WARNING) }
            }
        }

        @JavascriptInterface
        fun isNetworkAvailable(): Boolean {
            return try {
                val cm = mContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun saveSearchHistory(name: String, lat: Double, lon: Double) {
            if (!lat.isFinite() || !lon.isFinite()) return
            // Sanitize the delimiters out of the name so one bad entry can't corrupt the cache.
            val safeName = name.replace("|", " ").replace(";", " ").trim()
            if (safeName.isEmpty()) return
            val prefs = mContext.getSharedPreferences("AegisSearchCache", Context.MODE_PRIVATE)
            val history = prefs.getString("history", "") ?: ""
            val newEntry = "$safeName|$lat|$lon"

            // Keep last 50, remove duplicates
            val list = history.split(";").filter { it.isNotEmpty() && !it.startsWith("$safeName|") }.toMutableList()
            list.add(0, newEntry)
            if (list.size > 50) list.removeAt(list.size - 1)

            prefs.edit().putString("history", list.joinToString(";")).apply()
        }

        @JavascriptInterface
        fun getSearchHistory(): String {
            return mContext.getSharedPreferences("AegisSearchCache", Context.MODE_PRIVATE).getString("history", "") ?: ""
        }
    }
}
