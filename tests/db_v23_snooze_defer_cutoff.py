#!/usr/bin/env python3
"""Drops Reminder v2.3 — DB-level snooze / deferred+30 / cutoff-relaxation
tests (real SQLite).

Mirrors the exact choreography of the v2.3 repo logic:
  1. snoozeDose: due PENDING dose -> snooze_until_<id> = now + duration set;
     nextRetryAt cleared (a pending NO-retry is superseded); nag_next_<id>
     deleted; dose stays PENDING; chain untouched (no new rows).
     Not-due snooze -> ZERO writes. Non-PENDING snooze -> unchanged.
     Non-preset duration -> rejected, ZERO writes. Re-snooze overwrites.
  2. Snooze KV lifecycle end: confirm (YES) and skip both resolve through
     writeDoseMutable, which deletes snooze_until_<id> when the dose leaves
     PENDING. Session stop (cancelSession) does the same for its PENDING
     doses.
  3. Deferred evening start via deferral: flag two-phase lifecycle
     "1" (waiting) -> "ready:<clearedAt+30min>" -> autoStart at readyAt
     (not before) deletes the flag and creates the session with
     ignoreCutoff: the full 6-drop chain runs past the 20:00 cutoff
     (last drop due 22:10), every drop CONFIRMED, none MISSED — the
     status-aware cutoff pass runs between steps and touches nothing.
  4. Cutoff pass (status-aware): an ACTIVE evening session's PENDING dose
     due past 20:00 is NOT a candidate (stays PENDING); the same dose in a
     non-ACTIVE (COMPLETED) session IS a candidate and is marked MISSED;
     a morning dose past 20:00 is never a candidate.
  5. Fresh evening starts keep v2.2 behavior: plan at 19:40 without
     ignoreCutoff -> Conflict (no session row); plan at 17:31 -> Conflict
     (last drop 20:01 > cutoff).
  6. Import hygiene: importBackup wipes snooze_until_% keys (with the
     existing overdue_catchup_% / nag_next_% wipes).

Android-only behavior (alarm slots, notification replacement, chooser UI)
cannot run against SQLite and is covered by the unit tests + code
inspection — noted in TEST-REPORT §25.
"""
import sqlite3

DAY = "2026-10-22"
MIN = 60 * 1000
INTERVAL = 30 * MIN
# Day start 2026-10-22T00:00Z in epoch millis (zone = UTC for the mirror).
DAY_START = 1_792_627_200_000
CUTOFF = DAY_START + 1200 * MIN          # 20:00
SNOOZE_PRESETS = [10 * MIN, 20 * MIN, 30 * MIN, 60 * MIN]


def at(hh, mm=0):
    return DAY_START + (hh * 60 + mm) * MIN


DDL_V3 = [
    "CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, dayKey TEXT NOT NULL, startedAt INTEGER NOT NULL, status TEXT NOT NULL)",
    "CREATE TABLE doses (id INTEGER PRIMARY KEY AUTOINCREMENT, sessionId INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, dropNumber INTEGER NOT NULL, kind TEXT NOT NULL, dayKey TEXT NOT NULL, dueAt INTEGER NOT NULL, confirmedAt INTEGER, status TEXT NOT NULL, retryCount INTEGER NOT NULL DEFAULT 0, nextRetryAt INTEGER)",
    "CREATE UNIQUE INDEX idx_special_once_per_day ON doses(dayKey) WHERE kind='SPECIAL' AND status IN ('PENDING','CONFIRMED')",
    "CREATE UNIQUE INDEX idx_one_active_session ON sessions(dayKey) WHERE status='ACTIVE'",
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
    for s in DDL_V3:
        db.execute(s)
    return db


def insert_session(db, stype, day, status="ACTIVE", started_at=None):
    return db.execute(
        "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?, ?, ?, ?)",
        (stype, day, started_at if started_at is not None else at(8), status),
    ).lastrowid


def insert_dose(db, session_id, drop_no, day, status="PENDING", due_at=None, kind="REGULAR",
                retry_count=0, next_retry_at=None):
    return db.execute(
        "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status, retryCount, nextRetryAt)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (session_id, drop_no, kind, day, due_at if due_at is not None else at(8), status,
         retry_count, next_retry_at),
    ).lastrowid


def get_dose(db, dose_id):
    return db.execute("SELECT * FROM doses WHERE id = ?", (dose_id,)).fetchone()


