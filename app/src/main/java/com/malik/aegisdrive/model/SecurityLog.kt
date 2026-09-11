package com.malik.aegisdrive.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class SecurityLog(
    val eventType: String = "", // PASSWORD_RESET, ACCOUNT_DELETION, NEW_SIGNUP
    val userId: String = "",
    val timestamp: Timestamp = Timestamp.now(),
    val platform: String = "Android"
)
