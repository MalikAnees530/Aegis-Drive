package com.malik.aegisdrive

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.transition.MaterialSharedAxis
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

@SuppressLint("SetTextI18n") 
class MonitorFragment : Fragment() {

    private lateinit var cameraPreview: PreviewView
    private lateinit var faceOverlay: FaceOverlayView
    private lateinit var tvTimer: TextView
    private lateinit var tvFPS: TextView
    private lateinit var tvLiveEar: TextView
    private lateinit var tvLiveMar: TextView

    private lateinit var tvStatusOverlay: TextView
    private lateinit var tvDetectionIcon: TextView
    private lateinit var tvDetectionLabel: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvEyeState: TextView
    private lateinit var tvDrowsiness: TextView
    private lateinit var tvAlertCount: TextView
    private lateinit var btnStopMonitor: MaterialButton
    private lateinit var btnMuteAlarm: MaterialButton
    private lateinit var statusOverlay: MaterialCardView
    private lateinit var liveBadgeCard: MaterialCardView
    private lateinit var tvLiveText: TextView
    private lateinit var tvSafetyScore: TextView
    private lateinit var pbSafetyScore: ProgressBar
    private lateinit var warningBanner: MaterialCardView

    private var cameraExecutor: ExecutorService? = null
    private var tfliteInterpreter: Interpreter? = null
    private var faceLandmarker: FaceLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var mediaPlayer: MediaPlayer? = null
    private var isMonitoring = false
    private var safetyScore = 100f
    
    private var alertCount = 0
    private var isAlarmActive = false 
    private var isManuallyMuted = false 
    private var muteTimestamp = 0L
    private var framesWithoutFace = 0
    private var closedEyeFrames = 0
    private var openEyeFrames = 0

    private val sequenceLength = 30
    private val frameSequence = ArrayDeque<FloatArray>(sequenceLength)
    private val predictionHistory = ArrayDeque<Int>(5) 
    
    private val rightEyeIdx = intArrayOf(33, 160, 158, 133, 153, 144)
    private val leftEyeIdx = intArrayOf(362, 385, 387, 263, 373, 380)
    private val lipsIdx = intArrayOf(78, 308, 13, 14)

