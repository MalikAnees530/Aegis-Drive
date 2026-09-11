package com.malik.aegisdrive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import java.io.File
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private val TAG = "SettingsActivity"
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var profileListener: ListenerRegistration? = null
    private var prefListener: ListenerRegistration? = null

    /**
     * The number the user typed, held while the SEND_SMS system dialog is on screen. The Family
     * Notification feature is worthless without the permission (the SMS could never leave the
     * handset), so the number is only committed once the grant comes back positive.
     */
    private var pendingEmergencyNumber: String? = null

    /**
     * Registered unconditionally at field-initialisation time — ActivityResult launchers must be
     * registered before the Activity reaches STARTED, so this cannot be created lazily inside the
     * dialog's click handler.
     */
    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val number = pendingEmergencyNumber
            pendingEmergencyNumber = null

            // The two permissions are NOT equally important, so they are judged separately:
            //  - SEND_SMS is MANDATORY. Without it the alert can never leave the handset, so the
            //    number is not saved at all rather than giving a false sense of security.
            //  - READ_PHONE_STATE is BEST-EFFORT. It only powers the dual-SIM rescue in
            //    EmergencyContactManager; denying it must never block the feature, it just means
            //    the platform's default SMS SIM is used unconditionally, as it was before.
            val smsGranted = grants[Manifest.permission.SEND_SMS] == true

            if (smsGranted && number != null) {
                if (grants[Manifest.permission.READ_PHONE_STATE] != true) {
                    Log.d(TAG, "READ_PHONE_STATE denied - dual-SIM rescue off; default SIM will be used")
                }
                saveEmergencyContact(number)
            } else {
                // Denied -> do NOT save. Tell the user exactly why the feature is unavailable.
                Toast.makeText(
                    applicationContext,
                    getString(R.string.sms_permission_required),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 🚀 CRITICAL FIX: BREAK INFINITE RECREATION LOOP
        try {
            val settingsPrefs = getSharedPreferences("AegisSettings", Context.MODE_PRIVATE)
            val isDarkMode = settingsPrefs.getBoolean("dark_mode", true)
            val targetMode = if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO

            if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
                AppCompatDelegate.setDefaultNightMode(targetMode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply dark mode", e)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        AegisMotion.applyPressToButtons(findViewById(android.R.id.content))
        
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        
        setupListeners()
        setupDeleteAccount()
    }

    override fun onResume() {
        super.onResume()
        loadUserData()
        syncPreferences()
        updateFamilyContactStatus()
    }

    /** Reflects the currently-saved emergency contact in the Family Notification row subtitle. */
    private fun updateFamilyContactStatus() {
        val statusView = findViewById<TextView>(R.id.tvFamilyContactStatus) ?: return
        val editView = findViewById<TextView>(R.id.btnEditFamilyContact)
        val saved = EmergencyContactManager.getSavedNumber(this)
        if (saved.isNullOrBlank()) {
            statusView.text = "Add emergency contact number"
            editView?.visibility = View.GONE
        } else {
            statusView.text = "Alerts sent to $saved"
            editView?.visibility = View.VISIBLE
        }
    }

    /**
     * Prompts for the emergency contact number, validates it, then saves it both locally
     * (SharedPreferences) and to the user's Firestore profile.
     */
    private fun showFamilyNotificationDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_emergency_contact, null)
        val til = dialogView.findViewById<TextInputLayout>(R.id.tilEmergencyNumber)
        val input = dialogView.findViewById<TextInputEditText>(R.id.etEmergencyNumber)

        // Pre-fill with any existing number so the user can review / edit it.
        EmergencyContactManager.getSavedNumber(this)?.let { input.setText(it) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Add Emergency Contact Number")
            .setView(dialogView)
            .setPositiveButton("SAVE", null) // overridden below to control dismissal
            .setNegativeButton("CANCEL", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = input.text?.toString()?.trim().orEmpty()

                if (!EmergencyContactManager.isValidPhoneNumber(entered)) {
                    til.error = "Enter a valid phone number (include country code)"
                    return@setOnClickListener
                }
                til.error = null
                dialog.dismiss()

                // Gate the save on SEND_SMS: an emergency contact we can never text is a false
                // sense of security, so the permission is requested at the moment of saving.
                // READ_PHONE_STATE rides along in the same prompt so the dual-SIM rescue is
                // available too; it is requested here rather than mid-drive, because a system
                // dialog must never appear while the driver is at the wheel.
                val missing = EMERGENCY_PERMISSIONS.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }

                if (missing.isEmpty()) {
                    saveEmergencyContact(entered)
                } else {
                    pendingEmergencyNumber = entered
                    smsPermissionLauncher.launch(missing.toTypedArray())
                }
            }
        }
        dialog.show()
    }

    private fun saveEmergencyContact(number: String) {
        // 1. Persist locally first so the feature works offline immediately. This is a synchronous
        //    commit, so by the time it returns the number is genuinely on disk and cannot be lost
        //    by a later crash or background kill.
        if (!EmergencyContactManager.saveLocal(this, number)) {
            // Storage refused the write. Say so loudly rather than showing "saved" over a number
            // that is not actually there - a silently stale emergency contact is worse than none.
            Log.e(TAG, "Emergency contact could not be written to local storage")
            Toast.makeText(
                applicationContext,
                getString(R.string.sms_contact_save_failed),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        updateFamilyContactStatus()

        // 2. Sync to Firebase under the authenticated user's profile.
        EmergencyContactManager.syncToFirebase(
            number = number,
            onSuccess = {
                Toast.makeText(applicationContext, "Emergency contact saved", Toast.LENGTH_LONG).show()
            },
            onFailure = { e ->
                Log.e(TAG, "Emergency contact cloud sync failed", e)
                Toast.makeText(
                    applicationContext,
                    "Saved on device, but cloud sync failed",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun syncPreferences() {
        val uid = auth.currentUser?.uid ?: return
        val userRef = db.collection("users").document(uid)

        // 🚀 READ: Hydrate switches from Cloud Preferences map
        prefListener?.remove()
        prefListener = userRef.addSnapshotListener { doc, _ ->
            if (doc != null && doc.exists()) {
                val prefs = doc.get("preferences") as? Map<*, *>
                
                runOnUiThread {
                    findViewById<MaterialSwitch>(R.id.switchDarkMode)?.apply {
                        val isEnabled = prefs?.get("darkModeEnabled") as? Boolean ?: true
                        if (isChecked != isEnabled) isChecked = isEnabled
                    }
                    findViewById<MaterialSwitch>(R.id.switchAlertSound)?.apply {
                        val isEnabled = prefs?.get("audibleAlertsEnabled") as? Boolean ?: true
                        if (isChecked != isEnabled) isChecked = isEnabled
                    }
                    findViewById<MaterialSwitch>(R.id.switchVibration)?.apply {
                        val isEnabled = prefs?.get("hapticFeedbackEnabled") as? Boolean ?: true
                        if (isChecked != isEnabled) isChecked = isEnabled
                    }
                }
            }
        }
    }

    private fun setupDeleteAccount() {
        findViewById<View>(R.id.btnLogout)?.setOnLongClickListener {
            showDeleteAccountDialog()
            true
        }
    }

    private fun showDeleteAccountDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("PERMANENT DELETION")
            .setMessage("This will archive your data and terminate your identity. This cannot be undone.")
            .setPositiveButton("DELETE EVERYTHING") { _, _ ->
                performSoftDelete()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun performSoftDelete() {
        val user = auth.currentUser ?: return
        val uid = user.uid

        db.collection("users").document(uid).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val data = document.data?.toMutableMap() ?: mutableMapOf<String, Any>()
                    data["deletedAt"] = FieldValue.serverTimestamp()
                    data["originalUid"] = uid

                    db.collection("DeletedUserRecord").add(data)
                        .addOnSuccessListener {
                            db.collection("users").document(uid).delete()
                                .addOnSuccessListener {
                                    user.delete().addOnCompleteListener { task ->
                                        if (task.isSuccessful) {
                                            getSharedPreferences("AegisProfile", Context.MODE_PRIVATE).edit().clear().apply()
                                            getSharedPreferences("AegisData", Context.MODE_PRIVATE).edit().clear().apply()
                                            startActivity(Intent(this, LoginActivity::class.java).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                            })
                                            finishAffinity()
                                        }
                                    }
                                }
                        }
                }
            }
    }

    private fun loadUserData() {
        try {
            val currentUser = auth.currentUser ?: return
            findViewById<TextView>(R.id.tvProfileEmail)?.text = currentUser.email ?: "No Email"

            profileListener?.remove()
            profileListener = db.collection("users").document(currentUser.uid)
                .addSnapshotListener { document, e ->
                    if (e != null) {
                        Log.w(TAG, "Listen failed.", e)
                        return@addSnapshotListener
                    }
                    if (document != null && document.exists()) {
                        val name = document.getString("name") ?: document.getString("displayName") ?: "Operator"
                        findViewById<TextView>(R.id.tvProfileName)?.text = name

                        // 🚀 MASTER FIX: READ STATS DIRECTLY FROM ROOT
                        val totalDrives = document.getLong("totalDrives") ?: 0L
                        val totalDuration = document.getLong("totalDuration") ?: 0L
                        val scoreSum = document.getLong("lifetimeScoreSum") ?: 0L

                        val safetyPercent = if (totalDrives > 0) (scoreSum.toDouble() / totalDrives) else 100.0
                        
                        // 🚀 DYNAMIC TIME FORMATTING (H M S)
                        val h = totalDuration / 3600
                        val m = (totalDuration % 3600) / 60
                        val s = totalDuration % 60

                        val driveTimeText = when {
                            h > 0 -> String.format(java.util.Locale.US, "%dh %dm %ds", h, m, s)
                            m > 0 -> String.format(java.util.Locale.US, "%dm %ds", m, s)
                            else -> String.format(java.util.Locale.US, "%ds", s)
                        }

                        runOnUiThread {
                            findViewById<TextView>(R.id.tvStatTrips)?.text = totalDrives.toString()
                            findViewById<TextView>(R.id.tvStatDriveTime)?.text = driveTimeText
                            findViewById<TextView>(R.id.tvStatSafety)?.text = String.format(Locale.US, "%.1f%%", safetyPercent)
                        }
                    }
                }

            // 🚀 LOAD SAVED AVATAR FROM LOCAL STORAGE
            try {
                val profilePrefs = getSharedPreferences("AegisProfile", Context.MODE_PRIVATE)
                val imagePath = profilePrefs.getString("user_image_path", null)
                if (imagePath != null) {
                    val uri = Uri.parse(imagePath)
                    val path = uri.path // Robustly extract path from URI string
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) {
                            val ivAvatar = findViewById<ImageView>(R.id.ivProfileAvatar)
                            ivAvatar?.setImageURI(Uri.fromFile(file))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load avatar", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Profile load failed", e)
        }
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        findViewById<View>(R.id.editBtn)?.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }

        val uid = auth.currentUser?.uid ?: return
        val userRef = db.collection("users").document(uid)
        val prefs = getSharedPreferences("AegisSettings", Context.MODE_PRIVATE)

        findViewById<MaterialSwitch>(R.id.switchAlertSound)?.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                userRef.update("preferences.audibleAlertsEnabled", isChecked)
                prefs.edit().putBoolean("alert_sound", isChecked).apply()
            }
        }

        findViewById<MaterialSwitch>(R.id.switchVibration)?.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                userRef.update("preferences.hapticFeedbackEnabled", isChecked)
                prefs.edit().putBoolean("vibration", isChecked).apply()
            }
        }

        findViewById<MaterialSwitch>(R.id.switchDarkMode)?.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                try {
                    userRef.update("preferences.darkModeEnabled", isChecked)
                    prefs.edit().putBoolean("dark_mode", isChecked).apply()
                    val targetMode = if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                    if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
                        AppCompatDelegate.setDefaultNightMode(targetMode)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Theme switch failed", e)
                }
            }
        }

        findViewById<View>(R.id.rowFamilyNotification)?.setOnClickListener {
            showFamilyNotificationDialog()
        }
        findViewById<View>(R.id.btnEditFamilyContact)?.setOnClickListener {
            showFamilyNotificationDialog()
        }

        findViewById<View>(R.id.rowAbout)?.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Aegis Drive Intelligence")
                .setMessage("Aegis Drive is an offline-first digital guardian that uses Edge AI to detect driver fatigue in real-time. It combines proactive safety alerts with premium navigation to ensure a secure journey.")
                .setPositiveButton("CLOSE", null)
                .show()
        }

        findViewById<View>(R.id.btnLogout)?.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Terminate Session?")
                .setPositiveButton("LOGOUT") { _, _ ->
                    auth.signOut()
                    val intent = Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finishAffinity()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        profileListener?.remove()
        prefListener?.remove()
    }

    companion object {
        /**
         * Requested together when the emergency contact is saved. Order matters only for the
         * dialog sequence; [smsPermissionLauncher] judges each grant on its own merits.
         */
        private val EMERGENCY_PERMISSIONS = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
    }
}
