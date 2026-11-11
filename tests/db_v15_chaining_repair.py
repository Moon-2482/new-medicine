#!/usr/bin/env python3
"""Drops Reminder v1.5 — DB-level tests against real SQLite.

Mirrors the exact SQL choreography of DropsRepository:
  - startSession  -> INSERT session + ONLY drop 1 (strict chaining)
  - confirmDose   -> UPDATE current to CONFIRMED + INSERT drop N+1
  - declineDose   -> UPDATE retryCount/nextRetryAt (no new rows)
  - ensureDataRepaired -> load rows, planDoseRepair rules, DELETE by id

Covers: chaining (1 row at start, +1 row per YES, special once at drop 8,
NO-retry creates nothing), the partial unique special index, and the
14-row corruption repair (3 CONFIRMED + 1 PENDING drop 4 afterwards).
"""
import sqlite3
import sys

DAY = "2026-10-19"
T0 = 1_790_000_000_000  # fake epoch ms base (09:00-ish)
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

# ---------------------------------------------------------------- chaining
def test_chaining():
    db = fresh_db()
    # repo.startSession(MORNING): INSERT session + ONLY drop 1
    cur = db.execute(
        "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?,?,?,?)",
        ("MORNING", DAY, T0, "ACTIVE"))
    sid = cur.lastrowid
    db.execute(
        "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status) VALUES (?,?,?,?,?,?)",
        (sid, 1, "REGULAR", DAY, T0, "PENDING"))
    db.commit()
    n = db.execute("SELECT COUNT(*) FROM doses WHERE sessionId=?", (sid,)).fetchone()[0]
    check("chaining: start inserts exactly 1 row (drop 1)", n == 1, f"rows={n}")

    # repo.confirmDose YES walk: confirm N, insert N+1 with dueAt=confirmedAt+30min
    due = {1: T0}
    for k in range(1, 9):
        now = due[k] + (6 * MIN if k % 2 == 1 else 0)  # wandering confirm times
        row = db.execute(
            "SELECT id FROM doses WHERE sessionId=? AND dropNumber=?", (sid, k)).fetchone()
        if k > 1:
            check(f"chaining: drop {k+1} row absent before drop {k} confirmed",
                  db.execute("SELECT COUNT(*) FROM doses WHERE sessionId=? AND dropNumber=?",
                             (sid, k + 1)).fetchone()[0] == 0)
        else:
            check("chaining: drop 2 row absent before drop 1 confirmed",
                  db.execute("SELECT COUNT(*) FROM doses WHERE sessionId=? AND dropNumber=2",
                             (sid,)).fetchone()[0] == 0)
        assert row, f"drop {k} row must exist"
        db.execute("UPDATE doses SET status='CONFIRMED', confirmedAt=? WHERE id=?", (now, row[0]))
        if k < 8:
            kind = "SPECIAL" if k + 1 == 8 else "REGULAR"
            due[k + 1] = now + 30 * MIN
            db.execute(
                "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status) VALUES (?,?,?,?,?,?)",
                (sid, k + 1, kind, DAY, due[k + 1], "PENDING"))
        else:
            db.execute("UPDATE sessions SET status='COMPLETED' WHERE id=?", (sid,))
        db.commit()

    total = db.execute("SELECT COUNT(*) FROM doses WHERE sessionId=?", (sid,)).fetchone()[0]
    check("chaining: exactly 8 rows ever created", total == 8, f"rows={total}")
    conf = db.execute("SELECT COUNT(*) FROM doses WHERE sessionId=? AND status='CONFIRMED'",
                      (sid,)).fetchone()[0]
    check("chaining: 8 confirmed", conf == 8, f"confirmed={conf}")
    sp = db.execute(
        "SELECT COUNT(*) FROM doses WHERE sessionId=? AND kind='SPECIAL' AND status='CONFIRMED' AND dropNumber=8",
        (sid,)).fetchone()[0]
    check("chaining: special confirmed exactly once at drop 8", sp == 1, f"special={sp}")
    dup = db.execute(
        "SELECT COUNT(*) FROM (SELECT dropNumber FROM doses WHERE sessionId=? GROUP BY dropNumber HAVING COUNT(*) > 1)",
        (sid,)).fetchone()[0]
    check("chaining: zero duplicate dropNumbers", dup == 0)

