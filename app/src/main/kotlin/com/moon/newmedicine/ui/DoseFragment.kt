package com.moon.newmedicine.ui

import android.app.Fragment
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.R
import com.moon.newmedicine.core.ConfirmResult
import com.moon.newmedicine.core.Dose
import com.moon.newmedicine.core.DoseKind
import com.moon.newmedicine.core.DoseSettings
import com.moon.newmedicine.core.DoseStatus
import com.moon.newmedicine.core.ScheduleEngine
import com.moon.newmedicine.core.SessionType
import com.moon.newmedicine.util.TimeFormat

/** Single-dose screen: YES / NO with live countdowns and retry info. */
class DoseFragment : Fragment() {

    companion object {
        private const val ARG_DOSE_ID = "doseId"

        /** doseId == -1 selects the next due dose automatically. */
        fun forDose(doseId: Long): DoseFragment = DoseFragment().apply {
            arguments = Bundle().apply { putLong(ARG_DOSE_ID, doseId) }
        }
    }

    private var doseIdArg: Long = -1L
    private var dose: Dose? = null
    private var settings: DoseSettings = DoseSettings()
    private var sessionLabel: String = ""
    private var sessionTotal: Int = 0
    private var sessionType: SessionType? = null

    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    private lateinit var tvDropNumber: TextView
    private lateinit var tvSessionLabel: TextView
    private lateinit var tvSpecialBadge: TextView
    private lateinit var tvDueTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvOverdue: TextView
    private lateinit var tvRetryInfo: TextView
    private lateinit var tvNextReminder: TextView
    private lateinit var tvNotDueHint: TextView
    private lateinit var btnYes: Button
    private lateinit var btnNo: Button
    private lateinit var btnSkip: Button
    private lateinit var btnSnooze: Button
    private lateinit var tvAfterYes: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        doseIdArg = arguments?.getLong(ARG_DOSE_ID, -1L) ?: -1L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.fragment_dose, container, false)
        tvDropNumber = v.findViewById(R.id.tv_drop_number)
        tvSessionLabel = v.findViewById(R.id.tv_session_label)
        tvSpecialBadge = v.findViewById(R.id.tv_special_badge)
        tvDueTime = v.findViewById(R.id.tv_due_time)
        tvStatus = v.findViewById(R.id.tv_status)
        tvOverdue = v.findViewById(R.id.tv_overdue)
        tvRetryInfo = v.findViewById(R.id.tv_retry_info)
        tvNextReminder = v.findViewById(R.id.tv_next_reminder)
        tvNotDueHint = v.findViewById(R.id.tv_not_due_hint)
        btnYes = v.findViewById(R.id.btn_yes)
        btnNo = v.findViewById(R.id.btn_no)
        btnSkip = v.findViewById(R.id.btn_skip)
        btnSnooze = v.findViewById(R.id.btn_snooze)
        tvAfterYes = v.findViewById(R.id.tv_after_yes)

        btnYes.setOnClickListener { onYes() }
        btnNo.setOnClickListener { onNo() }
        btnSkip.setOnClickListener { onSkip() }
        btnSnooze.setOnClickListener { onSnooze() }
        return v
    }

    override fun onResume() {
        super.onResume()
        load()
        ticker = object : Runnable {
            override fun run() {
                val d = dose
                if (d != null && isAdded) bindLive(d, System.currentTimeMillis())
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

    private fun load() {
        val act = activity ?: return
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val now = System.currentTimeMillis()
                val s = repo.getSettings()
                val dayKey = repo.todayKey(now)
                val d = if (doseIdArg == -1L) repo.nextDueDose(dayKey, now) else repo.getDose(doseIdArg)
                val sess = d?.let { repo.getSession(it.sessionId) }
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (d == null) {
                        showNotFound()
                        return@runOnUiThread
                    }
                    dose = d
                    settings = s
                    sessionLabel = sess?.let { label(it.type) + " session" } ?: ""
                    sessionTotal = sess?.let { ScheduleEngine.dropCountFor(it.type, s) } ?: 0
                    sessionType = sess?.type
                    bindStatic(d)
                    bindLive(d, now)
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showNotFound() {
        tvDropNumber.text = "—"
        tvSessionLabel.text = ""
        tvSpecialBadge.visibility = View.GONE
        tvDueTime.text = ""
        tvStatus.text = getString(R.string.dose_not_found)
        tvOverdue.visibility = View.GONE
        tvRetryInfo.text = ""
        tvNextReminder.text = ""
        tvNotDueHint.visibility = View.GONE
        btnYes.isEnabled = false
        btnNo.isEnabled = false
        btnSkip.visibility = View.GONE
        btnSkip.isEnabled = false
        btnSnooze.visibility = View.GONE
        btnSnooze.isEnabled = false
        tvAfterYes.visibility = View.GONE
    }

    private fun bindStatic(d: Dose) {
        // v2.2: show the DISPLAY number (afternoon/evening sequence 6 → 7).
        val displayNumber = sessionType?.let { ScheduleEngine.displayNumberFor(it, d.dropNumber) }
            ?: d.dropNumber
        tvDropNumber.text = displayNumber.toString()
        tvSessionLabel.text = if (sessionTotal > 0) "$sessionLabel — drop $displayNumber of $sessionTotal"
            else sessionLabel
        tvSpecialBadge.visibility = if (d.kind == DoseKind.SPECIAL) View.VISIBLE else View.GONE
        tvDueTime.text = "Due at ${TimeFormat.formatTime(d.dueAt)}"
        tvAfterYes.visibility = View.GONE
    }

    private fun bindLive(d: Dose, now: Long) {
        tvStatus.text = "Status: ${d.status.name.lowercase().replaceFirstChar(Char::titlecase)}"
        tvOverdue.visibility =
            if (ScheduleEngine.isOverdue(d, now, settings)) View.VISIBLE else View.GONE

        tvRetryInfo.text =
            if (d.status == DoseStatus.PENDING && d.retryCount > 0 && d.nextRetryAt != null) {
                "Not yet — next reminder in " +
                    TimeFormat.formatCountdown(maxOf(0L, d.nextRetryAt - now)) +
                    " (retry ${d.retryCount})"
            } else ""

        val reminderAt = d.nextReminderAt()
        tvNextReminder.text = if (reminderAt != null) {
            "Next reminder in " + TimeFormat.formatCountdown(maxOf(0L, reminderAt - now))
        } else {
            "No further reminders"
        }

        val pending = d.status == DoseStatus.PENDING
        // Accidental-tap guard: YES/NO stay disabled until the drop is due
        // (60s grace). The 1s ticker rebinds, so they enable automatically
        // at due time.
        val confirmable = pending && ScheduleEngine.canConfirm(d.dueAt, now)
        btnYes.isEnabled = confirmable
        btnNo.isEnabled = confirmable
        // Skip is shown only for the pending dose, and — like YES/NO —
        // enabled only once the drop is due (accidental-tap guard).
        btnSkip.visibility = if (pending) View.VISIBLE else View.GONE
        btnSkip.isEnabled = confirmable
        // v2.3: Snooze follows Skip's rule exactly (visible while pending,
        // enabled once due) — it opens the duration chooser.
        btnSnooze.visibility = if (pending) View.VISIBLE else View.GONE
        btnSnooze.isEnabled = confirmable
        if (pending && !confirmable) {
            tvNotDueHint.visibility = View.VISIBLE
            tvNotDueHint.text = getString(
                R.string.not_due_hint,
                TimeFormat.formatCountdown(maxOf(0L, d.dueAt - now)),
            )
        } else {
            tvNotDueHint.visibility = View.GONE
        }
    }

    private fun onYes() {
        val act = activity ?: return
        val id = dose?.id ?: return
        // v2.1: answering in-app silences the alarm ring.
        com.moon.newmedicine.alarm.ReminderAlarmService.stop(act, leaveReminder = false)
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val now = System.currentTimeMillis()
                val result = repo.confirmDose(id, now)
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    if (result.notDueYet) {
                        // Defensive: buttons are disabled when not due, but a
                        // stale tap must never confirm early.
                        Toast.makeText(act, R.string.not_due_yet, Toast.LENGTH_SHORT).show()
                    } else if (result.unchanged) {
                        Toast.makeText(act, R.string.already_recorded, Toast.LENGTH_SHORT).show()
                    } else {
                        val recorded = "Recorded at ${TimeFormat.formatTime(now)} ✓"
                        val nextLine = result.nextDueAt?.let { "\nNext drop at ${TimeFormat.formatTime(it)}" }
                            ?: "\nSession complete ✓"
                        tvAfterYes.text = recorded + nextLine
                        tvAfterYes.visibility = View.VISIBLE
                    }
                    dose = result.dose
                    bindLive(result.dose, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun onNo() {
        val act = activity ?: return
        val id = dose?.id ?: return
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
                    } else {
                        dose = result.dose
                    }
                    bindLive(dose ?: return@runOnUiThread, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Skip: mark this drop skipped and move the chain to the next drop.
     * SKIP CONTRACT (implemented by the engine track):
     * repo.skipDose(doseId, now) returns SkipResult(dose, nextDueAt,
     * unchanged, notDueYet).
     */
    private fun onSkip() {
        val act = activity ?: return
        val id = dose?.id ?: return
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
                            // Defensive: the button is disabled when not due.
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
                    dose = result.dose
                    bindLive(result.dose, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Snooze (v2.3): opens the duration chooser for this dose — the same
     * chooser the notification's Snooze action opens. The dose stays
     * PENDING; picking a duration applies repo.snoozeDose.
     */
    private fun onSnooze() {
        val act = activity ?: return
        val id = dose?.id ?: return
        val chooser = Intent().apply {
            setClassName(act.packageName, "com.moon.newmedicine.ui.SnoozeActivity")
            putExtra(com.moon.newmedicine.alarm.AlarmScheduler.EXTRA_DOSE_ID, id)
        }
        startActivity(chooser)
    }

    private fun label(type: SessionType): String = when (type) {
        SessionType.MORNING -> "Morning"
        SessionType.AFTERNOON -> "Afternoon"
        SessionType.EVENING -> "Evening"
    }
}
