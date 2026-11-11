package com.moon.newmedicine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * New Medicine v1.0: display numbering is plain identity.
 *
 * There is no special drop in this app, so the old Drops Reminder's
 * v2.2 remap (afternoon/evening sequence 6 displays as 7, so "6" could
 * only ever mean the morning special) is gone: every session shows
 * drops 1–4 as 1–4. Stored doses keep the SEQUENCE number
 * ([Dose.dropNumber]); only the number RENDERED to the user changes,
 * and here it never does. These tests pin the pure mapping for every
 * (type × sequence) pair, prove [ScheduleEngine.kindForDrop] never
 * returns SPECIAL, and pin the two pure HomeView formatters that render
 * a drop number.
 */
class DisplayNumberTest {

    private val settings = DoseSettings()
    private val now = 1_760_000_000_000L
    private val dayKey = "2026-10-20"

    // ------------------------------------------------------------- mapping

    @Test
    fun everySessionIsIdentityForDropsOneToFour() {
        for (type in SessionType.values()) {
            for (n in 1..4) {
                assertEquals(n, ScheduleEngine.displayNumberFor(type, n))
            }
        }
    }

    // ------------------------------------------- kindForDrop is unaffected

    @Test
    fun afternoonSequenceFourIsStillRegularNeverSpecial() {
        assertEquals(
            DoseKind.REGULAR,
            ScheduleEngine.kindForDrop(SessionType.AFTERNOON, 4, settings, specialTaken = false),
        )
    }

    @Test
    fun eveningSequenceFourIsStillRegularNeverSpecial() {
        assertEquals(
            DoseKind.REGULAR,
            ScheduleEngine.kindForDrop(SessionType.EVENING, 4, settings, specialTaken = false),
        )
    }

    @Test
    fun morningSequenceFourIsStillRegularNeverSpecial() {
        assertEquals(
            DoseKind.REGULAR,
            ScheduleEngine.kindForDrop(SessionType.MORNING, 4, settings, specialTaken = false),
        )
    }

    // -------------------------------------------- HomeView render surfaces

    private fun dose(sessionId: Long, dropNumber: Int, dueAt: Long = now + 10 * 60_000L) = Dose(
        id = 11L, sessionId = sessionId, dropNumber = dropNumber, kind = DoseKind.REGULAR,
        dayKey = dayKey, dueAt = dueAt, status = DoseStatus.PENDING,
    )

    private fun session(id: Long, type: SessionType) = DoseSession(
        id = id, type = type, dayKey = dayKey, startedAt = now - 3_600_000L,
        status = SessionStatus.ACTIVE,
    )

    private val noDefer: (SessionType) -> Boolean = { false }
    private val nothingDone: (SessionType) -> Boolean = { false }

    @Test
    fun nextUpLineAfterAfternoonDropThreeSaysDropFour() {
        val s = session(1L, SessionType.AFTERNOON)
        val hero = HomeView.heroState(
            dose(s.id, 3), s, settings, now, listOf(s), noDefer, nothingDone,
        )
        assertEquals(
            "Next: Drop 4 of 4 — due 10 min after you tap YES",
            HomeView.nextUpLine(hero, settings),
        )
    }

    @Test
    fun nextUpLineAfterMorningDropThreeSaysDropFour() {
        val s = session(1L, SessionType.MORNING)
        val hero = HomeView.heroState(
            dose(s.id, 3), s, settings, now, listOf(s), noDefer, nothingDone,
        )
        assertEquals(
            "Next: Drop 4 of 4 — due 10 min after you tap YES",
            HomeView.nextUpLine(hero, settings),
        )
    }

    @Test
    fun nextUpLineForAfternoonLastDropIsTheLastDropLine() {
        val s = session(1L, SessionType.AFTERNOON)
        val hero = HomeView.heroState(
            dose(s.id, 4), s, settings, now, listOf(s), noDefer, nothingDone,
        )
        assertEquals(
            "Last drop of this session — after YES the session completes",
            HomeView.nextUpLine(hero, settings),
        )
    }

    @Test
    fun timelineAfternoonOnLastDropShowsDropFour() {
        val s = session(1L, SessionType.AFTERNOON)
        val rows = HomeView.timelineRows(
            listOf(s), settings, now, noDefer,
            progressOf = { if (it == SessionType.AFTERNOON) Progress(3, 0, 0, 1, 4) else null },
            currentDropOf = { if (it == SessionType.AFTERNOON) 4 else null },
        )
        val afternoon = rows.first { it.type == SessionType.AFTERNOON }
        assertEquals("In progress — Drop 4 of 4", afternoon.detail)
    }

    @Test
    fun timelineMorningOnLastDropShowsDropFour() {
        val s = session(1L, SessionType.MORNING)
        val rows = HomeView.timelineRows(
            listOf(s), settings, now, noDefer,
            progressOf = { if (it == SessionType.MORNING) Progress(3, 0, 0, 1, 4) else null },
            currentDropOf = { if (it == SessionType.MORNING) 4 else null },
        )
        val morning = rows.first { it.type == SessionType.MORNING }
        assertEquals("In progress — Drop 4 of 4", morning.detail)
    }
}
