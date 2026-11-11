package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * v2.3: snooze + deferred-start delay (+30 min) + cutoff relaxation —
 * pure-engine coverage (user-confirmed workflow, 2026-10-22).
 *
 *  1. SNOOZE: while now < snooze_until the dose is untouchable — the
 *     snooze-aware shouldNag / isCatchupDue never fire; at/after expiry
 *     the plain v2.2 rules resume unchanged.
 *  2. DEFERRED START: readyAt = blocker-clear + exactly 30 minutes; the
 *     KV flag value encodes waiting ("1") vs ready ("ready:<millis>").
 *  3. CUTOFF: a deferred evening start (planSession ignoreCutoff=true)
 *     plans the full session past 20:00; a fresh start does not. Doses of
 *     an ACTIVE evening session are never cutoff candidates.
 *
 * The repo glue (KV lifecycle, alarm re-arms, transactions) needs
 * Android/SQLite and is mirrored in tests/db_v23_snooze_defer_cutoff.py.
 */
class SnoozeDeferCutoffTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val dayKey = "2026-10-22"
    // 2026-10-22T08:00:00Z — a dose due "now" in the fake timeline.
    private val due = 1_792_656_000_000L
    private val settings = DoseSettings()

    private fun dose(
        status: DoseStatus = DoseStatus.PENDING,
        dueAt: Long = due,
        nextRetryAt: Long? = null,
    ) = Dose(
        id = 7L, sessionId = 3L, dropNumber = 2, kind = DoseKind.REGULAR,
        dayKey = dayKey, dueAt = dueAt, status = status, nextRetryAt = nextRetryAt,
    )

    // ------------------------------------------------------- snooze basics

    @Test
    fun `snooze presets are exactly 10 20 30 60 minutes`() {
        assertEquals(
            listOf(10L, 20L, 30L, 60L).map { it * 60_000L },
            ScheduleEngine.SNOOZE_DURATIONS_MILLIS,
        )
    }

    @Test
    fun `snooze key format`() {
        assertEquals("snooze_until_7", ScheduleEngine.snoozeKey(7L))
        assertEquals("snooze_until_123", ScheduleEngine.snoozeKey(123L))
    }

    @Test
    fun `isSnoozed inside the window only`() {
        val until = due + 20 * 60_000L
        assertTrue(ScheduleEngine.isSnoozed(until, due))
        assertTrue(ScheduleEngine.isSnoozed(until, until - 1))
        assertFalse(ScheduleEngine.isSnoozed(until, until)) // expiry: normal behavior resumes
        assertFalse(ScheduleEngine.isSnoozed(until, until + 60_000L))
        assertFalse(ScheduleEngine.isSnoozed(null, due))
    }

    // ------------------------------------------------- snooze suppresses nag

    @Test
    fun `nag suppressed inside the snooze window however overdue`() {
        val d = dose()
        val now = due + 3 * 60 * 60_000L // 3 hours overdue, no nag yet
        // Plain rule would nag…
        assertTrue(ScheduleEngine.shouldNag(d, now, settings, null))
        // …but a live snooze makes the dose untouchable.
        assertFalse(ScheduleEngine.shouldNag(d, now, settings, null, snoozeUntil = now + 60_000L))
    }

    @Test
    fun `nag resumes at and after snooze expiry`() {
        val d = dose()
        val until = due + 30 * 60_000L
        val now = until // exactly at expiry
        assertTrue(ScheduleEngine.shouldNag(d, now, settings, null, snoozeUntil = until))
        assertTrue(ScheduleEngine.shouldNag(d, now + 60_000L, settings, null, snoozeUntil = until))
    }

    @Test
    fun `nag cadence after snooze still honors lastNagAt`() {
        val d = dose()
        val until = due + 30 * 60_000L
        val lastNag = until + 5 * 60_000L
        // Inside the next interval after a post-snooze nag: suppressed by cadence.
        assertFalse(ScheduleEngine.shouldNag(d, lastNag + 60_000L, settings, lastNag, snoozeUntil = until))
        assertTrue(ScheduleEngine.shouldNag(d, lastNag + settings.nagIntervalMillis, settings, lastNag, snoozeUntil = until))
    }

    // --------------------------------------------- snooze suppresses catch-up

    @Test
    fun `catch-up suppressed inside the snooze window`() {
        val d = dose() // due long ago, reminder time far past tolerance
        val now = due + 60 * 60_000L
        assertTrue(ScheduleEngine.isCatchupDue(d, now))
        assertFalse(ScheduleEngine.isCatchupDue(d, now, snoozeUntil = now + 1))
    }

    @Test
    fun `catch-up resumes at snooze expiry`() {
        val d = dose()
        val until = due + 30 * 60_000L
        assertTrue(ScheduleEngine.isCatchupDue(d, until, snoozeUntil = until))
        assertTrue(ScheduleEngine.isCatchupDue(d, until + 60_000L, snoozeUntil = until))
    }

    @Test
    fun `snoozed dose with a NO retry armed is also untouchable`() {
        // NO-vs-snooze interplay: whichever was chosen LAST owns the slot.
        // A dose snoozed after a NO carries nextRetryAt, but inside the
        // snooze window neither catch-up (keyed on nextReminderAt) nor nag
        // may fire.
        val d = dose(nextRetryAt = due + 5 * 60_000L)
        val now = due + 30 * 60_000L
        val until = now + 10 * 60_000L
        assertFalse(ScheduleEngine.isCatchupDue(d, now, snoozeUntil = until))
        assertFalse(ScheduleEngine.shouldNag(d, now, settings, null, snoozeUntil = until))
    }

    // ------------------------------------------------ deferred start delay

    @Test
    fun `deferred readyAt is exactly 30 minutes after the blocker clears`() {
        val clearedAt = 1_792_656_000_000L
        assertEquals(30L * 60 * 1000, ScheduleEngine.DEFERRED_START_DELAY_MILLIS)
        assertEquals(clearedAt + 30L * 60 * 1000, ScheduleEngine.deferredReadyAt(clearedAt))
    }

    @Test
    fun `deferred flag value encodes waiting vs ready`() {
        assertEquals("1", ScheduleEngine.deferredFlagValue(null))
        assertEquals("ready:1792692600000", ScheduleEngine.deferredFlagValue(1_792_692_600_000L))
    }

    @Test
    fun `deferred readyAt parses back from the flag`() {
        assertEquals(
            1_792_692_600_000L,
            ScheduleEngine.deferredReadyAtFromFlag("ready:1792692600000"),
        )
        assertEquals(
            1_792_692_600_000L,
            ScheduleEngine.deferredReadyAtFromFlag(
                ScheduleEngine.deferredFlagValue(1_792_692_600_000L)
            ),
        )
    }

    @Test
    fun `deferred readyAt is null for waiting absent or malformed flags`() {
        assertNull(ScheduleEngine.deferredReadyAtFromFlag("1")) // blocker still ACTIVE
        assertNull(ScheduleEngine.deferredReadyAtFromFlag(null))
        assertNull(ScheduleEngine.deferredReadyAtFromFlag(""))
        assertNull(ScheduleEngine.deferredReadyAtFromFlag("ready:"))
        assertNull(ScheduleEngine.deferredReadyAtFromFlag("ready:abc"))
        assertNull(ScheduleEngine.deferredReadyAtFromFlag("garbage"))
    }

    // ------------------------------------------------------------ cutoff

    @Test
    fun `fresh evening start that cannot fit the cutoff still conflicts`() {
        // 19:31 UTC start, 4 drops x 10 min -> last drop due 20:01 > 20:00.
        val start = ScheduleEngine.minutesOfDayToMillis(dayKey, 19 * 60 + 31, utc)
        val plan = ScheduleEngine.planSession(
            SessionType.EVENING, dayKey, start, settings,
            specialAlreadyTakenToday = true, zone = utc,
        )
        assertTrue(plan is SessionPlan.Conflict)
    }

    @Test
    fun `deferred evening start bypasses the fit check and plans all 4 drops`() {
        // 19:31 UTC start -> last drop due 20:01, past the 20:00 cutoff.
        val start = ScheduleEngine.minutesOfDayToMillis(dayKey, 19 * 60 + 31, utc)
        val plan = ScheduleEngine.planSession(
            SessionType.EVENING, dayKey, start, settings,
            specialAlreadyTakenToday = true, zone = utc, ignoreCutoff = true,
        )
        assertTrue(plan is SessionPlan.Ok)
        val doses = (plan as SessionPlan.Ok).doses
        assertEquals(4, doses.size)
        assertEquals(start, doses.first().dueAt)
        // The full session runs past the cutoff — by design (user order).
        val cutoff = ScheduleEngine.minutesOfDayToMillis(dayKey, settings.cutoffMinutesOfDay, utc)
        assertTrue(doses.last().dueAt > cutoff)
        assertEquals(start + 3 * settings.doseIntervalMillis, doses.last().dueAt)
    }

    @Test
    fun `deferred evening start very late still plans in full`() {
        // Blocker cleared 19:40 -> deferred start 20:10, entirely past cutoff.
        val start = ScheduleEngine.minutesOfDayToMillis(dayKey, 20 * 60 + 10, utc)
        val plan = ScheduleEngine.planSession(
            SessionType.EVENING, dayKey, start, settings,
            specialAlreadyTakenToday = true, zone = utc, ignoreCutoff = true,
        )
        assertTrue(plan is SessionPlan.Ok)
        assertEquals(4, (plan as SessionPlan.Ok).doses.size)
    }

    @Test
    fun `ignoreCutoff does not change morning or afternoon planning`() {
        val late = ScheduleEngine.minutesOfDayToMillis(dayKey, 23 * 60, utc)
        for (type in listOf(SessionType.MORNING, SessionType.AFTERNOON)) {
            val plan = ScheduleEngine.planSession(
                type, dayKey, late, settings,
                specialAlreadyTakenToday = false, zone = utc, ignoreCutoff = true,
            )
            assertTrue("$type must plan fine at any hour", plan is SessionPlan.Ok)
        }
    }

    @Test
    fun `cutoff candidates exclude doses of an ACTIVE evening session`() {
        val eveningDose = dose()
        val candidates = ScheduleEngine.eveningCutoffCandidates(
            listOf(eveningDose),
            sessionTypeOf = { SessionType.EVENING },
            sessionStatusOf = { SessionStatus.ACTIVE },
        )
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `cutoff candidates keep evening doses of non-active sessions`() {
        val eveningDose = dose()
        for (status in listOf(SessionStatus.COMPLETED, SessionStatus.CANCELLED, SessionStatus.CONFLICT)) {
            val candidates = ScheduleEngine.eveningCutoffCandidates(
                listOf(eveningDose),
                sessionTypeOf = { SessionType.EVENING },
                sessionStatusOf = { status },
            )
            assertEquals("status $status keeps the dose a candidate", 1, candidates.size)
        }
    }

    @Test
    fun `cutoff candidates with unknown status keep the pre-v23 behavior`() {
        val eveningDose = dose()
        val candidates = ScheduleEngine.eveningCutoffCandidates(
            listOf(eveningDose),
            sessionTypeOf = { SessionType.EVENING },
        )
        assertEquals(1, candidates.size)
        // Morning doses are never candidates, whatever the status.
        val morning = ScheduleEngine.eveningCutoffCandidates(
            listOf(eveningDose),
            sessionTypeOf = { SessionType.MORNING },
            sessionStatusOf = { SessionStatus.COMPLETED },
        )
        assertTrue(morning.isEmpty())
    }

    // ------------------------------------------- HomeView deferred start time

    @Test
    fun `hero deferred start carries the planned start time`() {
        val afternoon = DoseSession(
            id = 1L, type = SessionType.AFTERNOON, dayKey = dayKey,
            startedAt = due - 3_600_000L, status = SessionStatus.COMPLETED,
        )
        // 2026-10-22T18:10:00Z
        val startsAt = 1_792_692_600_000L
        val hero = HomeView.heroState(
            null, null, settings, due, listOf(afternoon),
            deferred = { it == SessionType.EVENING },
            doneOf = { false },
            deferredStartAtOf = { if (it == SessionType.EVENING) startsAt else null },
        )
        assertTrue(hero is HeroState.DeferredStart)
        hero as HeroState.DeferredStart
        assertEquals(SessionType.EVENING, hero.waitingType)
        assertEquals(startsAt, hero.startsAtMillis)
        val line = HomeView.nextUpLine(hero, settings, utc)
        assertTrue("line was: $line", line.contains("6:10 PM"))
        assertTrue("line was: $line", line.contains("30 min after Afternoon"))
    }

    @Test
    fun `hero deferred start without a known time keeps the old wording`() {
        val hero = HeroState.DeferredStart(
            waitingType = SessionType.EVENING, blockingType = SessionType.AFTERNOON,
        )
        assertNull(hero.startsAtMillis)
        assertEquals(
            "Evening starts automatically after Afternoon finishes",
            HomeView.nextUpLine(hero, settings, utc),
        )
    }

    @Test
    fun `timeline deferred row shows the planned start time when known`() {
        val afternoon = DoseSession(
            id = 1L, type = SessionType.AFTERNOON, dayKey = dayKey,
            startedAt = due - 3_600_000L, status = SessionStatus.COMPLETED,
        )
        val startsAt = 1_792_692_600_000L // 18:10 UTC
        val rows = HomeView.timelineRows(
            listOf(afternoon), settings, due,
            deferred = { it == SessionType.EVENING },
            progressOf = { null },
            currentDropOf = { null },
            deferredStartAtOf = { if (it == SessionType.EVENING) startsAt else null },
            zone = utc,
        )
        val evening = rows.first { it.type == SessionType.EVENING }
        assertEquals(TimelineStatus.WAITING_DEFERRED, evening.status)
        assertTrue("detail was: ${evening.detail}", evening.detail.contains("6:10 PM"))
        assertTrue("detail was: ${evening.detail}", evening.detail.contains("30 min after"))
    }

    @Test
    fun `timeline deferred row without a known time keeps the old wording`() {
        val afternoon = DoseSession(
            id = 1L, type = SessionType.AFTERNOON, dayKey = dayKey,
            startedAt = due - 3_600_000L, status = SessionStatus.ACTIVE,
        )
        val rows = HomeView.timelineRows(
            listOf(afternoon), settings, due,
            deferred = { it == SessionType.EVENING },
            progressOf = { null },
            currentDropOf = { null },
            zone = utc,
        )
        val evening = rows.first { it.type == SessionType.EVENING }
        assertEquals(TimelineStatus.WAITING_DEFERRED, evening.status)
        assertEquals("Waiting — starts after Afternoon finishes", evening.detail)
    }

    // ------------------------------------------------------ SnoozeResult

    @Test
    fun `SnoozeResult carries dose, until, unchanged and defaults notDueYet to false`() {
        val d = dose()
        val r = SnoozeResult(dose = d, snoozedUntil = due + 20 * 60_000L, unchanged = false)
        assertEquals(d, r.dose)
        assertEquals(due + 20 * 60_000L, r.snoozedUntil)
        assertFalse(r.unchanged)
        assertFalse(r.notDueYet)
        val refused = SnoozeResult(dose = d, snoozedUntil = null, unchanged = false, notDueYet = true)
        assertTrue(refused.notDueYet)
        assertNull(refused.snoozedUntil)
    }
}
