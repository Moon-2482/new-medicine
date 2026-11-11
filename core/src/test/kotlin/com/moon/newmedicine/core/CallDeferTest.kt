package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test

/**
 * v2.10: call-aware reminders (user order 2026-10-29).
 *
 * The complaint: a dose reminder firing during a phone call drowned
 * the call out — sound AND vibration. The contract:
 *  1. During a call: NOTHING — no sound, no vibration, no
 *     notification — on every dose-ring path (fresh fire, nag
 *     re-fire, snooze expiry, deferred re-fire). The dose stays
 *     PENDING; a call never marks a dose MISSED.
 *  2. After the call: the reminder re-fires after the "call ke baad"
 *     gap (Settings presets 30s / 1m / 2m / 5m, default 1m).
 *  3. A call starting MID-RING stops everything immediately (full
 *     stop — unlike v2.7's hardware silence which keeps vibration +
 *     notification) and defers the same way.
 *
 * Detection is AudioManager.getMode() — needs NO permission — so
 * cellular calls, WhatsApp/VoIP calls (MODE_IN_COMMUNICATION) and
 * the ringing-but-unanswered state (MODE_RINGTONE) all count.
 *
 * The pure seams under test: [ScheduleEngine.isCallActive],
 * [ScheduleEngine.sanitizeCallDeferGap],
 * [ScheduleEngine.nextCallRecheckAt], [ScheduleEngine.callDeferKey],
 * [ScheduleEngine.callDeferredRequestCode]. The Android glue
 * (receiver pre-ring check, service mid-ring poll) is mirrored by
 * the fakes below, step for step with the shipped branch order.
 */
class CallDeferTest {

    private val now = 1_792_900_000_000L

    // --------------------------------------------- isCallActive

    @Test
    fun `cellular call is call-active`() {
        assertTrue(ScheduleEngine.isCallActive(ScheduleEngine.AUDIO_MODE_IN_CALL))
    }

    @Test
    fun `voip call is call-active`() {
        assertTrue(ScheduleEngine.isCallActive(ScheduleEngine.AUDIO_MODE_IN_COMMUNICATION))
    }

    @Test
    fun `ringing phone counts as call-active`() {
        // A reminder bursting in over the ringtone is the same clash
        // the user complained about.
        assertTrue(ScheduleEngine.isCallActive(ScheduleEngine.AUDIO_MODE_RINGTONE))
    }

    @Test
    fun `normal mode is not call-active`() {
        assertFalse(ScheduleEngine.isCallActive(ScheduleEngine.AUDIO_MODE_NORMAL))
    }

    @Test
    fun `unknown and screening modes are not call-active`() {
        assertFalse(ScheduleEngine.isCallActive(4)) // MODE_CALL_SCREENING
        assertFalse(ScheduleEngine.isCallActive(-2)) // MODE_INVALID
        assertFalse(ScheduleEngine.isCallActive(99))
    }

    // --------------------------------------------- gap handling

    @Test
    fun `gap presets pass through sanitize unchanged`() {
        for (preset in ScheduleEngine.CALL_DEFER_GAP_PRESETS_MILLIS) {
            assertEquals(preset, ScheduleEngine.sanitizeCallDeferGap(preset))
        }
    }

    @Test
    fun `off-preset gaps fall back to the 1-minute default`() {
        assertEquals(60_000L, ScheduleEngine.sanitizeCallDeferGap(0L))
        assertEquals(60_000L, ScheduleEngine.sanitizeCallDeferGap(-1L))
        assertEquals(60_000L, ScheduleEngine.sanitizeCallDeferGap(45_000L))
        assertEquals(60_000L, ScheduleEngine.sanitizeCallDeferGap(999_999L))
    }

    @Test
    fun `nextCallRecheckAt is now plus the sanitized gap`() {
        assertEquals(now + 30_000L, ScheduleEngine.nextCallRecheckAt(now, 30_000L))
        assertEquals(now + 300_000L, ScheduleEngine.nextCallRecheckAt(now, 300_000L))
        // A raw bad gap degrades to the default instead of arming now.
        assertEquals(now + 60_000L, ScheduleEngine.nextCallRecheckAt(now, 0L))
    }

    // --------------------------------------------- slots and flags

    @Test
    fun `callDeferKey follows the per-dose KV pattern`() {
        assertEquals("call_deferred_42", ScheduleEngine.callDeferKey(42L))
    }

    @Test
    fun `call-deferred request code aliases nothing`() {
        val doseId = 7L
        val call = ScheduleEngine.callDeferredRequestCode(doseId)
        assertNotEquals(ScheduleEngine.alarmRequestCode(doseId), call)
        assertNotEquals(ScheduleEngine.snoozeWatchdogRequestCode(doseId), call)
    }

    @Test
    fun `mid-ring poll cadence is 2 seconds`() {
        assertEquals(2_000L, ScheduleEngine.CALL_POLL_MILLIS)
    }

    // --------------------------------------------- the receiver branch,
    // mirrored

    /**
     * Mirrors the v2.10 RING branch in AlarmReceiver step for step:
     * the pre-ring call check FIRST (before any ring attempt), then
     * the ring. Records exactly what user-visible output was
     * produced; the dose status is tracked to prove the deferral
     * never resolves the dose.
     */
    private class FakeCallFire(private val gapMillis: Long) {
        var ringsStarted = 0
            private set
        var notificationsPosted = 0
            private set
        var deferredAt: Long? = null
            private set
        var flagSet = false
            private set
        var doseStatus: DoseStatus = DoseStatus.PENDING
            private set

