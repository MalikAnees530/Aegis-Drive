package com.malik.aegisdrive

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import java.util.Calendar
import java.util.Locale

@SuppressLint("SetTextI18n", "CommitPrefEdits")
class HomeFragment : Fragment() {

    private val TAG = "HomeFragment"
    private var profileListener: ListenerRegistration? = null
    private var sessionListener: ListenerRegistration? = null
    private var latestSessionId: String = ""
    // Play the score count-up / progress-fill only on the first data render, not every snapshot.
    private var animatedScoreOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Premium fade-through when switching to the Home tab.
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = 250L }
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = 250L }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AegisMotion.applyPressToButtons(view)
        
        try {
            setupClickListeners(view)
            checkAIModelStatus(view)
        } catch (e: Exception) {
            Log.e(TAG, "onViewCreated failed", e)
        }
    }

    override fun onStart() {
        super.onStart()
        view?.let { startRealTimeSync(it) }
    }

    override fun onStop() {
        super.onStop()
        profileListener?.remove()
        sessionListener?.remove()
    }

    private fun startRealTimeSync(view: View) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()

        // 🚀 LISTENER 1: LIFETIME STATS (ROOT USER DOC)
        profileListener?.remove()
        profileListener = db.collection("users").document(uid).addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w(TAG, "Profile sync failed.", e)
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists() && isAdded) {
                val name = snapshot.getString("name") ?: snapshot.getString("displayName") ?: "Operator"
                val totalDrives = snapshot.getLong("totalDrives") ?: 0L
                val scoreSum = snapshot.getLong("lifetimeScoreSum") ?: 0L
                
                // Calculate Safety Rating
                val rating = if (totalDrives > 0) (scoreSum.toDouble() / totalDrives) else 100.0

                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val greeting = when (hour) {
                    in 0..11 -> "Good Morning"
                    in 12..16 -> "Good Afternoon"
                    in 17..20 -> "Good Evening"
                    else -> "Good Night"
                }
                
                activity?.runOnUiThread {
                    view.findViewById<TextView>(R.id.tvGreeting)?.text = "$greeting, $name"
                    view.findViewById<TextView>(R.id.tvRatingValue)?.text = String.format(Locale.US, "%.1f%%", rating)
                }
            }
        }

        // 🚀 LISTENER 2: INDEX-FREE LATEST SESSION (LOCAL SORT)
        sessionListener?.remove()
        sessionListener = db.collection("DriveSessions")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("FIREBASE_CRASH", "Session sync failed: ${e.message}")
                    return@addSnapshotListener
                }

                if (snapshots != null && isAdded) {
                    // 🚀 LOCAL SORTING: Bypass Composite Index requirement
                    val sortedDocs = snapshots.documents.sortedByDescending { 
                        it.getTimestamp("timestamp") ?: it.getTimestamp("startTime") 
                    }

                    if (sortedDocs.isEmpty()) {
                        activity?.runOnUiThread {
                            updateUIComponents(view, 0, 0, 0, 100, "No driving history")
                        }
                    } else {
                        val last = sortedDocs[0]
                        latestSessionId = last.id 
                        
                        try {
                            val score = last.getLong("score")?.toInt() ?: 100
                            val alerts = last.getLong("alerts")?.toInt() ?: 0
                            val duration = last.getLong("duration")?.toInt() ?: 0
                            val focus = last.getLong("focusLevel")?.toInt() ?: 100
                            val date = last.getString("dateString") ?: "Recent Session"

                            activity?.runOnUiThread {
                                updateUIComponents(view, score, alerts, duration, focus, date)
                            }
                        } catch (ex: Exception) {
                            Log.e("Aegis", "Data parse error", ex)
                        }
                    }
                }
            }
    }

    private fun updateUIComponents(view: View, score: Int, alerts: Int, seconds: Int, focus: Int, date: String) {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        
        val formattedDuration = if (h > 0) String.format(Locale.US, "%dh %dm %ds", h, m, s) else String.format(Locale.US, "%dm %ds", m, s)

        view.findViewById<TextView>(R.id.tvDriveTimeValue)?.text = formattedDuration
        view.findViewById<TextView>(R.id.tvAlertsValue)?.text = alerts.toString()
        view.findViewById<TextView>(R.id.tvFocusValue)?.text = "$focus%"

        val tvHistoryTitle = view.findViewById<TextView>(R.id.tvHistoryTitle)
        val tvHistoryDate = view.findViewById<TextView>(R.id.tvHistoryDate)
        
        if (date != "No driving history") {
            tvHistoryTitle?.text = "Latest Session: $score%"
            tvHistoryDate?.text = date
        } else {
            tvHistoryTitle?.text = "No Recent Sessions"
            tvHistoryDate?.text = "Complete a drive to see data."
        }

        val colorHex = if (score > 75) "#6ABF69" else if (score > 45) "#FFB74D" else "#EF5350"
        val parsedColor = colorHex.toColorInt()
        
        val tvScore = view.findViewById<TextView>(R.id.tvScoreValue)
        tvScore?.setTextColor(parsedColor)
        val pb = view.findViewById<ProgressBar>(R.id.scoreProgress)
        pb?.progressTintList = ColorStateList.valueOf(parsedColor)
        // Animate the count-up + progress fill on first render; set directly thereafter.
        if (!animatedScoreOnce) {
            animatedScoreOnce = true
            tvScore?.let { AegisMotion.countUp(it, score) }
            pb?.let { AegisMotion.progressTo(it, score) }
        } else {
            tvScore?.text = score.toString()
            pb?.progress = score
        }

        val tvStatus = view.findViewById<TextView>(R.id.tvStatusText)
        tvStatus?.text = if (score > 75) "SAFE" else if (score > 45) "WARNING" else "DANGER"
        tvStatus?.setTextColor(parsedColor)
        view.findViewById<View>(R.id.statusIndicator)?.backgroundTintList = ColorStateList.valueOf(parsedColor)
    }

    private fun checkAIModelStatus(view: View) {
        val tvAIStatus = view.findViewById<TextView>(R.id.tvAIStatus)
        val tvAIStatusMessage = view.findViewById<TextView>(R.id.tvAIStatusMessage)
        try {
            val assetFiles = requireContext().assets.list("")
            val hasLSTM = assetFiles?.contains("aegis_drive_model.tflite") == true
            val hasFaceMesh = assetFiles?.contains("face_landmarker.task") == true

            if (hasLSTM && hasFaceMesh) {
                tvAIStatus?.text = "ONLINE"
                tvAIStatus?.setTextColor("#6ABF69".toColorInt())
                tvAIStatusMessage?.text = "Shields active • Models Loaded"
            } else {
                tvAIStatus?.text = "OFFLINE"
                tvAIStatus?.setTextColor("#EF5350".toColorInt())
                tvAIStatusMessage?.text = "Models Missing"
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI Model check failed", e)
        }
    }

    private fun setupClickListeners(view: View) {
        view.findViewById<MaterialButton>(R.id.btnStartMonitoring)?.setOnClickListener {
            val bottomNav = activity?.findViewById<BottomNavigationView>(R.id.bottomNavigationView)
            bottomNav?.selectedItemId = R.id.monitorFragment
        }
        view.findViewById<MaterialCardView>(R.id.btnSettings)?.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
        view.findViewById<MaterialCardView>(R.id.btnNotif)?.setOnClickListener {
            try {
                val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_security_logs, null)
                val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.CustomDialogTheme)
                    .setView(dialogView)
                    .create()
                dialogView.findViewById<View>(R.id.btnConfirm)?.setOnClickListener { dialog.dismiss() }
                dialog.show()
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            } catch (e: Exception) {
                Log.e(TAG, "Notification dialog inflation failed", e)
            }
        }
        view.findViewById<TextView>(R.id.btnViewAllSessions)?.setOnClickListener {
            startActivity(Intent(requireContext(), SessionHistoryActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }

        view.findViewById<View>(R.id.cardRecentSession)?.setOnClickListener {
            if (latestSessionId.isNotEmpty()) {
                SessionDetailBottomSheet(latestSessionId).show(parentFragmentManager, "SessionDetail")
            } else {
                AegisNotify.show(requireContext(), "No session intelligence available.", AegisNotify.Type.INFO)
            }
        }
    }
}
