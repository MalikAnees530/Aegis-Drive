package com.malik.aegisdrive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    // Safely observes completed drive sessions to trigger the Family Notification when a single
    // session exceeds the alert threshold. Fully decoupled from MonitorFragment.
    private var familyAlertObserver: FamilyAlertObserver? = null

    // PHASE 3: RUNTIME PERMISSIONS MATRIX launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        try {
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
            
            if (!cameraGranted || !locationGranted || !audioGranted) {
                Log.w(TAG, "Critical permissions denied. Safety features may be degraded.")
            } else {
                Log.d(TAG, "All production permissions granted.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing permission results", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            // 🚀 SILENT NOTIFICATION REQUEST (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                }
            }

            // 🚀 CRITICAL FIX 1: BREAK INFINITE RECREATION LOOP
            val prefs = getSharedPreferences("AegisSettings", Context.MODE_PRIVATE)
            val isDarkMode = prefs.getBoolean("dark_mode", true)
            val targetMode = if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO

            if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
                AppCompatDelegate.setDefaultNightMode(targetMode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply dark mode", e)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 🚀 FAMILY NOTIFICATION: start observing completed drive sessions for alert overflow.
        // MainActivity hosts MonitorFragment, so this observer stays alive across a full drive.
        try {
            familyAlertObserver = FamilyAlertObserver(applicationContext).also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Family Alert observer", e)
        }

        try {
            // 🚀 CRITICAL FIX 2: IMPENETRABLE NAVIGATION SETUP
            val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottomNavigationView)
            
            // Primary Method: findFragmentById
            var navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            
            // Secondary Fallback: Search all fragments if ID lookup fails during rapid recreation
            if (navHostFragment == null) {
                Log.w(TAG, "ID-based NavHost lookup failed. Attempting fragment list fallback...")
                navHostFragment = supportFragmentManager.fragments.firstOrNull { it is NavHostFragment } as? NavHostFragment
            }

            if (navHostFragment != null) {
                bottomNavigationView.setupWithNavController(navHostFragment.navController)
                Log.d(TAG, "Navigation Controller successfully attached.")

                // 🚀 PERMANENT FIX: Bypass corrupted XML ColorStateLists
                try {
                    val states = arrayOf(
                        intArrayOf(android.R.attr.state_checked), // checked
                        intArrayOf(-android.R.attr.state_checked) // unchecked
                    )

                    val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

                    // Use guaranteed safe hex colors: Aegis Blue for active, Slate/Grey for inactive
                    val activeColor = Color.parseColor("#38BDF8")
                    val inactiveColor = if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")

                    val colors = intArrayOf(activeColor, inactiveColor)
                    val colorStateList = ColorStateList(states, colors)

                    bottomNavigationView.itemIconTintList = colorStateList
                    bottomNavigationView.itemTextColor = colorStateList
                    Log.d(TAG, "Programmatic nav colors applied successfully.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply programmatic nav colors", e)
                }

            } else {
                Log.e(TAG, "FATAL: NavHostFragment could not be located in any lifecycle state.")
            }

            // Post to the message queue to ensure the activity is fully resumed and attached.
            window.decorView.post {
                if (!isFinishing && !isDestroyed) {
                    checkAndRequestPermissions()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MainActivity Navigation initialization failed", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        familyAlertObserver?.stop()
        familyAlertObserver = null
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val listPermissionsNeeded = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (listPermissionsNeeded.isNotEmpty()) {
            Log.d(TAG, "Requesting production permissions: $listPermissionsNeeded")
            requestPermissionLauncher.launch(listPermissionsNeeded.toTypedArray())
        }
    }
}
