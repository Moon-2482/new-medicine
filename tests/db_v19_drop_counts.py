#!/usr/bin/env python3
"""Drops Reminder v1.9 — DB-level drop-count migration tests (real SQLite).

Mirrors the exact choreography of
DropsRepository.ensureDropCountMigration:
  1. flag drop_count_v19_done == "done" -> no-op
  2. saved morningDropCount == 8 (old default) -> 7;
     saved afternoonDropCount == 7 (old default) -> 6;
     saved eveningDropCount == 7 (old default) -> 6;
     any other saved value (explicit user choice) untouched;
     absent keys resolve to the new defaults via saveSettings(copy)
  3. set flag "done"

The migration touches ONLY the settings table — never dose rows.
Mid-session safety (a running morning session completing at #7 instead of
inventing #8) lives in the confirmDose/isLastDrop path and is covered by
DropCountTest.kt on the JVM.

The repo itself needs Android and cannot run on the JVM; the pure
decision (migratedDropCounts) is additionally covered by DropCountTest.kt.
"""
import sqlite3
import sys

DAY = "2026-10-19"
NOW = 1_790_000_000_000  # fake "today 09:00-ish"
MIN = 60_000

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

def get_setting(db, key):
    r = db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
    return r[0] if r else None

def set_setting(db, key, value):
    db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", (key, value))

def raw_int(v):
    return int(v) if v is not None and v.lstrip("-").isdigit() else None

def add_session(db, type_="MORNING", status="ACTIVE", day=DAY):
    cur = db.execute(
        "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?, ?, ?, ?)",
        (type_, day, NOW - 60 * MIN, status))
    return cur.lastrowid

def add_dose(db, sid, n, kind="REGULAR", status="PENDING", day=DAY):
    cur = db.execute(
        """INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt,
                              confirmedAt, status, retryCount, nextRetryAt)
           VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL)""",
        (sid, n, kind, day, NOW + (n - 1) * 30 * MIN,
         NOW if status == "CONFIRMED" else None, status))
    return cur.lastrowid

def dose_rows(db, sid):
    return db.execute(
        "SELECT dropNumber, kind, status FROM doses WHERE sessionId=? ORDER BY dropNumber",
        (sid,)).fetchall()

def migrate(db):
    """Mirror of DropsRepository.ensureDropCountMigration."""
    if get_setting(db, "drop_count_v19_done") == "done":
        return "noop-flag"
    m = raw_int(get_setting(db, "morningDropCount"))
    a = raw_int(get_setting(db, "afternoonDropCount"))
    e = raw_int(get_setting(db, "eveningDropCount"))
    nm = 7 if m == 8 else m
    na = 6 if a == 7 else a
    ne = 6 if e == 7 else e
    changed = (nm, na, ne) != (m, a, e)
    if changed:
        # mirrors saveSettings(s.copy(...)): absent keys resolve to the new defaults
        set_setting(db, "morningDropCount", str(nm if nm is not None else 7))
        set_setting(db, "afternoonDropCount", str(na if na is not None else 6))
        set_setting(db, "eveningDropCount", str(ne if ne is not None else 6))
    db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES ('drop_count_v19_done', 'done')")
    db.commit()
    return "migrated" if changed else "noop-values"

# --- 1. main case: old defaults 8/7/7 -> 7/6/6 + flag ---
db = fresh_db()
set_setting(db, "morningDropCount", "8")
set_setting(db, "afternoonDropCount", "7")
set_setting(db, "eveningDropCount", "7")
check("8/7/7: migrated", migrate(db) == "migrated")
check("8/7/7: morning -> 7", get_setting(db, "morningDropCount") == "7")
check("8/7/7: afternoon -> 6", get_setting(db, "afternoonDropCount") == "6")
check("8/7/7: evening -> 6", get_setting(db, "eveningDropCount") == "6")
check("8/7/7: flag set", get_setting(db, "drop_count_v19_done") == "done")

# --- 2. explicit user choices untouched ---
db = fresh_db()
set_setting(db, "morningDropCount", "9")
set_setting(db, "afternoonDropCount", "5")
set_setting(db, "eveningDropCount", "5")
migrate(db)
check("explicit 9/5/5: untouched", (
    get_setting(db, "morningDropCount") == "9"
    and get_setting(db, "afternoonDropCount") == "5"
    and get_setting(db, "eveningDropCount") == "5"))
check("explicit 9/5/5: flag set", get_setting(db, "drop_count_v19_done") == "done")

# --- 3. flag already set -> no-op ---
db = fresh_db()
set_setting(db, "morningDropCount", "8")
set_setting(db, "drop_count_v19_done", "done")
check("flag set: no-op", migrate(db) == "noop-flag"
      and get_setting(db, "morningDropCount") == "8")

# --- 4. fresh install (no keys) -> values no-op, flag set ---
db = fresh_db()
check("fresh: values no-op", migrate(db) == "noop-values"
      and get_setting(db, "morningDropCount") is None)
check("fresh: flag set", get_setting(db, "drop_count_v19_done") == "done")

# --- 5. partial: only morning saved as old default ---
db = fresh_db()
set_setting(db, "morningDropCount", "8")
migrate(db)
check("partial: morning -> 7", get_setting(db, "morningDropCount") == "7")
check("partial: absent afternoon resolves to 6",
      get_setting(db, "afternoonDropCount") == "6")
check("partial: absent evening resolves to 6",
      get_setting(db, "eveningDropCount") == "6")

# --- 6. mid-session: migration never touches dose rows ---
db = fresh_db()
set_setting(db, "morningDropCount", "8")
set_setting(db, "afternoonDropCount", "7")
set_setting(db, "eveningDropCount", "7")
sid = add_session(db)  # ACTIVE morning session, 6 taken under the old count
for n in range(1, 7):
    add_dose(db, sid, n, status="CONFIRMED")
add_dose(db, sid, 7)  # pending chain head
before = dose_rows(db, sid)
migrate(db)
check("mid-session: dose rows untouched", dose_rows(db, sid) == before)
check("mid-session: counts migrated",
      get_setting(db, "morningDropCount") == "7"
      and get_setting(db, "afternoonDropCount") == "6"
      and get_setting(db, "eveningDropCount") == "6")

print(f"\n{len(passed)} passed, {len(failed)} failed")
sys.exit(1 if failed else 0)