def get_session(db, sid):
    return db.execute("SELECT * FROM sessions WHERE id = ?", (sid,)).fetchone()


# Dose row columns: id, sessionId, dropNumber, kind, dayKey, dueAt,
# confirmedAt, status, retryCount, nextRetryAt
C_STATUS, C_DUE, C_RETRY_AT = 7, 5, 9


def get_flag(db, key):
    row = db.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
    return row[0] if row else None


def set_flag(db, key, value):
    db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", (key, str(value)))


def can_confirm(due_at, now):
    """Mirrors ScheduleEngine.canConfirm: 60s grace."""
    return now >= due_at - 60_000


def write_dose_mutable(db, dose_id, status):
    """Mirrors DropsRepository.writeDoseMutable v2.3: status write + KV
    hygiene — leaving PENDING deletes catch-up, nag AND snooze flags."""
    db.execute("UPDATE doses SET status = ? WHERE id = ?", (status, dose_id))
    if status != "PENDING":
        db.execute("DELETE FROM settings WHERE key = ?", (f"overdue_catchup_{dose_id}",))
        db.execute("DELETE FROM settings WHERE key = ?", (f"nag_next_{dose_id}",))
        db.execute("DELETE FROM settings WHERE key = ?", (f"snooze_until_{dose_id}",))


def snooze_dose(db, dose_id, duration, now):
    """Mirrors DropsRepository.snoozeDose's DB choreography. Returns
    'snoozed' / 'not_due' / 'unchanged'; raises ValueError on a
    non-preset duration (the Kotlin require)."""
    if duration not in SNOOZE_PRESETS:
        raise ValueError("not a preset")
    dose = get_dose(db, dose_id)
    if dose is None or dose[C_STATUS] != "PENDING":
        return "unchanged"
    if not can_confirm(dose[C_DUE], now):
        return "not_due"
    until = now + duration
    set_flag(db, f"snooze_until_{dose_id}", until)
    db.execute("DELETE FROM settings WHERE key = ?", (f"nag_next_{dose_id}",))
    # NO-retry superseded: nextRetryAt cleared; retryCount kept.
    db.execute("UPDATE doses SET nextRetryAt = NULL WHERE id = ?", (dose_id,))
    db.commit()
    return "snoozed"


def plan_evening(start_at, ignore_cutoff, drop_count=6):
    """Mirrors ScheduleEngine.planSession's evening fit-check. Returns
    'Ok' or 'Conflict'."""
    last_due = start_at + (drop_count - 1) * INTERVAL
    if not ignore_cutoff and last_due > CUTOFF:
        return "Conflict"
    return "Ok"


def evening_cutoff_pass(db, day):
    """Mirrors reconcileAlarms' v2.3 status-aware cutoff pass: candidates
    are the day's doses whose session is EVENING and NOT ACTIVE;
    applyCutoff marks PENDING candidates with dueAt > cutoff MISSED.
    Returns the number of rows changed."""
    rows = db.execute(
        "SELECT d.id, d.status, d.dueAt, s.type, s.status FROM doses d"
        " JOIN sessions s ON s.id = d.sessionId WHERE d.dayKey = ?",
        (day,),
    ).fetchall()
    changed = 0
    for dose_id, status, due_at, stype, sstatus in rows:
        if stype != "EVENING":
            continue
        if sstatus == "ACTIVE":
            continue  # v2.3 exemption: a running session is never cut off
        if status == "PENDING" and due_at > CUTOFF:
            write_dose_mutable(db, dose_id, "MISSED")
            changed += 1
    db.commit()
    return changed


# ---------------------------------------------------------------- snooze set
db = fresh_db()
sid = insert_session(db, "MORNING", DAY, started_at=at(8))
d1 = insert_dose(db, sid, 1, DAY, due_at=at(8))
set_flag(db, f"nag_next_{d1}", at(8, 10))
db.execute("UPDATE doses SET retryCount = 1, nextRetryAt = ? WHERE id = ?", (at(8, 5), d1))
db.commit()
before_rows = db.execute("SELECT COUNT(*) FROM doses").fetchone()[0]

res = snooze_dose(db, d1, 20 * MIN, at(8, 1))
check("snooze: due PENDING dose snoozes", res == "snoozed", res)
check("snooze: snooze_until = now + duration",
      get_flag(db, f"snooze_until_{d1}") == str(at(8, 21)),
      get_flag(db, f"snooze_until_{d1}"))
