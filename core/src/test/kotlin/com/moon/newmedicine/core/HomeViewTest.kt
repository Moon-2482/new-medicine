package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test

/**
 * v2.0 home UI: the pure HomeView decisions behind the hero card, the
 * TODAY TIMELINE and the "what happens next" line.
 *
 * The 17:00 overlap incident is the reason this file exists: the hero must
 * name the session that owns the due drop, and a deferred session must say
 * what it is waiting for.
 */
class HomeViewTest {

    private val now = 1_760_000_000_000L
    private val settings = DoseSettings()
    private val dayKey = "2026-10-19"

    private fun dose(
        id: Long = 11L,
        sessionId: Long = 1L,
        dropNumber: Int = 3,
        kind: DoseKind = DoseKind.REGULAR,
        dueAt: Long = now + 10 * 60_000L,
        status: DoseStatus = DoseStatus.PENDING,
    ) = Dose(
        id = id, sessionId = sessionId, dropNumber = dropNumber, kind = kind,
        dayKey = dayKey, dueAt = dueAt, status = status,
    )

    private fun session(
        id: Long = 1L,
        type: SessionType = SessionType.AFTERNOON,
        status: SessionStatus = SessionStatus.ACTIVE,
    ) = DoseSession(id = id, type = type, dayKey = dayKey, startedAt = now - 3_600_000L, status = status)

    private fun progress(taken: Int, total: Int) =
        Progress(taken = taken, pending = 0, missed = 0, upcoming = total - taken, total = total)

    private val noDefer: (SessionType) -> Boolean = { false }
    private val nothingDone: (SessionType) -> Boolean = { false }

    // ------------------------------------------------------------ heroState

    @Test
    fun `hero shows the due drop of the owning session`() {
        val s = session(type = SessionType.AFTERNOON)
        val d = dose(sessionId = s.id, dropNumber = 3, dueAt = now + 10 * 60_000L)
        val hero = HomeView.heroState(d, s, settings, now, listOf(s), noDefer, nothingDone)
        assertTrue(hero is HeroState.DueDrop)
        hero as HeroState.DueDrop
        assertEquals(3, hero.dropNumber)
        assertEquals(4, hero.total)
        assertEquals(SessionType.AFTERNOON, hero.sessionType)
        assertFalse(hero.isSpecial)
        // 10 minutes out: not respondable yet (60s grace rule).
        assertFalse(hero.canRespond)
        assertFalse(hero.isOverdue)
    }

    @Test
    fun `hero names the dose owning session on mismatch`() {
        // currentSession prefers the session holding the next due dose; if
        // they ever disagreed, the DOSE's session is the truth.
        val afternoon = session(id = 1L, type = SessionType.AFTERNOON)
        val evening = session(id = 2L, type = SessionType.EVENING)
        val d = dose(sessionId = 2L, dropNumber = 1, dueAt = now - 30_000L)
        val hero = HomeView.heroState(d, afternoon, settings, now, listOf(afternoon, evening), noDefer, nothingDone)
        assertTrue(hero is HeroState.DueDrop)
        assertEquals(SessionType.EVENING, (hero as HeroState.DueDrop).sessionType)
    }

    @Test
    fun `hero drop due now is respondable`() {
        val s = session()
        val d = dose(dueAt = now - 30_000L) // 30s past due: inside grace, not overdue
        val hero = HomeView.heroState(d, s, settings, now, listOf(s), noDefer, nothingDone)
            as HeroState.DueDrop
        assertTrue(hero.canRespond)
        assertFalse(hero.isOverdue)
    }

    @Test
    fun `hero overdue drop carries overdue minutes`() {
        val s = session()
        val d = dose(dueAt = now - 5 * 60_000L)
        val hero = HomeView.heroState(d, s, settings, now, listOf(s), noDefer, nothingDone)
            as HeroState.DueDrop
        assertTrue(hero.isOverdue)
        assertEquals(5L, hero.overdueByMin)
        assertTrue(hero.canRespond)
    }

