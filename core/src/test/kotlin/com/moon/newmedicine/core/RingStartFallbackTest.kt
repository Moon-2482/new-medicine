package com.moon.newmedicine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.8: the ring-start failure policy (live failure 2026-10-23, §30).
 *
 * On v2.7 the evening dose produced NO output at all in the background:
 * `AlarmReceiver`'s fail-loud catch only covers a REFUSED
 * `startForegroundService` — once the start is accepted, `ring()` has
 * already returned true, and `onStartCommand` / `startRinging` carried
 * no try/catch at all. Any failure inside the service in that window
 * (system FGS enforcement, process death, an uncaught throw in the
 * enlarged in-service surface) therefore meant zero user-visible
 * output, silently. The v2.8 contract: the ring is sacred — a failure
 * BEFORE the foreground notification is posted falls back to the
 * plain dose reminder (a dose is never invisible); a failure AFTER it
 * keeps the ring (an add-on must never tear down a live reminder);
 * the hardware-silence triggers register LAST, fully isolated, so a
 * throwing registration cannot stop the ring at all.
 *
 * [FakeRingStart] mirrors `ReminderAlarmService.startRinging`'s
 * orchestration step for step (same phases, same decision function,
 * same isolation), so the failure modes are replayable here.
 */
class RingStartFallbackTest {

    // ---------------------------------------------------- truth table

    @Test
    fun `failure before foreground on a dose posts the plain reminder`() {
        assertEquals(
            RingStartFallback.POST_PLAIN_REMINDER,
            ScheduleEngine.ringStartFallback(RingStartPhase.BEFORE_FOREGROUND, isTest = false),
        )
    }

    @Test
    fun `failure before foreground on the test ring stops quietly`() {
        // The user is looking at Settings when he taps Test — no dose is
        // at stake, and posting a dose reminder would be wrong.
        assertEquals(
            RingStartFallback.STOP_QUIETLY,
            ScheduleEngine.ringStartFallback(RingStartPhase.BEFORE_FOREGROUND, isTest = true),
        )
    }

    @Test
    fun `failure after foreground keeps the ring, dose or test`() {
        assertEquals(
            RingStartFallback.CONTINUE_RING,
            ScheduleEngine.ringStartFallback(RingStartPhase.AFTER_FOREGROUND, isTest = false),
        )
        assertEquals(
            RingStartFallback.CONTINUE_RING,
            ScheduleEngine.ringStartFallback(RingStartPhase.AFTER_FOREGROUND, isTest = true),
        )
    }

    // ---------------------------------------------------- orchestration

    @Test
    fun `clean start establishes the ring and registers the triggers`() {
        val start = FakeRingStart(failAt = null)
        start.run()
        assertTrue(start.ringEstablished)
        assertTrue(start.triggersRegistered)
        assertTrue(start.capArmed)
        assertFalse(start.fallbackPosted)
        assertFalse(start.stoppedQuietly)
    }

    @Test
    fun `throwing silence-trigger registration cannot stop the ring`() {
        // The v2.8 headline case: the add-on fails, the reminder must not.
        val start = FakeRingStart(failAt = "triggers")
        start.run()
        assertTrue("ring must survive a trigger-registration failure", start.ringEstablished)
        assertTrue(start.capArmed)
        assertFalse(start.triggersRegistered)
        assertFalse("no fallback needed — the ring itself is up", start.fallbackPosted)
        assertFalse(start.stoppedQuietly)
    }

    @Test
    fun `failure at the foreground step falls back to the plain reminder`() {
        val start = FakeRingStart(failAt = "foreground")
        start.run()
        assertFalse(start.ringEstablished)
        assertTrue("a dose must never be invisible", start.fallbackPosted)
        assertFalse(start.triggersRegistered)
    }

    @Test
    fun `failure at the foreground step of a test ring stops quietly`() {
        val start = FakeRingStart(failAt = "foreground", isTest = true)
        start.run()
        assertFalse(start.ringEstablished)
        assertFalse(start.fallbackPosted)
        assertTrue(start.stoppedQuietly)
    }

    @Test
    fun `failure in the alert step keeps the posted ring and re-arms the cap`() {
        val start = FakeRingStart(failAt = "alert")
        start.run()
        assertTrue("foreground notification is already up", start.foregroundPosted)
        assertFalse(start.fallbackPosted)
        assertFalse(start.stoppedQuietly)
        assertTrue("the 2-minute cap must still bound the episode", start.capArmed)
    }

    /**
     * Faithful miniature of the service's v2.8 `startRinging`: the
     * sacred region (foreground post, then the alert) runs under the
     * phase guard and consults [ScheduleEngine.ringStartFallback] on
     * failure; the silence triggers run after it, isolated, their
     * failure swallowed by design. [failAt] names the step that throws.
     */
    private class FakeRingStart(
        private val failAt: String?,
        private val isTest: Boolean = false,
    ) {
        var foregroundPosted = false
        var alertStarted = false
        var capArmed = false
        var triggersRegistered = false
        var fallbackPosted = false
        var stoppedQuietly = false

        val ringEstablished: Boolean get() = foregroundPosted && alertStarted

        fun run() {
            var phase = RingStartPhase.BEFORE_FOREGROUND
            try {
                step("foreground") { foregroundPosted = true }
                phase = RingStartPhase.AFTER_FOREGROUND
                step("alert") { alertStarted = true }
                capArmed = true
            } catch (t: Throwable) {
                when (ScheduleEngine.ringStartFallback(phase, isTest)) {
                    RingStartFallback.POST_PLAIN_REMINDER -> fallbackPosted = true
                    RingStartFallback.STOP_QUIETLY -> stoppedQuietly = true
                    // The foreground notification is up; never tear it
                    // down for a later failure — just keep the episode
                    // bounded by its cap.
                    RingStartFallback.CONTINUE_RING -> capArmed = true
                }
                return
            }
            try {
                step("triggers") { triggersRegistered = true }
            } catch (t: Throwable) {
                // Isolated by design: the ring is never touched.
            }
        }

        private fun step(name: String, block: () -> Unit) {
            if (failAt == name) throw RuntimeException("simulated failure at $name")
            block()
        }
    }
}
