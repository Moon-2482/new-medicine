#!/usr/bin/env python3
"""Drops Reminder v1.6 — DB-level catch-up tests against real SQLite.

Mirrors the exact choreography of DropsRepository:
  - reconcileAlarms catch-up pass: for each PENDING dose with
    isCatchupDue true and no overdue_catchup_<id>="1" flag ->
    schedule immediate (recorded in `scheduled`) + set flag
  - ScheduleEngine.reconcile skip rule: PENDING doses whose reminder is
    older than now-60s are NOT re-armed (catch-up doses are exactly these)
  - writeDoseMutable: when written status != PENDING -> delete the flag
    (decline/NO keeps PENDING -> flag stays)

The repo itself needs Android and cannot run on the JVM; the pure
isCatchupDue decision is additionally covered by CatchupTest.kt.
"""
import sqlite3
import sys

DAY = "2026-10-19"
NOW = 1_790_000_000_000  # fake "today 09:00-ish"
MIN = 60_000
TOL = 60_000

DDL = [
    "CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, dayKey TEXT NOT NULL, startedAt INTEGER NOT NULL, status TEXT NOT NULL)",
    "CREATE TABLE doses (id INTEGER PRIMARY KEY AUTOINCREMENT, sessionId INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, dropNumber INTEGER NOT NULL, kind TEXT NOT NULL, dayKey TEXT NOT NULL, dueAt INTEGER NOT NULL, confirmedAt INTEGER, status TEXT NOT NULL, retryCount INTEGER NOT NULL DEFAULT 0, nextRetryAt INTEGER)",
    "CREATE UNIQUE INDEX idx_special_once_per_day ON doses(dayKey) WHERE kind='SPECIAL' AND status IN ('PENDING','CONFIRMED')",
    "CREATE INDEX idx_doses_session ON doses(sessionId)",
    "CREATE INDEX idx_doses_status_day ON doses(status, dayKey)",
    "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
]

passed = []
failed = []

def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    print(("PASS " if cond else "FAIL ") + name + (f" -- {detail}" if detail and not cond else ""))

def fresh_db():
    db = sqlite3.connect(":memory:")
    for s in DDL:
        db.execute(s)
    return db

def get_flag(db, dose_id):
    r = db.execute("SELECT value FROM settings WHERE key=?",
                   (f"overdue_catchup_{dose_id}",)).fetchone()
    return r[0] if r else None

def set_flag(db, dose_id):
    db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, '1')",
               (f"overdue_catchup_{dose_id}",))

def is_catchup_due(row, now):
    """Mirror of ScheduleEngine.isCatchupDue."""
    trigger = row["nextRetryAt"] if row["nextRetryAt"] is not None else row["dueAt"]
    return row["status"] == "PENDING" and trigger < now - TOL

def reconcile_skip(row, now):
    """Mirror of ScheduleEngine.reconcile's skip branch."""
    trigger = row["nextRetryAt"] if row["nextRetryAt"] is not None else row["dueAt"]
    return not (trigger is not None and trigger >= now - TOL)

def load_pending(db):
    cur = db.execute("SELECT id, dueAt, nextRetryAt, status FROM doses WHERE status='PENDING'")
    return [{"id": r[0], "dueAt": r[1], "nextRetryAt": r[2], "status": r[3]} for r in cur.fetchall()]

def catchup_pass(db, now, scheduled):
    """Mirror of the new catch-up loop in reconcileAlarms."""
    for row in load_pending(db):
        if is_catchup_due(row, now) and get_flag(db, row["id"]) != "1":
            scheduled.append((row["id"], now))  # alarms.scheduleDoseAt(d, now)
            set_flag(db, row["id"])
    db.commit()

def write_dose_mutable(db, dose_id, status):
    """Mirror of writeDoseMutable incl. flag clearing."""
    db.execute("UPDATE doses SET status=? WHERE id=?", (status, dose_id))
    if status != "PENDING":
        db.execute("DELETE FROM settings WHERE key=?", (f"overdue_catchup_{dose_id}",))
    db.commit()

def seed(db):
    sid = db.execute(
        "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?,?,?,?)",
        ("MORNING", DAY, NOW - 60 * MIN, "ACTIVE")).lastrowid
    def dose(drop, due_at, status="PENDING", retry_at=None):
        return db.execute(
            "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status, nextRetryAt)"
            " VALUES (?,?,?,?,?,?,?)",
            (sid, drop, "REGULAR", DAY, due_at, status, retry_at)).lastrowid
    # A: 5-min-overdue pending (the v1.5 user scenario: alarm lost after due)
    a = dose(4, NOW - 5 * MIN)
    # B: future pending (normal path)
    b = dose(5, NOW + 20 * MIN)
    # C: NO-retry overdue (retry reminder lost)
    c = dose(6, NOW - 15 * MIN, retry_at=NOW - 5 * MIN)
    # D: confirmed, past due (must be cancelled, never catch-up)
    d = dose(3, NOW - 40 * MIN, status="CONFIRMED")
    # E: 30s-overdue (within tolerance -> reconcile re-arms, no catch-up)
    e = dose(7, NOW - 30_000)
    db.commit()
    return {"A": a, "B": b, "C": c, "D": d, "E": e}

