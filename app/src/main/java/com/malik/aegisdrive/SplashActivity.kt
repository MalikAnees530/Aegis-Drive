package com.malik.aegisdrive

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val TAG = "SplashActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        // 🚀 CRITICAL FIX 1: BREAK INFINITE RECREATION LOOP
        val prefs = getSharedPreferences("AegisSettings", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", true)
        val targetMode = if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO

        if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
            AppCompatDelegate.setDefaultNightMode(targetMode)
        }

        // installSplashScreen() MUST be called BEFORE super.onCreate()
        val splashScreen = installSplashScreen()
        
        super.onCreate(savedInstanceState)
        
        // Re-introducing fallback XML layout to prevent native splash hangs
        setContentView(R.layout.activity_splash)

        // Premium branded reveal: logo scales + fades in, wordmark follows.
        try {
            val logo = findViewById<android.widget.ImageView>(R.id.ivSplashLogo)
            val wordmark = findViewById<android.widget.TextView>(R.id.tvSplashWordmark)
            logo.apply {
                alpha = 0f
                scaleX = 0.82f
                scaleY = 0.82f
                animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                    .setDuration(720).start()
            }
            wordmark.apply {
                alpha = 0f
                translationY = 24f
                animate().alpha(1f).translationY(0f)
                    .setStartDelay(260)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .setDuration(600).start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Splash reveal animation skipped", e)
        }

        // Use lifecycleScope for crash-proof threading
        lifecycleScope.launch {
            try {
                // Ensure Firebase is initialized
                FirebaseApp.initializeApp(this@SplashActivity)
                
                // Mandatory 2-second branding delay
                delay(2000)
                
                // Perform routing after the delay
                performSessionCheck()
            } catch (e: Exception) {
                Log.e("SplashError", "Initialization or Coroutine failed", e)
                routeToLogin()
            }
        }
    }

    private fun performSessionCheck() {
        try {
            // Check current user session
            val auth = FirebaseAuth.getInstance()
            val currentUser = auth.currentUser
            
            if (currentUser != null) {
                Log.d(TAG, "Operator session active: ${currentUser.email}")
                val intent = Intent(this@SplashActivity, MainActivity::class.java)
                startActivity(intent)
            } else {
                Log.d(TAG, "No active session. Routing to Login.")
                routeToLogin()
            }
            finish()
        } catch (e: Exception) {
            Log.e("SplashError", "Routing failed - Firebase or Context issue", e)
            routeToLogin()
        }
    }

    private fun routeToLogin() {
        try {
            val intent = Intent(this@SplashActivity, LoginActivity::class.java)
            // Clear backstack
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e("SplashError", "Fatal: Could not route to LoginActivity", e)
        }
    }
}
