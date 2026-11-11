package com.moon.newmedicine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.core.SessionType
import com.moon.newmedicine.repo.DropsRepository

/**
 * Fires when an auto session-start alarm goes off (afternoon/evening).
 * Starts the session via [DropsRepository.autoStartSession]; a plan that
 * cannot fit the cutoff surfaces as a conflict notification.
 */
class SessionStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val typeName = intent.getStringExtra(AlarmScheduler.EXTRA_SESSION_TYPE) ?: return
        val type = try {
            SessionType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            return
        }
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext as DropsApp
                val repo = app.repository
                val now = System.currentTimeMillis()
                // Safety: the alarm may fire after midnight; roll the day
                // over first so the session attaches to the right date.
                repo.ensureDayRollover(now)
                // v2.3: if this alarm is the DEFERRED start's wake-up but
                // it somehow fired before the planned ready time (clock
                // change, stale slot), never start early — re-arm for the
                // ready time and wait. (The normal deferred fire lands
                // exactly at readyAt and passes this check.)
                val readyAt = repo.deferredReadyAt(type, repo.todayKey(now))
                if (readyAt != null && now < readyAt) {
                    repo.alarms.scheduleSessionStart(type, repo.todayKey(now), readyAt)
                    return@Thread
                }
                when (val result = repo.autoStartSession(type, now)) {
                    is DropsRepository.StartResult.Conflict ->
                        repo.notifications.showConflictNotification(result.reason)
                    is DropsRepository.StartResult.Deferred ->
                        // v1.13: the repo already recorded the deferred flag,
                        // cancelled this start alarm and posted the deferred
                        // notification. Nothing more to do here.
                        Unit
                    is DropsRepository.StartResult.Started,
                    is DropsRepository.StartResult.AlreadyActive -> {
                        // The first dose's alarm was scheduled by the repo.
                    }
                }
            } catch (t: Throwable) {
                // Swallowed: the next reconcile retries from the database.
            } finally {
                pending.finish()
            }
        }.start()
    }
}
