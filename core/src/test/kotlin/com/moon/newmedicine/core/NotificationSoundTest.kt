package com.moon.newmedicine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Alarm-grade sound selection + channel config versioning.
 *
 * The pure fallback order is: the bundled in-APK tone first (guaranteed
 * present), then the system TYPE_ALARM (audible even on silent/vibrate),
 * then TYPE_NOTIFICATION, then TYPE_RINGTONE. Ringtone type constants are
 * expressed as the platform Ints (4/2/1) with plain String stand-ins for
 * URIs so the pure core module never touches Android.
 */
class NotificationSoundTest {

    private val alarm = 4
    private val notification = 2
    private val ringtone = 1
    private val order = intArrayOf(alarm, notification, ringtone)

    @Test
    fun `alarm present wins and resolver is asked in preference order`() {
        val calls = mutableListOf<Int>()
        val result = NotificationSound.firstAvailable(order) { type ->
            calls.add(type)
            "uri:$type"
        }
        assertEquals("uri:$alarm", result)
        assertEquals(listOf(alarm), calls)
    }

    @Test
    fun `alarm missing falls back to notification`() {
        val calls = mutableListOf<Int>()
        val result = NotificationSound.firstAvailable(order) { type ->
            calls.add(type)
            if (type == alarm) null else "uri:$type"
        }
        assertEquals("uri:$notification", result)
        assertEquals(listOf(alarm, notification), calls)
    }

    @Test
    fun `alarm and notification missing falls back to ringtone`() {
        val result = NotificationSound.firstAvailable(order) { type ->
            if (type == ringtone) "uri:$type" else null
        }
        assertEquals("uri:$ringtone", result)
    }

    @Test
    fun `all missing returns null`() {
        val calls = mutableListOf<Int>()
        val result = NotificationSound.firstAvailable<String>(order) { type ->
            calls.add(type)
            null
        }
        assertNull(result)
        assertEquals(listOf(alarm, notification, ringtone), calls)
    }

    @Test
    fun `empty preference order returns null without calling resolver`() {
        var called = false
        val result = NotificationSound.firstAvailable<String>(intArrayOf()) {
            called = true
            "uri"
        }
        assertNull(result)
        assertEquals(false, called)
    }

    @Test
    fun `single type resolving to null returns null`() {
        val result = NotificationSound.firstAvailable<String>(intArrayOf(alarm)) { null }
        assertNull(result)
    }

    @Test
    fun `channel hash carries the alarm-v3 config version`() {
        val hash = NotificationSound.channelConfigHash(true, true)
        assertEquals(true, hash.contains("alarm-v3"))
    }

    @Test
    fun `channel hash differs from the alarm-v2 format so v1_11 channels are recreated`() {
        val v2Format = "true:true:alarm-v2"
        assertNotEquals(v2Format, NotificationSound.channelConfigHash(true, true))
    }

    @Test
    fun `channel hash varies with the settings it tracks`() {
        assertNotEquals(
            NotificationSound.channelConfigHash(true, true),
            NotificationSound.channelConfigHash(false, true),
        )
        assertNotEquals(
            NotificationSound.channelConfigHash(true, true),
            NotificationSound.channelConfigHash(true, false),
        )
        assertEquals(
            NotificationSound.channelConfigHash(true, false),
            NotificationSound.channelConfigHash(true, false),
        )
    }

    @Test
    fun `candidate list - bundled tone wins when present`() {
        val result = NotificationSound.firstAvailable(listOf("bundled-uri", "system-uri"))
        assertEquals("bundled-uri", result)
    }

    @Test
    fun `candidate list - bundled null falls through to system default`() {
        val result = NotificationSound.firstAvailable(listOf(null, "system-uri"))
        assertEquals("system-uri", result)
    }

    @Test
    fun `candidate list - all null returns null`() {
        val result = NotificationSound.firstAvailable<String>(listOf(null, null))
        assertNull(result)
    }

    @Test
    fun `candidate list - empty list returns null`() {
        val result = NotificationSound.firstAvailable<String>(emptyList())
        assertNull(result)
    }
}
