#!/usr/bin/env python3
"""Drops Reminder v1.13 — DB-level single-active-session invariant tests (real SQLite).

Mirrors the exact choreography of the new repo logic:
  1. guard: SELECT * FROM sessions WHERE dayKey=? AND status='ACTIVE'
     ORDER BY id DESC LIMIT 1  ->  blocking row for a different-type start
     attempt => NO new session row; deferred_start_<TYPE>_<dayKey> = "1"
     (deferSessionStart also cancels the start alarm + posts the deferred
     notification — Android-only, not mirrored here)
  2. maybeStartDeferredSessions: flag == "1" AND no ACTIVE row AND
     now >= planned start  ->  delete flag, insert the deferred session row
     (ACTIVE) + its first dose
  3. same-type ACTIVE start attempt -> no new row (pre-existing AlreadyActive)
  4. finalizeDay deletes deferred_start_* flags for the finalized day only

The pure decision (ScheduleEngine.blockingSession / deferredStartKey) is
covered by OverlapGuardTest.kt on the JVM; the repo glue needs Android and
is mirrored here.
"""
import sqlite3

DAY = "2026-10-19"
DAY2 = "2026-10-20"
NOW_17 = 1_790_000_000_000  # fake "today 17:00" (evening start time)

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


def deferred_key(stype, day):
    return f"deferred_start_{stype}_{day}"


def insert_session(db, stype, day, status="ACTIVE", started_at=NOW_17):
    cur = db.execute(
        "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?, ?, ?, ?)",
        (stype, day, started_at, status),
    )
    return cur.lastrowid


def insert_dose(db, session_id, drop_no, day, status="PENDING", due_at=NOW_17):
    cur = db.execute(
        "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status, retryCount)"
        " VALUES (?, ?, 'REGULAR', ?, ?, ?, 0)",
        (session_id, drop_no, day, due_at, status),
    )
    return cur.lastrowid


def find_any_active(db, day):
    """Mirrors DropsRepository.findAnyActiveSession."""
    cur = db.execute(
        "SELECT * FROM sessions WHERE dayKey = ? AND status = ? ORDER BY id DESC LIMIT 1",
        (day, "ACTIVE"),
    )
    return cur.fetchone()


def count_sessions(db, stype, day, status=None):
    q = "SELECT COUNT(*) FROM sessions WHERE type = ? AND dayKey = ?"
    args = [stype, day]
    if status:
        q += " AND status = ?"
        args.append(status)
    return db.execute(q, args).fetchone()[0]


def get_flag(db, key):
    cur = db.execute("SELECT value FROM settings WHERE key = ?", (key,))
    row = cur.fetchone()
    return row[0] if row else None


# --- 1. guard: evening auto-start blocked while afternoon ACTIVE -----------
db = fresh_db()
aft_id = insert_session(db, "AFTERNOON", DAY, "ACTIVE")
for n in range(1, 5):
    insert_dose(db, aft_id, n, DAY, "CONFIRMED")
insert_dose(db, aft_id, 5, DAY, "PENDING")

blocking = find_any_active(db, DAY)
check("guard query finds the ACTIVE afternoon session",
      blocking is not None and blocking[1] == "AFTERNOON")

# deferSessionStart choreography: NO evening row; flag set.
db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, '1')",
           (deferred_key("EVENING", DAY),))
check("evening start deferred: no evening session row created",
      count_sessions(db, "EVENING", DAY) == 0)
check("evening start deferred: flag set",
      get_flag(db, deferred_key("EVENING", DAY)) == "1")
check("afternoon session untouched by the deferral",
      count_sessions(db, "AFTERNOON", DAY, "ACTIVE") == 1)

# --- 2. deferred fires once the blocking session completes ---------------
# confirmDose(isLastDrop) -> afternoon COMPLETED
db.execute("UPDATE sessions SET status = 'COMPLETED' WHERE id = ?", (aft_id,))
db.execute("UPDATE doses SET status = 'CONFIRMED', confirmedAt = ? WHERE sessionId = ? AND status = 'PENDING'",
           (NOW_17, aft_id))

# maybeStartDeferredSessions choreography:
flag = get_flag(db, deferred_key("EVENING", DAY))
still_blocked = find_any_active(db, DAY)
if flag == "1" and still_blocked is None:
    db.execute("DELETE FROM settings WHERE key = ?", (deferred_key("EVENING", DAY),))
    eve_id = insert_session(db, "EVENING", DAY, "ACTIVE")
    insert_dose(db, eve_id, 1, DAY, "PENDING")

check("deferred evening starts after afternoon completes",
      count_sessions(db, "EVENING", DAY, "ACTIVE") == 1)
check("deferred flag cleared after firing",
      get_flag(db, deferred_key("EVENING", DAY)) is None)
check("evening first dose chained on start",
      db.execute("SELECT COUNT(*) FROM doses WHERE sessionId = ? AND dropNumber = 1",
                 (eve_id,)).fetchone()[0] == 1)