def test_no_retry_creates_no_rows():
    db = fresh_db()
    cur = db.execute(
        "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?,?,?,?)",
        ("AFTERNOON", DAY, T0, "ACTIVE"))
    sid = cur.lastrowid
    db.execute(
        "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status) VALUES (?,?,?,?,?,?)",
        (sid, 1, "REGULAR", DAY, T0, "PENDING"))
    db.commit()
    # 3x NO: only retryCount/nextRetryAt change
    for i in range(3):
        db.execute("UPDATE doses SET retryCount=?, nextRetryAt=? WHERE sessionId=? AND dropNumber=1",
                   (i + 1, T0 + (i + 1) * 5 * MIN, sid))
        db.commit()
    n = db.execute("SELECT COUNT(*) FROM doses WHERE sessionId=?", (sid,)).fetchone()[0]
    rc = db.execute("SELECT retryCount FROM doses WHERE sessionId=? AND dropNumber=1", (sid,)).fetchone()[0]
    check("NO-retry creates no rows", n == 1 and rc == 3, f"rows={n} retryCount={rc}")

def test_special_index():
    db = fresh_db()
    db.execute("INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?,?,?,?)",
               ("MORNING", DAY, T0, "ACTIVE"))
    db.execute(
        "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status) VALUES (?,?,?,?,?,?)",
        (1, 8, "SPECIAL", DAY, T0, "PENDING"))
    db.commit()
    try:
        db.execute(
            "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status) VALUES (?,?,?,?,?,?)",
            (1, 8, "SPECIAL", DAY, T0 + MIN, "PENDING"))
        db.commit()
        check("special index rejects a second live SPECIAL", False, "insert succeeded")
    except sqlite3.IntegrityError:
        check("special index rejects a second live SPECIAL", True)
    # After the first is MISSED, a new one is allowed (repair only deletes).
    db.execute("UPDATE doses SET status='MISSED' WHERE id=1")
    db.commit()
    try:
        db.execute(
            "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status) VALUES (?,?,?,?,?,?)",
            (1, 8, "SPECIAL", DAY, T0 + MIN, "PENDING"))
        db.commit()
        check("special index allows a new SPECIAL after the old one resolved", True)
    except sqlite3.IntegrityError:
        check("special index allows a new SPECIAL after the old one resolved", False)

# ------------------------------------------------------------------ repair
def plan_dose_repair(rows):
    """Mirror of ScheduleEngine.planDoseRepair: returns ids to delete."""
    delete = set()
    by_num = {}
    for r in rows:
        by_num.setdefault(r["dropNumber"], []).append(r)
    kept_confirmed, kept_pending = [], []
    for num, group in by_num.items():
        conf = [r for r in group if r["status"] == "CONFIRMED"]
        if conf:
            keep = min(conf, key=lambda r: r["id"])
            kept_confirmed.append(keep)
            delete.update(r["id"] for r in group if r["id"] != keep["id"])
        else:
            pend = [r for r in group if r["status"] == "PENDING"]
            if pend:
                keep = max(pend, key=lambda r: r["id"])
                kept_pending.append(keep)
                delete.update(r["id"] for r in group if r["id"] != keep["id"])
    max_c = max((r["dropNumber"] for r in kept_confirmed), default=0)
    stale = [r for r in kept_pending if r["dropNumber"] <= max_c]
    delete.update(r["id"] for r in stale)
    kept_pending = [r for r in kept_pending if r not in stale]
    head = min(kept_pending, key=lambda r: r["dropNumber"], default=None)
    delete.update(r["id"] for r in kept_pending if head is None or r["id"] != head["id"])
    return delete

def load_rows(db, sid):
    db.row_factory = sqlite3.Row
    rows = [dict(r) for r in db.execute("SELECT * FROM doses WHERE sessionId=? ORDER BY id", (sid,))]
    db.row_factory = None
    return rows

