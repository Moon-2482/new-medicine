package com.moon.newmedicine.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.R
import com.moon.newmedicine.alarm.AlarmScheduler
import com.moon.newmedicine.alarm.ReminderAlarmService
import com.moon.newmedicine.core.DoseStatus
import com.moon.newmedicine.core.ScheduleEngine
import com.moon.newmedicine.core.SessionType
import com.moon.newmedicine.util.TimeFormat

/**
 * v2.3: the snooze duration chooser — a small dialog-themed activity with
 * four big preset buttons (10 / 20 / 30 / 60 min). Opened from the dose
 * reminder's "Snooze" action and from the in-app Snooze buttons (hero
 * card, Dose screen).
 *
 * Picking a duration applies [com.moon.newmedicine.repo.DropsRepository.snoozeDose]:
 * the ring stops, the dose stays PENDING, the tray reminder is replaced by
 * the silent "Snoozed" note, and the snooze alarm fires the full ringing
 * reminder at expiry. Dismissing without a pick changes nothing — the
 * dose keeps its normal reminder/nag behavior.
 */
class SnoozeActivity : Activity() {

    private var doseId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snooze)
        doseId = intent.getLongExtra(AlarmScheduler.EXTRA_DOSE_ID, -1L)
        if (doseId < 0) {
            finish()
            return
        }
        // The user is interacting with this dose: silence the ring, but
        // leave the silent reminder copy in the tray in case the chooser
        // is dismissed without a pick. (If a duration IS picked, the
        // "Snoozed" note replaces that copy — same notification id.)
        ReminderAlarmService.stop(this, leaveReminder = true)

        val subtitle = findViewById<TextView>(R.id.tv_snooze_dose)
        bindDuration(R.id.btn_snooze_10, ScheduleEngine.SNOOZE_DURATIONS_MILLIS[0])
        bindDuration(R.id.btn_snooze_20, ScheduleEngine.SNOOZE_DURATIONS_MILLIS[1])
        bindDuration(R.id.btn_snooze_30, ScheduleEngine.SNOOZE_DURATIONS_MILLIS[2])
        bindDuration(R.id.btn_snooze_60, ScheduleEngine.SNOOZE_DURATIONS_MILLIS[3])
        findViewById<Button>(R.id.btn_snooze_cancel).setOnClickListener { finish() }

        // Label the dose being snoozed (display number, v2.2 rules). The
        // dose may have been resolved in the meantime — then there is
        // nothing to snooze and the chooser simply closes.
        val repo = (application as DropsApp).repository
        val id = doseId
        Thread({
            try {
                val dose = repo.getDose(id)
                val session = dose?.let { repo.getSession(it.sessionId) }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (dose == null || dose.status != DoseStatus.PENDING) {
                        finish()
                        return@runOnUiThread
                    }
                    val number = session?.let {
                        ScheduleEngine.displayNumberFor(it.type, dose.dropNumber)
                    } ?: dose.dropNumber
                    val label = session?.let { sessionLabel(it.type) + " session" } ?: ""
                    subtitle.text = "Drop $number — $label"
                    subtitle.visibility = View.VISIBLE
                }
            } catch (t: Throwable) {
                // Label is cosmetic; the buttons work without it.
            }
        }, "drops-snooze-label").apply { isDaemon = true }.start()
    }

    private fun bindDuration(buttonId: Int, durationMillis: Long) {
        findViewById<Button>(buttonId).setOnClickListener { applySnooze(durationMillis) }
    }

    private fun applySnooze(durationMillis: Long) {
        val repo = (application as DropsApp).repository
        val id = doseId
        Thread({
            try {
                val now = System.currentTimeMillis()
                val result = repo.snoozeDose(id, durationMillis, now)
                runOnUiThread {
                    when {
                        result.notDueYet ->
                            Toast.makeText(this, R.string.not_due_yet, Toast.LENGTH_SHORT).show()
                        result.unchanged ->
                            Toast.makeText(this, R.string.already_recorded, Toast.LENGTH_SHORT).show()
                        else ->
                            Toast.makeText(
                                this,
                                getString(
                                    R.string.snoozed_toast,
                                    TimeFormat.formatTime(result.snoozedUntil ?: now),
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                    }
                    finish()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (!isFinishing) {
                        Toast.makeText(this, "Error: ${t.message}", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }, "drops-snooze-apply").apply { isDaemon = true }.start()
    }

    private fun sessionLabel(type: SessionType): String = when (type) {
        SessionType.MORNING -> "Morning"
        SessionType.AFTERNOON -> "Afternoon"
        SessionType.EVENING -> "Evening"
    }
}
