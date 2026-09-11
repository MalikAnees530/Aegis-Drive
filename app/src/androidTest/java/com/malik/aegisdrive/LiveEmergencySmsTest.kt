package com.malik.aegisdrive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ⚠️ SENDS A REAL SMS TO THE REAL SAVED EMERGENCY CONTACT. ⚠️
 *
 * Deliberately NOT part of [FamilyAlertObserverTest] and deliberately NOT run by default — it is a
 * separate class so it can only execute when named explicitly:
 *
 *     adb shell am instrument -w -e class com.malik.aegisdrive.LiveEmergencySmsTest \
 *         com.malik.aegisdrive.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Every other test in this module replaces the dispatch with an in-memory seam precisely so nothing
 * leaves the handset. This one exists to verify the ONE leg those tests cannot cover: that the
 * message is genuinely accepted by the telephony stack and carried by a SIM with service.
 *
 * It uses NO overrides. Real saved number, real guard ladder, real SIM selection, real
 * [android.telephony.SmsManager], real multipart split, real sent-PendingIntent tracking. Exactly
 * one message is sent.
 *
 * Read the outcome from logcat (`EmergencyContact` tag) alongside the assertions here:
 *  - "Emergency SMS will be sent on subscription N"      -> which SIM the dual-SIM fallback picked
 *  - "Automated emergency SMS dispatched to X (N part(s))" -> accepted by the telephony service
 *  - "Emergency SMS part accepted by the network"        -> the radio actually sent it
 *  - "Emergency SMS REJECTED by radio: ..."              -> it did NOT go out, with the reason
 */
@RunWith(AndroidJUnit4::class)
class LiveEmergencySmsTest {

    @Test
    fun sendsOneRealEmergencySmsToTheSavedContact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val number = EmergencyContactManager.getSavedNumber(context)
        assertTrue(
            "No emergency contact is saved, so there is nothing to send to.",
            !number.isNullOrBlank()
        )
        assertTrue(
            "SEND_SMS is not granted, so the guard ladder would abort before the radio.",
            EmergencyContactManager.hasSmsPermission(context)
        )

        val dispatched = AtomicBoolean(false)
        val callbackArrived = CountDownLatch(1)

        // The real thing. Returns immediately; the send runs on Dispatchers.IO.
        EmergencyContactManager.sendAutomatedEmergencySMS(context) { ok ->
            dispatched.set(ok)
            callbackArrived.countDown()
        }

        assertTrue(
            "sendAutomatedEmergencySMS never invoked its result callback. That is the exact " +
                "silent-failure mode the callback exists to prevent - the caller's latch would " +
                "stay closed and the drive would be disarmed for good.",
            callbackArrived.await(60, TimeUnit.SECONDS)
        )

        assertTrue(
            "The SMS was NOT handed to the radio. Check logcat for which guard aborted: no " +
                "contact, non-dialable number, no telephony hardware, revoked permission, or a " +
                "blank message body.",
            dispatched.get()
        )

        // The synchronous result only proves the telephony SERVICE accepted it. The real verdict -
        // RESULT_OK vs RESULT_ERROR_NO_SERVICE / noDefault on a dual-SIM handset - arrives later on
        // the sent-PendingIntent. Hold the process open so that broadcast can land and be logged.
        Thread.sleep(25_000)
    }
}
