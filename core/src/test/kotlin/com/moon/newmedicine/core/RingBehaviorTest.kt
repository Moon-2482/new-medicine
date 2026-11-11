package com.moon.newmedicine.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.6: ringer-mode respect for the dose-reminder ring. The reminder
 * sound deliberately plays on the ALARM stream (which bypasses the
 * ringer mode), so the ring must decide explicitly: NORMAL → full
 * sound, VIBRATE → vibration only, SILENT → visual only.
 *
 * The core is Android-free, so the ringer modes are the raw
 * AudioManager Int values (SILENT = 0, VIBRATE = 1, NORMAL = 2).
 * Unknown / future values must fall back to the full ring: a ring that
 * sounds when it arguably shouldn't is stopped by one tap, while a
 * reminder that never alerts is a missed dose.
 */
class RingBehaviorTest {

    @Test
    fun normalModeRingsWithSound() {
        assertEquals(
            RingBehavior.SOUND_AND_VIBRATE,
            ScheduleEngine.ringBehaviorFor(2), // RINGER_MODE_NORMAL
        )
    }

    @Test
    fun vibrateModeIsVibrateOnly() {
        assertEquals(
            RingBehavior.VIBRATE_ONLY,
            ScheduleEngine.ringBehaviorFor(1), // RINGER_MODE_VIBRATE
        )
    }

    @Test
    fun silentModeIsVisualOnly() {
        assertEquals(
            RingBehavior.VISUAL_ONLY,
            ScheduleEngine.ringBehaviorFor(0), // RINGER_MODE_SILENT
        )
    }

    @Test
    fun unknownNegativeModeDefaultsToFullRing() {
        assertEquals(
            RingBehavior.SOUND_AND_VIBRATE,
            ScheduleEngine.ringBehaviorFor(-1),
        )
    }

    @Test
    fun unknownFutureModeDefaultsToFullRing() {
        assertEquals(
            RingBehavior.SOUND_AND_VIBRATE,
            ScheduleEngine.ringBehaviorFor(99),
        )
    }

    @Test
    fun extremeValuesDefaultToFullRing() {
        assertEquals(
            RingBehavior.SOUND_AND_VIBRATE,
            ScheduleEngine.ringBehaviorFor(Int.MIN_VALUE),
        )
        assertEquals(
            RingBehavior.SOUND_AND_VIBRATE,
            ScheduleEngine.ringBehaviorFor(Int.MAX_VALUE),
        )
    }
}
