package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * Wake detection for the morning session: instead of requiring a manual
 * "Start Session" tap, the app watches for first phone usage inside a
 * morning window (default 06:00–11:00) and pushes a "Drops start karein?"
 * prompt. All decisions are pure functions of (clock + saved state), tested
 * here with a fake clock — no waiting hours.
 *
 * Covered:
 *  1. in-window + recent usage -> prompt
 *  2. usage 2h ago -> no prompt
 *  3. outside the window -> no prompt (even with fresh usage)
 *  4. morning already started -> no prompt
 *  5. dismiss -> re-prompt on the next tick, but never twice in one tick
 *  6. usage access denied -> fallback prompts once at window start per day
 *  7. double "Start karein" action -> single session (second tap is a no-op)
 *  8. window end -> the chain stops (next check is tomorrow's window start)
 */
class WakeDetectorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val settings = DoseSettings() // window 06:00-11:00, check every 15 min
    private val window = ScheduleEngine.wakeWindow(settings)
    private val dayKey = "2026-10-19" // a Monday

    private fun at(hour: Int, minute: Int): Long =
        ScheduleEngine.minutesOfDayToMillis(dayKey, hour * 60 + minute, zone)

    // ------------------------------------------------------------------ 1

    @Test
    fun `1 - in-window with recent phone usage prompts`() {
        val now = at(8, 0)
        val lastUsed = now - 3 * 60_000L // used 3 minutes ago
        assertTrue(
            ScheduleEngine.shouldPromptWake(now, window, lastUsed, false, null, zone)
        )
    }

    // ------------------------------------------------------------------ 2

    @Test
    fun `2 - usage 2 hours ago does not prompt`() {
        val now = at(8, 0)
        val lastUsed = now - 2 * 60 * 60_000L // used 2 hours ago
        assertFalse(
            ScheduleEngine.shouldPromptWake(now, window, lastUsed, false, null, zone)
        )
    }

    // ------------------------------------------------------------------ 3

    @Test
    fun `3 - outside the window never prompts even with fresh usage`() {
        val now = at(12, 0) // window is 06:00-11:00
        val lastUsed = now - 60_000L // used 1 minute ago
        assertFalse(
            ScheduleEngine.shouldPromptWake(now, window, lastUsed, false, null, zone)
        )
        assertFalse(ScheduleEngine.isInWakeWindow(now, settings, zone))
    }

    // ------------------------------------------------------------------ 4

    @Test
    fun `4 - morning already started never prompts`() {
        val now = at(8, 0)
        val lastUsed = now - 60_000L
        assertFalse(
            ScheduleEngine.shouldPromptWake(now, window, lastUsed, true, null, zone)
        )
        assertFalse(
            ScheduleEngine.shouldPromptWakeFallback(now, window, true, null, dayKey, zone)
        )
    }

    // ------------------------------------------------------------------ 5

    @Test
    fun `5 - dismiss re-prompts on the next tick but never twice in one tick`() {
        val tick1 = at(8, 0)
        // First tick: fresh usage, nothing prompted yet -> prompt.
        assertTrue(
            ScheduleEngine.shouldPromptWake(tick1, window, tick1 - 60_000L, false, null, zone)
        )
        // The user dismisses (no action). A duplicated evaluation one minute
        // later (e.g. USER_PRESENT racing the alarm tick) must NOT prompt again.
        val dupTick = tick1 + 60_000L
        assertFalse(
            "duplicate evaluation inside one tick must not re-prompt",
            ScheduleEngine.shouldPromptWake(dupTick, window, dupTick - 30_000L, false, tick1, zone),
        )
        // Next tick (15 min later): still not started, phone used -> re-prompt.
        val tick2 = tick1 + 15 * 60_000L
        assertTrue(
            "dismissed prompt must re-appear on the next tick",
            ScheduleEngine.shouldPromptWake(tick2, window, tick2 - 60_000L, false, tick1, zone),
        )
    }

    // ------------------------------------------------------------------ 6

    @Test
    fun `6 - usage access denied falls back to one prompt at window start per day`() {
        // No usage signal: the usage path must stay silent (the fallback path
        // is used instead, so the two can never double-fire).
        assertFalse(
            ScheduleEngine.shouldPromptWake(at(8, 0), window, null, false, null, zone)
        )
        // Fallback: first tick at/after the window start prompts once.
        val windowStart = at(6, 0)
        assertTrue(
            ScheduleEngine.shouldPromptWakeFallback(windowStart, window, false, null, dayKey, zone)
        )
        // Same day, later tick: already prompted -> silent.
        assertFalse(
            ScheduleEngine.shouldPromptWakeFallback(at(9, 30), window, false, dayKey, dayKey, zone)
        )
        // Next day: the once-per-day budget resets.
        val nextDayKey = "2026-10-20"
        val nextDayStart = ScheduleEngine.minutesOfDayToMillis(nextDayKey, 6 * 60, zone)
        assertTrue(
            ScheduleEngine.shouldPromptWakeFallback(nextDayStart, window, false, dayKey, nextDayKey, zone)
        )
    }

    // ------------------------------------------------------------------ 7

    @Test
    fun `7 - double Start action results in a single morning session`() {
        assertTrue(
            "first Start karein tap must start the session",
            ScheduleEngine.shouldStartMorningFromWake(morningStartedToday = false),
        )
        assertFalse(
            "second Start karein tap must be a no-op",
            ScheduleEngine.shouldStartMorningFromWake(morningStartedToday = true),
        )
    }

    // ------------------------------------------------------------------ 8

    @Test
    fun `8 - window end stops the chain until tomorrow's window start`() {
        // 10:50 + 15 min would land at 11:05, past the 11:00 window end:
        // the chain must stop instead of firing outside the window.
        val late = at(10, 50)
        val next = ScheduleEngine.nextWakeCheckAt(late, settings, zone)
        val expectedTomorrowStart =
            ScheduleEngine.minutesOfDayToMillis("2026-10-20", 6 * 60, zone)
        assertEquals(expectedTomorrowStart, next)
    }

    // ------------------------------------------------------------- extras

    @Test
    fun `before the window the next check is the window start today`() {
        assertEquals(at(6, 0), ScheduleEngine.nextWakeCheckAt(at(5, 30), settings, zone))
    }

    @Test
    fun `inside the window the next check is one interval later`() {
        assertEquals(at(8, 15), ScheduleEngine.nextWakeCheckAt(at(8, 0), settings, zone))
    }

    @Test
    fun `window boundaries are start-inclusive and end-exclusive`() {
        assertTrue(ScheduleEngine.isInWakeWindow(at(6, 0), settings, zone))
        assertTrue(ScheduleEngine.isInWakeWindow(at(10, 59), settings, zone))
        assertFalse(ScheduleEngine.isInWakeWindow(at(11, 0), settings, zone))
        assertFalse(ScheduleEngine.isInWakeWindow(at(5, 59), settings, zone))
    }

    @Test
    fun `usage exactly at the interval edge still counts as just used`() {
        val now = at(8, 0)
        val edge = now - 15 * 60_000L // exactly one check interval ago
        assertTrue(ScheduleEngine.shouldPromptWake(now, window, edge, false, null, zone))
        val justOver = now - 15 * 60_000L - 1L
        assertFalse(ScheduleEngine.shouldPromptWake(now, window, justOver, false, null, zone))
    }

    @Test
    fun `degenerate window never prompts and never crashes`() {
        val bad = settings.copy(
            wakeWindowStartMinutesOfDay = 12 * 60,
            wakeWindowEndMinutesOfDay = 6 * 60,
        )
        val badWindow = ScheduleEngine.wakeWindow(bad)
        assertFalse(ScheduleEngine.isInWakeWindow(at(8, 0), bad, zone))
        assertFalse(
            ScheduleEngine.shouldPromptWake(at(8, 0), badWindow, at(7, 59), false, null, zone)
        )
        assertFalse(
            ScheduleEngine.shouldPromptWakeFallback(at(8, 0), badWindow, false, null, dayKey, zone)
        )
        // The chain still schedules a next check (tomorrow's start) instead of stalling.
        val next = ScheduleEngine.nextWakeCheckAt(at(8, 0), bad, zone)
        assertTrue(next > at(8, 0))
    }

    @Test
    fun `wake check interval is configurable`() {
        val custom = settings.copy(wakeCheckIntervalMinutes = 30)
        val customWindow = ScheduleEngine.wakeWindow(custom)
        assertEquals(30 * 60_000L, customWindow.checkIntervalMillis)
        // 20 minutes ago is fresh for a 30-min interval but stale for 15.
        val now = at(8, 0)
        val used20Ago = now - 20 * 60_000L
        assertTrue(ScheduleEngine.shouldPromptWake(now, customWindow, used20Ago, false, null, zone))
        assertFalse(ScheduleEngine.shouldPromptWake(now, window, used20Ago, false, null, zone))
        assertEquals(at(8, 30), ScheduleEngine.nextWakeCheckAt(now, custom, zone))
    }
}

