package com.malik.aegisdrive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device verification of BOTH Family Notification triggers, exercised against the real
 * [FamilyAlertObserver] and the real [AlertBus] inside the app process on the real handset.
 *
 * NOTHING IS SENT. [FamilyAlertObserver.smsDispatcherOverride] replaces the dispatch, so no
 * SmsManager call is made and no message reaches a real person — while still proving WHICH
 * trigger claimed the drive's single send-slot, and how many times.
 *
 * These tests drive [AlertBus] directly rather than the camera, because the two edges the bus
 * carries (rising = new alarm, falling = alarm cleared) are the complete contract between
 * MonitorFragment and the notification feature. Everything the triggers do is a function of
 * those edges and of time, both of which are reproducible here; a real drowsy face is not.
 *
 * The 45-second cases really do wait 45+ seconds. That is deliberate — the timeout is the
 * behaviour under test, and shortening it would test a different system.
 */
@RunWith(AndroidJUnit4::class)
class FamilyAlertObserverTest {

    /**
     * Nullable rather than lateinit on purpose: if setUp() fails before this is assigned, a
     * lateinit access in tearDown() throws and MASKS the real failure behind
     * "lateinit property observer has not been initialized".
     */
    private var observer: FamilyAlertObserver? = null


    /** Every trigger that claimed the send-slot, in order. Written from the timer coroutine. */
    private val fired = CopyOnWriteArrayList<FamilyAlertObserver.Trigger>()

    /** Lets a test block until a trigger actually fires instead of sleeping longer than needed. */
    @Volatile
    private var firedLatch = CountDownLatch(1)

    /** What the stubbed dispatcher reports back, mimicking a radio that accepted or refused. */
    @Volatile
    private var dispatchSucceeds = true

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Both triggers early-return when no contact is saved, which would make every assertion
        // below pass for the WRONG reason. This uses the IN-MEMORY override rather than writing a
        // dummy number to the real preference: an interrupted run (unplugged device, dropped ADB
        // transport) skips tearDown, and a written dummy would then be left PERSISTED as the
        // user's real emergency contact. An override simply dies with the process.
        EmergencyContactManager.contactOverride = TEST_CONTACT_NUMBER

        fired.clear()
        firedLatch = CountDownLatch(1)
        dispatchSucceeds = true

        FamilyAlertObserver.smsDispatcherOverride = { trigger, onResult ->
            fired += trigger
            firedLatch.countDown()
            onResult(dispatchSucceeds)
        }

        // The latch and the last-seen count are PROCESS-scoped by design (they must survive
        // Activity recreation mid-drive), so a previous test's claim would leak in and mask a
        // real failure here.
        FamilyAlertObserver.rearmAfterAsyncFailure()
        AlertBus.emitDrowsinessEnded()

