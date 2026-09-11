package com.malik.aegisdrive

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var resetProgressBar: ProgressBar
    private lateinit var btnSendReset: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)
        AegisMotion.applyPressToButtons(findViewById(android.R.id.content))

        // Ensure Firebase is initialized within the activity lifecycle
        com.google.firebase.FirebaseApp.initializeApp(this)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val emailLayout = findViewById<TextInputLayout>(R.id.emailInputLayout)
        val etEmail = findViewById<TextInputEditText>(R.id.etEmail)
        btnSendReset = findViewById(R.id.btnSendReset)
        val tvBackToLogin = findViewById<TextView>(R.id.tvBackToLogin)
        resetProgressBar = findViewById(R.id.resetProgressBar)

        btnSendReset.setOnClickListener {
            emailLayout.error = null
            val email = etEmail.text.toString().trim()

            if (email.isEmpty()) {
                emailLayout.error = "Identity email required"
            } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailLayout.error = "Invalid identity format"
            } else {
                hideKeyboard()
                performPasswordReset(email)
            }
        }

        tvBackToLogin.setOnClickListener {
            finish()
        }
    }

    private fun performPasswordReset(email: String) {
        setLoading(true)
        
        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // PHASE 4: FIRESTORE ANALYTICS - Audit trail for reset requests
                    val auditMap = hashMapOf(
                        "email" to email,
                        "timestamp" to FieldValue.serverTimestamp(),
                        "status" to "Reset Link Sent",
                        "platform" to "Android"
                    )

                    db.collection("PasswordResetDetails").add(auditMap)
                        .addOnSuccessListener {
                            setLoading(false)
                            showSuccessSnackbar("Secure reset link sent to your email!")
                            findViewById<TextInputEditText>(R.id.etEmail)?.text?.clear()
                        }
                        .addOnFailureListener { e ->
                            setLoading(false)
                            // Even if analytics fails, the user got their email
                            showSuccessSnackbar("Secure reset link sent to your email!")
                        }
                } else {
                    setLoading(false)
                    val message = task.exception?.localizedMessage ?: "Failed to send reset email"
                    showErrorSnackbar(message)
                }
            }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            btnSendReset.text = ""
            btnSendReset.isEnabled = false
            resetProgressBar.visibility = View.VISIBLE
        } else {
            btnSendReset.text = "SEND SECURE LINK"
            btnSendReset.isEnabled = true
            resetProgressBar.visibility = View.GONE
        }
    }

    private fun showSuccessSnackbar(message: String) {
        val snackbar = Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
        snackbar.setBackgroundTint(resources.getColor(R.color.status_safe, theme))
        snackbar.setTextColor(resources.getColor(R.color.white, theme))
        snackbar.show()
    }

    private fun showErrorSnackbar(message: String) {
        val snackbar = Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
        snackbar.setBackgroundTint(resources.getColor(R.color.error_red, theme))
        snackbar.setTextColor(resources.getColor(R.color.white, theme))
        snackbar.show()
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
}