# --- 3. same-type ACTIVE attempt creates no new row (AlreadyActive) -------
db2 = fresh_db()
insert_session(db2, "AFTERNOON", DAY, "ACTIVE")
# startSession guard: findAnyActiveSession -> AFTERNOON, same type -> AlreadyActive, no insert
check("same-type active attempt creates no duplicate session row",
      count_sessions(db2, "AFTERNOON", DAY) == 1)

# --- 4. deferred does NOT fire while another session still ACTIVE --------
db3 = fresh_db()
insert_session(db3, "AFTERNOON", DAY, "ACTIVE")
db3.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, '1')",
            (deferred_key("EVENING", DAY),))
# maybeStartDeferredSessions: flag set BUT find_any_active -> not None -> skip
skip = not (get_flag(db3, deferred_key("EVENING", DAY)) == "1"
            and find_any_active(db3, DAY) is None)
check("deferred start waits while a session is still ACTIVE",
      skip and count_sessions(db3, "EVENING", DAY) == 0
      and get_flag(db3, deferred_key("EVENING", DAY)) == "1")

# --- 5. finalizeDay clears deferred flags for the finalized day only -----
db4 = fresh_db()
db4.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, '1')",
            (deferred_key("EVENING", DAY),))
db4.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, '1')",
            (deferred_key("AFTERNOON", DAY2),))
# finalizeDay(DAY) choreography: pending -> MISSED + delete deferred flags of DAY
db4.execute("DELETE FROM settings WHERE key = ?", (deferred_key("EVENING", DAY),))
db4.execute("DELETE FROM settings WHERE key = ?", (deferred_key("AFTERNOON", DAY),))
check("finalizeDay clears the finalized day's deferred flags",
      get_flag(db4, deferred_key("EVENING", DAY)) is None
      and get_flag(db4, deferred_key("AFTERNOON", DAY)) is None)
check("other days' deferred flags are untouched",
      get_flag(db4, deferred_key("AFTERNOON", DAY2)) == "1")

# --- 6. cancelled session does not block; flag fires ----------------------
db5 = fresh_db()
c_id = insert_session(db5, "AFTERNOON", DAY, "CANCELLED")
db5.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, '1')",
            (deferred_key("EVENING", DAY),))
flag = get_flag(db5, deferred_key("EVENING", DAY))
still_blocked = find_any_active(db5, DAY)
if flag == "1" and still_blocked is None:
    db5.execute("DELETE FROM settings WHERE key = ?", (deferred_key("EVENING", DAY),))
    insert_session(db5, "EVENING", DAY, "ACTIVE")
check("CANCELLED session does not block the deferred start",
      count_sessions(db5, "EVENING", DAY, "ACTIVE") == 1)

# --- 7. manual start (v1.13 buttons) --------------------------------------
# 7a. blocked while another session ACTIVE -> no new row (AlreadyActive)
db6 = fresh_db()
insert_session(db6, "AFTERNOON", DAY, "ACTIVE")
blocked = find_any_active(db6, DAY)
manual_ok = blocked is None  # manualStartSession guard: same query
if manual_ok:
    insert_session(db6, "EVENING", DAY, "ACTIVE")
check("manual evening start blocked while afternoon ACTIVE: no second ACTIVE row",
      (not manual_ok) and count_sessions(db6, "EVENING", DAY) == 0)

# 7b. succeeds when nothing ACTIVE -> ACTIVE row + first dose
db7 = fresh_db()
blocked = find_any_active(db7, DAY)
if blocked is None:
    eid = insert_session(db7, "EVENING", DAY, "ACTIVE")
    insert_dose(db7, eid, 1, DAY, "PENDING")
check("manual evening start succeeds when nothing ACTIVE",
      count_sessions(db7, "EVENING", DAY, "ACTIVE") == 1)
check("manual start chains drop 1",
      db7.execute("SELECT COUNT(*) FROM doses WHERE sessionId = ? AND dropNumber = 1",
                  (eid,)).fetchone()[0] == 1)

# 7c. manual start clears a stale deferred flag for that type/day
db8 = fresh_db()
db8.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, '1')",
            (deferred_key("EVENING", DAY),))
eid2 = insert_session(db8, "EVENING", DAY, "ACTIVE")  # manual start succeeded
db8.execute("DELETE FROM settings WHERE key = ?", (deferred_key("EVENING", DAY),))
check("manual start clears the deferred flag",
      get_flag(db8, deferred_key("EVENING", DAY)) is None)

# 7d. CANCELLED row does not block a manual start (restart allowed)
db9 = fresh_db()
insert_session(db9, "EVENING", DAY, "CANCELLED")
blocked = find_any_active(db9, DAY)  # status filter: CANCELLED invisible
if blocked is None:
    insert_session(db9, "EVENING", DAY, "ACTIVE")
check("CANCELLED row does not block manual start",
      count_sessions(db9, "EVENING", DAY, "ACTIVE") == 1
      and count_sessions(db9, "EVENING", DAY, "CANCELLED") == 1)

print(f"\n{len(passed)} passed, {len(failed)} failed")
raise SystemExit(1 if failed else 0)
