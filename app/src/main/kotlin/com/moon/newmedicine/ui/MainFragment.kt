package com.moon.newmedicine.ui

import android.app.AlertDialog
import android.app.Fragment
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.R
import com.moon.newmedicine.core.Dose
import com.moon.newmedicine.core.DoseKind
import com.moon.newmedicine.core.DoseSettings
import com.moon.newmedicine.core.DoseSession
import com.moon.newmedicine.core.DoseStatus
import com.moon.newmedicine.core.HeroState
import com.moon.newmedicine.core.HomeView
import com.moon.newmedicine.core.Progress
import com.moon.newmedicine.core.ScheduleEngine
import com.moon.newmedicine.core.SessionStatus
import com.moon.newmedicine.core.SessionType
import com.moon.newmedicine.core.TimelineRow
import com.moon.newmedicine.core.TimelineStatus
import com.moon.newmedicine.repo.DropsRepository
import com.moon.newmedicine.repo.DropsRepository.StartResult
import com.moon.newmedicine.util.TimeFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Home screen v2.0: a hero card for the one due drop (session + drop number
 * + countdown + YES/NO/Skip all bound to the SAME dose), a TODAY TIMELINE
 * with unambiguous per-session statuses, then the existing start/stop
 * controls, special-drop line and daily completion bar.
 */
class MainFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private val zone: ZoneId = ZoneId.systemDefault()

    private lateinit var tvDate: TextView
    // Hero card
    private lateinit var tvHeroSession: TextView
    private lateinit var tvHeroDrop: TextView
    private lateinit var tvHeroSpecialBadge: TextView
    private lateinit var tvHeroCountdown: TextView
    private lateinit var heroButtonRow: LinearLayout
    private lateinit var btnHeroYes: Button
    private lateinit var btnHeroNo: Button
    private lateinit var btnHeroSkip: Button
    private lateinit var btnHeroSnooze: Button
    private lateinit var tvNextUp: TextView
    private var heroDoseId: Long? = null
    // Timeline card
    private lateinit var tvMorningStatus: TextView
    private lateinit var tvAfternoonStatus: TextView
    private lateinit var tvEveningStatus: TextView
    private lateinit var progressMorning: ProgressBar
    private lateinit var progressAfternoon: ProgressBar
    private lateinit var progressEvening: ProgressBar
    private lateinit var tvMorningCounts: TextView
    private lateinit var tvAfternoonCounts: TextView
    private lateinit var tvEveningCounts: TextView
    // Kept from v1.x
    private lateinit var btnStartMorning: Button
    private lateinit var btnStartAfternoon: Button
    private lateinit var btnStartEvening: Button
    private lateinit var btnStopSession: Button
    private var activeSession: DoseSession? = null
    private lateinit var tvSpecialStatus: TextView
    private lateinit var tvDailyPercent: TextView
    private lateinit var progressDaily: ProgressBar
    private lateinit var tvWakeBanner: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.fragment_main, container, false)
        tvDate = v.findViewById(R.id.tv_date)
        tvHeroSession = v.findViewById(R.id.tv_hero_session)
        tvHeroDrop = v.findViewById(R.id.tv_hero_drop)
        tvHeroSpecialBadge = v.findViewById(R.id.tv_hero_special_badge)
        tvHeroCountdown = v.findViewById(R.id.tv_hero_countdown)
        heroButtonRow = v.findViewById(R.id.hero_button_row)
        btnHeroYes = v.findViewById(R.id.btn_hero_yes)
        btnHeroNo = v.findViewById(R.id.btn_hero_no)
        btnHeroSkip = v.findViewById(R.id.btn_hero_skip)
        btnHeroSnooze = v.findViewById(R.id.btn_hero_snooze)
        tvNextUp = v.findViewById(R.id.tv_next_up)
        tvMorningStatus = v.findViewById(R.id.tv_morning_status)
        tvAfternoonStatus = v.findViewById(R.id.tv_afternoon_status)
        tvEveningStatus = v.findViewById(R.id.tv_evening_status)
        progressMorning = v.findViewById(R.id.progress_morning)
        progressAfternoon = v.findViewById(R.id.progress_afternoon)
        progressEvening = v.findViewById(R.id.progress_evening)
        tvMorningCounts = v.findViewById(R.id.tv_morning_counts)
        tvAfternoonCounts = v.findViewById(R.id.tv_afternoon_counts)
        tvEveningCounts = v.findViewById(R.id.tv_evening_counts)
        tvSpecialStatus = v.findViewById(R.id.tv_special_status)
        tvDailyPercent = v.findViewById(R.id.tv_daily_percent)
        progressDaily = v.findViewById(R.id.progress_daily)
        tvWakeBanner = v.findViewById(R.id.tv_wake_banner)
        btnStopSession = v.findViewById(R.id.btn_stop_session)
        btnStartMorning = v.findViewById(R.id.btn_start_morning)
        btnStartAfternoon = v.findViewById(R.id.btn_start_afternoon)
        btnStartEvening = v.findViewById(R.id.btn_start_evening)

        btnHeroYes.setOnClickListener { onHeroYes() }
        btnHeroNo.setOnClickListener { onHeroNo() }
        btnHeroSkip.setOnClickListener { onHeroSkip() }
        btnHeroSnooze.setOnClickListener { onHeroSnooze() }
        btnStartMorning.setOnClickListener { onStartMorning() }
        btnStartAfternoon.setOnClickListener { onStartManual(SessionType.AFTERNOON) }
        btnStartEvening.setOnClickListener { onStartManual(SessionType.EVENING) }
        btnStopSession.setOnClickListener { onStopSession() }
        return v
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ticker = object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(ticker!!, 1000)
    }

    override fun onPause() {
        super.onPause()
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
    }

    private fun refresh() {
        val act = activity ?: return
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val now = System.currentTimeMillis()
                val settings = repo.getSettings()
                val dayKey = repo.todayKey(now)
                val sessions = repo.getTodaySessions(dayKey)
                val current = repo.currentSession(dayKey, now)
                val next = repo.nextDueDose(dayKey, now)
                val day = repo.dayProgress(dayKey, now)
                val dosesToday = repo.getDosesForDay(dayKey)
                val progByType = SessionType.values().associateWith { type ->
                    sessions.firstOrNull { it.type == type }?.let { repo.sessionProgress(it.id, now) }
                }
                val special = dosesToday.firstOrNull { it.kind == DoseKind.SPECIAL }
                // Prefer the ACTIVE morning session for progress: after a
                // cancelled + restarted morning there can be two rows.
                val morningSession = sessions.firstOrNull {
                    it.type == SessionType.MORNING && it.status == SessionStatus.ACTIVE
                } ?: sessions.firstOrNull { it.type == SessionType.MORNING }
                val morningActive =
                    sessions.any { it.type == SessionType.MORNING && it.status == SessionStatus.ACTIVE }
                // A cancelled morning session must not look "started": the
                // user stopped it on purpose and may start again.
                val morningStarted = sessions.any {
                    it.type == SessionType.MORNING &&
                        (it.status == SessionStatus.ACTIVE || it.status == SessionStatus.COMPLETED)
                }
                val morningDone = morningSession != null && run {
                    val p = repo.sessionProgress(morningSession.id, now)
                    p.total > 0 && p.taken == p.total
                }
                // v1.13: manual Start buttons for afternoon/evening mirror the
                // morning button's one-shot pattern. (done, active) per type:
                // a CANCELLED/CONFLICT row leaves the button enabled so a
                // retry/restart is possible, exactly like the morning button.
                fun typeState(type: SessionType): Pair<Boolean, Boolean> {
                    val s = sessions.firstOrNull { it.type == type && it.status == SessionStatus.ACTIVE }
                        ?: sessions.firstOrNull { it.type == type && it.status == SessionStatus.COMPLETED }
                    val active = s?.status == SessionStatus.ACTIVE
                    val done = if (s != null) {
                        val p = repo.sessionProgress(s.id, now)
                        p.total > 0 && p.taken == p.total
                    } else false
                    return done to active
                }
                val (afternoonDone, afternoonActive) = typeState(SessionType.AFTERNOON)
                val (eveningDone, eveningActive) = typeState(SessionType.EVENING)
                // Inline wake banner: window active and morning not started yet.
                // (The Start button below the banner is the action; the manual
                // button stays as the fallback.)
                val wakeBannerVisible = !morningStarted && repo.isWakeWindowActive(now)
                // v2.0: the hero and timeline are pure decisions over this data.
                // v2.3: the deferred flag's VALUE now encodes the phase —
                // presence (any value) means deferred; the ready phase
                // carries the planned start (blocker-clear + 30 min).
                val deferredOf: (SessionType) -> Boolean = { type ->
                    repo.getRawSetting(ScheduleEngine.deferredStartKey(type, dayKey)) != null
                }
                val deferredStartAtOf: (SessionType) -> Long? = { type ->
                    repo.deferredReadyAt(type, dayKey)
                }
                val doneOf: (SessionType) -> Boolean = { type ->
                    when (type) {
                        SessionType.MORNING -> morningDone
                        SessionType.AFTERNOON -> afternoonDone
                        SessionType.EVENING -> eveningDone
                    }
                }
                val hero = HomeView.heroState(
                    next, current, settings, now, sessions, deferredOf, doneOf, deferredStartAtOf
                )
                val currentDropOf: (SessionType) -> Int? = { type ->
                    next?.takeIf { d ->
                        sessions.firstOrNull { it.id == d.sessionId }?.type == type
                    }?.dropNumber
                }
                // v2.10: render-time only — the "📞 Call ke baad" note on
                // the timeline row whose current pending dose had its
                // reminder call-deferred (KV flag set by the receiver /
                // the mid-ring poll, cleared when the dose actually
                // rings or is resolved).
                val callDeferredOf: (SessionType) -> Boolean = { type ->
                    val d = next?.takeIf {
                        sessions.firstOrNull { s -> s.id == it.sessionId }?.type == type
                    }
                    d != null && repo.getRawSetting(ScheduleEngine.callDeferKey(d.id)) != null
                }
                val rows = HomeView.timelineRows(
                    sessions, settings, now, deferredOf, progByType::get, currentDropOf,
                    deferredStartAtOf, zone, callDeferredOf,
                )
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    bind(
                        now, settings, current, hero, rows, progByType, special,
                        morningDone, morningActive, wakeBannerVisible,
                        afternoonDone, afternoonActive, eveningDone, eveningActive, day,
                    )
                }
            } catch (e: Exception) {
                // transient DB error: keep last frame, next tick retries
            }
        }.start()
    }

    private fun bind(
        now: Long,
        settings: DoseSettings,
        current: DoseSession?,
        hero: HeroState,
        rows: List<TimelineRow>,
        progByType: Map<SessionType, Progress?>,
        special: Dose?,
        morningDone: Boolean,
        morningActive: Boolean,
        wakeBannerVisible: Boolean,
        afternoonDone: Boolean,
        afternoonActive: Boolean,
        eveningDone: Boolean,
        eveningActive: Boolean,
        day: Progress,
    ) {
        tvDate.text = LocalDate.now(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))

        bindHero(hero, now, settings)

        for (row in rows) {
            when (row.type) {
                SessionType.MORNING -> bindTimelineRow(
                    row, progressMorning, tvMorningStatus, tvMorningCounts, progByType[SessionType.MORNING]
                )
                SessionType.AFTERNOON -> bindTimelineRow(
                    row, progressAfternoon, tvAfternoonStatus, tvAfternoonCounts, progByType[SessionType.AFTERNOON]
                )
                SessionType.EVENING -> bindTimelineRow(
                    row, progressEvening, tvEveningStatus, tvEveningCounts, progByType[SessionType.EVENING]
                )
            }
        }

        tvSpecialStatus.text = when {
            special == null ->
                // Strict chaining: the special row only exists once the
                // chain reaches it — until then it is still coming.
                if (morningActive && settings.specialDropEnabled)
                    "Special drop: scheduled for drop ${settings.specialDropPosition}"
                else "Special drop: not scheduled"
            special.status == DoseStatus.CONFIRMED ->
                "Special drop: taken at ${TimeFormat.formatTime(special.confirmedAt ?: now)} ✓"
            special.status == DoseStatus.MISSED -> "Special drop: missed ✗"
            else -> "Special drop: due ${TimeFormat.formatTime(special.dueAt)}"
        }

        tvDailyPercent.text = "Daily completion: ${day.percent}%"
        progressDaily.progress = day.percent

        tvWakeBanner.visibility = if (wakeBannerVisible) View.VISIBLE else View.GONE

        // "Session rok dein" is offered for the currently active session only.
        activeSession = current
        btnStopSession.visibility = if (current != null) View.VISIBLE else View.GONE

        // Start is a one-shot per morning: while a morning session is ACTIVE
        // the chain is already running (drop 1 was inserted at start), so the
        // button stays disabled instead of bouncing into an "already active"
        // toast. After a cancel the button re-enables (restart allowed).
        if (morningDone) {
            btnStartMorning.isEnabled = false
            btnStartMorning.text = getString(R.string.morning_done)
        } else if (morningActive) {
            btnStartMorning.isEnabled = false
            btnStartMorning.text = getString(R.string.morning_active)
        } else {
            btnStartMorning.isEnabled = true
            btnStartMorning.text = getString(R.string.start_morning)
        }

        // v1.13: manual afternoon/evening starts. Same one-shot pattern as
        // the morning button; the repo guard (single-active-session
        // invariant) is the backstop, so a tap can never create an overlap.
        bindStartButton(
            btnStartAfternoon, afternoonDone, afternoonActive,
            R.string.afternoon_done, R.string.afternoon_active, R.string.start_afternoon,
        )
        bindStartButton(
            btnStartEvening, eveningDone, eveningActive,
            R.string.evening_done, R.string.evening_active, R.string.start_evening,
        )
    }

    /**
     * The hero card: session name + drop number always describe the SAME
     * dose the YES/NO/Skip buttons act on ([heroDoseId]).
     */
    private fun bindHero(hero: HeroState, now: Long, settings: DoseSettings) {
        when (hero) {
            is HeroState.DueDrop -> {
                tvHeroSession.text = sessionLabel(hero.sessionType) + " session"
                // v2.2: hero.dropNumber is the SEQUENCE number; the card
                // shows the DISPLAY number (afternoon/evening last drop = 7).
                tvHeroDrop.text = getString(
                    R.string.hero_drop_of,
                    ScheduleEngine.displayNumberFor(hero.sessionType, hero.dropNumber),
                    hero.total,
                )
                tvHeroSpecialBadge.visibility =
                    if (hero.isSpecial) View.VISIBLE else View.GONE
                val reminderAt = hero.nextReminderAtMillis
                when {
                    hero.isOverdue -> {
                        tvHeroCountdown.text =
                            getString(R.string.overdue_by_min, hero.overdueByMin)
                        tvHeroCountdown.setTextColor(resources.getColor(R.color.danger, null))
                    }
                    reminderAt != null && reminderAt > now -> {
                        tvHeroCountdown.text =
                            "in " + TimeFormat.formatCountdown(maxOf(0L, reminderAt - now))
                        tvHeroCountdown.setTextColor(resources.getColor(R.color.primary, null))
                    }
                    else -> {
                        tvHeroCountdown.text = getString(R.string.due_now)
                        tvHeroCountdown.setTextColor(resources.getColor(R.color.accent, null))
                    }
                }
                // Accidental-tap guard (same as DoseFragment): the buttons
                // enable only once the drop is due (60s grace); the 1s
                // ticker rebinds, so they open automatically at due time.
                heroButtonRow.visibility = View.VISIBLE
                btnHeroYes.isEnabled = hero.canRespond
                btnHeroNo.isEnabled = hero.canRespond
                btnHeroSkip.visibility = if (hero.canRespond) View.VISIBLE else View.GONE
                // v2.3: Snooze mirrors Skip's rule exactly.
                btnHeroSnooze.visibility = if (hero.canRespond) View.VISIBLE else View.GONE
                heroDoseId = hero.doseId
            }
            is HeroState.NoActiveSession -> {
                tvHeroSession.text = getString(R.string.no_active_session)
                tvHeroDrop.text = getString(R.string.hero_no_drop)
                tvHeroSpecialBadge.visibility = View.GONE
                tvHeroCountdown.text = ""
                heroButtonRow.visibility = View.GONE
                btnHeroSkip.visibility = View.GONE
                btnHeroSnooze.visibility = View.GONE
                heroDoseId = null
            }
            is HeroState.DeferredStart -> {
                tvHeroSession.text =
                    getString(R.string.waiting_session, sessionLabel(hero.waitingType))
                tvHeroDrop.text = getString(R.string.hero_no_drop)
                tvHeroSpecialBadge.visibility = View.GONE
                // v2.3: once the blocker has cleared, the planned start
                // (clear + 30 min) is known — show the exact time.
                tvHeroCountdown.text = hero.startsAtMillis?.let {
                    "Starts at ${TimeFormat.formatTime(it)}"
                } ?: ""
                heroButtonRow.visibility = View.GONE
                btnHeroSkip.visibility = View.GONE
                btnHeroSnooze.visibility = View.GONE
                heroDoseId = null
            }
            HeroState.AllDone -> {
                tvHeroSession.text = getString(R.string.all_done_today)
                tvHeroDrop.text = "✓"
                tvHeroSpecialBadge.visibility = View.GONE
                tvHeroCountdown.text = ""
                heroButtonRow.visibility = View.GONE
                btnHeroSkip.visibility = View.GONE
                btnHeroSnooze.visibility = View.GONE
                heroDoseId = null
            }
        }
        tvNextUp.text = HomeView.nextUpLine(hero, settings, zone)
        tvNextUp.visibility = View.VISIBLE
    }

    private fun bindTimelineRow(
        row: TimelineRow,
        bar: ProgressBar,
        status: TextView,
        counts: TextView,
        progress: Progress?,
    ) {
        status.text = row.detail
        status.setTextColor(
            resources.getColor(
                when (row.status) {
                    TimelineStatus.DONE -> R.color.success
                    TimelineStatus.IN_PROGRESS -> R.color.primary
                    TimelineStatus.WAITING_DEFERRED -> R.color.accent
                    TimelineStatus.UPCOMING,
                    TimelineStatus.NOT_STARTED -> R.color.text_secondary
                },
                null,
            )
        )
        if (progress == null) {
            bar.progress = 0
            counts.text = getString(R.string.not_started_yet)
        } else {
            bar.progress = progress.percent
            counts.text =
                "Taken ${progress.taken} • Pending ${progress.pending} • " +
                    "Missed ${progress.missed} • Upcoming ${progress.upcoming}"
        }
    }

    private fun bindStartButton(
        btn: Button,
        done: Boolean,
        active: Boolean,
        doneRes: Int,
        activeRes: Int,
        startRes: Int,
    ) {
        when {
            done -> {
                btn.isEnabled = false
                btn.text = getString(doneRes)
            }
            active -> {
                btn.isEnabled = false
                btn.text = getString(activeRes)
            }
            else -> {
                btn.isEnabled = true
                btn.text = getString(startRes)
            }
        }
    }

    /** Hero YES: confirm the dose the hero shows (mirrors DoseFragment.onYes). */
    private fun onHeroYes() {
        val act = activity ?: return
        val id = heroDoseId ?: return
        // v2.1: answering in-app silences the alarm ring.
        com.moon.newmedicine.alarm.ReminderAlarmService.stop(act, leaveReminder = false)
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val now = System.currentTimeMillis()
                val result = repo.confirmDose(id, now)
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    when {
                        result.notDueYet ->
                            // Defensive: buttons are disabled when not due, but
                            // a stale tap must never confirm early.
                            Toast.makeText(act, R.string.not_due_yet, Toast.LENGTH_SHORT).show()
                        result.unchanged ->
                            Toast.makeText(act, R.string.already_recorded, Toast.LENGTH_SHORT).show()
                        else -> {
                            val msg = result.nextDueAt?.let {
                                getString(R.string.hero_taken_next, TimeFormat.formatTime(it))
                            } ?: getString(R.string.hero_taken_done)
                            Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                    refresh()
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Hero NO: decline the dose the hero shows (mirrors DoseFragment.onNo). */
    private fun onHeroNo() {
        val act = activity ?: return
        val id = heroDoseId ?: return
        // v2.1: answering in-app silences the alarm ring.
        com.moon.newmedicine.alarm.ReminderAlarmService.stop(act, leaveReminder = false)
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val now = System.currentTimeMillis()
                val result = repo.declineDose(id, now)
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (result.notDueYet) {
                        // Defensive: buttons are disabled when not due.
                        Toast.makeText(act, R.string.not_due_yet, Toast.LENGTH_SHORT).show()
                    }
                    refresh()
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Hero Skip: skip the dose the hero shows and move the chain to the
     * next drop. SKIP CONTRACT (implemented by the engine track):
     * repo.skipDose(doseId, now) returns SkipResult(dose, nextDueAt,
     * unchanged, notDueYet).
     */
    private fun onHeroSkip() {
        val act = activity ?: return
        val id = heroDoseId ?: return
        // v2.1: answering in-app silences the alarm ring.
        com.moon.newmedicine.alarm.ReminderAlarmService.stop(act, leaveReminder = false)
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val now = System.currentTimeMillis()
                val result = repo.skipDose(id, now)
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    when {
                        result.notDueYet ->
                            Toast.makeText(act, R.string.not_due_yet, Toast.LENGTH_SHORT).show()
                        result.unchanged ->
                            Toast.makeText(act, R.string.already_recorded, Toast.LENGTH_SHORT).show()
                        else -> {
                            val msg = result.nextDueAt?.let {
                                getString(R.string.skip_toast_skipped, TimeFormat.formatTime(it))
                            } ?: getString(R.string.skip_toast_complete)
                            Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                    refresh()
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Hero Snooze (v2.3): opens the duration chooser for the hero dose —
     * the same chooser the notification's Snooze action opens. The dose
     * stays PENDING; picking a duration applies repo.snoozeDose, which
     * stops the ring and arms the snooze alarm.
     */
    private fun onHeroSnooze() {
        val act = activity ?: return
        val id = heroDoseId ?: return
        val chooser = Intent().apply {
            setClassName(act.packageName, "com.moon.newmedicine.ui.SnoozeActivity")
            putExtra(com.moon.newmedicine.alarm.AlarmScheduler.EXTRA_DOSE_ID, id)
        }
        startActivity(chooser)
    }

    private fun onStartMorning() {
        val act = activity ?: return
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val now = System.currentTimeMillis()
                val result = repo.startMorningSession(now)
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    when (result) {
                        is StartResult.Started -> {
                            Toast.makeText(act, R.string.morning_started, Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                        is StartResult.AlreadyActive -> {
                            // v1.13: may be a DIFFERENT session blocking the
                            // start (single-active-session invariant) — name
                            // it so the user knows what to finish first.
                            val label = sessionLabel(result.session.type)
                            Toast.makeText(
                                act,
                                getString(R.string.session_blocked_by_active, label),
                                Toast.LENGTH_LONG,
                            ).show()
                            refresh()
                        }
                        is StartResult.Deferred -> {
                            // The manual path never defers (it returns
                            // AlreadyActive instead); defensive exhaustiveness.
                            Toast.makeText(
                                act, R.string.session_will_start_after, Toast.LENGTH_SHORT
                            ).show()
                            refresh()
                        }
                        is StartResult.Conflict -> {
                            AlertDialog.Builder(act)
                                .setTitle(R.string.cannot_start_session)
                                .setMessage(result.reason)
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * v1.13: manual afternoon/evening start. Goes through the same
     * single-active-session guard as every other start — if another session
     * is ACTIVE the repo refuses with AlreadyActive (no overlap ever) and
     * the user is told which session to finish first.
     */
    private fun onStartManual(type: SessionType) {
        val act = activity ?: return
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val now = System.currentTimeMillis()
                val result = repo.manualStartSession(type, now)
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    when (result) {
                        is StartResult.Started -> {
                            Toast.makeText(
                                act, "${sessionLabel(type)} session started", Toast.LENGTH_SHORT
                            ).show()
                            refresh()
                        }
                        is StartResult.AlreadyActive -> {
                            val label = sessionLabel(result.session.type)
                            Toast.makeText(
                                act,
                                getString(R.string.session_blocked_by_active, label),
                                Toast.LENGTH_LONG,
                            ).show()
                            refresh()
                        }
                        is StartResult.Conflict -> {
                            AlertDialog.Builder(act)
                                .setTitle(R.string.cannot_start_session)
                                .setMessage(result.reason)
                                .setPositiveButton(R.string.ok, null)
                                .show()
                        }
                        is StartResult.Deferred -> {
                            // The manual path never defers (it returns
                            // AlreadyActive instead); defensive exhaustiveness.
                            Toast.makeText(
                                act, R.string.session_will_start_after, Toast.LENGTH_SHORT
                            ).show()
                            refresh()
                        }
                    }
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun onStopSession() {
        val act = activity ?: return
        val session = activeSession ?: return
        AlertDialog.Builder(act)
            .setTitle(R.string.confirm_stop_session_title)
            .setMessage(R.string.confirm_stop_session)
            .setPositiveButton(R.string.stop) { _, _ -> doStopSession(session.id) }
            .setNegativeButton(R.string.keep, null)
            .show()
    }

    private fun doStopSession(sessionId: Long) {
        val act = activity ?: return
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val now = System.currentTimeMillis()
                val result = repo.cancelSession(sessionId, now)
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    when (result) {
                        is DropsRepository.CancelResult.Cancelled ->
                            Toast.makeText(act, R.string.session_stopped, Toast.LENGTH_SHORT).show()
                        is DropsRepository.CancelResult.NothingToDo ->
                            Toast.makeText(act, R.string.session_already_stopped, Toast.LENGTH_SHORT).show()
                    }
                    refresh()
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun sessionLabel(type: SessionType): String = when (type) {
        SessionType.MORNING -> getString(R.string.session_morning)
        SessionType.AFTERNOON -> getString(R.string.session_afternoon)
        SessionType.EVENING -> getString(R.string.session_evening)
    }
}
