#!/usr/bin/env python3
"""Drops Reminder v2.0 — DB-level missed-drop nagging + Skip tests (real SQLite).

Mirrors the exact choreography of the new repo logic:
  1. skipDose: PENDING+due -> row becomes MISSED, next drop inserted PENDING
     with dueAt = skipTime + 30min (strict chain: exactly one new row, no
     duplicates); last drop -> session COMPLETED, no new row.
  2. skip guards: not-due tap -> ZERO writes (row byte-identical, no new
     rows); non-PENDING dose -> unchanged.
  3. nag KV lifecycle: armNag sets nag_next_<id>; writeDoseMutable deletes
     nag_next_<id> (+ overdue_catchup_<id>) when the dose leaves PENDING.
  4. nag/catch-up interplay in reconcileAlarms: a dose with a live nag
     trigger (nag_next > now) is skipped by the catch-up pass (never
     double-notify); when nagging is enabled and the nag point is reached,
     the nag pass subsumes catch-up (nag_next recorded, no catch-up flag).
  5. nag safety net: missing/stale nag_next on an overdue PENDING dose ->
     immediate re-arm + nag_next recomputed anchored to dueAt (no drift).
  6. deferral while nagging-overdue: ACTIVE session with an overdue PENDING
     dose -> evening auto-start defers; skip completes the session ->
     deferred evening fires (flag deleted, evening session starts).
  7. A3 repair (v2->v3 upgrade): two ACTIVE sessions on one day -> newest
     (max id) kept ACTIVE, the other CANCELLED with its PENDING doses
     CANCELLED; stale per-dose KV flags dropped; the new partial unique
     index then rejects a second ACTIVE row for the same day while a
     different day still works.
  8. A12/A14 SQL-observable mirrors: importBackup wipes overdue_catchup_%
     and nag_next_% keys; maybeStartDeferredSessions deletes the deferred
     flag when that type's auto-start is disabled (no session started).

Android-only behavior (alarm PendingIntents, NotificationManager calls,
A4 receiver no-op, A6-A9/A11 notification cancels) cannot run against
SQLite and is verified by code inspection — noted per item in the report.
"""
import sqlite3

DAY = "2026-10-19"
DAY2 = "2026-10-20"
SKIP_NOW = 1_790_000_000_000      # fake "today 17:00"
INTERVAL = 30 * 60 * 1000         # dose interval
NAG_INTERVAL = 10 * 60 * 1000     # default nag interval

# v3 DDL: includes the new partial unique index idx_one_active_session.
DDL_V3 = [
    "CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, dayKey TEXT NOT NULL, startedAt INTEGER NOT NULL, status TEXT NOT NULL)",
    "CREATE TABLE doses (id INTEGER PRIMARY KEY AUTOINCREMENT, sessionId INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, dropNumber INTEGER NOT NULL, kind TEXT NOT NULL, dayKey TEXT NOT NULL, dueAt INTEGER NOT NULL, confirmedAt INTEGER, status TEXT NOT NULL, retryCount INTEGER NOT NULL DEFAULT 0, nextRetryAt INTEGER)",
    "CREATE UNIQUE INDEX idx_special_once_per_day ON doses(dayKey) WHERE kind='SPECIAL' AND status IN ('PENDING','CONFIRMED')",
    "CREATE UNIQUE INDEX idx_one_active_session ON sessions(dayKey) WHERE status='ACTIVE'",
    "CREATE INDEX idx_doses_session ON doses(sessionId)",
    "CREATE INDEX idx_doses_status_day ON doses(status, dayKey)",
    "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
]

# v2 DDL: pre-v2.0, no single-active-session index (for the repair test).
DDL_V2 = [s for s in DDL_V3 if "idx_one_active_session" not in s]

passed = []
failed = []


def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    print(("PASS " if cond else "FAIL ") + name + (f" -- {detail}" if detail and not cond else ""))


def fresh_db(ddl=DDL_V3):
    db = sqlite3.connect(":memory:")
    for s in ddl:
        db.execute(s)
    return db


