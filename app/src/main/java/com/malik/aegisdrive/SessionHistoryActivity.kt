package com.malik.aegisdrive

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import com.malik.aegisdrive.model.DriveSession

@SuppressLint("SetTextI18n")
class SessionHistoryActivity : AppCompatActivity() {

    private var historyListener: ListenerRegistration? = null
    private lateinit var rvHistory: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var progressBar: ProgressBar
    private val sessionList = mutableListOf<DriveSession>()
    private lateinit var adapter: SessionHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_history)

        rvHistory = findViewById(R.id.rvHistory)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        progressBar = findViewById(R.id.progressBar)

        findViewById<View>(R.id.btnBack)?.setOnClickListener { finish() }
        findViewById<View>(R.id.btnWipeHistory)?.setOnClickListener { confirmWipeHistory() }

        adapter = SessionHistoryAdapter(sessionList)
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        startHistorySync()
    }

    override fun onStop() {
        super.onStop()
        historyListener?.remove()
    }

    override fun onResume() {
        super.onResume()
        applyProgrammaticTheme()
    }

    private fun applyProgrammaticTheme() {
        val prefs = getSharedPreferences("AegisSettings", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", true)

        val root = findViewById<View>(R.id.historyRoot)
        val tvHeader = findViewById<TextView>(R.id.tvHistoryHeader)
        val btnBackCard = findViewById<MaterialCardView>(R.id.btnBack)
        val ivBackArrow = findViewById<ImageView>(R.id.ivBackArrow)

        val bgColor = if (isDarkMode) "#121212" else "#F8F9FA"
        val txtColor = if (isDarkMode) "#FFFFFF" else "#1F1F1F"
        val cardBg = if (isDarkMode) "#1E1E1E" else "#FFFFFF"
        val strokeColor = if (isDarkMode) "#3C4043" else "#DADCE0"

        root?.setBackgroundColor(Color.parseColor(bgColor))
        tvHeader?.setTextColor(Color.parseColor(txtColor))
        btnBackCard?.setCardBackgroundColor(Color.parseColor(cardBg))
        btnBackCard?.setStrokeColor(Color.parseColor(strokeColor))
        ivBackArrow?.setColorFilter(Color.parseColor(txtColor))
    }

    private fun startHistorySync() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        progressBar.visibility = View.VISIBLE

        // 🚀 INDEX-FREE SYNC: Flat Schema + Local Sort
        historyListener?.remove()
        historyListener = FirebaseFirestore.getInstance().collection("DriveSessions")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { snapshots, e ->
                progressBar.visibility = View.GONE
                if (e != null) {
                    Log.e("FIREBASE_CRASH", "History sync failed: ${e.message}")
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    val rawList = mutableListOf<DriveSession>()
                    for (doc in snapshots) {
                        try {
                            val session = doc.toObject(DriveSession::class.java)
                            if (session != null) {
                                session.id = doc.id
                                rawList.add(session)
                            }
                        } catch (ex: Exception) {
                            Log.e("History", "Mapping error", ex)
                        }
                    }
                    
                    // 🚀 LOCAL SORTING: Guarantee order without Composite Index
                    val sortedList = rawList.sortedByDescending { it.timestamp ?: it.startTime }
                    
                    sessionList.clear()
                    sessionList.addAll(sortedList)
                    
                    runOnUiThread {
                        adapter.notifyDataSetChanged()
                        tvEmptyState.visibility = if (sessionList.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
    }

    private fun confirmWipeHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Wipe Intelligence History?")
            .setMessage("This will permanently delete all your driving logs from the cloud. This cannot be undone.")
            .setPositiveButton("WIPE EVERYTHING") { _, _ -> performWipe() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun performWipe() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()

        // 🚀 TACTICAL WIPE: Target root collection sessions for this user
        db.collection("DriveSessions").whereEqualTo("userId", uid).get()
            .addOnSuccessListener { snapshots ->
                val batch = db.batch()
                for (doc in snapshots) batch.delete(doc.reference)
                
                // Reset Lifetime Stats in user root
                val userRef = db.collection("users").document(uid)
                batch.update(userRef, mapOf(
                    "totalDrives" to 0L,
                    "totalDuration" to 0L,
                    "lifetimeScoreSum" to 0L
                ))
                
                batch.commit().addOnSuccessListener {
                    AegisNotify.show(this, "Intelligence History Wiped", AegisNotify.Type.SUCCESS)
                }
            }.addOnFailureListener { e ->
                Log.e("FIREBASE_CRASH", "Wipe failed: ${e.message}")
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        historyListener?.remove()
    }
}
