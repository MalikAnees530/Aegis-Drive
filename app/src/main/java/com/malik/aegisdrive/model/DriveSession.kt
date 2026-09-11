package com.malik.aegisdrive.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class DriveSession(
    @get:Exclude var id: String = "",
    val userId: String = "",
    val score: Int = 0,
    val duration: Int = 0,
    val alerts: Int = 0,
    val focusLevel: Int = 0,
    val timestamp: Timestamp? = null,
    val startTime: Timestamp? = null,
    val dateString: String = ""
)
