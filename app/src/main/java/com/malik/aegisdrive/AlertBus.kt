package com.malik.aegisdrive

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-light in-process event bus for real-time safety alerts.
 *
 * MonitorFragment emits two things here:
 *  - [emitAlert] — its current per-session alert count, the instant a new drowsiness/distraction
 *    alarm becomes active (the RISING edge of an episode).
 *  - [emitDrowsinessEnded] — the instant that alarm clears (the FALLING edge).
 *
 * Together those two edges let listeners measure how long a single episode has lasted, which is
 * what the 45-second continuous-unconsciousness override in [FamilyAlertObserver] is built on.
 * Listeners react immediately — no Firestore round-trip, no waiting for the drive to end.
 *
 * THREADING: the two emissions arrive on DIFFERENT threads. [emitAlert] is called inside
 * MonitorFragment's `runOnUiThread`, so it lands on the UI thread; [emitDrowsinessEnded] is called
 * from the camera analysis path, so it lands on the CameraX analyser thread. Listeners must be
 * written to tolerate both.
 */
object AlertBus {

    /** Notified with the live per-session alert count each time a new alert fires. */
    fun interface Listener {
        fun onAlert(sessionAlertCount: Int)
    }

    /** Notified once when an active drowsiness episode ends (driver recovered / alarm cleared). */
    fun interface RecoveryListener {
        fun onDrowsinessEnded()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val recoveryListeners = CopyOnWriteArraySet<RecoveryListener>()

    /**
     * Whether a drowsiness episode is currently in progress, used for EDGE DETECTION.
     *
     * MonitorFragment calls its stop path from a per-frame branch, so [emitDrowsinessEnded] would
     * otherwise fire ~30x/second for the entire time the driver is perfectly fine. Gating on this
     * flag collapses that to a single notification per real transition, keeping the camera
     * analysis thread's cost to one atomic read per frame.
     *
     * ATOMIC, not merely @Volatile: MonitorFragment's stop path is reachable from BOTH the UI
     * thread (the MUTE button) and the CameraX analyser thread (per-frame recovery), so two
     * threads can call [emitDrowsinessEnded] concurrently. `if (flag) { flag = false }` on a
     * volatile is a compound operation and both callers could pass it; compareAndSet collapses
     * the test and the claim into one indivisible step so exactly one caller wins the transition.
     */
    private val drowsinessActive = AtomicBoolean(false)

    /**
     * Whether the bus currently believes a drowsiness episode is in progress.
     *
     * Exposed so a long-running timer can RE-VALIDATE at the moment it fires rather than trusting
     * that it was cancelled. MonitorFragment delivers the rising edge via `runOnUiThread` (deferred)
     * but calls the falling edge synchronously, so for a very short episode the two can arrive out
     * of order and a cancellation can be missed. Checking this at timeout means two independent
     * conditions must hold before an emergency fires.
     */
    val isDrowsinessActive: Boolean
        get() = drowsinessActive.get()

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun registerRecovery(listener: RecoveryListener) {
        recoveryListeners.add(listener)
    }

    fun unregisterRecovery(listener: RecoveryListener) {
        recoveryListeners.remove(listener)
    }

    /** Emit the current session alert count to all registered listeners (episode STARTED). */
    fun emitAlert(sessionAlertCount: Int) {
        drowsinessActive.set(true)
        for (l in listeners) l.onAlert(sessionAlertCount)
    }

    /**
     * Emit that the active drowsiness episode has ENDED.
     *
     * Safe to call unconditionally, repeatedly, and from multiple threads at once — everything
     * after the first call following an [emitAlert] is ignored until the next episode begins.
     */
    fun emitDrowsinessEnded() {
        // Atomic test-and-claim. Losing this CAS means either no episode is in progress, or
        // another thread already announced the end of this one; either way there is nothing to do.
        if (!drowsinessActive.compareAndSet(true, false)) return
        for (l in recoveryListeners) l.onDrowsinessEnded()
    }
}
