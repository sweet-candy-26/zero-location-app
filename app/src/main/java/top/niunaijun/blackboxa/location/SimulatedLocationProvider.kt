package top.niunaijun.blackboxa.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.entity.location.BLocation
import top.niunaijun.blackbox.fake.frameworks.BLocationManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Hardware GPS Simulation Provider
 * Simulates GPS telemetry (30 km/h, 5m accuracy, 10m altitude) and hooks
 * into the BlackBox virtual framework and Android location subsystem.
 */
object SimulatedLocationProvider {

    private const val TAG = "SimulatedLocation"

    private const val SPEED_KMH = 30.0
    private const val SPEED_MPS = (SPEED_KMH * 1000.0) / 3600.0 // 8.333 m/s
    private const val UPDATE_INTERVAL_MS = 2000L // 2 seconds
    private const val STEP_DISTANCE_METERS = SPEED_MPS * (UPDATE_INTERVAL_MS / 1000.0) // ~16.67 meters
    private const val STANDARD_ACCURACY = 5.0f // 5 meters
    private const val STANDARD_ALTITUDE = 10.0 // 10 meters

    @Volatile
    private var appContext: Context? = null
    private var systemLocationManager: LocationManager? = null

    @Volatile
    private var currentSimulatedLocation: Location? = null

    private var scheduler: ScheduledExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val locationListeners = CopyOnWriteArrayList<(Location) -> Unit>()

    /**
     * Initialize the provider in the Application class.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        systemLocationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        initTestProvider()
        Log.d(TAG, "SimulatedLocationProvider initialized")
    }

    /**
     * Configure Android mock test provider if permissions permit.
     */
    private fun initTestProvider() {
        val lm = systemLocationManager ?: return
        try {
            val providerName = LocationManager.GPS_PROVIDER
            if (lm.getProvider(providerName) != null) {
                try {
                    lm.addTestProvider(
                        providerName,
                        false, // requiresNetwork
                        false, // requiresSatellite
                        false, // requiresCell
                        false, // hasMonetaryCost
                        true,  // supportsAltitude
                        true,  // supportsSpeed
                        true,  // supportsBearing
                        1,     // powerRequirement
                        1      // accuracy
                    )
                } catch (ignored: IllegalArgumentException) {
                    // Provider already exists as test provider
                }
                lm.setTestProviderEnabled(providerName, true)
                Log.d(TAG, "Hardware GPS test provider configured successfully")
            }
        } catch (e: Exception) {
            Log.w(TAG, "System test provider registration fallback: ${e.message}")
        }
    }