/**
 * v1.3 — decideWakePrompt: one unified pure decision (the v1.2 "no morning
 * notification and no idea why" report). Covers the fallback catch-up
 * guarantee and the usage-access-granted-but-no-data fallthrough.
 */
class DecideWakePromptTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val settings = DoseSettings() // window 06:00-11:00, check every 15 min
    private val window = ScheduleEngine.wakeWindow(settings)
    private val dayKey = "2026-10-19"

    private fun at(hour: Int, minute: Int): Long =
        ScheduleEngine.minutesOfDayToMillis(dayKey, hour * 60 + minute, zone)

    private fun decide(
        now: Long,
        force: Boolean = false,
        hasAccess: Boolean = false,
        lastUsed: Long? = null,
        morningStarted: Boolean = false,
        lastPromptAt: Long? = null,
        promptedDay: String? = null,
    ) = ScheduleEngine.decideWakePrompt(
        now, window, force, hasAccess, lastUsed,
        morningStarted, lastPromptAt, promptedDay, dayKey, zone,
    )

    @Test
    fun `fallback catch-up - missed 06-00 tick, 08-47 tick still prompts`() {
        // The exact user scenario: window-start tick never ran, first
        // available tick inside the window must fire the once-per-day prompt.
        assertTrue(decide(at(8, 47)))
    }

    @Test
    fun `fallback - already prompted today does not re-prompt`() {
        assertFalse(decide(at(8, 47), promptedDay = dayKey))
    }

    @Test
    fun `fallback - next day prompts again`() {
        assertTrue(decide(at(8, 47), promptedDay = "2026-10-18"))
    }

    @Test
    fun `usage - fresh usage prompts`() {
        val now = at(8, 47)
        assertTrue(decide(now, hasAccess = true, lastUsed = now - 60_000L))
    }

    @Test
    fun `usage - granted but no data yet falls back to once-per-day prompt`() {
        // Access granted, UsageStatsManager returned nothing (fresh grant):
        // must not silently skip the whole day.
        assertTrue(decide(at(8, 47), hasAccess = true, lastUsed = null))
        assertFalse(decide(at(9, 0), hasAccess = true, lastUsed = null, promptedDay = dayKey))
    }

    @Test
    fun `usage - stale usage does not prompt`() {
        val now = at(8, 47)
        assertFalse(decide(now, hasAccess = true, lastUsed = now - 2 * 60 * 60_000L))
    }

    @Test
    fun `force - explicit 15-min-baadi request always prompts in window`() {
        assertTrue(decide(at(8, 47), force = true, promptedDay = dayKey))
    }

    @Test
    fun `out of window - never prompts (except explicit force request)`() {
        assertFalse(decide(at(5, 30)))
        assertFalse(decide(at(12, 0)))
        // An explicit "15 min baad" request is honored even if it lands just
        // outside the window — the user asked to be reminded.
        assertTrue(decide(at(11, 10), force = true))
    }

    @Test
    fun `morning started - never prompts`() {
        assertTrue(decide(at(8, 47), morningStarted = true).not())
    }

    @Test
    fun `degenerate window - never prompts, never crashes`() {
        val bad = window.copy(startMinutesOfDay = 660, endMinutesOfDay = 360)
        assertFalse(
            ScheduleEngine.decideWakePrompt(
                at(8, 47), bad, false, false, null, false, null, null, dayKey, zone,
            )
        )
    }

    @Test
    fun `diagnostics format - renders every fact`() {
        val d = WakeDiagnostics(
            usageAccessGranted = false,
            exactAlarmAllowed = false,
            notificationsEnabled = true,
            chainArmed = false,
            nextCheckAtMillis = at(9, 0),
            lastCheckOutcome = "NO_PROMPT",
            lastCheckAtMillis = at(8, 47),
        )
        val text = d.format(zone)
        assertTrue(text.contains("Usage access: NAHI"))
        assertTrue(text.contains("Exact alarms: NAHI"))
        assertTrue(text.contains("Notifications: haan"))
        assertTrue(text.contains("Wake chain armed: NAHI"))
        assertTrue(text.contains("Next check: 09:00"))
        assertTrue(text.contains("NO_PROMPT"))
        assertTrue(text.contains("08:47"))
    }

    @Test
    fun `diagnostics format - nulls render as dashes`() {
        val d = WakeDiagnostics(false, false, false, false, null, null, null)
        val text = d.format(zone)
        assertTrue(text.contains("Next check: —"))
        assertTrue(text.contains("Last check: — —"))
    }
}
