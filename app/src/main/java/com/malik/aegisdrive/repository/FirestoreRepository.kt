package com.malik.aegisdrive.repository

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.malik.aegisdrive.model.*

class FirestoreRepository {
    private val db = FirebaseFirestore.getInstance()

    fun createUserProfile(uid: String, email: String, name: String) {
        val user = UserProfile(
            email = email, 
            name = name, 
            displayName = name,
            totalDrives = 0,
            totalDuration = 0,
            lifetimeScoreSum = 0
        )
        db.collection("users").document(uid).set(user)
            .addOnSuccessListener { Log.d("AegisRepo", "Profile Created") }
            .addOnFailureListener { Log.e("AegisRepo", "Signup Sync Failed", it) }
    }

    fun saveDriveSession(uid: String, session: DriveSession) {
        // 🚀 TACTICAL PIVOT: Flat Schema logic
        val batch = db.batch()
        val sessionRef = db.collection("DriveSessions").document()
        val userRef = db.collection("users").document(uid)

        val sessionData = hashMapOf(
            "userId" to uid,
            "score" to session.score,
            "alerts" to session.alerts,
            "duration" to session.duration,
            "focusLevel" to session.focusLevel,
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "startTime" to session.startTime,
            "dateString" to session.dateString
        )

        batch.set(sessionRef, sessionData)
        batch.update(userRef, mapOf(
            "totalDrives" to com.google.firebase.firestore.FieldValue.increment(1),
            "totalDuration" to com.google.firebase.firestore.FieldValue.increment(session.duration.toLong()),
            "lifetimeScoreSum" to com.google.firebase.firestore.FieldValue.increment(session.score.toLong())
        ))

        batch.commit()
            .addOnSuccessListener { Log.d("AegisRepo", "Session Saved via Batch") }
            .addOnFailureListener { Log.e("AegisRepo", "Session Batch Failed", it) }
    }
}
