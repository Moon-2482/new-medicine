#!/usr/bin/env python3
"""Drops Reminder v2.5 — wake-chain self-healing: DB + chain-slot tests.

THE LIVE FAILURE (user report + Settings screenshot, 2026-10-23,
v2.4): the user woke ~08:00 and used the phone continuously, but the
"Drops start karein?" wake prompt NEVER arrived (window 06:00-11:00).
Wake status at 10:43 read: usage access granted, exact alarms allowed,
notifications enabled — but "Wake chain armed: NAHI", "Next check: -",
"Last check: MORNING_ALREADY_STARTED 08:45".

What the investigation proved (TEST-REPORT §27):
- The already-started DECISION is correctly day-scoped in source:
  hasMorningSessionToday runs SELECT ... WHERE dayKey = <today> and
  only counts MORNING rows in ACTIVE/COMPLETED. Yesterday's stuck
  ACTIVE morning can never satisfy it (pinned below with real SQL).
  The 08:45 outcome was recorded on 2026-10-22, when the morning HAD
  genuinely started — the diagnostics row shows time-of-day only, so
  yesterday's outcome masquerades as today's.
- The structural defect is the chain lifecycle: the ALREADY_STARTED
  branch of runWakeCheck (and the morning-started branch of
  rearmWakeChain) CANCELS the wake chain and arms NOTHING — not even
  tomorrow's first check. The chain's continuation then depends
  entirely on external re-arm triggers (process start, boot,
  DATE_CHANGED, settings change). Every trigger that fired on
  2026-10-22 after the morning started re-confirmed the cancel
  (correctly for that day), and reconcileAlarms — the pass that runs
  on every receiver fire — never validated the chain. On 2026-10-23
  no re-arm had landed by 10:43 (the diagnostics row is the proof),
  so ZERO wake evaluations ran inside the window.

This harness models the chain slot (armed flag + persisted next-check,
mirroring AlarmScheduler's stable slot + KEY_WAKE_NEXT_CHECK_AT) and
the repository choreography over real SQLite, for the two live days.
Modeled device condition for 2026-10-23 (the part no build-env test
can prove — OEM broadcast/process behavior): no process start and no
DATE_CHANGED handling occurred before the user opened the app; only
receiver-driven reconcile passes ran. Under v2.4 choreography that is
exactly the live silence; under v2.5 the chain has already
rolled forward on 2026-10-22, and any reconcile self-heals it anyway.

Modes:
  default          v2.5 choreography — every check must PASS.
  --reproduce-v24  v2.4 choreography — the designated checks must FAIL
                   in exactly the live way; exit 0 only if the failure
                   reproduces exactly.
"""
import sqlite3
import sys
from datetime import date, timedelta

MIN = 60 * 1000
BASE = date(2026, 10, 22)
DAY_START = 1_792_627_200_000  # 2026-10-22 00:00 Asia/Karachi
WIN_START, WIN_END, INTERVAL_MIN = 6 * 60, 11 * 60, 10  # per the device screenshot

REPRODUCE_V24 = "--reproduce-v24" in sys.argv
MODEL = "v2.4" if REPRODUCE_V24 else "v2.5"

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
    """In --reproduce-v24 mode these MUST fail (cond False) to prove the
    bug; in default mode the same assertions must pass."""
    if REPRODUCE_V24:
        ok = not cond
        (passed if ok else expect_fail).append(name)
        if ok:
            print("REPRODUCED " + name + " (v2.4 leaves the wake chain dead)")
        else:
            print("NOT-REPRODUCED " + name + " (expected the v2.4 dead chain)")
    else:
        check(name, cond, detail)


def at(day_off, hh, mm=0):
    return DAY_START + day_off * 24 * 60 * MIN + (hh * 60 + mm) * MIN


def day_key(now):
    off = (now - DAY_START) // (24 * 60 * MIN)
    return (BASE + timedelta(days=int(off))).isoformat()


