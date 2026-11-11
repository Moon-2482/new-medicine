package com.moon.newmedicine.ui

import android.app.Fragment
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.R
import com.moon.newmedicine.core.Dose
import com.moon.newmedicine.core.DoseKind
import com.moon.newmedicine.core.DoseSettings
import com.moon.newmedicine.core.DoseSession
import com.moon.newmedicine.core.DoseStatus
import com.moon.newmedicine.core.ScheduleEngine
import com.moon.newmedicine.core.SessionStatus
import com.moon.newmedicine.core.SessionType
import com.moon.newmedicine.util.TimeFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Scrollable 60-day history, built programmatically. */
class HistoryFragment : Fragment() {

    private lateinit var container: LinearLayout
    private val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.fragment_history, container, false)
        this.container = v.findViewById(R.id.history_container)
        return v
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private data class DayData(
        val dayKey: String,
        val sessions: List<DoseSession>,
        val doses: List<Dose>,
    )

    private fun load() {
        val act = activity ?: return
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val now = System.currentTimeMillis()
                val settings = repo.getSettings()
                val days = repo.getHistoryDays(60)
                val all = days.map { dk ->
                    DayData(dk, repo.getTodaySessions(dk), repo.getDosesForDay(dk))
                }
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    container.removeAllViews()
                    if (all.isEmpty()) {
                        container.addView(makeText(getString(R.string.no_history), 14f, R.color.text_secondary))
                        return@runOnUiThread
                    }
                    for (dd in all) addDay(act, dd, settings, now)
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) {
                        container.removeAllViews()
                        container.addView(makeText("Error: ${e.message}", 14f, R.color.danger))
                    }
                }
            }
        }.start()
    }

    private fun addDay(act: android.app.Activity, dd: DayData, settings: DoseSettings, now: Long) {
        val date = try {
            LocalDate.parse(dd.dayKey).format(dateFmt)
        } catch (e: Exception) {
            dd.dayKey
        }
        // Planned totals come from the session types (strict chaining: the
        // row count grows through the day, so it cannot be the denominator).
        // CONFLICT rows are failed attempts with no plan behind them.
        val plannedFor: (DoseSession) -> Int = { ScheduleEngine.dropCountFor(it.type, settings) }
        val dayPlanned = dd.sessions.filter { it.status != SessionStatus.CONFLICT }.sumOf(plannedFor)
        val percent = ScheduleEngine.dayCompletionPercent(dd.doses, dayPlanned)

        // Day header card.
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 16 }
        }
        header.addView(makeText(date, 16f, R.color.text_primary).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTypeface(null, Typeface.BOLD)
        })
        header.addView(makeText("$percent%", 16f, R.color.primary).apply {
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(header)

        if (dd.sessions.isEmpty()) {
            container.addView(makeText("No sessions", 13f, R.color.text_secondary).apply {
                setPadding(16, 8, 16, 8)
            })
            return
        }

        for (session in dd.sessions.sortedBy { it.type.ordinal }) {
            val sd = dd.doses.filter { it.sessionId == session.id }
            val p = ScheduleEngine.sessionProgress(sd, now, plannedFor(session))
            val mark = if (p.total > 0 && p.taken == p.total) " ✓" else ""
            container.addView(makeText(
                "${label(session.type)} — ${p.taken}/${p.total}$mark",
                14f, R.color.text_primary,
            ).apply {
                setTypeface(null, Typeface.BOLD)
                setPadding(16, 16, 16, 4)
            })
            // Only resolved drops are listed: future PENDING drops stay
            // hidden until they have a real outcome. The progress line
            // above is still computed over the full plan.
            val resolved = ScheduleEngine.resolvedDoses(sd).sortedBy { it.dropNumber }
            if (resolved.isEmpty()) {
                container.addView(makeText(
                    getString(R.string.no_drops_recorded), 13f, R.color.text_secondary,
                ).apply {
                    setPadding(32, 4, 16, 4)
                })
            }
            for (d in resolved) {
                container.addView(makeText(doseLine(d, session.type, settings, now), 13f, R.color.text_secondary).apply {
                    setPadding(32, 4, 16, 4)
                })
            }
        }
    }

    private fun doseLine(d: Dose, type: SessionType, settings: DoseSettings, now: Long): String {
        val star = if (d.kind == DoseKind.SPECIAL) "★ " else ""
        val time = TimeFormat.formatTime(d.dueAt)
        // v2.2: DISPLAY number — an afternoon/evening last drop reads
        // "Drop 7" here too, including rows recorded before the update
        // (stored sequence numbers are untouched; mapping is render-time).
        val num = ScheduleEngine.displayNumberFor(type, d.dropNumber)
        return when (d.status) {
            DoseStatus.CONFIRMED -> {
                val taken = "taken ${TimeFormat.formatTime(d.confirmedAt ?: d.dueAt)}"
                val late =
                    if (d.confirmedAt != null && d.confirmedAt > d.dueAt + settings.overdueGraceMillis) " (late)"
                    else ""
                "${star}Drop $num — $time ✓ $taken$late"
            }
            DoseStatus.MISSED -> "${star}Drop $num — $time ✗ missed"
            DoseStatus.CANCELLED -> "${star}Drop $num — $time ○ cancelled"
            DoseStatus.PENDING -> {
                val od = if (ScheduleEngine.isOverdue(d, now, settings)) " (overdue)" else ""
                "${star}Drop $num — $time ○ pending$od"
            }
        }
    }

    private fun makeText(text: String, sp: Float, colorRes: Int): TextView =
        TextView(activity).apply {
            this.text = text
            textSize = sp
            setTextColor(resources.getColor(colorRes, null))
        }

    private fun label(type: SessionType): String = when (type) {
        SessionType.MORNING -> "Morning"
        SessionType.AFTERNOON -> "Afternoon"
        SessionType.EVENING -> "Evening"
    }
}
