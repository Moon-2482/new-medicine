package com.moon.newmedicine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.R
import com.moon.newmedicine.core.ConfirmResult
import com.moon.newmedicine.core.DeclineResult

/**
 * Handles the YES ("Taken") / NO ("Not Yet") / Skip actions on a dose
 * notification.
 *
 * YES is idempotent (confirmDose returns unchanged=true on a double tap);
 * NO arms a retry for the same dose; Skip marks the drop MISSED and moves
 * the chain to the next drop (repo.skipDose). Taps before the dose is due
 * are refused (notDueYet) with a short toast — notifications fire at due
 * time so this is defensive. Stale actions on a dose that is no longer
 * pending just clear the notification.
 */
class DoseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val doseId = intent.getLongExtra(AlarmScheduler.EXTRA_DOSE_ID, -1L)
        if (doseId < 0) return
        if (action == AlarmScheduler.ACTION_SNOOZE) {
            // v2.3: Snooze is not an answer — it opens the duration
            // chooser. The ring stops (the user is interacting) but a
            // silent copy of the reminder stays in the tray: if the
            // chooser is dismissed without a pick, the dose is exactly as
            // reminded as before. Picking a duration replaces the tray
            // entry with the "Snoozed" note (repo.snoozeDose).
            ReminderAlarmService.stop(context, leaveReminder = true)
            val chooser = Intent().apply {
                setClassName(context.packageName, "com.moon.newmedicine.ui.SnoozeActivity")
                putExtra(AlarmScheduler.EXTRA_DOSE_ID, doseId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            try {
                context.startActivity(chooser)
            } catch (t: Throwable) {
                // Chooser could not open: the reminder stays in the tray
                // (leaveReminder above) — nothing is lost.
            }
            return
        }
        // v2.1: any answer (YES / NO / Skip) stops the alarm ring at once.
        ReminderAlarmService.stop(context, leaveReminder = false)
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext as DropsApp
                val repo = app.repository
                val now = System.currentTimeMillis()
                when (action) {
                    AlarmScheduler.ACTION_YES -> {
                        try {
                            val res = repo.confirmDose(doseId, now)
                            // A6: the reminder cleanup is now owned by
                            // repo.confirmDose — the call below was redundant.
                            if (res.notDueYet) {
                                showNotDueYet(context)
                            } else if (!res.unchanged) {
                                val session = repo.getSession(res.dose.sessionId)
                                repo.notifications.showTaken(res.dose, session)
                            }
                        } catch (e: IllegalArgumentException) {
                            repo.notifications.cancelDoseNotification(doseId)
                        }
                    }
                    AlarmScheduler.ACTION_NO -> {
                        try {
                            val res = repo.declineDose(doseId, now)
                            // A6: updateAfterNo is now posted by
                            // repo.declineDose itself (in-app NO included) —
                            // the call below was redundant.
                            if (res.notDueYet) {
                                showNotDueYet(context)
                            }
                        } catch (e: IllegalArgumentException) {
                            repo.notifications.cancelDoseNotification(doseId)
                        }
                    }
                    AlarmScheduler.ACTION_SKIP -> {
                        try {
                            val res = repo.skipDose(doseId, now)
                            // repo.skipDose already dropped the alarm slot and
                            // the reminder notification post-commit; surface
                            // the confirmation (or the refusal toast).
                            when {
                                res.notDueYet -> showNotDueYet(context)
                                res.unchanged -> { /* stale: repo cleared the notification */ }
                                else -> {
                                    val session = repo.getSession(res.dose.sessionId)
                                    if (session != null) {
                                        repo.notifications.showSkipped(res.dose, session, res.nextDueAt)
                                    }
                                }
                            }
                        } catch (e: IllegalArgumentException) {
                            repo.notifications.cancelDoseNotification(doseId)
                        }
                    }
                }
            } catch (t: Throwable) {
                // Swallowed: a failed action must never crash the receiver.
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun showNotDueYet(context: Context) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.not_due_yet, Toast.LENGTH_SHORT).show()
        }
    }
}
