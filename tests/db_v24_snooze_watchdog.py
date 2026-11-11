#!/usr/bin/env python3
"""Drops Reminder v2.4 — snooze-expiry watchdog: DB + alarm-slot tests.

THE LIVE BUG (user report, 2026-10-22, v2.3): a due drop was snoozed
for 10 minutes; the snooze applied (silence during the window), but at
expiry the alarm never rang.

What the investigation proved (TEST-REPORT §26): the v2.3 fire-path
DECISION logic is correct (source + shipped-DEX disassembly + a
faithful simulation all agree). The structural defect is that the
snooze-expiry reminder had exactly ONE delivery chance: snoozeDose sets
snooze_until_<id>, DELETES nag_next_<id>, and arms the dose's single
one-shot alarm slot for the expiry. If that one fire produces no ring
on the device (cold process / OEM background block on the ring
service's foreground start — the receiver's blanket catch swallows
every failure), then afterwards: dose slot EMPTY (one-shot consumed),
nag flag ABSENT (only a successful fire's armNag or a reconcile
recreates it), and no actor is scheduled to run again. Every other
reminder in the app is self-healing; the snooze expiry was not.

v2.4 fix modeled here: snoozeDose ALSO arms an independent WATCHDOG
alarm (own request-code slot) for snooze_until + 2 min, running the
identical receiver path. Resolving the dose (YES / NO / Skip / session
stop / finalize / clear-history) cancels it; a re-snooze moves it.
This harness models BOTH alarm slots as state — the modeling the v2.3
harness explicitly skipped ("alarm slots cannot run against SQLite")
— because the slot state is exactly where the bug lived.

Modes:
  default          v2.4 choreography — every check must PASS.
  --reproduce-v23  v2.3 choreography — the lost-fire checks must FAIL
                   (that failure IS the reproduction); the script
                   exits 0 only if the v2.3 failure reproduces exactly.
"""
import sqlite3
import sys

DAY = "2026-10-22"
MIN = 60 * 1000
DAY_START = 1_792_627_200_000
SNOOZE_PRESETS = [10 * MIN, 20 * MIN, 30 * MIN, 60 * MIN]
WATCHDOG_DELAY = 2 * MIN
NAG_INTERVAL = 10 * MIN

REPRODUCE_V23 = "--reproduce-v23" in sys.argv
MODEL = "v2.3" if REPRODUCE_V23 else "v2.4"


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

passed, failed, expect_fail = [], [], []


def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    print(("PASS " if cond else "FAIL ") + name + (f" -- {detail}" if detail and not cond else ""))


def check_repro(name, cond, detail=""):
    """In --reproduce-v23 mode these MUST fail (cond False) to prove the
    bug; in default mode the same assertions must pass."""
    if REPRODUCE_V23:
        ok = not cond
        (passed if ok else expect_fail).append(name)
        if ok:
            print("REPRODUCED " + name + " (v2.3 leaves the dose silent)")
        else:
            print("NOT-REPRODUCED " + name + " (expected the v2.3 silence)")
    else:
        check(name, cond, detail)


