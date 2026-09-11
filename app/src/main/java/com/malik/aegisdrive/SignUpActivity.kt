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

class SignUpActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var signUpProgressBar: ProgressBar
    private lateinit var btnSignUp: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)
        AegisMotion.applyPressToButtons(findViewById(android.R.id.content))

        // Ensure Firebase is initialized within the activity lifecycle
        com.google.firebase.FirebaseApp.initializeApp(this)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val nameLayout = findViewById<TextInputLayout>(R.id.nameInputLayout)
        val emailLayout = findViewById<TextInputLayout>(R.id.emailInputLayout)
        val passwordLayout = findViewById<TextInputLayout>(R.id.passwordInputLayout)
        val etFullName = findViewById<TextInputEditText>(R.id.etFullName)
        val etEmail = findViewById<TextInputEditText>(R.id.etEmail)
        val etPassword = findViewById<TextInputEditText>(R.id.etPassword)
        btnSignUp = findViewById(R.id.btnSignUp)
        val tvGoToLogin = findViewById<TextView>(R.id.tvGoToLogin)
        signUpProgressBar = findViewById(R.id.signUpProgressBar)

        btnSignUp.setOnClickListener {
            nameLayout.error = null
            emailLayout.error = null
            passwordLayout.error = null

            val name = etFullName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            var isValid = true
            if (name.isEmpty()) {
                nameLayout.error = "Operator name required"
                isValid = false
            }
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailLayout.error = "Valid email required"
                isValid = false
            }
            if (password.length < 8) {
                passwordLayout.error = "Min 8 characters required"
                isValid = false
            }

            if (isValid) {
                hideKeyboard()
                performSignUp(name, email, password)
            }
        }

        tvGoToLogin.setOnClickListener { finish() }
    }

    private fun performSignUp(name: String, email: String, password: String) {
        setLoading(true)
        
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    user?.let { authenticatedUser ->
                        val uid = authenticatedUser.uid
                        // 🚀 SYNC: Create visible document in 'users' collection
                        val userMap = hashMapOf(
                            "name" to name,
                            "email" to email,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "lastLogin" to FieldValue.serverTimestamp(),
                            "totalDrives" to 0,
                            "averageScore" to 100
                        )

                        db.collection("users").document(uid)
                            .set(userMap)
                            .addOnSuccessListener {
                                // 🚀 Initialize User-Specific Lifetime Analytics
                                val initialAnalytics = mapOf(
                                    "totalDrives" to 0,
                                    "totalDuration" to 0,
                                    "lifetimeScoreSum" to 0
                                )
                                db.collection("SystemAnalytics").document(uid).set(initialAnalytics)

                                // PHASE 2: ENTERPRISE ANALYTICS - Increment global total_users
                                db.collection("SystemAnalytics").document("counters")
                                    .update("total_users", FieldValue.increment(1))
                                    .addOnFailureListener {
                                        db.collection("SystemAnalytics").document("counters")
                                            .set(hashMapOf("total_users" to 1), com.google.firebase.firestore.SetOptions.merge())
                                    }

                                setLoading(false)
                                AegisNotify.show(this, "Identity Initialized!", AegisNotify.Type.SUCCESS)
                                startActivity(Intent(this, MainActivity::class.java))
                                finishAffinity()
                            }
                            .addOnFailureListener { e ->
                                setLoading(false)
                                showErrorSnackbar("Firestore Error: ${e.localizedMessage}")
                            }
                    }
                } else {
                    setLoading(false)
                    val exception = task.exception
                    val message = if (exception is FirebaseAuthException) {
                        "Identity Error: ${exception.message}"
                    } else {
                        exception?.localizedMessage ?: "Registration failed"
                    }
                    showErrorSnackbar(message)
                }
            }
    }

    private fun setLoading(isLoading: Boolean) {
        if (isLoading) {
            btnSignUp.text = ""
            btnSignUp.isEnabled = false
            signUpProgressBar.visibility = View.VISIBLE
        } else {
            btnSignUp.text = "INITIALIZE IDENTITY"
            btnSignUp.isEnabled = true
            signUpProgressBar.visibility = View.GONE
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
