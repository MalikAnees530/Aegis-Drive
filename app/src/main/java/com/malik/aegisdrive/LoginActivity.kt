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
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var loginProgressBar: ProgressBar
    private lateinit var btnLogin: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        AegisMotion.applyPressToButtons(findViewById(android.R.id.content))

        // Ensure Firebase is initialized within the activity lifecycle
        com.google.firebase.FirebaseApp.initializeApp(this)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val emailLayout = findViewById<TextInputLayout>(R.id.emailInputLayout)
        val passwordLayout = findViewById<TextInputLayout>(R.id.passwordInputLayout)
        val etEmail = findViewById<TextInputEditText>(R.id.etEmail)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        val btnSignUp = findViewById<TextView>(R.id.tvGoToSignUp)
        val tvForgotPassword = findViewById<TextView>(R.id.tvForgotPassword)
        loginProgressBar = findViewById(R.id.loginProgressBar)

        tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        btnLogin.setOnClickListener {
            emailLayout.error = null
            passwordLayout.error = null

            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            var isValid = true
            if (email.isEmpty()) {
                emailLayout.error = "Identity email required"
                isValid = false
            } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailLayout.error = "Invalid identity format"
                isValid = false
            }

            if (password.isEmpty()) {
                passwordLayout.error = "Access key required"
                isValid = false
            }

            if (isValid) {
                hideKeyboard()
                performLogin(email, password)
            }
        }

        btnSignUp.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
    }

    private fun performLogin(email: String, password: String) {
        setLoading(true)
        
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    user?.let {
                        // 🚀 SYNC: Update visible lastLogin in Firestore
                        db.collection("users").document(it.uid)
                            .update("lastLogin", FieldValue.serverTimestamp())
                            .addOnSuccessListener {
                                // PHASE 2: ENTERPRISE ANALYTICS - Increment active_logins_today
                                db.collection("SystemAnalytics").document("counters")
                                    .update("active_logins_today", FieldValue.increment(1))
                                    .addOnFailureListener {
                                        db.collection("SystemAnalytics").document("counters")
                                            .set(hashMapOf("active_logins_today" to 1), com.google.firebase.firestore.SetOptions.merge())
                                    }

                                setLoading(false)
                                startActivity(Intent(this, MainActivity::class.java))
                                finish()
                            }
                            .addOnFailureListener { e ->
                                setLoading(false)
                                showErrorSnackbar("Sync Error: ${e.localizedMessage}")
                            }
                    }
                } else {
                    setLoading(false)
                    val exception = task.exception
                    val message = if (exception is FirebaseAuthException) {
                        "Access Denied: ${exception.message}"
                    } else {
                        exception?.localizedMessage ?: "Authentication failed"
                    }
                    showErrorSnackbar(message)
                }
            }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            btnLogin.text = ""
            btnLogin.isEnabled = false
            loginProgressBar.visibility = View.VISIBLE
        } else {
            btnLogin.text = "ACCESS PORTAL"
            btnLogin.isEnabled = true
            loginProgressBar.visibility = View.GONE
        }
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