    private val classLabels = arrayOf("Normal", "Drowsiness (Eye Close)", "Yawning")
    private val lstmModelFile = "aegis_drive_model.tflite"

    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private val timerHandler = Handler(Looper.getMainLooper())
    private var elapsedSeconds = 0
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            elapsedSeconds++
            val h = elapsedSeconds / 3600
            val m = (elapsedSeconds % 3600) / 60
            val s = elapsedSeconds % 60
            tvTimer.text = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
            timerHandler.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val TAG = "AegisDrive"
        private const val CAMERA_PERMISSION_CODE = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enter-only: zoom the HUD in for tab cohesion; no exit transition so the
        // live camera preview is never scaled/faded during teardown. No logic touched.
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = 250L }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_monitor, container, false)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            AegisNotify.show(requireContext(), "Camera permission is required for monitoring", AegisNotify.Type.WARNING)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        AegisMotion.applyPressToButtons(view)
        
        pbSafetyScore.max = 100 

        // 🚀 SAFE PROGRAMMATIC ADDITION: Prevent duplicates and lag
        val parent = cameraPreview.parent as ViewGroup
        var existingOverlay: FaceOverlayView? = null
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i) is FaceOverlayView) {
                existingOverlay = parent.getChildAt(i) as FaceOverlayView
                break
            }
        }

        if (existingOverlay != null) {
            faceOverlay = existingOverlay
        } else {
            faceOverlay = FaceOverlayView(requireContext())
            faceOverlay.layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT
            )
            parent.addView(faceOverlay)
        }
        faceOverlay.bringToFront()
        faceOverlay.elevation = 100f
        
        setupAudioEngine()
        loadAIModels()

        isMonitoring = false
        setUIMonitoringState(false)
        btnMuteAlarm.visibility = View.GONE

        // 🚀 PERMISSION FIX: Only check, don't auto-request on every creation to avoid loop
        if (hasCameraPermission()) startCamera()

        btnStopMonitor.setOnClickListener {
            if (!hasCameraPermission()) {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                return@setOnClickListener
            }
            if (isMonitoring) stopMonitoring() else startMonitoring()
        }

        btnMuteAlarm.setOnClickListener {
            isManuallyMuted = true 
            muteTimestamp = System.currentTimeMillis() 
            stopAudioAlarm()
        }
    }

    private fun setupAudioEngine() {
        try {
            val ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(requireContext(), RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(requireContext(), ringtoneUri)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM) 
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setAudioAttributes(audioAttributes)
                isLooping = true 
                prepare()
            }
        } catch (e: Exception) { 
            Log.e(TAG, "Ringtone Engine Error: ${e.message}")
            try {
                val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer?.setDataSource(requireContext(), alertUri)
                mediaPlayer?.prepare()
            } catch (e2: Exception) { Log.e(TAG, "Complete Audio Failure") }
        }
    }

    private var lastVibrationTime = 0L

    private fun triggerVibration(duration: Long) {
        try {
            val ctx = context ?: return
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vibratorManager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }

            if (vibrator.hasVibrator()) {
                // 🚀 HIGH PRIORITY: Use USAGE_ALARM to bypass certain system restrictions and ensure visibility
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE),
                        audioAttributes
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration Error: ${e.message}")
        }
    }

    private fun playAudioAlarm() {
        val activity = activity ?: return
        if (!isAdded) return
        
        val prefs = activity.getSharedPreferences("AegisSettings", Context.MODE_PRIVATE)
        val soundEnabled = prefs.getBoolean("alert_sound", true)
        val vibrationEnabled = prefs.getBoolean("vibration", true)

        if (vibrationEnabled) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastVibrationTime > 1500) { // Vibrate every 1.5s during danger
                triggerVibration(1000) // 🚀 Increased to 1s for better feedback
                lastVibrationTime = currentTime
            }
        }

        if (!isAlarmActive) {
            isAlarmActive = true
            if (soundEnabled) mediaPlayer?.start()
            
            activity?.runOnUiThread {
                btnMuteAlarm.visibility = View.VISIBLE
                alertCount++
                tvAlertCount.text = alertCount.toString()
                AlertBus.emitAlert(alertCount)
            }
        } else if (soundEnabled && mediaPlayer?.isPlaying == false && !isManuallyMuted) {
            mediaPlayer?.start()
        }
    }

    private fun stopAudioAlarm() {
        isAlarmActive = false
        AlertBus.emitDrowsinessEnded()
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            mediaPlayer?.seekTo(0)
        }
        activity?.runOnUiThread { btnMuteAlarm.visibility = View.GONE }
    }

    override fun onResume() {
        super.onResume()
        if (!isMonitoring) setUIMonitoringState(false)
    }

    override fun onPause() {
        super.onPause()
        stopMonitoring()
    }

    override fun onStop() {
        super.onStop()
        if (isMonitoring) stopMonitoring()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopMonitoring()
        cameraProvider?.unbindAll()
        cameraExecutor?.shutdown()
        cameraExecutor = null
        tfliteInterpreter?.close()
        faceLandmarker?.close()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun startMonitoring() {
        isMonitoring = true
        elapsedSeconds = 0
        alertCount = 0
        safetyScore = 100f
        frameCount = 0
        lastFpsTime = System.currentTimeMillis()
        isManuallyMuted = false
        isAlarmActive = false
        closedEyeFrames = 0
        openEyeFrames = 0
        framesWithoutFace = 0
        frameSequence.clear() 
        predictionHistory.clear()
        faceOverlay.clear()

        tvAlertCount.text = "0"
        tvTimer.text = "00:00:00"

        updateSafetyUI()
        setUIMonitoringState(true)

        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)
    }

    private fun stopMonitoring() {
        if (!isMonitoring) return
        
        // A missing uid only makes the CLOUD SYNC impossible - it must never stop the DRIVE from
        // stopping. Returning outright here skipped every line below, including stopAudioAlarm(),
        // which is the one and only call site of AlertBus.emitDrowsinessEnded(). The 45s
        // unconsciousness timer therefore stayed armed and the bus still believed the episode was
        // running, so the family received a FALSE emergency SMS 45 seconds after the driver
        // pressed STOP - and the alarm kept sounding, because isMonitoring was never cleared.
        // Reachable whenever the session is signed out (token expiry, sign-out elsewhere, account
        // deletion), and onPause() routes through here too, so merely backgrounding the app hit it.
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            isMonitoring = false
            timerHandler.removeCallbacks(timerRunnable)
            setUIMonitoringState(false)
            stopAudioAlarm()
            btnMuteAlarm.visibility = View.GONE
            Log.w(TAG, "No authenticated user - drive stopped locally, session NOT synced")
            return
        }
        val db = FirebaseFirestore.getInstance()
        
        val finalScore = safetyScore.toInt()
        val totalAlerts = alertCount
        val durationSeconds = elapsedSeconds
        val focusLevel = (finalScore - (totalAlerts * 4)).coerceIn(0, 100)

        // 🚀 TACTICAL PIVOT: Flat Schema session data
        val sessionData = hashMapOf(
            "userId" to uid,
            "score" to finalScore,
            "alerts" to totalAlerts,
            "duration" to durationSeconds,
            "focusLevel" to focusLevel,
            "timestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "startTime" to com.google.firebase.Timestamp(Date(System.currentTimeMillis() - (durationSeconds * 1000))),
            "dateString" to SimpleDateFormat("MMM dd, yyyy - hh:mm a", Locale.getDefault()).format(Date())
        )

        // 🚀 BATCH WRITE: Root DriveSessions + User Stats
        val batch = db.batch()
        val sessionRef = db.collection("DriveSessions").document()
        val userRef = db.collection("users").document(uid)

        batch.set(sessionRef, sessionData)

        val userUpdates = hashMapOf(
            "totalDrives" to com.google.firebase.firestore.FieldValue.increment(1),
            "totalDuration" to com.google.firebase.firestore.FieldValue.increment(durationSeconds.toLong()),
            "lifetimeScoreSum" to com.google.firebase.firestore.FieldValue.increment(finalScore.toLong())
        )
        batch.set(userRef, userUpdates, com.google.firebase.firestore.SetOptions.merge())

        batch.commit().addOnSuccessListener {
            Log.d(TAG, "Sync Successful: Root collection updated.")
            // Return the user to the Home tab after a completed drive. MainActivity already hosts
            // these fragments via BottomNavigationView + Nav Component, so switching selectedItemId
            // navigates to HomeFragment (whose Firestore snapshot listeners refresh the stats).
            // Restarting MainActivity here was a no-op — it was already the current instance, so the
            // Monitor tab stayed selected.
            val bottomNav = activity?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigationView)
            bottomNav?.selectedItemId = R.id.homeFragment
        }.addOnFailureListener { e ->
            Log.e("FIREBASE_CRASH", "Batch failed: ${e.message}")
            AegisNotify.show(requireContext(), "Sync Failure: Check Connectivity", AegisNotify.Type.ERROR)
        }

        isMonitoring = false
        timerHandler.removeCallbacks(timerRunnable)
        setUIMonitoringState(false)
        stopAudioAlarm()
        btnMuteAlarm.visibility = View.GONE
        faceOverlay.clear()
        AegisNotify.show(requireContext(), "Telemetry Synchronized", AegisNotify.Type.INFO)
    }

    private fun setUIMonitoringState(isActive: Boolean) {
        if (isActive) {
            btnStopMonitor.text = getString(R.string.stop_monitoring)
            btnStopMonitor.setTextColor("#0F172A".toColorInt())
            btnStopMonitor.backgroundTintList = ColorStateList.valueOf("#38BDF8".toColorInt())
            liveBadgeCard.setCardBackgroundColor(ColorStateList.valueOf("#AA22C55E".toColorInt()))
            tvLiveText.text = getString(R.string.live)
            setStatus(getString(R.string.secure), "#22C55E")
        } else {
            btnStopMonitor.text = getString(R.string.begin_monitoring)
            btnStopMonitor.setTextColor("#0F172A".toColorInt())
            btnStopMonitor.backgroundTintList = ColorStateList.valueOf("#38BDF8".toColorInt())
            liveBadgeCard.setCardBackgroundColor(ColorStateList.valueOf("#AAEF4444".toColorInt()))
            tvLiveText.text = getString(R.string.offline)
            setStatus(getString(R.string.standby), "#94A3B8")
            tvDetectionIcon.text = "–"
            tvDetectionLabel.text = getString(R.string.waiting)
            tvDetectionLabel.setTextColor("#64748B".toColorInt())
            tvConfidence.text = "–"
            tvConfidence.setTextColor("#38BDF8".toColorInt())
            tvEyeState.text = "–"
            tvEyeState.setTextColor("#FFFFFF".toColorInt())
            tvDrowsiness.text = getString(R.string.stable)
            tvDrowsiness.setTextColor("#94A3B8".toColorInt())
            tvLiveEar.text = getString(R.string.eye_openness_default)
            tvLiveMar.text = getString(R.string.mouth_gap_default)
            tvFPS.text = getString(R.string.default_fps)
            tvTimer.text = getString(R.string.default_timer)
            warningBanner.visibility = View.GONE
            
            val scoreInt = safetyScore.toInt()
            tvSafetyScore.text = "$scoreInt%"
            pbSafetyScore.progress = scoreInt
        }
    }

    private fun startCamera() {
        if (cameraExecutor == null || cameraExecutor!!.isShutdown) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val previewResolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder().setResolutionSelector(previewResolution).build().also { it.setSurfaceProvider(cameraPreview.surfaceProvider) }

                val analysisResolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis -> analysis.setAnalyzer(cameraExecutor!!) { imageProxy -> analyzeFrame(imageProxy) } }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) { Log.e(TAG, "Camera bind failed: ${e.message}") }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!isMonitoring) {
            imageProxy.close()
            return
        }

        try {
            frameCount++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000) {
                val fps = frameCount
                frameCount = 0
                lastFpsTime = now
                activity?.runOnUiThread { tvFPS.text = "$fps FPS" }
            }

            val rawBitmap = imageProxy.toBitmap()
            val argbBitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
            
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotatedBitmap = Bitmap.createBitmap(argbBitmap, 0, 0, argbBitmap.width, argbBitmap.height, matrix, true)
            
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val result = faceLandmarker?.detect(mpImage)

            if (result != null && result.faceLandmarks().isNotEmpty()) {
                framesWithoutFace = 0 
                
                val landmarks = result.faceLandmarks()[0]
                val rightEyePoints = rightEyeIdx.map { landmarks[it] }
                val leftEyePoints = leftEyeIdx.map { landmarks[it] }
                val lipPoints = lipsIdx.map { landmarks[it] }

                val rightEar = calculateEAR(rightEyePoints)
                val leftEar = calculateEAR(leftEyePoints)
                val avgEar = (rightEar + leftEar) / 2.0f
                val mar = calculateMAR(lipPoints)

                activity?.runOnUiThread {
                    tvLiveEar.text = String.format(Locale.US, "Eye Openness: %.2f", avgEar)
                    tvLiveMar.text = String.format(Locale.US, "Mouth Gap: %.2f", mar)
                }

                // 🚀 TASK 1: EXTREME PERSPECTIVE IGNORING & ADVANCED THRESHOLDING
                val nose = landmarks[1]
                val topHead = landmarks[10]
                val chin = landmarks[152]
                val leftEyeInner = landmarks[133]
                val rightEyeInner = landmarks[362]

                // 1. Calculate Pitch (Up/Down) & Yaw (Left/Right)
                val upperFace = distance(topHead, nose)
                val lowerFace = distance(nose, chin)
                val pitchRatio = if (upperFace > 0) lowerFace / upperFace else 1.0f

                val leftDist = distance(leftEyeInner, nose)
                val rightDist = distance(rightEyeInner, nose)
                val yawRatio = maxOf(leftDist, rightDist) / minOf(leftDist, rightDist).coerceAtLeast(0.001f)

                // 2. EXTREME ANGLE DEADZONE CHECK
                // If the head is turned extremely far, assume eyes are open to prevent false alarms
                val isExtremeAngle = yawRatio > 2.0f || pitchRatio > 1.6f || pitchRatio < 0.6f

                // 3. ULTRA-PRECISE DYNAMIC THRESHOLD (Even more aggressive drop)
                val dynamicEarThreshold = when {
                    pitchRatio > 1.35f -> 0.06f // Looking heavily upward: drastic drop
                    pitchRatio > 1.20f -> 0.09f // Looking slightly upward
                    yawRatio > 1.6f -> 0.06f    // Extreme Left/Right
                    yawRatio > 1.3f -> 0.10f    // Moderate Left/Right
                    mar > 0.40f -> 0.12f        // Yawning
                    else -> 0.16f               // Normal precise posture
                }

                // 4. INSTANT STATE EVALUATION WITH DEADZONE OVERRIDE
                if (isExtremeAngle) {
                    openEyeFrames++
                    closedEyeFrames = 0
                } else {
                    if (avgEar < dynamicEarThreshold) {
                        closedEyeFrames++
                        openEyeFrames = 0
                    } else {
                        openEyeFrames++
                        closedEyeFrames = 0
                    }
                }

                activity?.runOnUiThread {
                    // 4. INSTANT UI RESPONSE (Trigger on 2 frames for immediate visual feedback)
                    if (closedEyeFrames >= 2) {
                        tvEyeState.text = "Closed"
                        tvEyeState.setTextColor(android.graphics.Color.parseColor("#EF5350"))
                        warningBanner.visibility = View.VISIBLE 
                        
                        if (closedEyeFrames % 10 == 0) triggerVibration(200) 
                    } else if (openEyeFrames >= 1) { // Instant recovery to Open
                        tvEyeState.text = "Open"
                        tvEyeState.setTextColor(android.graphics.Color.parseColor("#6ABF69"))
                        warningBanner.visibility = View.GONE 
                    }
                }

                faceOverlay.updateData(leftEyePoints, rightEyePoints, lipPoints, rotatedBitmap.width, rotatedBitmap.height)

                if (frameSequence.size == sequenceLength) frameSequence.removeFirst()
                frameSequence.addLast(floatArrayOf(avgEar, mar))

                if (frameSequence.size == sequenceLength) {
                    runLSTMInference(avgEar, mar)
                } else {
                    activity?.runOnUiThread {
                        tvDetectionLabel.text = "Face Tracked"
                        tvDetectionLabel.setTextColor("#38BDF8".toColorInt())
                        tvConfidence.text = "${frameSequence.size}/30"
                    }
                }
            } else {
                framesWithoutFace++
                if (framesWithoutFace > 5) {
                    faceOverlay.clear() 
                    activity?.runOnUiThread {
                        tvDetectionLabel.text = "No Target"
                        tvDetectionLabel.setTextColor("#EF4444".toColorInt())
                        tvConfidence.text = "Searching..."
                        warningBanner.visibility = View.GONE
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Analysis error: ${e.message}")
        } finally { imageProxy.close() }
    }

    private fun runLSTMInference(currentEar: Float, currentMar: Float) {
        val interpreter = tfliteInterpreter ?: return
        try {
            val inputTensor = interpreter.getInputTensor(0)
            val expectedInputFloats = inputTensor.numElements() 
            val outputTensor = interpreter.getOutputTensor(0)
            val expectedOutputFloats = outputTensor.numElements()

            val inputBuffer = ByteBuffer.allocateDirect(expectedInputFloats * 4).apply { order(ByteOrder.nativeOrder()) }
            val outputBuffer = ByteBuffer.allocateDirect(expectedOutputFloats * 4).apply { order(ByteOrder.nativeOrder()) }

            val flatList = mutableListOf<Float>()
            for (frame in frameSequence) {
                flatList.add(frame[0]) 
                flatList.add(frame[1]) 
            }

            for (i in 0 until expectedInputFloats) {
                if (i < flatList.size) inputBuffer.putFloat(flatList[i]) else inputBuffer.putFloat(0f) 
            }
            inputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            val scores = FloatArray(expectedOutputFloats)
            outputBuffer.asFloatBuffer().get(scores)

            val rawClassIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
            val confidence = (scores[rawClassIdx] * 100).toInt().coerceIn(0, 100)

            var finalClassIdx = rawClassIdx
            if (finalClassIdx == 2 && currentMar < 0.35f) finalClassIdx = 0 
            if (currentEar < 0.15f) finalClassIdx = 1 

            if (predictionHistory.size == 5) predictionHistory.removeFirst()
            predictionHistory.addLast(finalClassIdx)
            val smoothedClassIdx = predictionHistory.groupBy { it }.maxByOrNull { it.value.size }?.key ?: finalClassIdx

            activity?.runOnUiThread { updateUI(smoothedClassIdx, confidence) }
        } catch (e: Exception) { Log.e(TAG, "LSTM Inference failed: ${e.message}") }
    }

    private fun distance(p1: NormalizedLandmark, p2: NormalizedLandmark): Float {
        val dx = p1.x() - p2.x()
        val dy = p1.y() - p2.y()
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun calculateEAR(eye: List<NormalizedLandmark>): Float {
        val distA = distance(eye[1], eye[5])
        val distB = distance(eye[2], eye[4])
        val distC = distance(eye[0], eye[3])
        return if (distC == 0f) 0f else (distA + distB) / (2.0f * distC)
    }

    private fun calculateMAR(lips: List<NormalizedLandmark>): Float {
        val distA = distance(lips[2], lips[3]) 
        val distC = distance(lips[0], lips[1]) 
        return if (distC == 0f) 0f else distA / distC
    }

    private fun updateUI(labelIndex: Int, confidence: Int) {
        if (!isMonitoring) return
        tvDetectionLabel.text = classLabels[labelIndex]
        tvConfidence.text = "$confidence%"

        when (labelIndex) {
            0 -> { 
                tvDetectionIcon.text = "🛡️"
                tvDetectionLabel.setTextColor("#22C55E".toColorInt())
                tvDrowsiness.text = "Optimized"
                tvDrowsiness.setTextColor("#22C55E".toColorInt())
                setStatus("● SECURE", "#22C55E")
                safetyScore = minOf(100f, safetyScore + 1.0f) 
            }
            1 -> { 
                tvDetectionIcon.text = "😴"
                tvDetectionLabel.setTextColor("#EF4444".toColorInt())
                tvDrowsiness.text = "Danger" 
                tvDrowsiness.setTextColor("#EF4444".toColorInt())
                setStatus("● DANGER", "#EF4444")
                safetyScore = maxOf(0f, safetyScore - 1.5f) 
            }
            2 -> { 
                tvDetectionIcon.text = "🥱"
                tvDetectionLabel.setTextColor("#F59E0B".toColorInt())
                tvDrowsiness.text = "Fatigue" 
                tvDrowsiness.setTextColor("#F59E0B".toColorInt())
                setStatus("● WARNING", "#F59E0B")
                safetyScore = maxOf(0f, safetyScore - 0.5f)
            }
        }
        updateSafetyUI()
    }

    private fun updateSafetyUI() {
        safetyScore = safetyScore.coerceIn(0f, 100f)
        val scoreInt = safetyScore.toInt()
        tvSafetyScore.text = "$scoreInt%"
        pbSafetyScore.progress = scoreInt
        
        try {
            val prefs = requireActivity().getSharedPreferences("AegisData", Context.MODE_PRIVATE)
            prefs.edit().putInt("LAST_SCORE", scoreInt).apply()
        } catch (e: Exception) { }

    // 🚀 UPDATED COLOR THRESHOLD TO 50
    val color = when {
        safetyScore > 75 -> "#6ABF69"
        safetyScore > 50 -> "#FFB74D" 
        else             -> "#EF5350"
    }
    pbSafetyScore.progressTintList = ColorStateList.valueOf(android.graphics.Color.parseColor(color))

    // 🚀 UPDATED ALARM THRESHOLD TO 50
    if (safetyScore <= 50f) {
        if (isManuallyMuted) {
            if (System.currentTimeMillis() - muteTimestamp > 10000) { 
                isManuallyMuted = false 
                playAudioAlarm()
            }
        } else { playAudioAlarm() }
    } else if (safetyScore > 60f) { // Buffer increased to 60f to prevent alarm stuttering
        isManuallyMuted = false 
        stopAudioAlarm()
    }
    }

    private fun setStatus(text: String, hexColor: String) {
        val color = hexColor.toColorInt()
        tvStatusOverlay.text = text
        tvStatusOverlay.setTextColor(color)
        statusOverlay.strokeColor = color
    }

    private fun loadAIModels() {
        try {
            val fd = requireContext().assets.openFd(lstmModelFile)
            val modelBuffer = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            tfliteInterpreter = Interpreter(modelBuffer)
        } catch (e: Exception) { Log.e(TAG, "LSTM load failed: ${e.message}") }

        try {
            val baseOptions = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(requireContext(), options)
        } catch (e: Exception) { Log.e(TAG, "MediaPipe load failed: ${e.message}") }
    }

    private fun bindViews(view: View) {
        cameraPreview    = view.findViewById(R.id.cameraPreview)
        tvTimer          = view.findViewById(R.id.tvTimer)
        tvFPS            = view.findViewById(R.id.tvFPS)
        tvLiveEar        = view.findViewById(R.id.tvLiveEar)
        tvLiveMar        = view.findViewById(R.id.tvLiveMar)
        tvStatusOverlay  = view.findViewById(R.id.tvStatusOverlay)
        tvDetectionIcon  = view.findViewById(R.id.tvDetectionIcon)
        tvDetectionLabel = view.findViewById(R.id.tvDetectionLabel)
        tvConfidence     = view.findViewById(R.id.tvConfidence)
        tvEyeState       = view.findViewById(R.id.tvEyeState)
        tvDrowsiness     = view.findViewById(R.id.tvDrowsiness)
        tvAlertCount     = view.findViewById(R.id.tvAlertCount)
        btnStopMonitor   = view.findViewById(R.id.btnStopMonitor)
        btnMuteAlarm     = view.findViewById(R.id.btnMuteAlarm) 
        statusOverlay    = view.findViewById(R.id.statusOverlay)
        liveBadgeCard    = view.findViewById(R.id.liveBadge)
        tvLiveText       = view.findViewById(R.id.tvLiveText)
        tvSafetyScore    = view.findViewById(R.id.tvSafetyScore)
        pbSafetyScore    = view.findViewById(R.id.pbSafetyScore)
        warningBanner    = view.findViewById(R.id.warningBanner)
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
