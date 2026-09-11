package com.malik.aegisdrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.io.File
import java.io.FileOutputStream

class EditProfileActivity : AppCompatActivity() {

    private lateinit var ivAvatarEdit: ImageView
    private lateinit var etName: EditText
    private lateinit var etEmailRead: EditText
    private var selectedImageUri: Uri? = null
    private var profileListener: ListenerRegistration? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val internalUri = saveImageToInternalStorage(uri)
            if (internalUri != null) {
                selectedImageUri = internalUri
                ivAvatarEdit.setImageURI(internalUri)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            pickImageLauncher.launch("image/*")
        } else {
            AegisNotify.show(this, "Permission denied for gallery access", AegisNotify.Type.WARNING)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ... (rest of onCreate)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)
        AegisMotion.applyPressToButtons(findViewById(android.R.id.content))

        ivAvatarEdit = findViewById(R.id.ivAvatarEdit)
        etName = findViewById(R.id.etName)
        etEmailRead = findViewById(R.id.etEmailRead)

        setupListeners()
        loadCurrentProfile()
    }

    private fun loadCurrentProfile() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        // 🚀 TACTICAL FIX: Prioritize 'name' field
        profileListener?.remove()
        profileListener = FirebaseFirestore.getInstance().collection("users").document(uid)
            .addSnapshotListener { document, e ->
                if (e != null) {
                    Log.w("EditProfile", "Listen failed.", e)
                    return@addSnapshotListener
                }
                if (document != null && document.exists()) {
                    val name = document.getString("name") ?: document.getString("displayName") ?: ""
                    etName.setText(name)
                    
                    // Hydrate from Firestore if available
                    val cloudImagePath = document.getString("profileImageUrl")
                    if (cloudImagePath != null && selectedImageUri == null) {
                        val file = File(Uri.parse(cloudImagePath).path ?: "")
                        if (file.exists()) ivAvatarEdit.setImageURI(Uri.fromFile(file))
                    }
                }
            }
        
        etEmailRead.setText(FirebaseAuth.getInstance().currentUser?.email ?: "Not Authenticated")

        // Load Local Avatar path fallback
        val profilePrefs = getSharedPreferences("AegisProfile", Context.MODE_PRIVATE)
        val imagePath = profilePrefs.getString("user_image_path", null)
        if (imagePath != null && selectedImageUri == null) {
            val file = File(Uri.parse(imagePath).path ?: "")
            if (file.exists()) {
                ivAvatarEdit.setImageURI(Uri.fromFile(file))
            }
        }
    }

    private fun setupListeners() {
        findViewById<MaterialCardView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<MaterialCardView>(R.id.btnChangeAvatar).setOnClickListener { 
            val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pickImageLauncher.launch("image/*")
            } else {
                requestPermissionLauncher.launch(permission)
            }
        }
        findViewById<MaterialButton>(R.id.btnSaveProfile).setOnClickListener { saveProfile() }
        findViewById<MaterialButton>(R.id.btnDeleteAccount).setOnClickListener { confirmAccountDeletion() }
    }

    private fun saveImageToInternalStorage(uri: Uri): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val file = File(filesDir, "profile_picture.jpg")
            val outputStream = FileOutputStream(file)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            Uri.fromFile(file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveProfile() {
        val newName = etName.text.toString().trim()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        
        if (newName.isEmpty()) {
            AegisNotify.show(this, "Name cannot be empty", AegisNotify.Type.WARNING)
            return
        }

        val updates = hashMapOf<String, Any>(
            "name" to newName
        )
        
        selectedImageUri?.let { uri ->
            updates["profileImageUrl"] = uri.toString()
            // Persist to SharedPreferences for offline speed
            val profilePrefs = getSharedPreferences("AegisProfile", Context.MODE_PRIVATE)
            profilePrefs.edit().putString("user_image_path", uri.toString()).apply()
        }

        // 🚀 UPDATE PIVOT: Target 'name' and 'profileImageUrl'
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .update(updates)
            .addOnSuccessListener {
                AegisNotify.show(this, "Aegis Profile Hydrated", AegisNotify.Type.SUCCESS)
                finish()
            }
            .addOnFailureListener { e ->
                Log.e("FIREBASE_CRASH", "Profile update failed: ${e.message}")
                AegisNotify.show(this, "Update Failed", AegisNotify.Type.ERROR)
            }
    }

    private fun confirmAccountDeletion() {
        MaterialAlertDialogBuilder(this)
            .setTitle("CRITICAL: Delete Account?")
            .setMessage("This action is irreversible. All your data will be permanently erased.\n\nAre you sure?")
            .setPositiveButton("DELETE EVERYTHING") { _, _ -> deleteAccountData() }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun deleteAccountData() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        
        user.delete().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                // Clear Local Data
                getSharedPreferences("AegisProfile", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("AegisData", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("AegisSettings", Context.MODE_PRIVATE).edit().clear().apply()
                
                // Firestore cleanup
                FirebaseFirestore.getInstance().collection("users").document(user.uid).delete()

                AegisNotify.show(this, "Aegis Identity Purged", AegisNotify.Type.SUCCESS)

                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finishAffinity()
            } else {
                val exception = task.exception
                if (exception is com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException) {
                    val snackbar = Snackbar.make(findViewById(android.R.id.content), 
                        "Security: Please logout and login again to delete account.", Snackbar.LENGTH_LONG)
                    snackbar.show()
                } else {
                    AegisNotify.show(this, "Purge Failed: ${exception?.localizedMessage}", AegisNotify.Type.ERROR)
                }
            }
        }
    }
}