    /**
     * Set a fixed simulated GPS location.
     * Generates a standard Android Location object and propagates it to
     * BlackBox virtual applications and system listeners.
     */
    fun setLocation(
        lat: Double,
        lng: Double,
        speed: Float = 0.0f,
        bearing: Float = 0.0f
    ): Location {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lng
            accuracy = STANDARD_ACCURACY
            altitude = STANDARD_ALTITUDE
            this.speed = speed
            this.bearing = bearing
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    setSpeedAccuracyMetersPerSecond(0.5f)
                    setBearingAccuracyDegrees(1.0f)
                    setVerticalAccuracyMeters(1.0f)
                } catch (ignored: Throwable) {
                }
            }

            val extrasBundle = Bundle().apply {
                putInt("satellites", 12)
                putInt("satellitesvalue", 12)
            }
            extras = extrasBundle
        }

        currentSimulatedLocation = location

        // 1. Hook into BlackBox virtualization engine
        injectIntoBlackBox(lat, lng)

        // 2. Hook into system LocationManager test provider
        injectIntoSystemLocationManager(location)

        // 3. Notify internal listeners
        notifyListeners(location)

        Log.d(TAG, "Location set: lat=$lat, lng=$lng, speed=$speed, bearing=$bearing")
        return location
    }

    /**
     * Injects custom location into BlackBox's BLocationManager.
     */
    private fun injectIntoBlackBox(lat: Double, lng: Double) {
        try {
            val bLocation = BLocation(lat, lng)
            val bm = BLocationManager.get()

            // Update global fallback in BlackBox framework
            bm.setGlobalLocation(bLocation)

            // Apply global mode to all virtual packages installed in user 0
            val installedApps = BlackBoxCore.get().getInstalledApplications(0, 0)
            if (installedApps != null) {
                for (appInfo in installedApps) {
                    val pkg = appInfo?.packageName
                    if (!pkg.isNullOrEmpty()) {
                        bm.setPattern(0, pkg, BLocationManager.GLOBAL_MODE)
                        bm.setLocation(0, pkg, bLocation)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting location into BlackBox: ${e.message}")
        }
    }

    /**
     * Push location to Android LocationManager test provider.
     */
    @SuppressLint("MissingPermission")
    private fun injectIntoSystemLocationManager(location: Location) {
        val lm = systemLocationManager ?: return
        try {
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        } catch (e: Exception) {
            Log.v(TAG, "setTestProviderLocation notice: ${e.message}")
        }
    }

    /**
     * Route Simulation:
     * Accepts a list of coordinate waypoints and moves sequentially along the path,
     * updating position every 2 seconds at a simulated vehicle speed of 30 km/h.
     */
    fun startRoute(waypoints: List<Pair<Double, Double>>) {
        stopRoute()
        if (waypoints.isEmpty()) return

        if (waypoints.size == 1) {
            setLocation(waypoints[0].first, waypoints[0].second)
            return
        }

        var currentWaypointIndex = 0
        var currentLat = waypoints[0].first
        var currentLng = waypoints[0].second

        setLocation(currentLat, currentLng, speed = SPEED_MPS.toFloat(), bearing = 0.0f)

        val exec = Executors.newSingleThreadScheduledExecutor()
        scheduler = exec

        exec.scheduleWithFixedDelay({
            try {
                if (exec.isShutdown) return@scheduleWithFixedDelay

                val target = waypoints[currentWaypointIndex + 1]
                val targetLat = target.first
                val targetLng = target.second

                val distanceToTarget = computeDistance(currentLat, currentLng, targetLat, targetLng)
                val bearing = computeBearing(currentLat, currentLng, targetLat, targetLng)

                if (distanceToTarget <= STEP_DISTANCE_METERS) {
                    currentLat = targetLat
                    currentLng = targetLng
                    currentWaypointIndex++

                    if (currentWaypointIndex >= waypoints.size - 1) {
                        currentWaypointIndex = 0
                    }
                } else {
                    val nextPoint = computeDestinationPoint(
                        currentLat,
                        currentLng,
                        STEP_DISTANCE_METERS,
                        bearing.toDouble()
                    )
                    currentLat = nextPoint.first
                    currentLng = nextPoint.second
                }

                mainHandler.post {
                    setLocation(
                        lat = currentLat,
                        lng = currentLng,
                        speed = SPEED_MPS.toFloat(),
                        bearing = bearing
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Route update error: ${e.message}")
            }
        }, UPDATE_INTERVAL_MS, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS)

        Log.d(TAG, "Route simulation started with ${waypoints.size} waypoints at 30 km/h")
    }

    /**
     * Stops any currently executing route simulation.
     */
    fun stopRoute() {
        try {
            scheduler?.shutdownNow()
            scheduler = null
        } catch (ignored: Exception) {
        }

        currentSimulatedLocation?.let { loc ->
            setLocation(loc.latitude, loc.longitude, speed = 0.0f, bearing = loc.bearing)
        }
        Log.d(TAG, "Route simulation stopped")
    }

    /**
     * Check if a route simulation is actively running.
     */
    fun isRouteRunning(): Boolean {
        return scheduler != null && !scheduler!!.isShutdown
    }

    /**
     * Retrieve the last simulated Location object.
     */
    fun getLastKnownLocation(): Location? {
        return currentSimulatedLocation
    }

    /**
     * Register a listener for location changes.
     */
    fun addLocationListener(listener: (Location) -> Unit) {
        locationListeners.add(listener)
        currentSimulatedLocation?.let { listener(it) }
    }

    /**
     * Remove a registered location listener.
     */
    fun removeLocationListener(listener: (Location) -> Unit) {
        locationListeners.remove(listener)
    }

    private fun notifyListeners(location: Location) {
        for (listener in locationListeners) {
            try {
                listener(location)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener: ${e.message}")
            }
        }
    }

    /**
     * Compute distance in meters between two coordinates.
     */
    fun computeDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    /**
     * Compute bearing in degrees from point 1 to point 2.
     */
    fun computeBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    /**
     * Calculate destination coordinates given a starting point, distance in meters, and bearing.
     */
    fun computeDestinationPoint(
        lat: Double,
        lng: Double,
        distanceMeters: Double,
        bearingDeg: Double
    ): Pair<Double, Double> {
        val earthRadius = 6371000.0 // meters
        val angularDist = distanceMeters / earthRadius
        val bearingRad = Math.toRadians(bearingDeg)
        val lat1Rad = Math.toRadians(lat)
        val lon1Rad = Math.toRadians(lng)

        val lat2Rad = asin(
            sin(lat1Rad) * cos(angularDist) +
                    cos(lat1Rad) * sin(angularDist) * cos(bearingRad)
        )
        val lon2Rad = lon1Rad + atan2(
            sin(bearingRad) * sin(angularDist) * cos(lat1Rad),
            cos(angularDist) - sin(lat1Rad) * sin(lat2Rad)
        )
        return Pair(Math.toDegrees(lat2Rad), Math.toDegrees(lon2Rad))
    }
}
