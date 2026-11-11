package com.moon.newmedicine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1: pure reminder-sound resolution (the ringing service's play choice)
 * and the ring cap. The Android-side loading of a [SelectedSound.Custom]
 * URI (and its fall back to the bundled tone when the URI is dead) is
 * device behavior; what is pinned here is the resolution ORDER and the cap.
 */
class ReminderSoundTest {

    @Test
    fun nullStoredUriMeansBuiltIn() {
        assertEquals(SelectedSound.BuiltIn, NotificationSound.selectSound(null))
    }

    @Test
    fun emptyStoredUriMeansBuiltIn() {
        assertEquals(SelectedSound.BuiltIn, NotificationSound.selectSound(""))
    }

    @Test
    fun blankStoredUriMeansBuiltIn() {
        assertEquals(SelectedSound.BuiltIn, NotificationSound.selectSound("   "))
    }

    @Test
    fun storedPickerUriWins() {
        val uri = "content://media/internal/audio/media/12?title=Alarm"
        val sel = NotificationSound.selectSound(uri)
        assertTrue(sel is SelectedSound.Custom)
        assertEquals(uri, (sel as SelectedSound.Custom).uriString)
    }

    @Test
    fun storedSystemAlarmUriWins() {
        val uri = "content://settings/system/alarm_alert"
        val sel = NotificationSound.selectSound(uri)
        assertEquals(SelectedSound.Custom(uri), sel)
    }

    @Test
    fun ringCapIsTwoMinutes() {
        assertEquals(120_000L, NotificationSound.RING_MAX_MILLIS)
    }
}
