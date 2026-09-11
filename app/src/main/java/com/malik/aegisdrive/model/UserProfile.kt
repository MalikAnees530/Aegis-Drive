package com.malik.aegisdrive.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class UserProfile(
    val email: String = "",
    val name: String = "",
    val displayName: String = "",
    val profileImageUrl: String = "",
    val totalDrives: Long = 0,
    val totalDuration: Long = 0,
    val lifetimeScoreSum: Long = 0,
    val accountCreatedAt: Timestamp = Timestamp.now(),
    val preferences: Map<String, Any> = mapOf(
        "darkModeEnabled" to true,
        "audibleAlertsEnabled" to true,
        "hapticFeedbackEnabled" to true
    )
)