    @Test
    fun `hero never flags a special drop - none exists in New Medicine`() {
        val s = session(type = SessionType.MORNING)
        val d = dose(
            sessionId = s.id, dropNumber = 4, kind = DoseKind.REGULAR,
            dueAt = now - 10_000L,
        )
        val hero = HomeView.heroState(d, s, settings, now, listOf(s), noDefer, nothingDone)
            as HeroState.DueDrop
        assertFalse(hero.isSpecial)
        assertEquals(4, hero.dropNumber)
        assertEquals(4, hero.total) // morning count from settings
        assertTrue(hero.canRespond)
    }

    @Test
    fun `hero with no active session offers starts`() {
        val hero = HomeView.heroState(null, null, settings, now, emptyList(), noDefer, nothingDone)
        assertTrue(hero is HeroState.NoActiveSession)
        hero as HeroState.NoActiveSession
        assertTrue(hero.canStartMorning)
        assertTrue(hero.canStartAfternoon)
        assertTrue(hero.canStartEvening)
    }

    @Test
    fun `hero names the deferred start and its blocker`() {
        val morning = session(id = 1L, type = SessionType.MORNING, status = SessionStatus.ACTIVE)
        val deferred: (SessionType) -> Boolean = { it == SessionType.AFTERNOON }
        val hero = HomeView.heroState(null, null, settings, now, listOf(morning), deferred, nothingDone)
        assertTrue(hero is HeroState.DeferredStart)
        hero as HeroState.DeferredStart
        assertEquals(SessionType.AFTERNOON, hero.waitingType)
        assertEquals(SessionType.MORNING, hero.blockingType)
    }

    @Test
    fun `hero all done when every session completed`() {
        val sessions = SessionType.values().mapIndexed { i, t ->
            session(id = i + 1L, type = t, status = SessionStatus.COMPLETED)
        }
        val hero = HomeView.heroState(null, null, settings, now, sessions, noDefer, { true })
        assertEquals(HeroState.AllDone, hero)
    }

    @Test
    fun `hero never offers starts while a session is active`() {
        // Degenerate mid-commit state: ACTIVE row, no pending dose. The
        // hero must not suggest starting anything.
        val s = session()
        val hero = HomeView.heroState(null, s, settings, now, listOf(s), noDefer, nothingDone)
        assertTrue(hero is HeroState.NoActiveSession)
        hero as HeroState.NoActiveSession
        assertFalse(hero.canStartMorning)
        assertFalse(hero.canStartAfternoon)
        assertFalse(hero.canStartEvening)
    }

    // -------------------------------------------------------- timelineRows

    private fun rows(
        sessions: List<DoseSession>,
        deferred: (SessionType) -> Boolean = noDefer,
        progressOf: (SessionType) -> Progress? = { null },
        currentDropOf: (SessionType) -> Int? = { null },
    ) = HomeView.timelineRows(sessions, settings, now, deferred, progressOf, currentDropOf)

    @Test
    fun `timeline done row`() {
        val s = session(type = SessionType.MORNING, status = SessionStatus.COMPLETED)
        val r = rows(listOf(s), progressOf = { if (it == SessionType.MORNING) progress(7, 7) else null })
        val morning = r.first { it.type == SessionType.MORNING }
        assertEquals(TimelineStatus.DONE, morning.status)
        assertEquals("✓ Done — 7/7 taken", morning.detail)
    }

    @Test
    fun `timeline in-progress row names the current drop`() {
        val s = session(type = SessionType.AFTERNOON)
        val r = rows(
            listOf(s),
            progressOf = { if (it == SessionType.AFTERNOON) progress(2, 6) else null },
            currentDropOf = { if (it == SessionType.AFTERNOON) 3 else null },
        )
        val afternoon = r.first { it.type == SessionType.AFTERNOON }
        assertEquals(TimelineStatus.IN_PROGRESS, afternoon.status)
        assertEquals("In progress — Drop 3 of 6", afternoon.detail)
    }

