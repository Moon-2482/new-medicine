package com.moon.newmedicine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.moon.newmedicine.DropsApp

/**
 * Fires for every morning wake-detection check: the regular chain tick
 * (every [com.moon.newmedicine.core.DoseSettings.wakeCheckIntervalMinutes]
 * inside the wake window), the one-off "15 min baad" snooze, and the
 * best-effort USER_PRESENT path. Delegates the whole decision to
 * [com.moon.newmedicine.repo.DropsRepository.runWakeCheck], which reads the
 * database as the source of truth — so a stale or duplicated alarm can only
 * ever re-evaluate current state, never double-prompt.
 */
class WakeCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val force = intent.getBooleanExtra(AlarmScheduler.EXTRA_FORCE_WAKE_CHECK, false)
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext as DropsApp
                app.repository.runWakeCheck(System.currentTimeMillis(), force)
            } catch (t: Throwable) {
                // Swallowed: the chain re-arms on the next app open / boot.
            } finally {
                pending.finish()
            }
        }.start()
    }
}
