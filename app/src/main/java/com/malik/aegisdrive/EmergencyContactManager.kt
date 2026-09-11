package com.malik.aegisdrive

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.ServiceState
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.util.Patterns
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Central manager for the "Family Notification" (Emergency Contact) feature.
 *
 * Responsibilities:
 *  - Validate and persist the emergency contact number locally (SharedPreferences, matching the
 *    project's existing storage pattern).
 *  - Sync the number to Firestore under the authenticated user's profile (users/{uid}).
 *  - Dispatch a fully automated carrier SMS to the saved contact — zero taps, zero third-party
 *    APIs, no network dependency beyond the SIM's cellular radio.
 *
 * This helper is completely decoupled from MonitorFragment and never touches its logic.
 */
object EmergencyContactManager {

    private const val TAG = "EmergencyContact"

    private const val PREFS_NAME = "AegisEmergency"
    private const val KEY_NUMBER = "emergency_contact_number"

    /** Alert count in a single session that must be EXCEEDED before we notify family. */
    const val ALERT_THRESHOLD = 10

    /** Shortest digit count E.164 considers routable; below this the address cannot be dialled. */
    private const val MIN_DIALABLE_DIGITS = 7

    /**
     * Process-scoped scope for SMS dispatch. SupervisorJob so one failed send can never cancel a
     * later one; this deliberately outlives any Activity because a drive can continue across
     * configuration changes and the alert must still go out.
     */
    private val dispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Base action for the sent-result broadcast; a unique suffix is appended per dispatch. */
    private const val ACTION_SMS_SENT = "com.aegisdrive.SMS_SENT"

    /** Distinguishes concurrent/sequential dispatches so their results never cross-talk. */
    private val dispatchIdCounter = AtomicInteger(0)

    /**
     * Safety net: if the radio never reports back at all (OEM quirk, killed telephony service),
     * the receiver is torn down anyway so it cannot leak for the life of the process.
     */
    private const val RESULT_TIMEOUT_MS = 2 * 60 * 1000L

    // ─────────────────────────────────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true if [raw] looks like a valid phone number. */
    fun isValidPhoneNumber(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        // Must contain a plausible number of digits (7..15 per E.164) ...
        val digitCount = trimmed.count { it.isDigit() }
        if (digitCount < 7 || digitCount > 15) return false
        // ... and match the platform phone pattern.
        return Patterns.PHONE.matcher(trimmed).matches()
    }

    /**
     * Reduces a user-entered number to a dialable SMS destination: digits only, plus a LEADING '+'
     * if the user supplied one. Spaces, dashes and parentheses are stripped.
     *
     * The '+' is deliberately preserved (unlike the old WhatsApp sanitiser, which dropped it):
     * SmsManager hands the address straight to the carrier, and an international number without
     * its '+' prefix is interpreted as a national number and misroutes.
     */
    fun sanitizeForSms(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+") && digits.isNotEmpty()) "+$digits" else digits
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local storage (SharedPreferences)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persists the emergency contact locally, SYNCHRONOUSLY.
     *
     * commit() rather than apply() is deliberate. apply() only queues the write and returns, so if
     * the process dies before that queue flushes — a crash, an OEM background kill, the user
     * swiping the app away right after saving — the write is silently LOST and the PREVIOUS number
     * silently returns. For an emergency contact that is a safety failure disguised as a UI glitch:
     * the driver believes their family will be texted, and a stale number is on file instead.
     *
     * The cost is a single small blocking write on the caller's thread, incurred once, on an
     * explicit user tap in Settings — never on the drive path, which only ever READS this value.
     *
     * @return true if the value reached disk.
     */
    fun saveLocal(context: Context, number: String): Boolean {
        val committed = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NUMBER, number.trim())
            .commit()
        if (!committed) Log.e(TAG, "Emergency contact FAILED to persist locally")
        return committed
    }

    fun getSavedNumber(context: Context): String? =
        contactOverride?.takeIf { it.isNotBlank() }
            ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_NUMBER, null)
                ?.takeIf { it.isNotBlank() }

    /**
     * TEST SEAM — null in production. Supplies a stand-in contact number so instrumented tests can
     * satisfy the "a contact is configured" precondition WITHOUT writing to the real preference.
     *
     * This exists because the obvious alternative — having a test save a dummy number and restore
     * the real one in teardown — is not crash-safe. If the run is interrupted (device unplugged,
     * ADB transport dropped, process killed) teardown never executes and the DUMMY NUMBER IS LEFT
     * PERSISTED ON DISK as the user's emergency contact, surviving reboots. That happened on the
     * project's own test handset and silently replaced a real family member's number.
     *
     * Being in-memory only, this override cannot outlive the process, and the stored number is
     * never modified at all — so the worst case of a leaked override is bounded and self-healing,
     * whereas the worst case of a leaked WRITE is a permanently wrong emergency contact.
     */
    @VisibleForTesting
    internal var contactOverride: String? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Firebase (Firestore) sync
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Syncs the emergency number to Firestore under the current user's profile document.
     * [onSuccess] / [onFailure] let the caller surface a Toast per the feature spec.
     */
    fun syncToFirebase(
        number: String,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            onFailure(IllegalStateException("No authenticated user"))
            return
        }

        val data = mapOf(
            "emergencyContact" to number.trim(),
            "emergencyContactUpdatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                Log.d(TAG, "Emergency contact synced to Firestore")
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync emergency contact", e)
                onFailure(e)
            }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission
    // ─────────────────────────────────────────────────────────────────────────

    /** True once the user has granted SEND_SMS. Required before any automated dispatch. */
    fun hasSmsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    // ─────────────────────────────────────────────────────────────────────────
    // Automated native SMS dispatch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Dispatches the urgent safety SMS to the saved emergency contact — fully automatically, in the
     * background, with ZERO user interaction. Nothing is opened, nothing must be tapped: the driver
     * can be asleep and the message still leaves the handset.
     *
     * This uses the native AOSP [SmsManager] and the device's own SIM/carrier plan only. There is
     * no third-party SDK, no HTTP call, no API key and no developer-side cost.
     *
     * The destination number is fetched LIVE from SharedPreferences at the exact moment this is
     * called — never cached or passed in — so an edit made mid-drive is picked up instantly on the
     * next alert without an app restart.
     *
     * Stability note: this may be invoked from a non-Activity (observer / application) context, so
     * all feedback uses a plain [Toast] on the main looper, never the themed AegisNotify overlay —
     * AegisNotify inflates a Material layout that requires a themed Activity context and crashes
     * when inflated from applicationContext.
     *
     * Threading: returns IMMEDIATELY. The dispatch itself is handed to [Dispatchers.IO] so the
     * caller's thread — in practice the UI thread, since MonitorFragment emits inside
     * runOnUiThread — is never held by the telephony binder round-trip.
     *
     * @param onResult invoked with true only if the message was actually handed to the radio, and
     *   false for every abort/failure path. Delivered on a background thread. Callers use this to
     *   decide whether to keep their "already notified" latch closed or re-arm for a retry.
     */
    fun sendAutomatedEmergencySMS(context: Context, onResult: ((Boolean) -> Unit)? = null) {
        // Resolved on the CALLER's thread: applicationContext is cheap and this keeps any
        // hypothetical Activity reference from being captured into the coroutine.
        val safeContext = context.applicationContext
        dispatchScope.launch {
            // catch(Throwable) — NOT catch(Exception) — is load-bearing. dispatchSms rethrows
            // whatever the radio threw so the receiver can be torn down first, and an Error
            // (NoClassDefFoundError on an OEM without telephony, OutOfMemoryError) would sail
            // past its inner Exception handlers. If that escaped here, onResult would never
            // fire, the caller's latch would stay closed, and the drive would be silently
            // disarmed for good — the exact failure this callback exists to prevent. It would
            // also reach the thread's uncaught handler and crash the app.
            val dispatched = try {
                dispatchSms(safeContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Unexpected throwable during emergency SMS dispatch", t)
                toastOnMain(safeContext, safeContext.getString(R.string.sms_send_failed))
                false
            }
            onResult?.invoke(dispatched)
        }
    }

    /**
     * The actual guarded dispatch. Always runs off the main thread (see [sendAutomatedEmergencySMS]).
     *
     * @return true only when [SmsManager.sendMultipartTextMessage] accepted the message.
     */
    private fun dispatchSms(safeContext: Context): Boolean {
        // EVERY path below uses applicationContext. This method runs from an observer with no
        // Activity attached, so an Activity context here risks a leak or a crash if the hosting
        // screen has already been destroyed mid-drive.

        // ── Guard 1 ── No contact configured. Abort silently-but-logged; this is a normal state
        // for a user who never opened Settings, not an error worth alarming the driver about.
        val savedNumber = getSavedNumber(safeContext)
        if (savedNumber.isNullOrBlank()) {
            Log.w(TAG, "Emergency SMS aborted: no contact number saved")
            toastOnMain(safeContext, safeContext.getString(R.string.sms_no_contact_saved))
            return false
        }

        // ── Guard 2 ── Stored value survives sanitisation as a dialable address. Protects against
        // a corrupted/hand-edited SharedPreferences entry that never passed isValidPhoneNumber.
        val phone = sanitizeForSms(savedNumber)
        if (phone.count { it.isDigit() } < MIN_DIALABLE_DIGITS) {
            Log.e(TAG, "Emergency SMS aborted: saved number is not dialable after sanitisation")
            toastOnMain(safeContext, safeContext.getString(R.string.sms_send_failed))
            return false
        }

        // ── Guard 3 ── Device physically capable of SMS. Wi-Fi tablets and most emulators have no
        // telephony radio; calling into SmsManager there throws UnsupportedOperationException.
        if (!deviceCanSendSms(safeContext)) {
            Log.e(TAG, "Emergency SMS aborted: device has no SMS-capable telephony hardware")
            toastOnMain(safeContext, safeContext.getString(R.string.sms_send_failed))
            return false
        }

        // ── Guard 4 ── Permission re-verified HERE, immediately before dispatch — never trusted
        // from setup time. The user can revoke SEND_SMS in system Settings at any point after
        // saving the contact, including while a drive is already in progress.
        if (!hasSmsPermission(safeContext)) {
            Log.e(TAG, "Emergency SMS aborted: SEND_SMS was revoked after setup")
            toastOnMain(safeContext, safeContext.getString(R.string.sms_permission_revoked))
            return false
        }

        // ── Guard 5 ── Non-empty body. sendMultipartTextMessage throws IllegalArgumentException
        // on an empty part list, which a blank/mis-translated string resource would produce.
        val message = safeContext.getString(R.string.family_alert_message)
        if (message.isBlank()) {
            Log.e(TAG, "Emergency SMS aborted: alert message resource is blank")
            toastOnMain(safeContext, safeContext.getString(R.string.sms_send_failed))
            return false
        }

        return try {
            val smsManager = obtainSmsManager(safeContext)
            if (smsManager == null) {
                Log.e(TAG, "Emergency SMS aborted: SmsManager unavailable on this device")
                toastOnMain(safeContext, safeContext.getString(R.string.sms_send_failed))
                return false
            }

            // ALWAYS multipart, even though the body is currently a single 149-char GSM-7 part.
            // divideMessage() computes the correct split for whichever encoding the body actually
            // triggers, so a future translation, a longer rewrite, or an accented character can
            // never silently truncate at the radio or throw for over-length input.
            val parts = smsManager.divideMessage(message)
            if (parts.isNullOrEmpty()) {
                Log.e(TAG, "Emergency SMS aborted: divideMessage returned no parts")
                toastOnMain(safeContext, safeContext.getString(R.string.sms_send_failed))
                return false
            }

            // Register the result receiver FIRST so a fast rejection cannot outrun us.
            //
            // Result tracking is BEST-EFFORT. If the receiver cannot be registered (OEM quirk, or
            // an Android 14+ receiver-flag rejection) we still send the message unmonitored:
            // losing a life-safety alert because we could not set up monitoring of it would be
            // the wrong trade-off. Without this, a registerReceiver failure would also surface as
            // the misleading "SMS Permission revoked" Toast from the SecurityException handler.
            val tracking = try {
                beginResultTracking(safeContext, parts.size)
            } catch (e: Exception) {
                Log.e(TAG, "Could not register SMS result receiver - sending UNMONITORED", e)
                null
            }

            try {
                smsManager.sendMultipartTextMessage(phone, null, parts, tracking?.sentIntents, null)
            } catch (t: Throwable) {
                // The send never reached the radio, so no result broadcast will ever arrive.
                // Tear the receiver down immediately rather than waiting out the watchdog.
                tracking?.abandon?.invoke()
                throw t
            }
            Log.d(TAG, "Automated emergency SMS dispatched to $phone (${parts.size} part(s))")
            true
        } catch (e: SecurityException) {
            // Revoked in the microseconds between Guard 4 and dispatch, or blocked by an OEM /
            // work-profile policy. Distinct message so the cause is obvious on stage.
            Log.e(TAG, "Emergency SMS rejected: SEND_SMS denied at dispatch", e)
            toastOnMain(safeContext, safeContext.getString(R.string.sms_permission_revoked))
            false
        } catch (e: Exception) {
            // Catch-all backstop: IllegalArgumentException (malformed address),
            // UnsupportedOperationException (no telephony), airplane mode, absent/locked SIM,
            // OEM-specific RuntimeExceptions. Nothing from this method may reach the driver.
            Log.e(TAG, "Failed to send automated emergency SMS", e)
            toastOnMain(safeContext, safeContext.getString(R.string.sms_send_failed))
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Asynchronous radio-result tracking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Live tracking for one dispatch: the per-part sent-[PendingIntent]s to hand to the radio, and
     * an [abandon] hook that tears the receiver down if the send never actually happens.
     */
    private class ResultTracking(
        val sentIntents: ArrayList<PendingIntent>,
        val abandon: () -> Unit
    )

    /**
     * Registers a one-shot [BroadcastReceiver] for this dispatch and builds one sent-PendingIntent
     * per message part.
     *
     * MUST be called BEFORE [SmsManager.sendMultipartTextMessage] — the telephony service can
     * broadcast a rejection almost immediately (radio off, no service), and a receiver registered
     * afterwards would miss it and re-create the silent failure this whole mechanism removes.
     *
     * Lifecycle: the receiver unregisters itself as soon as every part has reported, and a
     * [RESULT_TIMEOUT_MS] watchdog tears it down even if the radio never answers. Teardown is
     * idempotent, so the two paths can race harmlessly. Registration uses applicationContext and
     * [ContextCompat.RECEIVER_NOT_EXPORTED] — required on Android 14+ (targetSdk 35) and correct
     * here because a PendingIntent is dispatched under OUR app's identity, not the sender's.
     */
    private fun beginResultTracking(context: Context, partCount: Int): ResultTracking {
        val dispatchId = dispatchIdCounter.incrementAndGet()
        val action = "$ACTION_SMS_SENT.$dispatchId"

        val outstandingParts = AtomicInteger(partCount)
        val failureAlreadyReported = AtomicBoolean(false)
        val tornDown = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())

        // Declared first so the receiver body and the watchdog can both reference it.
        var receiverRef: BroadcastReceiver? = null

        val tearDown = tearDown@{
            // compareAndSet makes this safe to call from the receiver AND the watchdog.
            if (!tornDown.compareAndSet(false, true)) return@tearDown
            try {
                receiverRef?.let { context.unregisterReceiver(it) }
            } catch (e: IllegalArgumentException) {
                // Already unregistered - benign, nothing to clean up.
                Log.d(TAG, "SMS result receiver was already unregistered")
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context?, intent: Intent?) {
                // onReceive for a context-registered receiver runs on the main thread.
                val code = resultCode
                if (code == Activity.RESULT_OK) {
                    Log.d(TAG, "Emergency SMS part accepted by the network (dispatch $dispatchId)")
                } else if (failureAlreadyReported.compareAndSet(false, true)) {
                    // Only the FIRST failing part reports; a 3-part message must not produce
                    // 3 Toasts. "noDefault" is the dual-SIM-without-a-default-subscription case.
                    val noDefaultSim = intent?.getBooleanExtra("noDefault", false) ?: false
                    Log.e(
                        TAG,
                        "Emergency SMS REJECTED by radio: ${describeSendResult(code)}" +
                            (if (noDefaultSim) " [no default SMS subscription - dual SIM]" else "")
                    )
                    toastOnMain(context, context.getString(R.string.sms_failed_async))
                    // Re-open the send-slot so the next alert retries instead of the drive
                    // silently ending with the family never contacted.
                    FamilyAlertObserver.rearmAfterAsyncFailure()
                }

                if (outstandingParts.decrementAndGet() <= 0) tearDown()
            }
        }
        receiverRef = receiver

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        mainHandler.postDelayed({ tearDown() }, RESULT_TIMEOUT_MS)

        val sentIntents = ArrayList<PendingIntent>(partCount)
        repeat(partCount) { index ->
            sentIntents += PendingIntent.getBroadcast(
                context,
                // Unique requestCode per part, otherwise getBroadcast returns the SAME
                // PendingIntent for every part and only one result is ever delivered.
                dispatchId * 1000 + index,
                Intent(action).setPackage(context.packageName),
                // FLAG_IMMUTABLE is mandatory from Android 12 (API 31); the radio must not be
                // able to rewrite the intent it hands back to us.
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return ResultTracking(sentIntents, tearDown)
    }

    /** Human-readable name for an SMS sent-result code, for logcat triage. */
    private fun describeSendResult(code: Int): String = when (code) {
        Activity.RESULT_OK -> "RESULT_OK"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
        SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "RESULT_ERROR_LIMIT_EXCEEDED"
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "RESULT_ERROR_FDN_CHECK_FAILURE"
        else -> "UNKNOWN_RESULT_CODE($code)"
    }

    /**
     * True when the handset plausibly has SMS-capable telephony hardware.
     *
     * Accepts EITHER the granular [PackageManager.FEATURE_TELEPHONY_MESSAGING] (added in API 31,
     * the precise feature [SmsManager] is annotated with) OR the broad, long-standing
     * [PackageManager.FEATURE_TELEPHONY].
     *
     * Requiring ONLY the granular feature on API 31+ was a real, total failure of this feature.
     * API 31 split the monolithic telephony feature into sub-features, but plenty of shipping
     * devices never added the sub-feature declarations. The project's own TECNO KJ7 (Android 14,
     * MediaTek, DUAL-SIM with two live SIMs) advertises exactly:
     *
     *     android.hardware.telephony, .telephony.gsm, .telephony.ims     -- but NOT .messaging
     *
     * so this returned false and EVERY emergency SMS was aborted at the guard with
     * "device has no SMS-capable telephony hardware". Verified on-device 2026-08-14: the family
     * notification had never once been able to send on that handset.
     *
     * Widening is the safe direction. This is only a cheap pre-flight hint: if a device genuinely
     * has no modem, [SmsManager] throws UnsupportedOperationException and the catch-all in
     * [dispatchSms] reports the failure properly. A capability probe that FAILS CLOSED on a
     * life-safety path is far worse than one that lets the real call decide.
     */
    private fun deviceCanSendSms(context: Context): Boolean {
        val packageManager = context.packageManager
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
    }

    /**
     * Resolves the platform [SmsManager] bound to the SIM most likely to actually deliver.
     *
     * The platform default is used whenever it is serviceable. On a DUAL-SIM handset, however,
     * the "default SMS subscription" is a sticky user setting that keeps pointing at a SIM even
     * after that SIM loses service entirely — which is exactly the state this feature must
     * survive, because the send is SILENT: [SmsManager.sendMultipartTextMessage] accepts the
     * message synchronously and only the asynchronous sent-PendingIntent reports the
     * RESULT_ERROR_NO_SERVICE that followed. The family is never told, and nothing on screen
     * says so. Verified on the project's own TECNO KJ7 test handset, where defaultSmsSubId
     * pointed at a SIM reporting carrierName="No service" while the other SIM was registered.
     *
     * So: prefer the default subscription, but if it is NOT registered on a network, fall back
     * to any active subscription that is.
     *
     * API 31+ exposes SmsManager as a proper system service; the static [SmsManager.getDefault]
     * is deprecated there but remains the only route on the API 26..30 devices this app still
     * supports (minSdk = 26). Likewise [SmsManager.createForSubscriptionId] is an instance method
     * added in API 31, so older devices must use the deprecated static variant.
     */
    private fun obtainSmsManager(context: Context): SmsManager? {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return null

        // INVALID means "keep the platform default" — either everything is fine, or we lack the
        // permission to tell. Both cases must behave exactly as they did before this fallback
        // existed: attempt the send on the default SIM rather than refusing to send at all.
        val subId = pickSmsSubscriptionId(context)
        if (!SubscriptionManager.isValidSubscriptionId(subId)) return base

        return try {
            val bound = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                base.createForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            }
            Log.d(TAG, "Emergency SMS will be sent on subscription $subId")
            bound ?: base
        } catch (e: Exception) {
            // Never let SIM selection be the reason a life-safety alert does not go out.
            Log.e(TAG, "Could not bind SmsManager to subscription $subId - using default", e)
            base
        }
    }

    /**
     * Chooses the subscription to send on, or [SubscriptionManager.INVALID_SUBSCRIPTION_ID] to
     * mean "use the platform default".
     *
     * Returns INVALID — deliberately, not a guess — whenever service state cannot be established:
     * READ_PHONE_STATE not granted, a single-SIM device, or any OEM telephony quirk. Guessing a
     * subscription we cannot verify would risk sending on a WORSE SIM than the user's own choice.
     */
    private fun pickSmsSubscriptionId(context: Context): Int {
        val invalid = SubscriptionManager.INVALID_SUBSCRIPTION_ID

        // Service state is unreadable without READ_PHONE_STATE, so there is nothing to decide.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "READ_PHONE_STATE not granted - using the default SMS subscription as-is")
            return invalid
        }

        return try {
            val defaultSubId = SubscriptionManager.getDefaultSmsSubscriptionId()

            // Happy path: the user's chosen SMS SIM is registered. Respect it.
            if (SubscriptionManager.isValidSubscriptionId(defaultSubId) &&
                isSubscriptionInService(context, defaultSubId)
            ) {
                return invalid
            }

            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
                ?: return invalid
            val active = subscriptionManager.activeSubscriptionInfoList
            if (active.isNullOrEmpty()) return invalid

            // Only override when a DIFFERENT subscription is genuinely in service. If none is,
            // fall through to the default so the message is still attempted.
            val rescue = active.firstOrNull {
                it.subscriptionId != defaultSubId && isSubscriptionInService(context, it.subscriptionId)
            } ?: return invalid

            Log.w(
                TAG,
                "Default SMS subscription $defaultSubId is out of service - " +
                    "falling back to in-service subscription ${rescue.subscriptionId}"
            )
            rescue.subscriptionId
        } catch (e: Exception) {
            // SecurityException on OEMs with stricter telephony gating, or any RuntimeException
            // from the subscription service. Fall back to the platform default.
            Log.e(TAG, "SIM selection failed - using the default SMS subscription", e)
            invalid
        }
    }

    /** True when [subId] is currently registered on a network and can therefore carry an SMS. */
    private fun isSubscriptionInService(context: Context, subId: Int): Boolean = try {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            ?.createForSubscriptionId(subId)
        telephonyManager?.serviceState?.state == ServiceState.STATE_IN_SERVICE
    } catch (e: Exception) {
        Log.e(TAG, "Could not read service state for subscription $subId", e)
        false
    }

    /**
     * Toasts from any thread. AlertBus emits on the UI thread today, but this method is a public
     * entry point — posting to the main looper keeps it crash-proof if it is ever called from a
     * worker thread (Toast requires a prepared Looper).
     */
    private fun toastOnMain(context: Context, text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
