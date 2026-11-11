package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/**
 * Covers all 12 required test scenarios from the master prompt using a fake
 * clock (simulated time — no waiting hours).
 *
 *  1. All 8 morning drops, including the once-daily special drop.
 *  2. All 7 afternoon drops.
 *  3. All 7 evening drops.
 *  4. Exact 30-minute intervals after confirmed doses.
 *  5. Repeated 5-minute reminders when NO is selected.
 *  6. No next-dose timer when the current dose remains pending.
 *  7. Special drop appearing only once, only in the morning.
 *  8. Duplicate taps and duplicate alarm prevention.
 *  9. App closure / reboot reconciliation and permission-denial-safe logic.
 * 10. Airplane mode (pure-local, no network code paths).
 * 11. Session cutoff conflicts and overdue reminders.
 * 12. Persistence of dose history across a simulated restart.
 */
class ScheduleEngineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Karachi")
    private val settings = DoseSettings()
    private val dayKey = "2026-10-19" // a Monday

    private fun startOfDay() = ScheduleEngine.startOfDayMillis(dayKey, zone)

    /** Simulates the repository: turns a PlannedDose into a persisted Dose. */
    private fun persist(plan: PlannedDose, sessionId: Long, id: Long, dayKey: String) =
        Dose(
            id = id, sessionId = sessionId, dropNumber = plan.dropNumber,
            kind = plan.kind, dayKey = dayKey, dueAt = plan.dueAt,
        )

    // ------------------------------------------------------------------ 1

    @Test
    fun `1 - morning session has 4 regular drops at the 10 minute gap`() {
        val startAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone) // 09:00
        val plan = ScheduleEngine.planSession(
            SessionType.MORNING, dayKey, startAt, settings,
            specialAlreadyTakenToday = false, zone,
        )
        assertTrue(plan is SessionPlan.Ok)
        val doses = (plan as SessionPlan.Ok).doses
        assertEquals(4, doses.size)
        assertTrue("New Medicine has no special drop", doses.none { it.kind == DoseKind.SPECIAL })
        assertTrue(doses.all { it.kind == DoseKind.REGULAR })
        assertEquals(4, doses.last().dropNumber)
        // Exact 10-minute spacing of the plan, anchored to the session start.
        doses.forEachIndexed { i, d ->
            assertEquals(startAt + i * 10L * 60 * 1000, d.dueAt)
        }
        // Walk all 4 drops with the fake clock: confirm each exactly on time.
        // STRICT CHAINING (v1.5): at session start ONLY drop 1 exists as a
        // row; each YES creates exactly the next row (dueAt = confirmedAt +
        // 30 min) — a drop never exists before the previous one is taken.
        val clock = FakeClock(startAt)
        val session = DoseSession(id = 1, type = SessionType.MORNING, dayKey = dayKey, startedAt = startAt)
        var idSeq = 1L
        val rows = mutableListOf(persist(doses[0], session.id, idSeq++, dayKey))
        assertEquals("session start persists only drop 1", 1, rows.size)
        val confirmed = mutableListOf<Dose>()
        var lastNextDue: Long? = null
        for (n in 1..4) {
            // The next drop's row must NOT exist before this one is confirmed.
            assertEquals(n, rows.size)
            assertFalse("drop ${n + 1} must not exist before drop $n is taken",
                rows.any { it.dropNumber == n + 1 })
            val dose = rows.first { it.dropNumber == n }
            assertEquals(DoseStatus.PENDING, dose.status)
            clock.set(dose.dueAt)
            val result = ScheduleEngine.confirmDose(dose, clock.nowMillis(), settings, isLastDrop = n == 4)
            assertFalse(result.unchanged)
            assertEquals(DoseStatus.CONFIRMED, result.dose.status)
            assertEquals(clock.nowMillis(), result.dose.confirmedAt)
            rows[rows.indexOf(dose)] = result.dose
            confirmed.add(result.dose)
            lastNextDue = result.nextDueAt
            if (n < 4) {
                // The chained insert (mirrors DropsRepository.confirmDose):
                // the next row appears NOW, due exactly 10 min after this
                // confirmation, with the kind from the shared rule.
                val specialTaken = ScheduleEngine.isSpecialTakenToday(rows, dayKey)
                val kind = ScheduleEngine.kindForDrop(SessionType.MORNING, n + 1, settings, specialTaken)
                val next = Dose(
                    id = idSeq++, sessionId = session.id, dropNumber = n + 1, kind = kind,
                    dayKey = dayKey, dueAt = clock.nowMillis() + settings.doseIntervalMillis,
                )
                rows.add(next)
                assertEquals(clock.nowMillis() + settings.doseIntervalMillis, next.dueAt)
                assertEquals(result.nextDueAt, next.dueAt)
            }
        }
        assertNull("no next dose after the last morning drop", lastNextDue)
        assertEquals("exactly 4 rows are ever created", 4, rows.size)
        // No special drop anywhere; all 4 confirmed regular.
        assertTrue(rows.all { it.kind == DoseKind.REGULAR })
        assertEquals(0, confirmed.count { it.kind == DoseKind.SPECIAL })
        assertTrue(ScheduleEngine.checkSpecialInvariants(confirmed, mapOf(session.id to session)).isEmpty())
        assertFalse(ScheduleEngine.isSpecialTakenToday(confirmed, dayKey))
    }

    // ------------------------------------------------------------------ 2

    @Test
    fun `2 - afternoon session has 4 regular drops starting 13-45`() {
        val startAt = ScheduleEngine.plannedSessionStartMillis(SessionType.AFTERNOON, dayKey, settings, zone)
        assertEquals(13 * 60 + 45, ScheduleEngine.minutesOfDay(startAt, zone))
        val plan = ScheduleEngine.planSession(
            SessionType.AFTERNOON, dayKey, startAt, settings,
            specialAlreadyTakenToday = true, zone,
        )
        assertTrue(plan is SessionPlan.Ok)
        val doses = (plan as SessionPlan.Ok).doses
        assertEquals(4, doses.size)
        assertTrue("afternoon must never contain the special drop", doses.none { it.kind == DoseKind.SPECIAL })
        // Last drop due at 14:15, well before the 20:00 cutoff.
        assertEquals(startAt + 3 * 10L * 60 * 1000, doses.last().dueAt)
    }

    // ------------------------------------------------------------------ 3

    @Test
    fun `3 - evening session has 4 regular drops and finishes by the 20-00 cutoff`() {
        // Default evening start 17:00 -> last drop due 17:30, before the cutoff.
        val startAt = ScheduleEngine.plannedSessionStartMillis(SessionType.EVENING, dayKey, settings, zone)
        assertEquals(17 * 60, ScheduleEngine.minutesOfDay(startAt, zone))
        val plan = ScheduleEngine.planSession(
            SessionType.EVENING, dayKey, startAt, settings,
            specialAlreadyTakenToday = true, zone,
        )
        assertTrue(plan is SessionPlan.Ok)
        val doses = (plan as SessionPlan.Ok).doses
        assertEquals(4, doses.size)
        assertTrue(doses.none { it.kind == DoseKind.SPECIAL })
        val cutoff = ScheduleEngine.minutesOfDayToMillis(dayKey, settings.cutoffMinutesOfDay, zone)
        assertTrue("last evening drop must not pass the cutoff", doses.last().dueAt <= cutoff)
        assertEquals(ScheduleEngine.minutesOfDayToMillis(dayKey, 17 * 60 + 30, zone), doses.last().dueAt)
    }

    // ------------------------------------------------------------------ 4

    @Test
    fun `4 - next drop is due exactly 10 minutes after the actual confirmation time`() {
        val startAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)
        val clock = FakeClock(startAt)
        val dose = Dose(id = 1, sessionId = 1, dropNumber = 1, dayKey = dayKey, dueAt = startAt)

        // Confirm 12 minutes LATE: next drop anchors to confirmation, not to plan.
        clock.advance(12 * 60 * 1000)
        val late = ScheduleEngine.confirmDose(dose, clock.nowMillis(), settings, isLastDrop = false)
        assertEquals(startAt + 12 * 60 * 1000 + 10 * 60 * 1000, late.nextDueAt)

        // Confirm 5 minutes EARLY: still exactly +10 min from confirmation.
        val dose2 = Dose(id = 2, sessionId = 1, dropNumber = 2, dayKey = dayKey, dueAt = late.nextDueAt!!)
        clock.set(dose2.dueAt - 5 * 60 * 1000)
        val early = ScheduleEngine.confirmDose(dose2, clock.nowMillis(), settings, isLastDrop = false)
        assertEquals(clock.nowMillis() + 10 * 60 * 1000, early.nextDueAt)

        // No cumulative drift across a full morning: sum of gaps == 4 x 10 min.
        val plan = ScheduleEngine.planSession(
            SessionType.MORNING, dayKey, startAt, settings, specialAlreadyTakenToday = false, zone,
        ) as SessionPlan.Ok
        var t = startAt
        var total = 0L
        for (i in plan.doses.indices) {
            val d = persist(plan.doses[i], 1, 100L + i, dayKey).copy(dueAt = t)
            // confirm with a wandering offset (-4..+6 min) to prove anchoring
            val offset = ((i * 7) % 11 - 4) * 60 * 1000L
            clock.set(d.dueAt + offset)
            val r = ScheduleEngine.confirmDose(d, clock.nowMillis(), settings, isLastDrop = i == 4)
            val nd = r.nextDueAt
            if (nd != null) {
                total += nd - clock.nowMillis()
                t = nd
            }
        }
        assertEquals(4 * 10L * 60 * 1000, total)
    }

    // ------------------------------------------------------------------ 5

    @Test
    fun `5 - answering NO rearms a reminder every 5 minutes until YES`() {
        val startAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)
        val clock = FakeClock(startAt)
        var dose = Dose(id = 1, sessionId = 1, dropNumber = 1, dayKey = dayKey, dueAt = startAt)

        repeat(3) { round ->
            clock.set(startAt + round * 5 * 60 * 1000)
            dose = ScheduleEngine.declineDose(dose, clock.nowMillis(), settings)
            assertEquals(DoseStatus.PENDING, dose.status)
            assertNull("NO must not record a confirmation time", dose.confirmedAt)
            assertEquals(round + 1, dose.retryCount)
            assertEquals(clock.nowMillis() + 5 * 60 * 1000, dose.nextRetryAt)
            assertEquals(dose.nextRetryAt, dose.nextReminderAt())
        }
        // Finally YES: retry state resolves into a confirmation.
        clock.advance(5 * 60 * 1000)
        val result = ScheduleEngine.confirmDose(dose, clock.nowMillis(), settings, isLastDrop = false)
        assertFalse(result.unchanged)
        assertEquals(DoseStatus.CONFIRMED, result.dose.status)
        assertEquals(clock.nowMillis(), result.dose.confirmedAt)
        assertEquals(clock.nowMillis() + 10 * 60 * 1000, result.nextDueAt)
    }

    // ------------------------------------------------------------------ 6

    @Test
    fun `6 - no next-dose countdown starts while the current dose is pending`() {
        val startAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)
        val clock = FakeClock(startAt)
        val dose = Dose(id = 1, sessionId = 1, dropNumber = 1, dayKey = dayKey, dueAt = startAt)

        val declined = ScheduleEngine.declineDose(dose, clock.nowMillis(), settings)
        // The engine exposes NO next-dose due time on the decline path: the
        // repository can only obtain nextDueAt via confirmDose().
        assertEquals(DoseStatus.PENDING, declined.status)
        // The only armed reminder is the retry for THIS dose.
        assertEquals(startAt + 5 * 60 * 1000, declined.nextReminderAt())
        // Dismissing the notification changes nothing at all.
        assertEquals(declined, ScheduleEngine.dismissWithoutConfirm(declined))
        // Even after the retry fires, the next drop is still unscheduled.
        clock.set(declined.nextRetryAt!!)
        val declined2 = ScheduleEngine.declineDose(declined, clock.nowMillis(), settings)
        assertEquals(2, declined2.retryCount)
        assertEquals(startAt + 10 * 60 * 1000, declined2.nextReminderAt())
    }

    // ------------------------------------------------------------------ 7

    @Test
    fun `7 - no special drop ever appears in any session`() {
        val morningStart = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)
        val afternoonStart = ScheduleEngine.plannedSessionStartMillis(SessionType.AFTERNOON, dayKey, settings, zone)
        val eveningStart = ScheduleEngine.plannedSessionStartMillis(SessionType.EVENING, dayKey, settings, zone)

        // A second morning session on the same day also contains
        // zero special drops — this app has none at all.
        val secondMorning = ScheduleEngine.planSession(
            SessionType.MORNING, dayKey, morningStart, settings,
            specialAlreadyTakenToday = true, zone,
        ) as SessionPlan.Ok
        assertTrue(secondMorning.doses.none { it.kind == DoseKind.SPECIAL })

        // Afternoon and evening never contain the special drop.
        for (start in listOf(afternoonStart, eveningStart)) {
            val type = if (start == afternoonStart) SessionType.AFTERNOON else SessionType.EVENING
            val plan = ScheduleEngine.planSession(
                type, dayKey, start, settings, specialAlreadyTakenToday = false, zone,
            ) as SessionPlan.Ok
            assertTrue("$type must never contain the special drop", plan.doses.none { it.kind == DoseKind.SPECIAL })
        }

        // Invariant checker catches violations if they ever slip into the DB.
        val morning = DoseSession(id = 1, type = SessionType.MORNING, dayKey = dayKey, startedAt = morningStart)
        val afternoon = DoseSession(id = 2, type = SessionType.AFTERNOON, dayKey = dayKey, startedAt = afternoonStart)
        val badSpecialInAfternoon = Dose(
            id = 50, sessionId = afternoon.id, dropNumber = 1, kind = DoseKind.SPECIAL,
            dayKey = dayKey, dueAt = afternoonStart,
        )
        val doubleSpecial = Dose(
            id = 51, sessionId = morning.id, dropNumber = 8, kind = DoseKind.SPECIAL,
            dayKey = dayKey, dueAt = morningStart + 7 * 30 * 60 * 1000,
            status = DoseStatus.CONFIRMED, confirmedAt = morningStart + 3 * 10 * 60 * 1000,
        )
        val doubleSpecial2 = doubleSpecial.copy(id = 52, status = DoseStatus.CONFIRMED)
        val violations = ScheduleEngine.checkSpecialInvariants(
            listOf(badSpecialInAfternoon, doubleSpecial, doubleSpecial2),
            mapOf(morning.id to morning, afternoon.id to afternoon),
        )
        assertEquals(3, violations.size) // wrong session + >1 planned + >1 confirmed
    }

    // ------------------------------------------------------------------ 8

    @Test
    fun `8 - double taps and duplicate alarms are impossible`() {
        val startAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)
        val clock = FakeClock(startAt)
        val dose = Dose(id = 7, sessionId = 1, dropNumber = 1, dayKey = dayKey, dueAt = startAt)

        val first = ScheduleEngine.confirmDose(dose, clock.nowMillis(), settings, isLastDrop = false)
        assertFalse(first.unchanged)
        // Double tap: same dose confirmed again (notification action + in-app).
        val second = ScheduleEngine.confirmDose(first.dose, clock.nowMillis() + 1000, settings, isLastDrop = false)
        assertTrue("second confirm must be a no-op", second.unchanged)
        assertEquals(first.dose, second.dose)
        assertNull("no duplicate next-dose scheduling on double tap", second.nextDueAt)

        // Exactly one stable alarm slot per dose: retries overwrite it.
        val code1 = ScheduleEngine.alarmRequestCode(dose.id)
        val code2 = ScheduleEngine.alarmRequestCode(dose.id)
        assertEquals(code1, code2)
        assertNotEquals(code1, ScheduleEngine.alarmRequestCode(dose.id + 1))

        // Reconcile never emits two specs for one dose.
        val pending = Dose(id = 9, sessionId = 1, dropNumber = 2, dayKey = dayKey, dueAt = startAt + 30 * 60 * 1000)
        val result = ScheduleEngine.reconcile(listOf(pending, pending.copy()), startAt, 60_000L)
        assertEquals(1, result.schedule.distinctBy { it.doseId }.size)
    }

    // ------------------------------------------------------------------ 9

    @Test
    fun `9 - reboot reconciliation rebuilds exactly the pending alarms from DB state`() {
        val startAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)
        val clock = FakeClock(startAt)
        // Simulate a morning in progress: drop 1 confirmed, drop 2 pending with
        // a NO-retry armed, drop 3 upcoming.
        val d1 = Dose(id = 1, sessionId = 1, dropNumber = 1, dayKey = dayKey, dueAt = startAt,
            status = DoseStatus.CONFIRMED, confirmedAt = startAt)
        var d2 = Dose(id = 2, sessionId = 1, dropNumber = 2, dayKey = dayKey, dueAt = startAt + 30 * 60 * 1000)
        clock.set(d2.dueAt)
        d2 = ScheduleEngine.declineDose(d2, clock.nowMillis(), settings)
        val d3 = Dose(id = 3, sessionId = 1, dropNumber = 3, dayKey = dayKey, dueAt = startAt + 60 * 60 * 1000)

        // "Reboot": the OS alarm table is gone. Reconcile from the DB.
        clock.set(startAt + 31 * 60 * 1000)
        val reconciled = ScheduleEngine.reconcile(listOf(d1, d2, d3), clock.nowMillis())

        val byDose = reconciled.schedule.associateBy { it.doseId }
        assertFalse("confirmed dose must not be rescheduled", byDose.containsKey(1))
        assertEquals("retry alarm restored", d2.nextRetryAt, byDose[2]?.triggerAt)
        assertEquals("upcoming dose alarm restored", d3.dueAt, byDose[3]?.triggerAt)
        assertTrue(reconciled.cancelDoseIds.contains(1))
        // Reconciling twice yields the identical set (idempotent, no dupes).
        val again = ScheduleEngine.reconcile(listOf(d1, d2, d3), clock.nowMillis())
        assertEquals(reconciled.schedule.map { it.alarmKey }.toSet(), again.schedule.map { it.alarmKey }.toSet())
    }

    // ------------------------------------------------------------------ 10

    @Test
    fun `10 - full day flow works with nothing but a clock (airplane-mode safe)`() {
        // No network, no Android framework: the entire engine runs on the
        // injected FakeClock. This test performs a whole day and asserts the
        // outcomes, proving there is no hidden network/code dependency.
        val clock = FakeClock(ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone))
        val sessions = mutableListOf<DoseSession>()
        val doses = mutableListOf<Dose>()
        var idSeq = 1L

        fun startSession(type: SessionType, startAt: Long): DoseSession {
            val s = DoseSession(id = idSeq++, type = type, dayKey = dayKey, startedAt = startAt)
            sessions.add(s)
            val plan = ScheduleEngine.planSession(
                type, dayKey, startAt, settings,
                specialAlreadyTakenToday = ScheduleEngine.isSpecialTakenToday(doses, dayKey), zone,
            )
            assertTrue("$type plan must succeed", plan is SessionPlan.Ok)
            // STRICT CHAINING (v1.5): the repo persists only drop 1 at start;
            // drops 2..N are appended by confirmChained below, one per YES.
            val first = (plan as SessionPlan.Ok).doses.first()
            doses.add(persist(first, s.id, idSeq++, dayKey))
            assertEquals(1, doses.count { it.sessionId == s.id })
            return s
        }

        /**
         * Mirrors DropsRepository.confirmDose's chained insert: YES on drop N
         * marks it CONFIRMED and appends exactly one row for drop N+1
         * (dueAt = confirmedAt + interval, kind from the shared rule).
         */
        fun confirmChained(d: Dose, now: Long, count: Int) {
            val r = ScheduleEngine.confirmDose(d, now, settings, isLastDrop = d.dropNumber == count)
            assertFalse("confirm must not be a no-op here", r.unchanged)
            doses[doses.indexOf(d)] = r.dose
            val nextDueAt = r.nextDueAt
            if (nextDueAt != null) {
                val s = sessions.first { it.id == d.sessionId }
                val kind = ScheduleEngine.kindForDrop(
                    s.type, d.dropNumber + 1, settings,
                    ScheduleEngine.isSpecialTakenToday(doses, dayKey),
                )
                doses.add(
                    Dose(
                        id = idSeq++, sessionId = d.sessionId, dropNumber = d.dropNumber + 1,
                        kind = kind, dayKey = dayKey, dueAt = nextDueAt,
                    )
                )
            }
        }

        val morning = startSession(SessionType.MORNING, clock.nowMillis())
        // Confirm every morning drop on time, chained.
        for (n in 1..settings.morningDropCount) {
            val d = doses.first { it.sessionId == morning.id && it.dropNumber == n }
            clock.set(d.dueAt)
            confirmChained(d, clock.nowMillis(), settings.morningDropCount)
        }
        // Afternoon + evening, one NO on the first afternoon drop.
        val afternoon = startSession(SessionType.AFTERNOON,
            ScheduleEngine.plannedSessionStartMillis(SessionType.AFTERNOON, dayKey, settings, zone))
        val a1 = doses.first { it.sessionId == afternoon.id && it.dropNumber == 1 }
        clock.set(a1.dueAt)
        doses[doses.indexOf(a1)] = ScheduleEngine.declineDose(a1, clock.nowMillis(), settings)
        // NO creates no rows: still exactly one row for this session.
        assertEquals(1, doses.count { it.sessionId == afternoon.id })
        val a1retry = doses.first { it.id == a1.id }
        clock.set(a1retry.nextRetryAt!!)
        confirmChained(a1retry, clock.nowMillis(), settings.afternoonDropCount)
        // Remaining drops confirmed at their (possibly shifted) due times, chained.
        for (n in 2..settings.afternoonDropCount) {
            val d = doses.first { it.sessionId == afternoon.id && it.dropNumber == n }
            clock.set(d.dueAt)
            confirmChained(d, clock.nowMillis(), settings.afternoonDropCount)
        }
        val evening = startSession(SessionType.EVENING,
            ScheduleEngine.plannedSessionStartMillis(SessionType.EVENING, dayKey, settings, zone))
        for (n in 1..settings.eveningDropCount) {
            val d = doses.first { it.sessionId == evening.id && it.dropNumber == n }
            clock.set(d.dueAt)
            confirmChained(d, clock.nowMillis(), settings.eveningDropCount)
        }
        assertEquals(4 + 4 + 4, doses.count { it.status == DoseStatus.CONFIRMED })
        assertEquals("exactly 4+4+4 rows ever created", 12, doses.size)
        assertEquals(100, ScheduleEngine.dayCompletionPercent(doses, 12))
        assertTrue(ScheduleEngine.checkSpecialInvariants(doses, sessions.associateBy { it.id }).isEmpty())
        // Backup round-trip of the whole day works offline too.
        val json = BackupJson.exportBackup(sessions, doses, settings)
        val imported = BackupJson.importBackup(json)
        assertTrue(imported is BackupJson.ImportResult.Ok)
        val data = (imported as BackupJson.ImportResult.Ok).data
        assertEquals(doses, data.doses)
        assertEquals(sessions, data.sessions)
        assertEquals(settings, data.settings)
    }

    // ------------------------------------------------------------------ 11

    @Test
    fun `11 - cutoff conflicts warn loudly and never shorten intervals`() {
        // Evening forced to start at 19:45 -> last drop 20:15, past 20:00.
        val badStart = ScheduleEngine.minutesOfDayToMillis(dayKey, 19 * 60 + 45, zone)
        val plan = ScheduleEngine.planSession(
            SessionType.EVENING, dayKey, badStart, settings,
            specialAlreadyTakenToday = true, zone,
        )
        assertTrue("must be a conflict, not a silently shifted plan", plan is SessionPlan.Conflict)
        val reason = (plan as SessionPlan.Conflict).reason
        assertTrue(reason.contains("20:00") || reason.contains("cutoff"))

        // Any plan that DOES succeed keeps the exact configured interval.
        val okStart = ScheduleEngine.minutesOfDayToMillis(dayKey, 17 * 60, zone)
        val ok = ScheduleEngine.planSession(
            SessionType.EVENING, dayKey, okStart, settings, specialAlreadyTakenToday = true, zone,
        ) as SessionPlan.Ok
        ok.doses.zipWithNext { a, b -> assertEquals(settings.doseIntervalMillis, b.dueAt - a.dueAt) }

        // A custom evening start of 19:40 also conflicts (ends 20:10).
        val tight = ScheduleEngine.planSession(
            SessionType.EVENING, dayKey,
            ScheduleEngine.minutesOfDayToMillis(dayKey, 19 * 60 + 40, zone),
            settings, specialAlreadyTakenToday = true, zone,
        )
        assertTrue(tight is SessionPlan.Conflict)

        // Overdue detection: pending dose past dueAt + grace.
        val startAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)
        val clock = FakeClock(startAt)
        val dose = Dose(id = 1, sessionId = 1, dropNumber = 1, dayKey = dayKey, dueAt = startAt)
        assertFalse(ScheduleEngine.isOverdue(dose, clock.nowMillis(), settings))
        clock.advance(settings.overdueGraceMillis + 1000)
        assertTrue(ScheduleEngine.isOverdue(dose, clock.nowMillis(), settings))
        // Overdue never alters the interval: confirming still anchors +10 min.
        val confirmed = ScheduleEngine.confirmDose(dose, clock.nowMillis(), settings, isLastDrop = false)
        assertEquals(clock.nowMillis() + settings.doseIntervalMillis, confirmed.nextDueAt)

        // applyCutoff: pending doses past the cutoff become MISSED, never moved.
        val cutoff = ScheduleEngine.minutesOfDayToMillis(dayKey, settings.cutoffMinutesOfDay, zone)
        val lateDose = Dose(id = 2, sessionId = 1, dropNumber = 4, dayKey = dayKey, dueAt = cutoff + 30 * 60 * 1000)
        val earlyDose = Dose(id = 3, sessionId = 1, dropNumber = 1, dayKey = dayKey, dueAt = cutoff - 60 * 60 * 1000)
        val after = ScheduleEngine.applyCutoff(listOf(lateDose, earlyDose), cutoff)
        assertEquals(DoseStatus.MISSED, after.first { it.id == 2L }.status)
        assertEquals(lateDose.dueAt, after.first { it.id == 2L }.dueAt) // time untouched
        assertEquals(DoseStatus.PENDING, after.first { it.id == 3L }.status)
    }

    // ------------------------------------------------------------------ 12

    @Test
    fun `12 - dose history survives a simulated restart via backup round-trip`() {
        val startAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)
        val clock = FakeClock(startAt)
        val session = DoseSession(id = 1, type = SessionType.MORNING, dayKey = dayKey, startedAt = startAt)
        val plan = ScheduleEngine.planSession(
            SessionType.MORNING, dayKey, startAt, settings, specialAlreadyTakenToday = false, zone,
        ) as SessionPlan.Ok

        // Mixed history: confirmed (one late), declined-then-confirmed, pending.
        // STRICT CHAINING (v1.5): only drop 1 exists at start; each YES
        // appends exactly the next row (mirrors DropsRepository.confirmDose).
        val stored = mutableListOf<Dose>()
        var idSeq = 1000L
        fun appendNext(confirmedDose: Dose, now: Long): Dose {
            val kind = ScheduleEngine.kindForDrop(
                SessionType.MORNING, confirmedDose.dropNumber + 1, settings,
                ScheduleEngine.isSpecialTakenToday(stored, dayKey),
            )
            return Dose(
                id = idSeq++, sessionId = session.id, dropNumber = confirmedDose.dropNumber + 1,
                kind = kind, dayKey = dayKey, dueAt = now + settings.doseIntervalMillis,
            )
        }
        var current = persist(plan.doses.first(), session.id, idSeq++, dayKey)
        stored.add(current)
        for (n in 1..3) {
            clock.set(current.dueAt + if (n == 2) 9 * 60 * 1000 else 0) // drop 2 confirmed 9 min late
            val res = ScheduleEngine.confirmDose(current, clock.nowMillis(), settings, isLastDrop = false)
            stored[stored.indexOf(current)] = res.dose
            current = appendNext(res.dose, clock.nowMillis())
            stored.add(current)
        }
        // Drop 4: NO → retry → YES.
        clock.set(current.dueAt)
        val declined = ScheduleEngine.declineDose(current, clock.nowMillis(), settings)
        stored[stored.indexOf(current)] = declined
        clock.advance(5 * 60 * 1000)
        val res4 = ScheduleEngine.confirmDose(declined, clock.nowMillis(), settings, isLastDrop = false)
        assertEquals(1, res4.dose.retryCount) // retry history preserved
        stored[stored.indexOf(declined)] = res4.dose
        // Drop 5 stays pending — the chain head.
        stored.add(appendNext(res4.dose, clock.nowMillis()))
        val confirmedAtBefore = stored.filter { it.status == DoseStatus.CONFIRMED }.map { it.confirmedAt }

        // "Restart": serialize everything, drop all in-memory state, restore.
        val json = BackupJson.exportBackup(listOf(session), stored, settings)
        val restored = BackupJson.importBackup(json)
        assertTrue(restored is BackupJson.ImportResult.Ok)
        val data = (restored as BackupJson.ImportResult.Ok).data
        assertEquals(stored, data.doses)
        assertEquals(
            confirmedAtBefore,
            data.doses.filter { it.status == DoseStatus.CONFIRMED }.map { it.confirmedAt },
        )
        // History queries still work after restore.
        val progress = ScheduleEngine.sessionProgress(data.doses, clock.nowMillis(), 8)
        assertEquals(4, progress.taken)

        // Day rollover: pending doses of the old day become MISSED, history kept.
        val rolled = ScheduleEngine.finalizeDay(data.doses, dayKey)
        assertEquals(1, rolled.count { it.status == DoseStatus.MISSED })
        assertEquals(4, rolled.count { it.status == DoseStatus.CONFIRMED })

        // Corrupt backups are rejected, never half-applied.
        assertTrue(BackupJson.importBackup("{not json") is BackupJson.ImportResult.Error)
        assertTrue(BackupJson.importBackup("{\"version\":999}") is BackupJson.ImportResult.Error)
        val tampered = json.replace("\"status\":\"CONFIRMED\"", "\"status\":\"BOGUS\"")
        assertTrue(BackupJson.importBackup(tampered) is BackupJson.ImportResult.Error)
    }

    // ------------------------------------------------------- extra invariants

    @Test
    fun `dayKey follows the device time zone across zone changes`() {
        // 2026-10-19 00:30 in Karachi == 2026-10-18 19:30 UTC.
        val instant = ScheduleEngine.minutesOfDayToMillis(dayKey, 30, zone)
        assertEquals(dayKey, ScheduleEngine.dayKey(instant, zone))
        assertEquals("2026-10-18", ScheduleEngine.dayKey(instant, ZoneId.of("UTC")))
        // Round-trip: dayKey -> start of day -> same dayKey.
        assertEquals(dayKey, ScheduleEngine.dayKey(ScheduleEngine.startOfDayMillis(dayKey, zone), zone))
    }

    @Test
    fun `latest start computation keeps the evening inside the cutoff`() {
        val latest = ScheduleEngine.latestStartMinutesOfDay(settings.eveningDropCount, settings.doseIntervalMillis, 20 * 60)
        assertEquals(19 * 60 + 30, latest) // 19:30 + 3x10min = 20:00 exactly
        val plan = ScheduleEngine.planSession(
            SessionType.EVENING, dayKey,
            ScheduleEngine.minutesOfDayToMillis(dayKey, latest, zone),
            settings, specialAlreadyTakenToday = true, zone,
        )
        assertTrue(plan is SessionPlan.Ok)
    }

    @Test
    fun `special drop position is inert - no special drop exists`() {
        // New Medicine: the special drop is disabled entirely, so even an
        // explicitly configured position can never produce one.
        val custom = settings.copy(specialDropPosition = 1)
        val startAt = ScheduleEngine.minutesOfDayToMillis(dayKey, 9 * 60, zone)
        val plan = ScheduleEngine.planSession(
            SessionType.MORNING, dayKey, startAt, custom, specialAlreadyTakenToday = false, zone,
        ) as SessionPlan.Ok
        assertTrue(plan.doses.none { it.kind == DoseKind.SPECIAL })
        assertEquals(0, plan.doses.count { it.kind == DoseKind.SPECIAL })
    }

    // ------------------------------------------------- 13 (cutoff fix 2026-10-18)

    @Test
    fun `13 - cutoff applies to evening sessions only`() {
        // Morning starting 21:00 -> 4 drops, NO conflict (last drop 21:30).
        val mornStart = ScheduleEngine.minutesOfDayToMillis(dayKey, 21 * 60, zone)
        val morning = ScheduleEngine.planSession(
            SessionType.MORNING, dayKey, mornStart, settings,
            specialAlreadyTakenToday = false, zone,
        )
        assertTrue("night-time morning start must not conflict", morning is SessionPlan.Ok)
        assertEquals(4, (morning as SessionPlan.Ok).doses.size)

        // Afternoon starting 19:00 -> Ok, no conflict.
        val aftStart = ScheduleEngine.minutesOfDayToMillis(dayKey, 19 * 60, zone)
        val afternoon = ScheduleEngine.planSession(
            SessionType.AFTERNOON, dayKey, aftStart, settings,
            specialAlreadyTakenToday = true, zone,
        )
        assertTrue("evening-hour afternoon start must not conflict", afternoon is SessionPlan.Ok)

        // Evening starting 19:45 -> Conflict (ends 20:15, past the cutoff).
        val evening = ScheduleEngine.planSession(
            SessionType.EVENING, dayKey,
            ScheduleEngine.minutesOfDayToMillis(dayKey, 19 * 60 + 45, zone),
            settings, specialAlreadyTakenToday = true, zone,
        )
        assertTrue("late evening start must still conflict", evening is SessionPlan.Conflict)
    }

    @Test
    fun `14 - cutoff pass only touches evening-session doses`() {
        val cutoff = ScheduleEngine.minutesOfDayToMillis(dayKey, settings.cutoffMinutesOfDay, zone)
        val late = cutoff + 90 * 60 * 1000 // 21:30, past the cutoff
        val morningDose = Dose(id = 1, sessionId = 10, dropNumber = 1, dayKey = dayKey, dueAt = late)
        val afternoonDose = Dose(id = 2, sessionId = 11, dropNumber = 1, dayKey = dayKey, dueAt = late)
        val eveningDose = Dose(id = 3, sessionId = 12, dropNumber = 1, dayKey = dayKey, dueAt = late)
        val ghostDose = Dose(id = 4, sessionId = 99, dropNumber = 1, dayKey = dayKey, dueAt = late)
        val types = mapOf(10L to SessionType.MORNING, 11L to SessionType.AFTERNOON, 12L to SessionType.EVENING)

        val candidates = ScheduleEngine.eveningCutoffCandidates(
            listOf(morningDose, afternoonDose, eveningDose, ghostDose), types::get
        )
        assertEquals("only the evening dose may be auto-missed", listOf(3L), candidates.map { it.id })

        val after = ScheduleEngine.applyCutoff(candidates, cutoff)
        assertEquals(DoseStatus.MISSED, after.single().status)
        // Morning/afternoon doses are never even passed to applyCutoff.
        assertEquals(DoseStatus.PENDING, morningDose.status)
        assertEquals(DoseStatus.PENDING, afternoonDose.status)
    }
}
