package com.moon.newmedicine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.core.ScheduleEngine
import com.moon.newmedicine.repo.DropsRepository

/**
 * Handles the actions on the "Drops start karein?" wake notification.
 *
 * - "Start karein": starts the morning session immediately (Drop 1 due now).
 *   Idempotent: [DropsRepository.startMorningSession] returns AlreadyActive
 *   when the session exists, so a double tap — or a tap racing the in-app
 *   Start button — can never create a duplicate session. The wake chain and
 *   the wake notification are cancelled either way.
 * - "15 min baad": schedules a single one-off re-check in 15 minutes. The
 *   regular chain keeps running; the snooze check prompts again if the
 *   morning session still hasn't started (the user explicitly asked to be
 *   reminded).
 */
class WakeActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext as DropsApp
                val repo = app.repository
                val now = System.currentTimeMillis()
                when (action) {
                    AlarmScheduler.ACTION_WAKE_START -> {
                        val dayKey = repo.todayKey(now)
                        if (ScheduleEngine.shouldStartMorningFromWake(repo.hasMorningSessionToday(dayKey))) {
                            repo.startMorningSession(now)
                        }
                        repo.cancelWakeChain(now)
                        repo.notifications.cancelWakePrompt()
                    }
                    AlarmScheduler.ACTION_WAKE_SNOOZE -> {
                        repo.snoozeWakeCheck(now)
                    }
                }
            } catch (t: Throwable) {
                // Swallowed: a failed action must never crash the receiver.
            } finally {
                pending.finish()
            }
        }.start()
    }
}