        observer = FamilyAlertObserver(context).also { it.start() }
    }

    @After
    fun tearDown() {
        observer?.stop()
        observer = null
        AlertBus.emitDrowsinessEnded()
        FamilyAlertObserver.rearmAfterAsyncFailure()
        // CRITICAL: a leaked override would silently disable the real emergency SMS.
        FamilyAlertObserver.smsDispatcherOverride = null
        // The user's stored number was never written to, so there is nothing to restore - only
        // this in-memory stand-in to clear.
        EmergencyContactManager.contactOverride = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TRIGGER 1 — accumulation (the 11th alert)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The threshold is EXCEEDED, not merely reached. An off-by-one here would text the family a
     * full alert early on every single drive.
     */
    @Test
    fun alertsUpToThreshold_doNotNotify() {
        driveAlerts(1..EmergencyContactManager.ALERT_THRESHOLD)

        assertEquals(
            "Family was notified at or below the ${EmergencyContactManager.ALERT_THRESHOLD}-alert " +
                "threshold - the accumulation trigger is off by one.",
            emptyList<FamilyAlertObserver.Trigger>(),
            fired.toList()
        )
    }

    /** The 11th alert is the first that must fire, and it must fire exactly once. */
    @Test
    fun alertAboveThreshold_notifiesFamilyExactlyOnce() {
        driveAlerts(1..EmergencyContactManager.ALERT_THRESHOLD + 1)

        assertEquals(
            "Alert ${EmergencyContactManager.ALERT_THRESHOLD + 1} did not fire the accumulation " +
                "trigger exactly once.",
            listOf(FamilyAlertObserver.Trigger.ACCUMULATION),
            fired.toList()
        )
    }

    /**
     * Alerts 12, 13, 14... must all lose the send-slot CAS. Without this the family is re-texted
     * on every further alert for the rest of the journey.
     */
    @Test
    fun alertsBeyondThreshold_doNotResend() {
        driveAlerts(1..EmergencyContactManager.ALERT_THRESHOLD + 1)
        driveAlerts(EmergencyContactManager.ALERT_THRESHOLD + 2..EmergencyContactManager.ALERT_THRESHOLD + 6)

        assertEquals(
            "The family was texted more than once in a single drive - the send-slot latch leaks.",
            1,
            fired.size
        )
    }

    /**
     * A drive's first alert must re-open the slot claimed by the PREVIOUS drive. Without this a
     * driver whose family was texted once would never be helped again for the life of the process.
     */
    @Test
    fun firstAlertOfNewDrive_reArmsTheSendSlot() {
        driveAlerts(1..EmergencyContactManager.ALERT_THRESHOLD + 1)
        // MonitorFragment.startMonitoring() zeroes alertCount, so the next drive restarts at 1.
        driveAlerts(1..EmergencyContactManager.ALERT_THRESHOLD + 1)

        assertEquals(
            "A new drive did not re-arm the send-slot, so its driver could never be helped.",
            listOf(
                FamilyAlertObserver.Trigger.ACCUMULATION,
                FamilyAlertObserver.Trigger.ACCUMULATION
            ),
            fired.toList()
        )
    }

    /**
     * Re-delivering the SAME count must not be read as a new drive. If two observers are ever
     * registered at once, both receive every count; a naive `count <= previous` test would let the
     * second delivery re-open the slot mid-drive and permit a duplicate SMS.
     */
    @Test
    fun repeatedSameCount_isNotMistakenForANewDrive() {
        driveAlerts(1..EmergencyContactManager.ALERT_THRESHOLD + 1)

        // The same post-threshold count delivered again, as a duplicate registration would produce.
        repeat(3) {
            AlertBus.emitAlert(EmergencyContactManager.ALERT_THRESHOLD + 1)
            AlertBus.emitDrowsinessEnded()
        }

        assertEquals(
            "A repeated alert count re-opened the send-slot mid-drive and allowed a duplicate SMS.",
            1,
            fired.size
        )
    }

    /** A dispatch the radio refused must NOT consume the drive's one send-slot. */
    @Test
    fun failedDispatch_reArmsSoTheNextAlertRetries() {
        dispatchSucceeds = false
        driveAlerts(1..EmergencyContactManager.ALERT_THRESHOLD + 1)
        assertEquals("The first attempt did not happen at all.", 1, fired.size)

        driveAlerts(EmergencyContactManager.ALERT_THRESHOLD + 2..EmergencyContactManager.ALERT_THRESHOLD + 2)

        assertEquals(
            "A FAILED send consumed the drive's only send-slot, so the family is never told.",
            2,
            fired.size
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TRIGGER 2 — 45-second continuous unconsciousness
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The entire reason TRIGGER 2 exists: an unconscious driver produces exactly ONE alert and
     * then nothing at all, so the accumulation counter alone would never reach them.
     */
    @Test
    fun unbrokenDrowsinessBeyond45s_notifiesOnASingleAlert() {
        AlertBus.emitAlert(1) // one alert only - nowhere near the accumulation threshold

        assertTrue(
            "45s of unbroken drowsiness did NOT fire. An unconscious driver would never be helped.",
            firedLatch.await(UNCONSCIOUSNESS_WAIT_MS, TimeUnit.MILLISECONDS)
        )
        assertEquals(
            "The wrong trigger fired for a continuous-closure episode.",
            listOf(FamilyAlertObserver.Trigger.UNCONSCIOUSNESS),
            fired.toList()
        )
    }

    /** Recovering before 45s must cancel the clock, or every ordinary drive ends in a false alarm. */
    @Test
    fun recoveryBefore45s_cancelsTheTimer() {
        AlertBus.emitAlert(1)
        Thread.sleep(5_000)
        AlertBus.emitDrowsinessEnded() // driver woke up

        Thread.sleep(UNCONSCIOUSNESS_WAIT_MS)

        assertEquals(
            "A FALSE emergency fired after the driver had already recovered.",
            emptyList<FamilyAlertObserver.Trigger>(),
            fired.toList()
        )
    }

    /**
     * Each episode deserves a FRESH window. A new alert 30s in must restart the clock rather than
     * inherit the remaining 15s and fire after only 20s of actual unbroken drowsiness.
     */
    @Test
    fun newEpisodeRestartsThe45sWindow() {
        AlertBus.emitAlert(1)
        Thread.sleep(30_000)

        AlertBus.emitDrowsinessEnded() // episode 1 ends at 30s
        AlertBus.emitAlert(2)          // episode 2 begins - the clock must restart from zero
        Thread.sleep(20_000)           // 50s since the first alert, but only 20s into this episode

        assertEquals(
            "The 45s window did not restart on a new episode - it fired after only 20s of " +
                "unbroken drowsiness, which would cause false emergencies on ordinary drives.",
            emptyList<FamilyAlertObserver.Trigger>(),
            fired.toList()
        )
    }

    /**
     * The bus's own edge detection must collapse a per-frame recovery branch into ONE falling
     * edge; MonitorFragment calls its stop path ~30x/second while the driver is perfectly fine.
     */
    @Test
    fun repeatedRecoveryEmissions_areCollapsedToOneEdge() {
        AlertBus.emitAlert(1)
        assertTrue("Bus did not register the episode as active.", AlertBus.isDrowsinessActive)

        repeat(50) { AlertBus.emitDrowsinessEnded() }
        assertTrue("Bus still believes an ended episode is running.", !AlertBus.isDrowsinessActive)

        Thread.sleep(UNCONSCIOUSNESS_WAIT_MS)
        assertEquals(
            "A stale timer survived the recovery edge and fired a false emergency.",
            emptyList<FamilyAlertObserver.Trigger>(),
            fired.toList()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // The two triggers share ONE send-slot
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Whichever trigger fires first wins the drive's single send-slot; the other stands down.
     * They can never both text the family for the same journey.
     */
    @Test
    fun unconsciousnessClaimsSlot_thenAccumulationStandsDown() {
        AlertBus.emitAlert(1)
        assertTrue(
            "TRIGGER 2 never fired, so this cannot prove TRIGGER 1 stands down.",
            firedLatch.await(UNCONSCIOUSNESS_WAIT_MS, TimeUnit.MILLISECONDS)
        )

        // Now push the SAME drive past the accumulation threshold.
        driveAlerts(2..EmergencyContactManager.ALERT_THRESHOLD + 1)

        assertEquals(
            "Both triggers texted the family for one drive - the shared send-slot is not holding.",
            listOf(FamilyAlertObserver.Trigger.UNCONSCIOUSNESS),
            fired.toList()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Emits each count as a COMPLETE episode (rising edge then falling edge), which is what
     * MonitorFragment produces for ordinary alerts that the driver recovers from. Closing each
     * episode keeps the 45s trigger out of the way so accumulation is tested in isolation.
     */
    private fun driveAlerts(counts: IntRange) {
        for (count in counts) {
            AlertBus.emitAlert(count)
            AlertBus.emitDrowsinessEnded()
        }
    }

    companion object {
        /** The 45s window plus slack for coroutine scheduling on a real, loaded handset. */
        private const val UNCONSCIOUSNESS_WAIT_MS = 52_000L

        /**
         * Reserved test range (Ofcom TV-drama block) - never allocated to a real subscriber, so
         * even a bug that bypassed [FamilyAlertObserver.smsDispatcherOverride] could not reach a
         * person. Held only in memory via [EmergencyContactManager.contactOverride]; it is never
         * written to storage and never dialled.
         */
        private const val TEST_CONTACT_NUMBER = "+447700900123"
    }
}