def test_catchup():
    db = fresh_db()
    ids = seed(db)
    scheduled = []
    catchup_pass(db, NOW, scheduled)

    fired = {dose_id for dose_id, _ in scheduled}
    check("catchup: overdue pending A gets immediate alarm", ids["A"] in fired)
    check("catchup: NO-retry overdue C gets immediate alarm", ids["C"] in fired)
    check("catchup: future B gets NO catch-up alarm", ids["B"] not in fired)
    check("catchup: confirmed D gets NO catch-up alarm", ids["D"] not in fired)
    check("catchup: within-tolerance E gets NO catch-up alarm", ids["E"] not in fired)
    check("catchup: flag set for A", get_flag(db, ids["A"]) == "1")
    check("catchup: flag set for C", get_flag(db, ids["C"]) == "1")
    check("catchup: NO flag for future B", get_flag(db, ids["B"]) is None)
    # Catch-up doses are exactly the ones reconcile skips: no double-scheduling.
    for dose_id in (ids["A"], ids["C"]):
        row = next(r for r in load_pending(db) if r["id"] == dose_id)
        check(f"catchup: dose {dose_id} is skipped by normal reconcile",
              reconcile_skip(row, NOW))

    # Anti-nag: doses already catch-up-notified are NEVER notified again.
    # (E becomes catch-up-due only in pass 2 -- it was within tolerance in
    # pass 1, so pass 2 is legitimately its FIRST catch-up.)
    n_before = len(scheduled)
    catchup_pass(db, NOW + 10 * MIN, scheduled)
    fired2 = {dose_id for dose_id, _ in scheduled[n_before:]}
    check("catchup: A not re-notified (flag)", ids["A"] not in fired2)
    check("catchup: C not re-notified (flag)", ids["C"] not in fired2)
    check("catchup: E gets its first catch-up in pass 2", fired2 == {ids["E"]},
          f"fired2={fired2}")
    # Pass 3: everything flagged -> total silence.
    n_before3 = len(scheduled)
    catchup_pass(db, NOW + 20 * MIN, scheduled)
    check("catchup: third pass notifies nothing (all flagged)",
          len(scheduled) == n_before3, f"scheduled grew {n_before3}->{len(scheduled)}")

    # Confirm clears the flag (writeDoseMutable hygiene).
    write_dose_mutable(db, ids["A"], "CONFIRMED")
    check("catchup: flag cleared when dose leaves PENDING", get_flag(db, ids["A"]) is None)

    # Decline (NO) keeps PENDING -> flag must stay.
    write_dose_mutable(db, ids["C"], "PENDING")
    check("catchup: flag stays on NO (still PENDING)", get_flag(db, ids["C"]) == "1")

    # Missed/cancelled also clear the flag.
    set_flag(db, ids["E"])
    write_dose_mutable(db, ids["E"], "MISSED")
    check("catchup: flag cleared on MISSED", get_flag(db, ids["E"]) is None)

def test_boundary():
    db = fresh_db()
    sid = db.execute(
        "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?,?,?,?)",
        ("MORNING", DAY, NOW - 60 * MIN, "ACTIVE")).lastrowid
    # Exactly at tolerance: not due (strict <).
    edge = db.execute(
        "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status)"
        " VALUES (?,?,?,?,?,?)",
        (sid, 1, "REGULAR", DAY, NOW - TOL, "PENDING")).lastrowid
    # 1ms beyond tolerance: due.
    past = db.execute(
        "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status)"
        " VALUES (?,?,?,?,?,?)",
        (sid, 2, "REGULAR", DAY, NOW - TOL - 1, "PENDING")).lastrowid
    db.commit()
    scheduled = []
    catchup_pass(db, NOW, scheduled)
    fired = {dose_id for dose_id, _ in scheduled}
    check("catchup: exactly-at-tolerance not due", edge not in fired)
    check("catchup: 1ms beyond tolerance due", past in fired)

test_catchup()
test_boundary()

print(f"\n{len(passed)}/{len(passed) + len(failed)} DB tests passed")
sys.exit(1 if failed else 0)
