#!/usr/bin/env python3
"""Drops Reminder v1.8 — DB-level special-position migration tests (real SQLite).

Mirrors the exact choreography of
DropsRepository.ensureSpecialPositionMigration:
  1. flag special_pos_v18_done == "done" -> no-op
  2. saved specialDropPosition == 8 (old default) -> UPDATE to 6;
     any other saved value (explicit user choice) untouched
  3. in-flight flip: ACTIVE MORNING session today + specialDropEnabled +
     no live SPECIAL today (PENDING or CONFIRMED, mirroring the partial
     unique index) + PENDING REGULAR dose at dropNumber 6 in that session
     -> UPDATE doses SET kind='SPECIAL' WHERE id=?
     (same row id/dueAt -> no alarm changes needed)
  4. set flag "done"

The repo itself needs Android and cannot run on the JVM; the pure
decisions (migratedSpecialPosition, flipTargetForSpecialPosition,
isSpecialLiveToday) are additionally covered by SpecialPositionTest.kt.
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

def dose_kind(db, dose_id):
    return db.execute("SELECT kind FROM doses WHERE id=?", (dose_id,)).fetchone()[0]

def migrate(db, special_enabled=True):
    """Mirror of DropsRepository.ensureSpecialPositionMigration."""
    if get_setting(db, "special_pos_v18_done") == "done":
        return "noop-flag"
    # 1. setting 8 -> 6 (old default only)
    raw = get_setting(db, "specialDropPosition")
    raw_int = int(raw) if raw is not None and raw.lstrip("-").isdigit() else None
    if raw_int == 8:
        set_setting(db, "specialDropPosition", "6")
    # 2. in-flight flip
    if special_enabled:
        sess = db.execute(
            "SELECT id FROM sessions WHERE type='MORNING' AND dayKey=? AND status='ACTIVE' "
            "ORDER BY id DESC LIMIT 1", (DAY,)).fetchone()
        if sess:
            live = db.execute(
                "SELECT COUNT(*) FROM doses WHERE dayKey=? AND kind='SPECIAL' "
                "AND status IN ('PENDING','CONFIRMED')", (DAY,)).fetchone()[0]
            if live == 0:
                tgt = db.execute(
                    "SELECT id FROM doses WHERE sessionId=? AND dropNumber=6 "
                    "AND status='PENDING' AND kind='REGULAR' ORDER BY id ASC LIMIT 1",
                    (sess[0],)).fetchone()
                if tgt:
                    db.execute("UPDATE doses SET kind='SPECIAL' WHERE id=?", (tgt[0],))
    db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES ('special_pos_v18_done', 'done')")
    db.commit()
    return "migrated"

def live_special_count(db):
    return db.execute(
        "SELECT COUNT(*) FROM doses WHERE dayKey=? AND kind='SPECIAL' "
        "AND status IN ('PENDING','CONFIRMED')", (DAY,)).fetchone()[0]

# --- 1. main case: setting 8 -> 6, pending REGULAR #6 flips to SPECIAL ---
db = fresh_db()
set_setting(db, "specialDropPosition", "8")
sid = add_session(db)
for n in range(1, 6):
    add_dose(db, sid, n, status="CONFIRMED")
d6 = add_dose(db, sid, 6)  # PENDING REGULAR
check("8->6: setting migrated", migrate(db) == "migrated" and get_setting(db, "specialDropPosition") == "6")
check("8->6: pending #6 flipped to SPECIAL", dose_kind(db, d6) == "SPECIAL")
check("8->6: flag set", get_setting(db, "special_pos_v18_done") == "done")
check("8->6: exactly one live special", live_special_count(db) == 1)
# index still enforced: a second live special must fail
try:
    add_dose(db, sid, 7, kind="SPECIAL")
    check("8->6: partial unique index enforced", False, "second live special inserted!")
except sqlite3.IntegrityError:
    check("8->6: partial unique index enforced", True)

# --- 2. explicit choice 5 stays 5 (flip still applies per spec) ---
db = fresh_db()
set_setting(db, "specialDropPosition", "5")
sid = add_session(db)
d6 = add_dose(db, sid, 6)
migrate(db)
check("explicit 5: setting untouched", get_setting(db, "specialDropPosition") == "5")
check("explicit 5: flip still applies (no live special)", dose_kind(db, d6) == "SPECIAL")

# --- 3. second run is a no-op ---
db = fresh_db()
set_setting(db, "specialDropPosition", "8")
set_setting(db, "special_pos_v18_done", "done")
sid = add_session(db)
d6 = add_dose(db, sid, 6)
check("flag set: no-op", migrate(db) == "noop-flag" and get_setting(db, "specialDropPosition") == "8")
check("flag set: no flip", dose_kind(db, d6) == "REGULAR")

# --- 4. #6 already CONFIRMED -> no flip, no special invented elsewhere ---
db = fresh_db()
set_setting(db, "specialDropPosition", "8")
sid = add_session(db)
for n in range(1, 7):
    add_dose(db, sid, n, status="CONFIRMED")
d7 = add_dose(db, sid, 7)  # pending chain head
migrate(db)
check("confirmed #6: setting still migrates", get_setting(db, "specialDropPosition") == "6")
check("confirmed #6: no special invented", live_special_count(db) == 0)
check("confirmed #6: #7 stays REGULAR", dose_kind(db, d7) == "REGULAR")

# --- 5. PENDING special already exists -> no flip ---
db = fresh_db()
set_setting(db, "specialDropPosition", "8")
sid = add_session(db)
d6 = add_dose(db, sid, 6)
add_dose(db, sid, 7, kind="SPECIAL")  # live special elsewhere
migrate(db)
check("live special exists: no flip", dose_kind(db, d6) == "REGULAR")
check("live special exists: still exactly one", live_special_count(db) == 1)

# --- 6. CONFIRMED special exists -> no flip ---
db = fresh_db()
set_setting(db, "specialDropPosition", "8")
sid = add_session(db)
d6 = add_dose(db, sid, 6)
add_dose(db, sid, 5, kind="SPECIAL", status="CONFIRMED")
migrate(db)
check("confirmed special exists: no flip", dose_kind(db, d6) == "REGULAR")

# --- 7. no active morning session -> setting migrates, nothing else ---
db = fresh_db()
set_setting(db, "specialDropPosition", "8")
migrate(db)
check("no session: setting migrates", get_setting(db, "specialDropPosition") == "6")
check("no session: flag set", get_setting(db, "special_pos_v18_done") == "done")

# --- 8. special disabled -> setting migrates, no flip ---
db = fresh_db()
set_setting(db, "specialDropPosition", "8")
sid = add_session(db)
d6 = add_dose(db, sid, 6)
migrate(db, special_enabled=False)
check("disabled: setting migrates", get_setting(db, "specialDropPosition") == "6")
check("disabled: no flip", dose_kind(db, d6) == "REGULAR")

print(f"\n{len(passed)} passed, {len(failed)} failed")
sys.exit(1 if failed else 0)
