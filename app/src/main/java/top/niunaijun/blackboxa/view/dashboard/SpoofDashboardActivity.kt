package top.niunaijun.blackboxa.view.dashboard

import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import top.niunaijun.blackboxa.databinding.ActivitySpoofDashboardBinding
import top.niunaijun.blackboxa.location.SimulatedLocationProvider

/**
 * Dashboard UI for controlling hardware GPS simulation.
 * Allows setting individual coordinates or starting route simulation at 30 km/h.
 */
class SpoofDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpoofDashboardBinding

    private val locationUpdateCallback: (Location) -> Unit = { location ->
        runOnUiThread {
            updateDashboardUI(location)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpoofDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        initListeners()
    }

    override fun onStart() {
        super.onStart()
        SimulatedLocationProvider.addLocationListener(locationUpdateCallback)
    }

    override fun onStop() {
        super.onStop()
        SimulatedLocationProvider.removeLocationListener(locationUpdateCallback)
    }

    private fun initViews() {
        // Display existing simulated location if active
        val lastLoc = SimulatedLocationProvider.getLastKnownLocation()
        if (lastLoc != null) {
            updateDashboardUI(lastLoc)
            binding.etLatitude.setText(lastLoc.latitude.toString())
            binding.etLongitude.setText(lastLoc.longitude.toString())
        }

        updateRouteButtonState(SimulatedLocationProvider.isRouteRunning())
    }

    private fun initListeners() {
        binding.btnSetLocation.setOnClickListener {
            val lat = binding.etLatitude.text?.toString()?.toDoubleOrNull()
            val lng = binding.etLongitude.text?.toString()?.toDoubleOrNull()

            if (lat == null || lng == null) {
                Toast.makeText(this, "Please enter valid Latitude and Longitude", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (SimulatedLocationProvider.isRouteRunning()) {
                SimulatedLocationProvider.stopRoute()
                updateRouteButtonState(false)
            }

            SimulatedLocationProvider.setLocation(lat, lng)
            Toast.makeText(this, "Simulated location updated: $lat, $lng", Toast.LENGTH_SHORT).show()
        }

        binding.btnStartRoute.setOnClickListener {
            val lat = binding.etLatitude.text?.toString()?.toDoubleOrNull()
            val lng = binding.etLongitude.text?.toString()?.toDoubleOrNull()

            if (lat == null || lng == null) {
                Toast.makeText(this, "Please enter valid starting coordinates", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (SimulatedLocationProvider.isRouteRunning()) {
                SimulatedLocationProvider.stopRoute()
                updateRouteButtonState(false)
                Toast.makeText(this, "Route simulation stopped", Toast.LENGTH_SHORT).show()
            } else {
                // Generate a loop route around starting coordinate (distance ~ 1-2 km)
                val routeWaypoints = listOf(
                    Pair(lat, lng),
                    Pair(lat + 0.0035, lng + 0.0025),
                    Pair(lat + 0.0050, lng - 0.0010),
                    Pair(lat + 0.0020, lng - 0.0035),
                    Pair(lat, lng)
                )

                SimulatedLocationProvider.startRoute(routeWaypoints)
                updateRouteButtonState(true)
                Toast.makeText(this, "Route simulation started (30 km/h, 5m accuracy)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateDashboardUI(location: Location) {
        val latStr = String.format("%.6f", location.latitude)
        val lngStr = String.format("%.6f", location.longitude)
        binding.tvCurrentCoordinates.text = "Lat: $latStr, Lng: $lngStr"

        val speedKmh = (location.speed * 3.6).toInt()
        binding.tvStatusSpeed.text = "Speed: $speedKmh km/h"
        binding.tvStatusAccuracy.text = "Accuracy: ${location.accuracy} m"

        if (SimulatedLocationProvider.isRouteRunning()) {
            binding.tvStatusMode.text = "Status: Simulating Route"
            binding.tvStatusMode.setTextColor(Color.parseColor("#38BDF8"))
        } else {
            binding.tvStatusMode.text = "Status: Static GPS"
            binding.tvStatusMode.setTextColor(Color.parseColor("#10B981"))
        }
    }

    private fun updateRouteButtonState(isRunning: Boolean) {
        if (isRunning) {
            binding.btnStartRoute.text = "Stop Route"
            binding.btnStartRoute.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
            binding.tvStatusMode.text = "Status: Simulating Route"
            binding.tvStatusMode.setTextColor(Color.parseColor("#38BDF8"))
        } else {
            binding.btnStartRoute.text = "Start Route (30 km/h)"
            binding.btnStartRoute.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#0284C7"))
            binding.tvStatusMode.text = "Status: Idle"
            binding.tvStatusMode.setTextColor(Color.parseColor("#10B981"))
        }
    }
}