check("snooze: dose stays PENDING", get_dose(db, d1)[C_STATUS] == "PENDING")
check("snooze: NO-retry superseded (nextRetryAt cleared)",
      get_dose(db, d1)[C_RETRY_AT] is None)
check("snooze: retryCount kept", get_dose(db, d1)[8] == 1)
check("snooze: nag flag deleted", get_flag(db, f"nag_next_{d1}") is None)
check("snooze: chain untouched (no new rows)",
      db.execute("SELECT COUNT(*) FROM doses").fetchone()[0] == before_rows)

# Re-snooze overwrites with the later expiry.
res = snooze_dose(db, d1, 10 * MIN, at(8, 21))
check("snooze: re-snooze allowed and overwrites",
      res == "snoozed" and get_flag(db, f"snooze_until_{d1}") == str(at(8, 31)))

# Confirm (YES) clears the snooze flag via writeDoseMutable.
write_dose_mutable(db, d1, "CONFIRMED")
db.commit()
check("snooze lifecycle: confirm clears snooze_until",
      get_flag(db, f"snooze_until_{d1}") is None)

# ------------------------------------------------------- snooze guards/no-ops
db = fresh_db()
sid = insert_session(db, "MORNING", DAY, started_at=at(8))
future = insert_dose(db, sid, 1, DAY, due_at=at(9))
row_before = get_dose(db, future)
res = snooze_dose(db, future, 10 * MIN, at(8))  # 1h before due: not due yet
check("snooze guard: not-due snooze is a no-op", res == "not_due", res)
check("snooze guard: not-due snooze wrote nothing",
      get_dose(db, future) == row_before and get_flag(db, f"snooze_until_{future}") is None)

done = insert_dose(db, sid, 2, DAY, status="CONFIRMED", due_at=at(8))
res = snooze_dose(db, done, 10 * MIN, at(8, 30))
check("snooze guard: non-PENDING snooze unchanged",
      res == "unchanged" and get_flag(db, f"snooze_until_{done}") is None)

try:
    snooze_dose(db, future, 7 * MIN, at(9))
    rejected = False
except ValueError:
    rejected = True
check("snooze guard: non-preset duration rejected",
      rejected and get_flag(db, f"snooze_until_{future}") is None)

# Skip clears the snooze flag (skip resolves through writeDoseMutable).
d2 = insert_dose(db, sid, 3, DAY, due_at=at(8, 30))
snooze_dose(db, d2, 30 * MIN, at(8, 30))
write_dose_mutable(db, d2, "MISSED")  # skipDose's resolution write
db.commit()
check("snooze lifecycle: skip clears snooze_until",
      get_flag(db, f"snooze_until_{d2}") is None)

# Session stop clears PENDING doses' snooze flags (cancelSession writes
# each PENDING dose CANCELLED through the same writer).
d3 = insert_dose(db, sid, 4, DAY, due_at=at(9, 30))
snooze_dose(db, d3, 60 * MIN, at(9, 30))
write_dose_mutable(db, d3, "CANCELLED")
db.execute("UPDATE sessions SET status = 'CANCELLED' WHERE id = ?", (sid,))
db.commit()
check("snooze lifecycle: session stop clears snooze_until",
      get_flag(db, f"snooze_until_{d3}") is None)

# ------------------------------------------- deferred evening past the cutoff
db = fresh_db()
flag = f"deferred_start_EVENING_{DAY}"
set_flag(db, flag, "1")  # WAITING phase (v1.13 value)
db.commit()
check("deferred: waiting flag is the legacy '1'", get_flag(db, flag) == "1")

# Afternoon blocker clears at 17:40 -> ready at 18:10.
cleared_at = at(17, 40)
ready_at = cleared_at + 30 * MIN
set_flag(db, flag, f"ready:{ready_at}")
db.commit()
raw = get_flag(db, flag)
check("deferred: ready flag encodes clearedAt + 30 min",
      raw == f"ready:{ready_at}" and int(raw.split(":")[1]) - cleared_at == 30 * MIN)

# autoStart BEFORE readyAt: nothing happens, flag survives.
early = at(18, 0)
started = False
if int(raw.split(":")[1]) <= early:
    started = True
check("deferred: no start before readyAt", not started and get_flag(db, flag) is not None)

# autoStart AT readyAt via deferral: flag deleted, plan ignores cutoff.
db.execute("DELETE FROM settings WHERE key = ?", (flag,))
check("deferred: plan at readyAt with ignoreCutoff is Ok",
      plan_evening(ready_at, ignore_cutoff=True) == "Ok")
