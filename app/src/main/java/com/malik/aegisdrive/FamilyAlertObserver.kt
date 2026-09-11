package com.malik.aegisdrive

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-time trigger for the Family Notification. Hosts TWO fully independent triggers.
 *
 * **TRIGGER 1 — accumulation (the 11th alert).** Listens to [AlertBus] for the live per-session
 * alert count. The instant a single drive EXCEEDS [EmergencyContactManager.ALERT_THRESHOLD]
 * alerts (i.e. the 11th), the emergency SMS is dispatched. Models "this driver is repeatedly
 * nodding off over the course of a journey."
 *
 * **TRIGGER 2 — continuous unconsciousness (45 seconds).** Measures the duration of a SINGLE
 * drowsiness episode using the rising edge ([AlertBus.emitAlert]) and falling edge
 * ([AlertBus.emitDrowsinessEnded]) that MonitorFragment publishes. If one episode runs unbroken
 * for [UNCONSCIOUSNESS_TIMEOUT_MS], the driver is treated as unconscious and the SMS fires
 * IMMEDIATELY, bypassing the alert counter entirely. Models "this driver has stopped responding
 * altogether" — which the accumulation counter alone would never catch, because an unconscious
 * driver produces exactly one alert and then nothing.
 *
 * The two triggers share one [notifiedThisSession] latch, claimed by atomic compare-and-set, so
 * whichever fires first wins the drive's single send-slot and the other stands down. They can
 * never both text the family for the same journey.
 *
 * This observer never touches MonitorFragment's logic; it only consumes the edges it emits.
 */
class FamilyAlertObserver(private val appContext: Context) {

    private val TAG = "FamilyAlertObserver"

    private val listener = AlertBus.Listener { count -> onAlert(count) }
    private val recoveryListener = AlertBus.RecoveryListener { onDrowsinessEnded() }

    /**
     * Timer for TRIGGER 2 (see class docs). Written from the UI thread (episode start) and read /
     * cancelled from the CameraX analyser thread (episode end), so every access goes through the
     * @Synchronized helpers below — "cancel the old job, then assign a new one" is a compound
     * operation that @Volatile alone would not make safe.
     */
    private var unconsciousnessJob: Job? = null