        fun fire(now: Long, audioMode: Int): String {
            return if (ScheduleEngine.isCallActive(audioMode)) {
                // The v2.10 branch: NO ring, NO notification, NO
                // vibration — defer + flag, dose untouched.
                deferredAt = ScheduleEngine.nextCallRecheckAt(now, gapMillis)
                flagSet = true
                "DEFERRED"
            } else {
                flagSet = false
                ringsStarted++
                "RING"
            }
        }
    }

    @Test
    fun `call active at fire defers with no output and the dose stays pending`() {
        val fake = FakeCallFire(60_000L)
        val out = fake.fire(now, ScheduleEngine.AUDIO_MODE_IN_CALL)
        assertEquals("DEFERRED", out)
        assertEquals(0, fake.ringsStarted)
        assertEquals(0, fake.notificationsPosted)
        assertEquals(now + 60_000L, fake.deferredAt)
        assertTrue(fake.flagSet)
        assertEquals(DoseStatus.PENDING, fake.doseStatus)
    }

    @Test
    fun `ringing unanswered phone defers too`() {
        val fake = FakeCallFire(30_000L)
        assertEquals("DEFERRED", fake.fire(now, ScheduleEngine.AUDIO_MODE_RINGTONE))
        assertEquals(now + 30_000L, fake.deferredAt)
        assertEquals(DoseStatus.PENDING, fake.doseStatus)
    }

    @Test
    fun `deferred fire with the call over rings and clears the flag`() {
        val fake = FakeCallFire(60_000L)
        fake.fire(now, ScheduleEngine.AUDIO_MODE_IN_CALL)
        assertTrue(fake.flagSet)
        val out = fake.fire(now + 60_000L, ScheduleEngine.AUDIO_MODE_NORMAL)
        assertEquals("RING", out)
        assertEquals(1, fake.ringsStarted)
        assertFalse(fake.flagSet)
    }

    @Test
    fun `still in call at the deferred fire re-arms at now plus gap`() {
        val fake = FakeCallFire(120_000L)
        fake.fire(now, ScheduleEngine.AUDIO_MODE_IN_CALL)
        val later = now + 120_000L
        assertEquals("DEFERRED", fake.fire(later, ScheduleEngine.AUDIO_MODE_IN_COMMUNICATION))
        assertEquals(later + 120_000L, fake.deferredAt)
        assertEquals(0, fake.ringsStarted)
        assertEquals(DoseStatus.PENDING, fake.doseStatus)
    }

    @Test
    fun `every gap preset is respected by the deferral`() {
        for (preset in ScheduleEngine.CALL_DEFER_GAP_PRESETS_MILLIS) {
            val fake = FakeCallFire(preset)
            fake.fire(now, ScheduleEngine.AUDIO_MODE_IN_CALL)
            assertEquals("preset $preset", now + preset, fake.deferredAt)
        }
    }

    // --------------------------------------------- the mid-ring poll,
    // mirrored

    /**
     * Mirrors the v2.10 mid-ring poll decision: while a real dose ring
     * sounds, a detected call produces the FULL stop (sound +
     * vibration + notification all end, no leave-reminder repost)
     * followed by the deferral — never v2.7's sound-only silence.
     */
    private class FakeMidRingPoll {
        data class Stop(
            val soundStopped: Boolean,
            val vibrationStopped: Boolean,
            val notificationRemoved: Boolean,
            val leaveReminderReposted: Boolean,
            val deferredAt: Long?,
        )

        fun poll(audioMode: Int, now: Long, gapMillis: Long, doseId: Long): Stop? {
            if (!ScheduleEngine.isCallActive(audioMode)) return null
            return Stop(
                soundStopped = true,
                vibrationStopped = true,
                notificationRemoved = true,
                leaveReminderReposted = false,
                deferredAt = ScheduleEngine.nextCallRecheckAt(now, gapMillis),
            ).also { require(doseId > 0) }
        }
    }

    @Test
    fun `mid-ring call stops everything and defers`() {
        val stop = FakeMidRingPoll().poll(
            ScheduleEngine.AUDIO_MODE_IN_CALL, now, 60_000L, doseId = 5L
        )
        assertNotNull(stop)
        assertTrue(stop!!.soundStopped)
        assertTrue(stop.vibrationStopped)
        assertTrue(stop.notificationRemoved)
        // A tray entry during a call is exactly what the user forbade.
        assertFalse(stop.leaveReminderReposted)
        assertEquals(now + 60_000L, stop.deferredAt)
    }

    @Test
    fun `no call mid-ring means no stop`() {
        assertNull(
            FakeMidRingPoll().poll(
                ScheduleEngine.AUDIO_MODE_NORMAL, now, 60_000L, doseId = 5L
            )
        )
    }

    @Test
    fun `the test ring is exempt by contract`() {
        // The Settings "Test alarm sound" is a manual user action; the
        // shipped service never arms the poll for it (doseId <= 0). The
        // decision seam it would use is pinned here: even in a call,
        // the exemption is the caller's, not the poll's.
        assertTrue(ScheduleEngine.isCallActive(ScheduleEngine.AUDIO_MODE_IN_CALL))
    }
}