def build_corrupted_fixture(db):
    """14 rows: 8 planned + 6 re-anchored (6 YESes across drops 1-3)."""
    db.execute("INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?,?,?,?)",
               ("MORNING", DAY, T0, "ACTIVE"))
    t, t1 = T0, T0 + 2 * MIN
    t9, t2 = t1 + 5 * MIN, t + 35 * MIN
    t10, t3 = t9 + 5 * MIN, t + 65 * MIN
    t11 = t2 + 5 * MIN
    rows = [
        (1, 1, "REGULAR", t, t1, "CONFIRMED"),
        (2, 2, "REGULAR", t + 30 * MIN, t2, "CONFIRMED"),
        (3, 3, "REGULAR", t + 60 * MIN, t3, "CONFIRMED"),
        (4, 4, "REGULAR", t + 90 * MIN, None, "PENDING"),
        (5, 5, "REGULAR", t + 120 * MIN, None, "PENDING"),
        (6, 6, "REGULAR", t + 150 * MIN, None, "PENDING"),
        (7, 7, "REGULAR", t + 180 * MIN, None, "PENDING"),
        (8, 8, "SPECIAL", t + 210 * MIN, None, "PENDING"),
        (9, 2, "REGULAR", t1 + 30 * MIN, t9, "CONFIRMED"),
        (10, 3, "REGULAR", t9 + 30 * MIN, t10, "CONFIRMED"),
        (11, 3, "REGULAR", t2 + 30 * MIN, t11, "CONFIRMED"),
        (12, 4, "REGULAR", t10 + 30 * MIN, None, "PENDING"),
        (13, 4, "REGULAR", t3 + 30 * MIN, None, "PENDING"),
        (14, 4, "REGULAR", t11 + 30 * MIN, None, "PENDING"),
    ]
    for (i, num, kind, due, conf, status) in rows:
        db.execute(
            "INSERT INTO doses (id, sessionId, dropNumber, kind, dayKey, dueAt, confirmedAt, status) VALUES (?,?,?,?,?,?,?,?)",
            (i, 1, num, kind, DAY, due, conf, status))
    db.commit()
    return t11  # a drop-3 confirmation anchoring the newest drop-4 row

def test_repair():
    db = fresh_db()
    t11 = build_corrupted_fixture(db)
    before = db.execute("SELECT COUNT(*) FROM doses").fetchone()[0]
    check("repair fixture: 14 rows", before == 14, f"rows={before}")
    taken = db.execute("SELECT COUNT(*) FROM doses WHERE status='CONFIRMED'").fetchone()[0]
    pend = db.execute("SELECT COUNT(*) FROM doses WHERE status='PENDING'").fetchone()[0]
    check("repair fixture mirrors Taken 6 / Upcoming 8", taken == 6 and pend == 8,
          f"taken={taken} pending={pend}")

    # ensureDataRepaired: one transaction, delete by id, then reconcile rebuilds.
    delete_ids = plan_dose_repair(load_rows(db, 1))
    db.execute(f"DELETE FROM doses WHERE id IN ({','.join('?' * len(delete_ids))})",
               tuple(delete_ids))
    db.execute("INSERT INTO settings (key, value) VALUES ('repair_v3_done','done')")
    db.commit()

    rows = load_rows(db, 1)
    conf_rows = [r for r in rows if r["status"] == "CONFIRMED"]
    pend_rows = [r for r in rows if r["status"] == "PENDING"]
    check("repair: 3 CONFIRMED survive", len(conf_rows) == 3, f"{len(conf_rows)}")
    check("repair: confirmed drops are 1-3",
          sorted(r["dropNumber"] for r in conf_rows) == [1, 2, 3])
    check("repair: exactly 1 PENDING survives", len(pend_rows) == 1, f"{len(pend_rows)}")
    head = pend_rows[0]
    check("repair: chain head is drop 4", head["dropNumber"] == 4)
    check("repair: drop 4 dueAt = drop3.confirmedAt + 30min",
          head["dueAt"] == t11 + 30 * MIN, f"dueAt={head['dueAt']} expected={t11 + 30 * MIN}")
    dup = db.execute(
        "SELECT COUNT(*) FROM (SELECT dropNumber FROM doses GROUP BY dropNumber HAVING COUNT(*) > 1)"
    ).fetchone()[0]
    check("repair: zero duplicate dropNumbers", dup == 0)
    # reconcile would arm exactly one alarm: the single pending chain head.
    due_now = db.execute(
        "SELECT COUNT(*) FROM doses WHERE status='PENDING'").fetchone()[0]
    check("repair: reconcile arms exactly 1 alarm", due_now == 1)
    # Idempotent: second run deletes nothing.
    check("repair: idempotent on clean state",
          len(plan_dose_repair(load_rows(db, 1))) == 0)
    flag = db.execute("SELECT value FROM settings WHERE key='repair_v3_done'").fetchone()[0]
    check("repair: KV flag set", flag == "done")

test_chaining()
test_no_retry_creates_no_rows()
test_special_index()
test_repair()

print(f"\n{len(passed)}/{len(passed) + len(failed)} DB tests passed")
sys.exit(1 if failed else 0)
