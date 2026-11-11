package com.moon.newmedicine.core

import org.junit.Assert.*
import org.junit.Test

/**
 * v1.6 overdue catch-up: [ScheduleEngine.isCatchupDue] is the exact
 * complement of the skip branch in [ScheduleEngine.reconcile] — a PENDING
 * dose whose reminder time passed beyond tolerance gets ONE immediate
 * reminder per overdue episode (the caller guards against nagging with a
 * per-dose KV flag).
 *
 * Pure engine tests; the repo's flag choreography is covered by the
 * DB-level python tests (real SQLite).
 */
class CatchupTest {

    private val now = 1_790_000_000_000L // fake "today 09:00-ish"
    private val MIN = 60_000L

    private fun dose(
        id: Long = 1L,
        dueAt: Long,
        nextRetryAt: Long? = null,
        status: DoseStatus = DoseStatus.PENDING,
    ) = Dose(
        id = id,
        sessionId = 1L,
        dropNumber = 1,
        kind = DoseKind.REGULAR,
        dayKey = "2026-10-19",
        dueAt = dueAt,
        status = status,
        nextRetryAt = nextRetryAt,
    )

    @Test fun `5-min-overdue pending dose needs catch-up`() {
        assertTrue(ScheduleEngine.isCatchupDue(dose(dueAt = now - 5 * MIN), now))
    }

    @Test fun `30s-overdue dose is within tolerance, no catch-up`() {
        assertFalse(ScheduleEngine.isCatchupDue(dose(dueAt = now - 30_000L), now))
    }

    @Test fun `future dose needs no catch-up`() {
        assertFalse(ScheduleEngine.isCatchupDue(dose(dueAt = now + 10 * MIN), now))
    }

    @Test fun `confirmed dose needs no catch-up`() {
        assertFalse(
            ScheduleEngine.isCatchupDue(
                dose(dueAt = now - 5 * MIN, status = DoseStatus.CONFIRMED), now
            )
        )
    }

    @Test fun `missed dose needs no catch-up`() {
        assertFalse(
            ScheduleEngine.isCatchupDue(
                dose(dueAt = now - 5 * MIN, status = DoseStatus.MISSED), now
            )
        )
    }

    @Test fun `cancelled dose needs no catch-up`() {
        assertFalse(
            ScheduleEngine.isCatchupDue(
                dose(dueAt = now - 5 * MIN, status = DoseStatus.CANCELLED), now
            )
        )
    }

    @Test fun `overdue NO-retry (past nextRetryAt) needs catch-up`() {
        // User tapped NO 10 min after due; the retry reminder never fired.
        val d = dose(dueAt = now - 15 * MIN, nextRetryAt = now - 5 * MIN)
        assertTrue(ScheduleEngine.isCatchupDue(d, now))
    }

    @Test fun `recent NO-retry (future nextRetryAt) needs no catch-up`() {
        val d = dose(dueAt = now - 15 * MIN, nextRetryAt = now + 4 * MIN)
        assertFalse(ScheduleEngine.isCatchupDue(d, now))
    }

    @Test fun `custom tolerance respected`() {
        val d = dose(dueAt = now - 90_000L)
        assertFalse(ScheduleEngine.isCatchupDue(d, now, pastToleranceMillis = 120_000L))
        assertTrue(ScheduleEngine.isCatchupDue(d, now, pastToleranceMillis = 60_000L))
    }

    @Test fun `exactly at tolerance boundary is not due`() {
        // Strict <: a dose exactly 60s overdue is still within tolerance.
        assertFalse(ScheduleEngine.isCatchupDue(dose(dueAt = now - 60_000L), now))
    }
}