def mod(now):  # minutes of day (modeled zone == the DAY_START base)
    return ((now - DAY_START) // MIN) % (24 * 60)


def next_wake_check_at(now):
    """Mirror of ScheduleEngine.nextWakeCheckAt."""
    if mod(now) < WIN_START:
        return at((now - DAY_START) // (24 * 60 * MIN), 6, 0)
    if mod(now) < WIN_END:
        nxt = now + INTERVAL_MIN * MIN
        if mod(nxt) < WIN_END and (nxt - DAY_START) // (24 * 60 * MIN) == (now - DAY_START) // (24 * 60 * MIN):
            return nxt
    return at((now - DAY_START) // (24 * 60 * MIN) + 1, 6, 0)


def next_after_started(now):
    """v2.5 only: ScheduleEngine.nextWakeChainAfterMorningStarted."""
    return at((now - DAY_START) // (24 * 60 * MIN) + 1, 6, 0)


def chain_needs_rearm(armed, next_check, now):
    """v2.5 only: ScheduleEngine.wakeChainNeedsRearm."""
    return (not armed) or next_check is None or next_check <= now


class World:
    """Real SQLite state + the modeled wake-chain alarm slot."""

    def __init__(self):
        self.db = sqlite3.connect(":memory:")
        for s in DDL_V3:
            self.db.execute(s)
        self.chain_armed = False
        self.chain_next = None
        self.outcomes = []       # (dayKey, outcome, now)
        self.last_prompt_at = None

    # ---- KV (settings table) ----
    def flag(self, key):
        row = self.db.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
        return row[0] if row else None

    def set_flag(self, key, value):
        self.db.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)", (key, str(value)))

    # ---- repository SQL (mirrors DropsRepository) ----
    def has_morning_today(self, dk):
        rows = self.db.execute("SELECT * FROM sessions WHERE dayKey = ?", (dk,)).fetchall()
        # columns: id, type, dayKey, startedAt, status
        return any(r[1] == "MORNING" and r[4] in ("ACTIVE", "COMPLETED") for r in rows)

    def finalize_day(self, dk):
        self.db.execute("UPDATE doses SET status = 'MISSED' WHERE dayKey = ? AND status = 'PENDING'", (dk,))

    def ensure_rollover(self, now):
        today = day_key(now)
        last = self.flag("lastDayKey")
        if last is None:
            self.set_flag("lastDayKey", today)
            return
        if last == today:
            return
        self.finalize_day(last)
        stale = [r[0] for r in self.db.execute(
            "SELECT DISTINCT dayKey FROM doses WHERE status = 'PENDING' AND dayKey != ?", (today,))]
        for d in stale:
            self.finalize_day(d)
        self.set_flag("lastDayKey", today)

    # ---- chain slot (mirrors AlarmScheduler wake chain) ----
    def schedule_chain(self, t):
        self.chain_armed, self.chain_next = True, t

    def cancel_chain(self):
        self.chain_armed, self.chain_next = False, None

    def stop_chain_for_today(self, now):
        """v2.5: cancel today's chain, arm tomorrow's first check."""
        self.cancel_chain()
        self.schedule_chain(next_after_started(now))

    # ---- choreography ----
    def run_wake_check(self, now, last_used=None):
        self.ensure_rollover(now)
        today = day_key(now)
        if self.has_morning_today(today):
            if MODEL == "v2.5":
                self.stop_chain_for_today(now)
            else:
                self.cancel_chain()
            self.outcomes.append((today, "MORNING_ALREADY_STARTED", now))
            return "MORNING_ALREADY_STARTED"
        if not (WIN_START <= mod(now) < WIN_END):
            self.schedule_chain(next_wake_check_at(now))
            self.outcomes.append((today, "WINDOW_ENDED", now))
            return "WINDOW_ENDED"
        prompt = (
            last_used is not None
            and -MIN <= now - last_used <= INTERVAL_MIN * MIN
            and (self.last_prompt_at is None or now - self.last_prompt_at >= INTERVAL_MIN * MIN)
        )
        if prompt:
            self.last_prompt_at = now
        self.schedule_chain(next_wake_check_at(now))
        outcome = "PROMPTED" if prompt else "NO_PROMPT"
        self.outcomes.append((today, outcome, now))
        return outcome

    def rearm_wake_chain(self, now):
        today = day_key(now)
        if self.has_morning_today(today):
            if MODEL == "v2.5":
                self.stop_chain_for_today(now)
            else:
                self.cancel_chain()
            return
        if WIN_START <= mod(now) < WIN_END:
            self.schedule_chain(now + MIN)
        else:
            self.schedule_chain(next_wake_check_at(now))

    def reconcile(self, now):
        """v2.4 reconcile never touches the wake chain (source-verified).
        v2.5 adds reality validation: re-arm iff the pure predicate says
        the chain is dead or stale — the recorded outcome is NOT trusted."""
        if MODEL == "v2.5" and chain_needs_rearm(self.chain_armed, self.chain_next, now):
            self.rearm_wake_chain(now)

    def process_start(self, now):
        """DropsApp.onCreate / BootReceiver: rollover, reconcile, rearm."""
        self.ensure_rollover(now)
        self.reconcile(now)
        self.rearm_wake_chain(now)

    def receiver_activity(self, now):
        """A receiver fire in a resident process: reconcile runs."""
        self.reconcile(now)

    def chain_tick(self, now, last_used=None):
        """The chain alarm fires iff armed and due; returns outcome/None."""
        if self.chain_armed and self.chain_next is not None and self.chain_next <= now:
            return self.run_wake_check(now, last_used)
        return None


w = World()

# ================= 2026-10-22: the morning that started and stuck =====
sid = w.db.execute(
    "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES ('MORNING', '2026-10-22', ?, 'ACTIVE')",
    (at(0, 7, 5),)).lastrowid
w.db.execute(
    "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, confirmedAt, status)"
    " VALUES (?, 1, 'REGULAR', '2026-10-22', ?, ?, 'CONFIRMED')",
    (sid, at(0, 7, 5), at(0, 7, 5)))
d2 = w.db.execute(
    "INSERT INTO doses (sessionId, dropNumber, kind, dayKey, dueAt, status)"
    " VALUES (?, 2, 'REGULAR', '2026-10-22', ?, 'PENDING')",
    (sid, at(0, 7, 35))).lastrowid
w.set_flag("lastDayKey", "2026-10-22")
w.set_flag(f"snooze_until_{d2}", at(0, 10, 30))  # the v2.3 snooze that stuck the session
w.db.commit()
w.schedule_chain(at(0, 8, 45))  # chain ticking normally that morning

# Suspect 1 pin (real SQL, both modes): yesterday's ACTIVE morning can
# never satisfy today's query — before any rollover/finalize.
check("day-scoping: 10-22 ACTIVE morning does not count for 10-23",
      not w.has_morning_today("2026-10-23"))
check("day-scoping: 10-22 morning counts for 10-22",
      w.has_morning_today("2026-10-22"))

# ---- 08:45, 2026-10-22: the (then-correct) ALREADY_STARTED poison ----
out = w.chain_tick(at(0, 8, 45), last_used=at(0, 8, 44))
check("10-22 08:45 evaluation concludes ALREADY_STARTED (true that day)",
      out == "MORNING_ALREADY_STARTED")
check_repro("after ALREADY_STARTED the chain survives for tomorrow",
            w.chain_armed and w.chain_next == at(1, 6, 0),
            f"armed={w.chain_armed} next={w.chain_next}")

# ---- the rest of 10-22: activity that runs reconcile/process starts --
w.receiver_activity(at(0, 13, 45))   # afternoon receiver activity
check_repro("10-22 afternoon reconcile leaves a live chain for 10-23",
            w.chain_armed and w.chain_next == at(1, 6, 0))
w.process_start(at(0, 20, 15))       # evening process start (rollover no-op same day)
check_repro("10-22 evening process start leaves a live chain for 10-23",
            w.chain_armed and w.chain_next == at(1, 6, 0))

# ============ 2026-10-23: device condition — no process start, ======
# ============ no DATE_CHANGED handling before the app is opened. ======
out = w.chain_tick(at(1, 8, 2), last_used=at(1, 8, 1))  # he woke ~08:00, first use 08:01
evals_1023 = [o for o in w.outcomes if o[0] == "2026-10-23"]
check_repro("10-23 morning: a wake evaluation runs inside the window",
            len(evals_1023) > 0, f"outcomes={w.outcomes}")
check_repro("10-23 morning: the wake prompt is shown",
            any(o[1] == "PROMPTED" for o in evals_1023))
if MODEL == "v2.5":
    st = w.db.execute("SELECT status FROM doses WHERE id = ?", (d2,)).fetchone()[0]
    check("rollover at the 10-23 tick finalized the stuck 10-22 dose as MISSED", st == "MISSED")
    check("after the prompt the chain keeps ticking", w.chain_armed and w.chain_next == at(1, 8, 12))

# ---- stale-next-check facet: a lost tick must self-heal at reconcile -
w.chain_armed, w.chain_next = True, at(1, 7, 0)  # armed, but its fire was lost
w.receiver_activity(at(1, 9, 0))
check_repro("stale next check is re-armed by reconcile validation",
            w.chain_armed and w.chain_next is not None and w.chain_next > at(1, 9, 0))

# ---- 10:44: he starts the morning session by hand (both modes) ------
w.db.execute(
    "INSERT INTO sessions (type, dayKey, startedAt, status) VALUES ('MORNING', '2026-10-23', ?, 'ACTIVE')",
    (at(1, 10, 44),))
w.db.commit()
out = w.run_wake_check(at(1, 10, 45))
check("genuine 10-23 morning start concludes ALREADY_STARTED", out == "MORNING_ALREADY_STARTED")
check_repro("after a genuine start the chain rolls to 10-24, not death",
            w.chain_armed and w.chain_next == at(2, 6, 0),
            f"armed={w.chain_armed} next={w.chain_next}")

print()
print(f"[{MODEL}] {len(passed)} passed, {len(failed)} failed")
if REPRODUCE_V24:
    # Success == the v2.4 dead chain reproduced exactly (every
    # check_repro recorded as REPRODUCED, nothing else failed).
    raise SystemExit(0 if not failed and not expect_fail else 1)
raise SystemExit(1 if failed else 0)
