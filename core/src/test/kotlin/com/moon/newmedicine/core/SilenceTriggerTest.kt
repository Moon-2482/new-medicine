package com.moon.newmedicine.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.7: hardware-button silence for the reminder ring. While the ring
 * is sounding, a power press (screen off/on) or any volume press must
 * kill ONLY the sound — the dose stays PENDING, the notification and
 * vibration carry on, and the next nag / re-fire rings audibly again.
 *
 * The Android-free core holds the two decisions the service makes:
 *  - [ScheduleEngine.volumeChangedSince]: did any WATCHED stream's
 *    volume change since the snapshot taken at ring start? Stream ids
 *    are raw AudioManager Ints: VOICE_CALL = 0, SYSTEM = 1, RING = 2,
 *    MUSIC = 3, ALARM = 4, NOTIFICATION = 5.
 *  - [ScheduleEngine.shouldSilenceSound]: is there a sounding,
 *    not-yet-silenced episode to silence?
 */
class SilenceTriggerTest {

    private val snapshot = mapOf(
        4 to 7, // STREAM_ALARM
        2 to 5, // STREAM_RING
        3 to 9, // STREAM_MUSIC
        5 to 4, // STREAM_NOTIFICATION
    )

    @Test
    fun noVolumeChangeDoesNotSilence() {
        assertFalse(ScheduleEngine.volumeChangedSince(snapshot, snapshot))
    }

    @Test
    fun alarmVolumeChangeSilences() {
        assertTrue(ScheduleEngine.volumeChangedSince(snapshot, snapshot + (4 to 6)))
    }

    @Test
    fun ringVolumeChangeSilences() {
        assertTrue(ScheduleEngine.volumeChangedSince(snapshot, snapshot + (2 to 0)))
    }

    @Test
    fun musicVolumeChangeSilences() {
        assertTrue(ScheduleEngine.volumeChangedSince(snapshot, snapshot + (3 to 10)))
    }

    @Test
    fun notificationVolumeChangeSilences() {
        assertTrue(ScheduleEngine.volumeChangedSince(snapshot, snapshot + (5 to 5)))
    }

    @Test
    fun unwatchedStreamChangesAreIgnored() {
        // STREAM_VOICE_CALL (0) and STREAM_SYSTEM (1) are not watched:
        // inside a call the keys drive the call stream, which the
        // settings observer cannot reliably surface — the power button
        // is the mid-call trigger (TEST-REPORT §29).
        val current = snapshot + (0 to 3) + (1 to 2)
        assertFalse(ScheduleEngine.volumeChangedSince(snapshot, current))
    }

    @Test
    fun watchedStreamMissingFromSnapshotProvesNothing() {
        val partial = mapOf(4 to 7)
        val current = mapOf(4 to 7, 2 to 0, 3 to 0, 5 to 0)
        assertFalse(ScheduleEngine.volumeChangedSince(partial, current))
    }

    @Test
    fun watchedStreamMissingFromCurrentCountsAsChanged() {
        val current = snapshot - 4
        assertTrue(ScheduleEngine.volumeChangedSince(snapshot, current))
    }

    @Test
    fun screenEventWhileSoundingSilences() {
        assertTrue(ScheduleEngine.shouldSilenceSound(soundPlaying = true, alreadySilenced = false))
    }

    @Test
    fun screenEventAfterSilenceIsNoOp() {
        assertFalse(ScheduleEngine.shouldSilenceSound(soundPlaying = true, alreadySilenced = true))
    }

    @Test
    fun triggerWithoutSoundIsNoOp() {
        // VIBRATE_ONLY / VISUAL_ONLY episodes have no player: triggers
        // must not disturb the vibration or the notification.
        assertFalse(ScheduleEngine.shouldSilenceSound(soundPlaying = false, alreadySilenced = false))
        assertFalse(ScheduleEngine.shouldSilenceSound(soundPlaying = false, alreadySilenced = true))
    }
}