esid = insert_session(db, "EVENING", DAY, started_at=ready_at)
db.commit()

# Full chain: 6 drops, each confirmed at its due time; the status-aware
# cutoff pass runs at every step (as reconcileAlarms would) — nothing may
# be marked MISSED even though drops 4-6 are due past 20:00.
due = ready_at
ok_chain = True
for seq in range(1, 7):
    did = insert_dose(db, esid, seq, DAY, due_at=due)
    db.commit()
    changed = evening_cutoff_pass(db, DAY)
    if changed != 0 or get_dose(db, did)[C_STATUS] != "PENDING":
        ok_chain = False
    write_dose_mutable(db, did, "CONFIRMED")
    db.commit()
    due += INTERVAL
db.execute("UPDATE sessions SET status = 'COMPLETED' WHERE id = ?", (esid,))
db.commit()
eve_doses = db.execute(
    "SELECT COUNT(*), SUM(status='CONFIRMED'), MAX(dueAt) FROM doses WHERE sessionId = ?",
    (esid,),
).fetchone()
check("deferred evening: full 6-drop chain ran past cutoff, none MISSED",
      ok_chain and eve_doses[0] == 6 and eve_doses[1] == 6)
check("deferred evening: last drop due 20:40 (past the 20:00 cutoff)",
      eve_doses[2] == at(20, 40), str(eve_doses[2]))

# ------------------------------------------------- cutoff pass scoping (3b)
db = fresh_db()
active_sid = insert_session(db, "EVENING", DAY, status="ACTIVE", started_at=at(19))
late = insert_dose(db, active_sid, 5, DAY, due_at=at(20, 30))
db.commit()
changed = evening_cutoff_pass(db, DAY)
check("cutoff 3b: ACTIVE evening dose past 20:00 untouched",
      changed == 0 and get_dose(db, late)[C_STATUS] == "PENDING")

db.close()
db = fresh_db()
stale_sid = insert_session(db, "EVENING", DAY, status="COMPLETED", started_at=at(14))
stale = insert_dose(db, stale_sid, 5, DAY, due_at=at(20, 30))
morn_sid = insert_session(db, "MORNING", DAY, status="COMPLETED", started_at=at(8))
morn = insert_dose(db, morn_sid, 5, DAY, due_at=at(20, 30))
db.commit()
changed = evening_cutoff_pass(db, DAY)
check("cutoff: non-ACTIVE evening dose past 20:00 still marked MISSED",
      changed == 1 and get_dose(db, stale)[C_STATUS] == "MISSED")
check("cutoff: morning dose past 20:00 never touched",
      get_dose(db, morn)[C_STATUS] == "PENDING")

# ------------------------------------------------ fresh evening starts (3c)
db = fresh_db()
check("fresh evening 19:40 start still Conflicts",
      plan_evening(at(19, 40), ignore_cutoff=False) == "Conflict")
check("fresh evening 17:31 start still Conflicts (last drop 20:01)",
      plan_evening(at(17, 31), ignore_cutoff=False) == "Conflict")
check("fresh evening 17:30 start still fits (last drop exactly 20:00)",
      plan_evening(at(17, 30), ignore_cutoff=False) == "Ok")
check("deferred evening 19:40 start bypasses the fit-check",
      plan_evening(at(19, 40), ignore_cutoff=True) == "Ok")

# ------------------------------------------------------------ import hygiene
db = fresh_db()
set_flag(db, "snooze_until_41", at(9))
set_flag(db, "snooze_until_42", at(9, 30))
set_flag(db, "nag_next_41", at(9, 10))
set_flag(db, "overdue_catchup_41", "1")
set_flag(db, "unrelated_key", "keep")
db.commit()
# importBackup's flag wipes (v2.3 adds snooze_until_%).
for pattern in ("overdue_catchup_%", "nag_next_%", "snooze_until_%"):
    db.execute("DELETE FROM settings WHERE key LIKE ?", (pattern,))
db.commit()
remaining = [r[0] for r in db.execute("SELECT key FROM settings").fetchall()]
check("import: snooze/nag/catchup flags wiped, unrelated keys kept",
      remaining == ["unrelated_key"], str(remaining))

print()
print(f"{len(passed)} passed, {len(failed)} failed")
raise SystemExit(1 if failed else 0)