    @Test
    fun `timeline deferred row names the blocker`() {
        val morning = session(id = 1L, type = SessionType.MORNING, status = SessionStatus.ACTIVE)
        val r = rows(
            listOf(morning),
            deferred = { it == SessionType.AFTERNOON },
            progressOf = { if (it == SessionType.MORNING) progress(2, 7) else null },
            currentDropOf = { if (it == SessionType.MORNING) 3 else null },
        )
        val afternoon = r.first { it.type == SessionType.AFTERNOON }
        assertEquals(TimelineStatus.WAITING_DEFERRED, afternoon.status)
        assertEquals("Waiting — starts after Morning finishes", afternoon.detail)
        // And the blocking session itself still shows in progress.
        val m = r.first { it.type == SessionType.MORNING }
        assertEquals(TimelineStatus.IN_PROGRESS, m.status)
        assertEquals("In progress — Drop 3 of 7", m.detail)
    }

    @Test
    fun `timeline upcoming evening shows start time`() {
        val r = rows(emptyList())
        val evening = r.first { it.type == SessionType.EVENING }
        assertEquals(TimelineStatus.UPCOMING, evening.status)
        assertEquals("Upcoming — starts 5:00 PM", evening.detail)
        val afternoon = r.first { it.type == SessionType.AFTERNOON }
        assertEquals(TimelineStatus.UPCOMING, afternoon.status)
        assertEquals("Upcoming — starts 1:45 PM", afternoon.detail)
    }

    @Test
    fun `timeline morning with no row is not started`() {
        val r = rows(emptyList())
        val morning = r.first { it.type == SessionType.MORNING }
        assertEquals(TimelineStatus.NOT_STARTED, morning.status)
        assertEquals("Not started", morning.detail)
    }

    @Test
    fun `timeline cancelled row is restartable not-started`() {
        val s = session(type = SessionType.AFTERNOON, status = SessionStatus.CANCELLED)
        val r = rows(
            listOf(s),
            progressOf = { if (it == SessionType.AFTERNOON) progress(3, 6) else null },
        )
        val afternoon = r.first { it.type == SessionType.AFTERNOON }
        assertEquals(TimelineStatus.NOT_STARTED, afternoon.status)
        assertEquals("Stopped — 3 of 6 taken, can restart", afternoon.detail)
    }

    @Test
    fun `timeline always has three rows in order`() {
        val r = rows(emptyList())
        assertEquals(
            listOf(SessionType.MORNING, SessionType.AFTERNOON, SessionType.EVENING),
            r.map { it.type },
        )
    }

    // ------------------------------------------------------------ nextUpLine

    @Test
    fun `next up line for a mid-session drop`() {
        val s = session()
        val d = dose(dropNumber = 3, dueAt = now - 10_000L)
        val hero = HomeView.heroState(d, s, settings, now, listOf(s), noDefer, nothingDone)
        assertEquals(
            "Next: Drop 4 of 4 — due 10 min after you tap YES",
            HomeView.nextUpLine(hero, settings),
        )
    }

    @Test
    fun `next up line for the last drop`() {
        val s = session()
        val d = dose(dropNumber = 4, dueAt = now - 10_000L)
        val hero = HomeView.heroState(d, s, settings, now, listOf(s), noDefer, nothingDone)
        assertEquals(
            "Last drop of this session — after YES the session completes",
            HomeView.nextUpLine(hero, settings),
        )
    }

    @Test
    fun `next up line with no active session`() {
        val hero = HomeView.heroState(null, null, settings, now, emptyList(), noDefer, nothingDone)
        assertEquals(
            "Tap a Start button below to begin the next session",
            HomeView.nextUpLine(hero, settings),
        )
    }

    @Test
    fun `next up line for a deferred start`() {
        val morning = session(id = 1L, type = SessionType.MORNING, status = SessionStatus.ACTIVE)
        val hero = HomeView.heroState(null, null, settings, now, listOf(morning), { it == SessionType.AFTERNOON }, nothingDone)
        assertEquals(
            "Afternoon starts automatically after Morning finishes",
            HomeView.nextUpLine(hero, settings),
        )
    }

    @Test
    fun `next up line when all done`() {
        assertEquals(
            "All sessions complete for today ✓",
            HomeView.nextUpLine(HeroState.AllDone, settings),
        )
    }
}