    /** delay() suspends rather than blocking, so this scope never occupies a thread while waiting. */
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        AlertBus.register(listener)
        AlertBus.registerRecovery(recoveryListener)
    }

    private fun onAlert(sessionAlertCount: Int) {
        // ── TRIGGER 2 ── A new drowsiness episode just STARTED. Restart the 45s unconsciousness
        // clock. This runs BEFORE the threshold check below because the continuous-closure
        // override is fully independent of how many alerts have accumulated — it must arm even
        // on the very first alert of a drive.
        startUnconsciousnessTimer()

        // ── NEW-DRIVE DETECTION ──
        // Release the send-slot ONLY when the count actually restarts. MonitorFragment zeroes its
        // alertCount in startMonitoring(), and within a single drive the count strictly increases
        // (1, 2, 3...), so a count that FAILS to increase is definitive proof that a new drive
        // began.
        //
        // This must NOT be simplified back to `if (count <= ALERT_THRESHOLD) release`. That older
        // heuristic was only safe while TRIGGER 1 existed alone: the latch could then be claimed
        // exclusively at count >= 11, so a claimed latch was never re-examined at a count <= 10.
        // TRIGGER 2 can claim the slot at ANY count — including 1 — after which alerts 2..10 of
        // the SAME drive would have released it again and allowed a second, third... SMS.
        // The test below is deliberately IDEMPOTENT — re-delivering the same count must not
        // release the slot. A plain `count <= previous` would: if two observers are ever
        // registered at once (the very scenario that forced this state to be process-scoped),
        // both receive the same count, the second sees previous == count, and would re-open the
        // slot mid-drive and permit a duplicate SMS.
        //
        //   count == 1        -> primary signal. MonitorFragment zeroes alertCount in
        //                        startMonitoring(), so a drive's first alert is always 1, and 1
        //                        can only ever be a drive's first alert. Safe to apply repeatedly,
        //                        because releasing at the start of a drive is always correct.
        //   count < previous  -> fallback. MonitorFragment emits via `activity?.runOnUiThread`,
        //                        which silently no-ops if the activity is momentarily null, so a
        //                        count-1 emission CAN be dropped. This still catches the reset.
        val previousCount = lastSeenAlertCount.getAndSet(sessionAlertCount)
        if (sessionAlertCount == 1 || sessionAlertCount < previousCount) {
            Log.d(TAG, "New drive detected (count $previousCount -> $sessionAlertCount) - re-arming")
            notifiedThisSession.set(false)
        }

        // Below the accumulation threshold: TRIGGER 1 does not fire. TRIGGER 2's timer is already
        // armed above and remains free to fire independently.
        if (sessionAlertCount <= EmergencyContactManager.ALERT_THRESHOLD) return

        // Atomically claim the single send-slot for this drive. compareAndSet is the whole
        // anti-spam mechanism: alerts 12, 13, 14... all lose the CAS and return here, so the
        // family is texted exactly once no matter how far the count climbs. Losing this CAS also
        // covers the case where TRIGGER 2 (45s unconsciousness) already claimed the slot.
        if (!notifiedThisSession.compareAndSet(false, true)) {
            Log.d(TAG, "Alert $sessionAlertCount exceeded limit but family already notified this drive")
            return
        }

        // Early-out (and re-arm) if no contact is saved yet — e.g. the user may still add one
        // mid-drive, in which case a later alert will fire the notification.
        if (EmergencyContactManager.getSavedNumber(appContext).isNullOrBlank()) {
            Log.d(TAG, "Alert limit exceeded ($sessionAlertCount) but no emergency contact saved")
            notifiedThisSession.set(false)
            return
        }

        Log.d(TAG, "Alert limit exceeded ($sessionAlertCount) -> notifying emergency contact")

        // Returns immediately; the send runs on Dispatchers.IO. The number is read live from
        // SharedPreferences inside the manager, so a mid-drive edit is always honoured.
        dispatchEmergencySms(Trigger.ACCUMULATION) { dispatched ->
            if (!dispatched) {
                // The send never reached the radio (permission revoked, no SIM, radio error).
                // Re-arm so the NEXT alert retries rather than leaving the family uninformed for
                // the rest of the drive — a failed attempt must not consume the one send-slot.
                Log.w(TAG, "Emergency SMS failed to dispatch - re-arming for the next alert")
                notifiedThisSession.set(false)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TRIGGER 2 — 45-second continuous unconsciousness override
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * (Re)starts the 45-second clock at the rising edge of a drowsiness episode.
     *
     * Restarting is correct rather than merely convenient: MonitorFragment only emits an alert on
     * a RISING edge, which means a new alert proves the previous episode must already have ended.
     * Each episode therefore deserves its own fresh 45-second window.
     */
    @Synchronized
    private fun startUnconsciousnessTimer() {
        unconsciousnessJob?.cancel()
        unconsciousnessJob = timerScope.launch {
            delay(UNCONSCIOUSNESS_TIMEOUT_MS)
            // Guards the narrow race where recovery arrives just as delay() completes.
            if (!isActive) return@launch
            onUnconsciousnessConfirmed()
        }
    }

    /**
     * Driver recovered, muted, or monitoring stopped — episode ended before 45s elapsed.
     *
     * `Job.cancel()` is null-safe here and is a documented no-op on an already-cancelled or
     * already-completed Job, so this cannot throw however often or from wherever it is called.
     *
     * @return true if a timer was actually running, so the caller can log the transition WITHOUT
     *   reading [unconsciousnessJob] outside this lock. That unguarded read was a genuine data
     *   race: the field is written on the UI thread and read on the analyser thread.
     */
    @Synchronized
    private fun cancelUnconsciousnessTimer(): Boolean {
        val wasRunning = unconsciousnessJob != null
        unconsciousnessJob?.cancel()
        unconsciousnessJob = null
        return wasRunning
    }

    /**
     * Called when an episode ends — on the CameraX analyser thread for per-frame recovery, or on
     * the UI thread when the driver taps MUTE. AlertBus performs atomic edge detection, so this
     * fires once per real transition rather than once per frame.
     *
     * Note this deliberately touches ONLY the 45s timer. It never reads or writes
     * [notifiedThisSession] and never influences the alert count, keeping the two triggers
     * completely independent.
     */
    private fun onDrowsinessEnded() {
        if (cancelUnconsciousnessTimer()) {
            Log.d(TAG, "Drowsiness episode ended - 45s unconsciousness timer cancelled")
        }
    }

    /**
     * 45 seconds of UNBROKEN drowsiness have elapsed. The driver is treated as unconscious and the
     * emergency SMS fires immediately, bypassing the 11-alert accumulation counter entirely.
     */
    private fun onUnconsciousnessConfirmed() {
        // ── Re-validation ── Two independent conditions must hold before an emergency fires:
        // this timer survived 45s uncancelled, AND the bus still believes the episode is running.
        // MonitorFragment delivers the rising edge via runOnUiThread (deferred) but calls the
        // falling edge synchronously, so for a very short episode the two can arrive out of order
        // and leave a timer armed for an episode that is already over. This check makes that
        // ordering hazard incapable of producing a false emergency SMS.
        if (!AlertBus.isDrowsinessActive) {
            Log.d(TAG, "45s elapsed but episode already ended (stale timer) - no SMS sent")
            return
        }

        if (EmergencyContactManager.getSavedNumber(appContext).isNullOrBlank()) {
            Log.d(TAG, "45s unconsciousness reached but no emergency contact saved")
            return
        }

        // SHARED LATCH with the 11-alert path. Whichever trigger reaches this CAS first wins the
        // drive's single send-slot; the other loses and returns. This is precisely what makes it
        // impossible for the two independent triggers to both text the family for one drive.
        if (!notifiedThisSession.compareAndSet(false, true)) {
            Log.d(TAG, "45s unconsciousness reached but family already notified this drive")
            return
        }

        Log.w(TAG, "*** 45s CONTINUOUS UNCONSCIOUSNESS *** -> emergency SMS (alert counter bypassed)")

        dispatchEmergencySms(Trigger.UNCONSCIOUSNESS) { dispatched ->
            if (!dispatched) {
                // Same re-arm contract as the 11-alert path: a failed attempt must not consume
                // the drive's one send-slot.
                Log.w(TAG, "Unconsciousness SMS failed to dispatch - re-arming")
                notifiedThisSession.set(false)
            }
        }
    }

    /**
     * The single funnel every emergency send passes through, regardless of which trigger won.
     *
     * It exists because this class's central decision — WHICH trigger claimed the drive's one
     * send-slot, and how many times — previously had no observable form other than handing a real
     * message to a real radio. That made the two triggers untestable on a real handset without
     * texting a real person, so [smsDispatcherOverride] lets an instrumented test observe the
     * decision while nothing leaves the phone. In production the override is null and this is a
     * direct call to [EmergencyContactManager.sendAutomatedEmergencySMS].
     */
    private fun dispatchEmergencySms(trigger: Trigger, onResult: (Boolean) -> Unit) {
        val override = smsDispatcherOverride
        if (override != null) {
            Log.w(TAG, "SMS dispatcher OVERRIDDEN ($trigger) - this must only happen under test")
            override(trigger, onResult)
            return
        }
        EmergencyContactManager.sendAutomatedEmergencySMS(appContext, onResult)
    }

    /** Which of the two independent triggers claimed the drive's send-slot. */
    enum class Trigger {
        /** The alert count EXCEEDED [EmergencyContactManager.ALERT_THRESHOLD]. */
        ACCUMULATION,

        /** A single drowsiness episode ran unbroken for [UNCONSCIOUSNESS_TIMEOUT_MS]. */
        UNCONSCIOUSNESS
    }

    fun stop() {
        AlertBus.unregister(listener)
        AlertBus.unregisterRecovery(recoveryListener)
        cancelUnconsciousnessTimer()
    }

    companion object {

        /** Unbroken drowsiness beyond this duration is treated as unconsciousness. */
        private const val UNCONSCIOUSNESS_TIMEOUT_MS = 45_000L

        /**
         * The "already texted this drive" latch.
         *
         * Deliberately PROCESS-scoped (companion) and atomic, not a plain instance field:
         *  - Instance-scoped state would reset to false whenever MainActivity is recreated
         *    (dark-mode toggle, rotation, system config change), letting a second observer fire a
         *    duplicate SMS mid-drive.
         *  - [AtomicBoolean.compareAndSet] makes the check-and-claim a single atomic step. A plain
         *    `if (flag) return; flag = true` is a race: two threads can both read false and both
         *    send. AlertBus.emitAlert() has no enforced thread contract, so this cannot rely on
         *    everything happening to arrive on the UI thread.
         */
        private val notifiedThisSession = AtomicBoolean(false)

        /**
         * Highest alert count seen so far in the current drive, used purely to detect when
         * MonitorFragment has zeroed its counter and a NEW drive has started.
         *
         * Process-scoped for the same reason as [notifiedThisSession]: instance-scoped state would
         * reset on Activity recreation and be read as a spurious "new drive" mid-journey, which
         * would release the send-slot and permit a duplicate SMS.
         */
        private val lastSeenAlertCount = AtomicInteger(0)

        /**
         * Re-opens the send-slot after the RADIO asynchronously rejected a message that
         * [EmergencyContactManager.sendAutomatedEmergencySMS] had already reported as dispatched.
         *
         * The synchronous return value only tells us the telephony service ACCEPTED the request.
         * Real rejections — no service, radio off, or a dual-SIM device with no default SMS
         * subscription ("noDefault") — arrive later via the sent-PendingIntent. Without this hook
         * the latch would stay closed and the family would never be told, which is precisely the
         * silent failure this patch exists to eliminate.
         *
         * Deliberate bias: if a late failure lands after a NEW drive has already claimed the slot,
         * this re-opens it and that drive may send a second message. For a life-safety alert an
         * extra SMS is the correct error to make rather than a missing one.
         */
        fun rearmAfterAsyncFailure() {
            notifiedThisSession.set(false)
        }

        /**
         * TEST SEAM — null in production, and the ONLY way to observe which trigger fired.
         *
         * When set, it replaces the real dispatch entirely: no [android.telephony.SmsManager]
         * call, nothing handed to the radio, no message to a real person. The callback it receives
         * is the same `onResult` contract the real sender honours, so a test can also drive the
         * re-arm-on-failure path deterministically instead of waiting for a genuine radio error.
         *
         * Process-scoped to match [notifiedThisSession]: an instance field would be missed by a
         * test that lets the observer be recreated. Instrumented tests MUST null this out in
         * teardown — a leaked override would silently disable the real emergency SMS.
         */
        @VisibleForTesting
        internal var smsDispatcherOverride: ((Trigger, (Boolean) -> Unit) -> Unit)? = null
    }
}