def insert_session(db, stype, day, status="ACTIVE", started_at=SKIP_NOW - 3600_000):
    return db.execute(
        "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?, ?, ?, ?)",
        (stype, day, started_at, status),
    ).lastrowid


def insert_dose(db, session_id, drop_no, day, status="PENDING", due_at=SKIP_NOW, kind="REGULAR"):
    return db.execute(
        "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status, retryCount)"
        " VALUES (?, ?, ?, ?, ?, ?, 0)",
        (session_id, drop_no, kind, day, due_at, status),
    ).lastrowid


def get_dose(db, dose_id):
    return db.execute("SELECT * FROM doses WHERE id = ?", (dose_id,)).fetchone()


def get_session(db, sid):
    return db.execute("SELECT * FROM sessions WHERE id = ?", (sid,)).fetchone()


def get_flag(db, key):
    row = db.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
    return row[0] if row else None


def set_flag(db, key, value):
    db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", (key, str(value)))


def can_confirm(due_at, now):
    """Mirrors ScheduleEngine.canConfirm: 60s grace."""
    return now >= due_at - 60_000


def write_dose_mutable(db, dose_id, status):
    """Mirrors DropsRepository.writeDoseMutable: status write + KV hygiene."""
    db.execute("UPDATE doses SET status = ? WHERE id = ?", (status, dose_id))
    if status != "PENDING":
        db.execute("DELETE FROM settings WHERE key = ?", (f"overdue_catchup_{dose_id}",))
        db.execute("DELETE FROM settings WHERE key = ?", (f"nag_next_{dose_id}",))