class World:
    """DB (real SQLite) + the two modeled AlarmManager slots."""

    def __init__(self):
        self.db = sqlite3.connect(":memory:")
        for s in DDL_V3:
            self.db.execute(s)
        self.dose_slot = {}      # doseId -> triggerAt (single one-shot slot)
        self.watchdog_slot = {}  # doseId -> triggerAt (v2.4 only)

    # ---- KV helpers (mirror getRawSetting/upsertSetting) ----
    def flag(self, key):
        row = self.db.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return row[0] if row else None

    def set_flag(self, key, value):
        self.db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", (key, str(value)))

    def del_flag(self, key):
        self.db.execute("DELETE FROM settings WHERE key = ?", (key,))

    # ---- dose helpers ----
    def dose(self, dose_id):
        return self.db.execute("SELECT * FROM doses WHERE id = ?", (dose_id,)).fetchone()

    # columns: id, sessionId, dropNumber, kind, dayKey, dueAt, confirmedAt, status, retryCount, nextRetryAt
    C_STATUS, C_DUE, C_RETRY_AT = 7, 5, 9

    def insert_session(self, stype, status="ACTIVE", started_at=None):
        return self.db.execute(
            "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES (?, ?, ?, ?)",
            (stype, DAY, started_at if started_at is not None else at(8), status),
        ).lastrowid

    def insert_dose(self, session_id, drop_no, due_at):
        return self.db.execute(
            "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status)"
            " VALUES (?, ?, 'REGULAR', ?, ?, 'PENDING')",
            (session_id, drop_no, DAY, due_at),
        ).lastrowid

    # ---- choreography (mirrors DropsRepository) ----
    def write_dose_mutable(self, dose_id, status):
        self.db.execute("UPDATE doses SET status = ? WHERE id = ?", (status, dose_id))
        if status != "PENDING":
            self.del_flag(f"overdue_catchup_{dose_id}")
            self.del_flag(f"nag_next_{dose_id}")
            self.del_flag(f"snooze_until_{dose_id}")
        self.db.commit()

    def arm_nag(self, dose_id, now):
        due_at = self.dose(dose_id)[self.C_DUE]
        nxt = due_at + ((max(0, now - due_at)) // NAG_INTERVAL + 1) * NAG_INTERVAL
        self.dose_slot[dose_id] = nxt
        self.set_flag(f"nag_next_{dose_id}", nxt)
        self.db.commit()
        return nxt

    def fire_decision(self, dose_id, now, already_ringing=False):
        """Mirrors ScheduleEngine.doseFireDecision (v2.4) / the v2.3
        receiver's inline branches (identical order)."""
        d = self.dose(dose_id)
        if d is None or d[self.C_STATUS] != "PENDING":
            return "STALE"
        until = self.flag(f"snooze_until_{dose_id}")
        if until is not None and now < int(until):
            return "SNOOZED"
        if already_ringing:
            return "ALREADY_RINGING"
        return "RING"

    def fire_dose_slot(self, dose_id, now):
        """AlarmManager delivers the dose slot: one-shot, consumed.
        Returns the decision the receiver reaches."""
        assert self.dose_slot.get(dose_id) is not None and self.dose_slot[dose_id] <= now
        del self.dose_slot[dose_id]
        decision = self.fire_decision(dose_id, now)
        if decision == "SNOOZED":
            self.dose_slot[dose_id] = int(self.flag(f"snooze_until_{dose_id}"))
        elif decision == "RING":
            self.arm_nag(dose_id, now)
        return decision

    def snooze(self, dose_id, duration, now):
        """Mirrors snoozeDose: flag set, nag flag deleted, retry
        cleared, dose slot armed at until; v2.4 also arms the watchdog."""
        if duration not in SNOOZE_PRESETS:
            raise ValueError("not a preset")
        d = self.dose(dose_id)
        if d[self.C_STATUS] != "PENDING":
            return "unchanged"
        if now < d[self.C_DUE] - 60_000:
            return "not_due"
        until = now + duration
        self.set_flag(f"snooze_until_{dose_id}", until)
        self.del_flag(f"nag_next_{dose_id}")
        self.db.execute("UPDATE doses SET nextRetryAt = NULL WHERE id = ?", (dose_id,))
        self.db.commit()
        self.dose_slot[dose_id] = until
        if MODEL == "v2.4":
            self.watchdog_slot[dose_id] = until + WATCHDOG_DELAY
        return "snoozed"

    def cancel_watchdog(self, dose_id):
        self.watchdog_slot.pop(dose_id, None)

    def resolve(self, dose_id, how, now):
        """YES / Skip / session-stop: writeDoseMutable hygiene + the
        post-commit alarm cancels (dose slot, and v2.4 the watchdog)."""
        status = {"yes": "CONFIRMED", "skip": "MISSED", "stop": "CANCELLED"}[how]
        self.write_dose_mutable(dose_id, status)
        self.dose_slot.pop(dose_id, None)
        if MODEL == "v2.4":
            self.cancel_watchdog(dose_id)

    def decline(self, dose_id, now):
        """NO: retry owns the dose slot; snooze flag spent (v2.3+);
        v2.4 also cancels the watchdog."""
        self.del_flag(f"snooze_until_{dose_id}")
        self.db.execute("UPDATE doses SET nextRetryAt = ? WHERE id = ?", (now + 5 * MIN, dose_id))
        self.db.commit()
        self.dose_slot[dose_id] = now + 5 * MIN
        if MODEL == "v2.4":
            self.cancel_watchdog(dose_id)


# ============================================================ the live sequence
w = World()
sid = w.insert_session("MORNING", started_at=at(9, 30))
d = w.insert_dose(sid, 2, at(10, 0))   # drop 2 due 10:00
w.dose_slot[d] = at(10, 0)             # armed by the previous YES
w.db.commit()

# 10:00 — due fire: rings; armNag takes the slot (dueAt + 10 min).
check("due fire decides RING", w.fire_dose_slot(d, at(10, 0)) == "RING")
check("due fire arms the nag on the dose slot", w.dose_slot.get(d) == at(10, 10))

# 10:01 — user snoozes 10 minutes.
check("snooze applies", w.snooze(d, 10 * MIN, at(10, 1)) == "snoozed")
check("snooze flag = 10:11", w.flag(f"snooze_until_{d}") == str(at(10, 11)))
check("snooze deletes the nag flag", w.flag(f"nag_next_{d}") is None)
check("dose slot re-armed at the snooze expiry", w.dose_slot.get(d) == at(10, 11))
if MODEL == "v2.4":
    check("v2.4: watchdog armed at expiry + 2 min", w.watchdog_slot.get(d) == at(10, 13))
else:
    check("v2.3: no watchdog exists (single delivery chance)", w.watchdog_slot.get(d) is None)

# During the window a stray fire decides SNOOZED and re-arms the slot.
w.dose_slot[d] = at(10, 5)  # a stale slot occupant fires early (defensive case)
check("stray fire inside the window decides SNOOZED", w.fire_dose_slot(d, at(10, 5)) == "SNOOZED")
check("snoozed early-return re-arms the expiry", w.dose_slot.get(d) == at(10, 11))

# 10:11 — THE EXPIRY FIRE IS LOST on the device (cold process / OEM
# block / swallowed failure): the one-shot slot is consumed and the
# receiver never runs. Model: consume the slot, run nothing.
del w.dose_slot[d]
check_repro(
    "after a lost expiry fire, a further reminder is still armed (watchdog)",
    w.watchdog_slot.get(d) == at(10, 13),
    f"watchdog={w.watchdog_slot.get(d)}",
)
check_repro(
    "after a lost expiry fire, the dose is NOT left with zero armed reminders",
    (w.dose_slot.get(d) is not None) or (w.watchdog_slot.get(d) is not None),
)

if MODEL == "v2.4":
    # 10:13 — the watchdog fires: identical receiver path -> RING, and
    # the nag chain re-arms from it (nag flag recreated).
    assert w.watchdog_slot.get(d) is not None
    del w.watchdog_slot[d]
    check("watchdog fire decides RING", w.fire_decision(d, at(10, 13)) == "RING")
    nxt = w.arm_nag(d, at(10, 13))
    check("watchdog fire re-arms nagging (flag recreated)", w.flag(f"nag_next_{d}") == str(nxt))
    check("nag cadence stays anchored to dueAt", nxt == at(10, 20))
else:
    # v2.3 end-state: nothing armed, no nag flag — silence until an
    # unrelated process start. This is the reproduced bug.
    check("v2.3 end-state: no nag flag (deleted at snooze, never recreated)",
          w.flag(f"nag_next_{d}") is None)
    check("v2.3 end-state: dose still PENDING (never answered, never reminded)",
          w.dose(d)[World.C_STATUS] == "PENDING")

# ============================================================ watchdog lifecycle
w = World()
sid = w.insert_session("MORNING", started_at=at(9, 30))
d = w.insert_dose(sid, 2, at(10, 0))
w.dose_slot[d] = at(10, 0)
w.db.commit()
w.fire_dose_slot(d, at(10, 0))
w.snooze(d, 10 * MIN, at(10, 1))
if MODEL == "v2.4":
    check("lifecycle: watchdog armed after snooze", w.watchdog_slot.get(d) == at(10, 13))
    # Re-snooze at the expiry fire moves the watchdog with the window.
    check("lifecycle: expiry fire rings (primary path intact)",
          w.fire_dose_slot(d, at(10, 11)) == "RING")
    w.snooze(d, 20 * MIN, at(10, 11))
    check("lifecycle: re-snooze moves the watchdog", w.watchdog_slot.get(d) == at(10, 33))
    # YES resolves: watchdog cancelled, never fires.
    del w.dose_slot[d]  # the re-snooze expiry fire (rings, user answers)
    w.resolve(d, "yes", at(10, 31))
    check("lifecycle: YES cancels the watchdog", w.watchdog_slot.get(d) is None)
    check("lifecycle: resolved dose's watchdog fire would decide STALE",
          w.fire_decision(d, at(10, 33)) == "STALE")
    # NO after an expiry: the retry owns reminders; watchdog cancelled.
    d2 = w.insert_dose(sid, 3, at(11, 0))
    w.dose_slot[d2] = at(11, 0)
    w.db.commit()
    w.fire_dose_slot(d2, at(11, 0))
    w.snooze(d2, 10 * MIN, at(11, 1))
    del w.dose_slot[d2]
    w.fire_decision(d2, at(11, 11))  # expiry fire rings
    w.decline(d2, at(11, 11))
    check("lifecycle: NO cancels the watchdog", w.watchdog_slot.get(d2) is None)
    check("lifecycle: NO-retry owns the dose slot", w.dose_slot.get(d2) == at(11, 16))
    # Skip resolves: watchdog cancelled.
    d3 = w.insert_dose(sid, 4, at(12, 0))
    w.dose_slot[d3] = at(12, 0)
    w.db.commit()
    w.fire_dose_slot(d3, at(12, 0))
    w.snooze(d3, 30 * MIN, at(12, 0))
    w.resolve(d3, "skip", at(12, 5))
    check("lifecycle: Skip cancels the watchdog", w.watchdog_slot.get(d3) is None)
    # Session stop resolves: watchdog cancelled.
    d4 = w.insert_dose(sid, 5, at(13, 0))
    w.dose_slot[d4] = at(13, 0)
    w.db.commit()
    w.fire_dose_slot(d4, at(13, 0))
    w.snooze(d4, 60 * MIN, at(13, 1))
    w.resolve(d4, "stop", at(13, 30))
    check("lifecycle: session stop cancels the watchdog", w.watchdog_slot.get(d4) is None)

print()
print(f"[{MODEL}] {len(passed)} passed, {len(failed)} failed")
if REPRODUCE_V23:
    # Success == the v2.3 silence reproduced exactly (the two
    # check_repro assertions recorded as REPRODUCED above).
    raise SystemExit(0 if not failed and not expect_fail else 1)
raise SystemExit(1 if failed else 0)