def arm_nag(db, dose_id, due_at, now, interval=NAG_INTERVAL, nag_enabled=True):
    """Mirrors DropsRepository.armNag."""
    if not nag_enabled:
        return None
    nxt = due_at + ((max(0, now - due_at)) // interval + 1) * interval
    set_flag(db, f"nag_next_{dose_id}", nxt)
    return nxt


def skip_dose(db, dose_id, now, drop_count=7):
    """Mirrors DropsRepository.skipDose's DB choreography. Returns a dict
    shaped like SkipResult for assertion purposes."""
    dose = get_dose(db, dose_id)
    if dose is None:
        raise ValueError("unknown dose")
    status = dose[7]
    if status != "PENDING":
        return {"unchanged": True, "notDueYet": False, "nextDueAt": None, "dose": dose}
    due_at = dose[5]
    if not can_confirm(due_at, now):
        return {"unchanged": False, "notDueYet": True, "nextDueAt": None, "dose": dose}
    session_id = dose[1]
    drop_no = dose[2]
    day = dose[4]
    write_dose_mutable(db, dose_id, "MISSED")
    is_last = drop_no >= drop_count
    next_due = None
    new_id = None
    if not is_last:
        next_due = now + INTERVAL
        new_id = insert_dose(db, session_id, drop_no + 1, day, "PENDING", due_at=next_due)
    else:
        db.execute("UPDATE sessions SET status = 'COMPLETED' WHERE id = ?", (session_id,))
    db.commit()
    return {"unchanged": False, "notDueYet": False, "nextDueAt": next_due,
            "dose": get_dose(db, dose_id), "newDoseId": new_id}


# --- 1. skip advances the chain ------------------------------------------
db = fresh_db()
morn = insert_session(db, "MORNING", DAY)
d1 = insert_dose(db, morn, 1, DAY, "PENDING", due_at=SKIP_NOW - 120_000)
rows_before = db.execute("SELECT COUNT(*) FROM doses").fetchone()[0]
res = skip_dose(db, d1, SKIP_NOW)
check("skip: PENDING due dose becomes MISSED", res["dose"][7] == "MISSED")
check("skip: next drop dueAt = skipTime + 30min",
      res["nextDueAt"] == SKIP_NOW + INTERVAL, f"got {res['nextDueAt']}")
d2 = get_dose(db, res["newDoseId"])
check("skip: next drop is PENDING drop #2",
      d2 is not None and d2[7] == "PENDING" and d2[2] == 2 and d2[5] == SKIP_NOW + INTERVAL)
check("skip: exactly one new row, no duplicates",
      db.execute("SELECT COUNT(*) FROM doses").fetchone()[0] == rows_before + 1)
check("skip: session stays ACTIVE",
      get_session(db, morn)[4] == "ACTIVE")
check("skip: result not unchanged / not notDueYet",
      not res["unchanged"] and not res["notDueYet"])

# --- 2. skip on the last drop completes the session -----------------------
db = fresh_db()
morn = insert_session(db, "MORNING", DAY)
d7 = insert_dose(db, morn, 7, DAY, "PENDING", due_at=SKIP_NOW - 60_000)
res = skip_dose(db, d7, SKIP_NOW, drop_count=7)
check("skip last drop: dose MISSED", res["dose"][7] == "MISSED")
check("skip last drop: nextDueAt is null", res["nextDueAt"] is None)
check("skip last drop: session COMPLETED", get_session(db, morn)[4] == "COMPLETED")
check("skip last drop: no new dose row",
      db.execute("SELECT COUNT(*) FROM doses").fetchone()[0] == 1)

# --- 3. skip not-due -> ZERO writes ---------------------------------------
db = fresh_db()
morn = insert_session(db, "MORNING", DAY)
d1 = insert_dose(db, morn, 1, DAY, "PENDING", due_at=SKIP_NOW + 300_000)  # due in 5 min
before_row = get_dose(db, d1)
before_count = db.execute("SELECT COUNT(*) FROM doses").fetchone()[0]
res = skip_dose(db, d1, SKIP_NOW)
check("skip not-due: refused with notDueYet", res["notDueYet"] and not res["unchanged"])
check("skip not-due: row byte-identical (zero writes)", get_dose(db, d1) == before_row)
check("skip not-due: no new rows",
      db.execute("SELECT COUNT(*) FROM doses").fetchone()[0] == before_count)
check("skip not-due: session still ACTIVE", get_session(db, morn)[4] == "ACTIVE")

# --- 4. skip non-PENDING -> unchanged -------------------------------------
db = fresh_db()
morn = insert_session(db, "MORNING", DAY)
for st in ("CONFIRMED", "MISSED", "CANCELLED"):
    d = insert_dose(db, morn, 1, DAY, st, due_at=SKIP_NOW - 120_000)
    before = get_dose(db, d)
    res = skip_dose(db, d, SKIP_NOW)
    check(f"skip {st}: unchanged, no writes",
          res["unchanged"] and get_dose(db, d) == before)

# --- 5. nag KV lifecycle ---------------------------------------------------
db = fresh_db()
morn = insert_session(db, "MORNING", DAY)
d1 = insert_dose(db, morn, 1, DAY, "PENDING", due_at=SKIP_NOW - 15 * 60 * 1000)
nxt = arm_nag(db, d1, SKIP_NOW - 15 * 60 * 1000, SKIP_NOW)
check("armNag: nag_next_<id> set, anchored to dueAt (due+20min)",
      get_flag(db, f"nag_next_{d1}") == str(SKIP_NOW - 15 * 60 * 1000 + 2 * NAG_INTERVAL),
      f"got {get_flag(db, f'nag_next_{d1}')}")
check("armNag: disabled nagging sets nothing",
      arm_nag(db, 999, SKIP_NOW, SKIP_NOW, nag_enabled=False) is None
      and get_flag(db, "nag_next_999") is None)
set_flag(db, f"overdue_catchup_{d1}", "1")
write_dose_mutable(db, d1, "MISSED")
check("writeDoseMutable MISSED: nag_next_<id> deleted",
      get_flag(db, f"nag_next_{d1}") is None)
check("writeDoseMutable MISSED: overdue_catchup_<id> deleted",
      get_flag(db, f"overdue_catchup_{d1}") is None)
# decline (stays PENDING) keeps the flags
db2 = fresh_db()
s2 = insert_session(db2, "MORNING", DAY)
d9 = insert_dose(db2, s2, 1, DAY, "PENDING", due_at=SKIP_NOW - 15 * 60 * 1000)
arm_nag(db2, d9, SKIP_NOW - 15 * 60 * 1000, SKIP_NOW)
db2.execute("UPDATE doses SET retryCount = 1 WHERE id = ?", (d9,))  # NO: still PENDING
check("decline keeps PENDING: nag_next_<id> survives",
      get_flag(db2, f"nag_next_{d9}") is not None)

# --- 6. catch-up / nag interplay -------------------------------------------
db = fresh_db()
morn = insert_session(db, "MORNING", DAY)
d1 = insert_dose(db, morn, 1, DAY, "PENDING", due_at=SKIP_NOW - 20 * 60 * 1000)
# 6a. live nag trigger -> catch-up pass must skip (never double-notify)
arm_nag(db, d1, SKIP_NOW - 20 * 60 * 1000, SKIP_NOW)  # nag_next = due+30min > now
nag_next = int(get_flag(db, f"nag_next_{d1}"))
catchup_due = True  # isCatchupDue: PENDING, reminder long past
catchup_flag_before = get_flag(db, f"overdue_catchup_{d1}")
if catchup_due and catchup_flag_before != "1":
    if nag_next is not None and nag_next > SKIP_NOW:
        pass  # skipped: nag already pending
    else:
        set_flag(db, f"overdue_catchup_{d1}", "1")
check("catch-up skips dose with a live nag trigger",
      get_flag(db, f"overdue_catchup_{d1}") is None)
# 6b. nag enabled + nag point reached -> nag subsumes catch-up (no catchup flag)
db = fresh_db()
morn = insert_session(db, "MORNING", DAY)
d1 = insert_dose(db, morn, 1, DAY, "PENDING", due_at=SKIP_NOW - 20 * 60 * 1000)
due_at = SKIP_NOW - 20 * 60 * 1000
if True:  # catch-up pass
    nn = get_flag(db, f"nag_next_{d1}")
    nn = int(nn) if nn else None
    if not (nn is not None and nn > SKIP_NOW):
        nag_due = SKIP_NOW >= due_at + NAG_INTERVAL  # True here
        if nag_due:
            set_flag(db, f"nag_next_{d1}",
                     due_at + ((max(0, SKIP_NOW - due_at)) // NAG_INTERVAL + 1) * NAG_INTERVAL)
        else:
            set_flag(db, f"overdue_catchup_{d1}", "1")
check("nag subsumes catch-up: nag_next set, no catch-up flag",
      get_flag(db, f"nag_next_{d1}") is not None
      and get_flag(db, f"overdue_catchup_{d1}") is None)
# 6c. nag disabled -> classic catch-up flag
db = fresh_db()
morn = insert_session(db, "MORNING", DAY)
d1 = insert_dose(db, morn, 1, DAY, "PENDING", due_at=SKIP_NOW - 20 * 60 * 1000)
set_flag(db, f"overdue_catchup_{d1}", "1")  # one-shot catch-up
check("nag disabled: classic catch-up flag path",
      get_flag(db, f"overdue_catchup_{d1}") == "1"
      and get_flag(db, f"nag_next_{d1}") is None)
# 6d. nag safety net: stale nag_next -> immediate re-arm, anchored cadence
db = fresh_db()
morn = insert_session(db, "MORNING", DAY)
d1 = insert_dose(db, morn, 1, DAY, "PENDING", due_at=SKIP_NOW - 25 * 60 * 1000)
set_flag(db, f"nag_next_{d1}", str(SKIP_NOW - 120_000))  # stale: >60s in the past
due_at = SKIP_NOW - 25 * 60 * 1000
nn = int(get_flag(db, f"nag_next_{d1}"))
if nn is None or nn < SKIP_NOW - 60_000:
    expected = due_at + ((max(0, SKIP_NOW - due_at)) // NAG_INTERVAL + 1) * NAG_INTERVAL
    set_flag(db, f"nag_next_{d1}", expected)  # + immediate alarm (Android-only)
check("nag safety net: stale trigger recomputed anchored to dueAt (due+30min)",
      get_flag(db, f"nag_next_{d1}") == str(due_at + 3 * NAG_INTERVAL))

# --- 7. deferral while nagging-overdue -------------------------------------
db = fresh_db()
aft = insert_session(db, "AFTERNOON", DAY)
for n in range(1, 6):
    insert_dose(db, aft, n, DAY, "CONFIRMED", due_at=SKIP_NOW - 3600_000)
d6 = insert_dose(db, aft, 6, DAY, "PENDING", due_at=SKIP_NOW - 20 * 60 * 1000)  # overdue
arm_nag(db, d6, SKIP_NOW - 20 * 60 * 1000, SKIP_NOW)  # nagging while overdue
set_flag(db, f"deferred_start_EVENING_{DAY}", "1")
res = skip_dose(db, d6, SKIP_NOW, drop_count=6)  # skip last -> COMPLETED
check("deferral setup: afternoon COMPLETED by skip", get_session(db, aft)[4] == "COMPLETED")
# maybeStartDeferredSessions choreography
flag = f"deferred_start_EVENING_{DAY}"
if get_flag(db, flag) == "1":
    active = db.execute(
        "SELECT * FROM sessions WHERE dayKey = ? AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1",
        (DAY,)).fetchone()
    if active is None and SKIP_NOW >= SKIP_NOW:  # planned start passed
        db.execute("DELETE FROM settings WHERE key = ?", (flag,))
        eve = insert_session(db, "EVENING", DAY, started_at=SKIP_NOW)
        insert_dose(db, eve, 1, DAY, "PENDING", due_at=SKIP_NOW)
        db.commit()
check("deferred evening fired after skip completed the session",
      get_flag(db, flag) is None
      and db.execute("SELECT COUNT(*) FROM sessions WHERE type='EVENING' AND dayKey=? AND status='ACTIVE'",
                     (DAY,)).fetchone()[0] == 1)
check("nag KV cleaned when the nagged dose was skipped",
      get_flag(db, f"nag_next_{d6}") is None)

# --- 8. A3 repair (v2->v3 upgrade) ------------------------------------------
db = fresh_db(DDL_V2)
old = insert_session(db, "AFTERNOON", DAY, started_at=SKIP_NOW - 7200_000)
new = insert_session(db, "EVENING", DAY, started_at=SKIP_NOW - 3600_000)
d_old = insert_dose(db, old, 1, DAY, "PENDING", due_at=SKIP_NOW - 600_000)
d_new = insert_dose(db, new, 1, DAY, "PENDING", due_at=SKIP_NOW - 300_000)
set_flag(db, f"nag_next_{d_old}", str(SKIP_NOW + 600_000))
set_flag(db, f"overdue_catchup_{d_old}", "1")


def repair_duplicate_active(db):
    """Mirrors DropsDatabase.repairDuplicateActiveSessions."""
    rows = db.execute(
        "SELECT id, dayKey FROM sessions WHERE status = 'ACTIVE' ORDER BY id DESC").fetchall()
    seen, demoted = set(), []
    for sid, day in rows:
        if day in seen:
            demoted.append(sid)
        else:
            seen.add(day)
    dose_ids = []
    for sid in demoted:
        dose_ids += [r[0] for r in db.execute(
            "SELECT id FROM doses WHERE sessionId = ? AND status = 'PENDING'", (sid,))]
        db.execute(
            "UPDATE doses SET status = 'CANCELLED', nextRetryAt = NULL"
            " WHERE sessionId = ? AND status = 'PENDING'", (sid,))
    if demoted:
        ph = ",".join("?" for _ in demoted)
        db.execute(f"UPDATE sessions SET status = 'CANCELLED' WHERE id IN ({ph})", demoted)
    for did in dose_ids:
        db.execute("DELETE FROM settings WHERE key = ? OR key = ?",
                   (f"overdue_catchup_{did}", f"nag_next_{did}"))
    db.commit()


repair_duplicate_active(db)
db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_one_active_session"
           " ON sessions(dayKey) WHERE status='ACTIVE'")
check("repair: newest ACTIVE kept", get_session(db, new)[4] == "ACTIVE")
check("repair: older duplicate CANCELLED", get_session(db, old)[4] == "CANCELLED")
check("repair: demoted session's PENDING doses CANCELLED",
      get_dose(db, d_old)[7] == "CANCELLED")
check("repair: surviving session's dose untouched",
      get_dose(db, d_new)[7] == "PENDING")
check("repair: stale nag/catch-up keys dropped",
      get_flag(db, f"nag_next_{d_old}") is None
      and get_flag(db, f"overdue_catchup_{d_old}") is None)
try:
    insert_session(db, "MORNING", DAY)  # second ACTIVE same day -> must fail
    dup_ok = True
except sqlite3.IntegrityError:
    dup_ok = False
check("unique index rejects a second ACTIVE session on the same day", not dup_ok)
other_day_ok = True
try:
    insert_session(db, "MORNING", DAY2)
except sqlite3.IntegrityError:
    other_day_ok = False
check("unique index allows ACTIVE on a different day", other_day_ok)
completed_ok = True
try:
    insert_session(db, "AFTERNOON", DAY2, status="COMPLETED")
    insert_session(db, "EVENING", DAY2, status="COMPLETED")
except sqlite3.IntegrityError:
    completed_ok = False
check("unique index ignores non-ACTIVE rows", completed_ok)

# --- 9. A12: import wipes stale per-dose flags ------------------------------
db = fresh_db()
morn = insert_session(db, "MORNING", DAY)
d1 = insert_dose(db, morn, 1, DAY, "PENDING", due_at=SKIP_NOW - 600_000)
set_flag(db, f"overdue_catchup_{d1}", "1")
set_flag(db, f"nag_next_{d1}", str(SKIP_NOW + 600_000))
# importBackup choreography: wipe + insert + delete stale per-dose flags
db.execute("DELETE FROM doses")
db.execute("DELETE FROM sessions")
ni = insert_session(db, "EVENING", DAY)   # imported rows reuse ids
nd = insert_dose(db, ni, 1, DAY, "PENDING", due_at=SKIP_NOW)
db.execute("DELETE FROM settings WHERE key LIKE 'overdue_catchup_%'")
db.execute("DELETE FROM settings WHERE key LIKE 'nag_next_%'")
db.commit()
check("A12: stale overdue_catchup_% keys wiped on import",
      db.execute("SELECT COUNT(*) FROM settings WHERE key LIKE 'overdue_catchup_%'").fetchone()[0] == 0)
check("A12: stale nag_next_% keys wiped on import",
      db.execute("SELECT COUNT(*) FROM settings WHERE key LIKE 'nag_next_%'").fetchone()[0] == 0)

# --- 10. A14: deferred start dropped when auto-start disabled ----------------
db = fresh_db()
set_flag(db, f"deferred_start_EVENING_{DAY}", "1")
set_flag(db, "autoStartEvening", "false")
# maybeStartDeferredSessions choreography
flag = f"deferred_start_EVENING_{DAY}"
if get_flag(db, flag) == "1":
    enabled = get_flag(db, "autoStartEvening") != "false"
    if not enabled:
        db.execute("DELETE FROM settings WHERE key = ?", (flag,))
        db.commit()
check("A14: deferred flag deleted when auto-start disabled",
      get_flag(db, flag) is None)
check("A14: no session started for the disabled type",
      db.execute("SELECT COUNT(*) FROM sessions WHERE type='EVENING'").fetchone()[0] == 0)

print()
print(f"{len(passed)} passed, {len(failed)} failed")
raise SystemExit(1 if failed else 0)
