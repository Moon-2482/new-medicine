package com.moon.newmedicine.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.core.Dose
import com.moon.newmedicine.core.DoseSession
import com.moon.newmedicine.core.DoseStatus
import com.moon.newmedicine.core.FireDecision
import com.moon.newmedicine.core.ScheduleEngine

/**
 * Fires when a dose reminder alarm goes off. Shows the reminder notification
 * (with YES/NO actions) unless the dose is no longer pending — a stale alarm
 * for a confirmed/missed dose only clears its notification.
 *
 * The branch decision is the pure [ScheduleEngine.doseFireDecision]
 * (unit-tested); this class only executes it. The dose's own alarm
 * slot, the v2.4 snooze-watchdog slot, and the v2.10 call-deferred
 * slot ([AlarmScheduler.ACTION_CALL_DEFERRED]) all deliver here,
 * indistinguishably — the deferred fire re-checks the call state in
 * the RING branch itself.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val doseId = intent.getLongExtra(AlarmScheduler.EXTRA_DOSE_ID, -1L)
        if (doseId < 0) return
        val pending = goAsync()
        Thread {
            // Loaded state kept outside the try so the catch can still
            // surface a visible reminder when the fire path itself fails
            // (v2.4: fail loud, never silent — see the catch below).
            var dose: Dose? = null
            var session: DoseSession? = null
            try {
                val app = context.applicationContext as DropsApp
                val repo = app.repository
                dose = repo.getDose(doseId)
                if (dose == null) return@Thread
                val now = System.currentTimeMillis()
                val snoozeUntil = repo.getSnoozeUntil(doseId)
                when (
                    ScheduleEngine.doseFireDecision(
                        dose.status, snoozeUntil, now,
                        alreadyRingingForDose = ReminderAlarmService.isRingingFor(doseId),
                    )
                ) {
                    FireDecision.STALE -> {
                        // Stale alarm (e.g. confirmed from the app just as
                        // the alarm fired): make sure no notification
                        // lingers and no ring for this dose keeps sounding.
                        ReminderAlarmService.stopIfRinging(context, doseId)
                        repo.notifications.cancelDoseNotification(doseId)
                        // v2.10: a resolved dose keeps no deferral note.
                        repo.deleteRawSetting(ScheduleEngine.callDeferKey(doseId))
                        return@Thread
                    }
                    FireDecision.SNOOZED -> {
                        // A fire inside a snooze window is a stale slot
                        // (e.g. a NO-retry armed just before the user
                        // snoozed — the snooze owns this dose now). Stay
                        // silent, make sure the snooze alarm itself is the
                        // armed one, and arm no nag. At/after expiry the
                        // decision is RING and the reminder fires normally.
                        // snoozeUntil is non-null here by construction of
                        // the SNOOZED decision.
                        repo.alarms.scheduleDoseAt(dose, snoozeUntil!!)
                        return@Thread
                    }
                    FireDecision.ALREADY_RINGING -> {
                        // This dose's ring is already sounding (e.g. the
                        // snooze watchdog fired right at the primary ring's
                        // 2-minute cap): never restart it — just re-arm
                        // the nag chain below.
                    }
                    FireDecision.RING -> {
                        val settings = repo.getSettings()
                        // v2.10: CALL SUPPRESSION (user order 2026-10-29).
                        // A ringing/vibrating reminder during a call
                        // drowned out his calls — so during a call there
                        // is NO sound, NO vibration, NO notification, on
                        // EVERY path that runs this branch (fresh dose
                        // fire, nag re-fire, snooze expiry, deferred
                        // re-fire). The dose stays PENDING; a one-shot
                        // deferred re-fire is armed at now + the "call ke
                        // baad" gap. Detection is AudioManager.getMode()
                        // — no permission needed — covering cellular,
                        // WhatsApp/VoIP and ringing-but-unanswered.
                        if (isCallActiveNow(context)) {
                            deferForCall(context, doseId)
                            repo.armNag(dose, now)
                            return@Thread
                        }
                        // The call is over (or was never on): any stale
                        // deferral note for this dose is cleared as the
                        // real ring proceeds.
                        repo.deleteRawSetting(ScheduleEngine.callDeferKey(doseId))
                        val overdue = ScheduleEngine.isOverdue(dose, now, settings)
                        session = repo.getSession(dose.sessionId)
                        // v2.1: the reminder RINGS like an alarm (looping
                        // selected sound, up to 2 minutes) via the
                        // foreground service. If the service start is
                        // refused, fall back to today's plain notification
                        // — the reminder itself is never swallowed.
                        // Sound toggle off → plain notification, as before.
                        //
                        // v2.9: a refused start must NOT settle for the
                        // quiet note on the first refusal (live failure
                        // 2026-10-24: a 30-minute snooze expired into a
                        // small quiet notification, no audible ring —
                        // the primary AND the watchdog both run this
                        // identical step, so a systematic refusal at
                        // that moment defeated both chances). Retry the
                        // start ONCE after a short pause — a transient
                        // refusal (cold-process exemption race) often
                        // clears within seconds — and only then fall
                        // back to the plain note, loudly logged. The
                        // retry is bounded ([ScheduleEngine
                        // .ringRetryAllowed]) and lives on the failure
                        // path only: a successful start behaves exactly
                        // as before, and a systematically refused start
                        // still ends with the fallback note, never
                        // silence.
                        var rang = settings.soundEnabled && ReminderAlarmService.ring(context, doseId)
                        var retriesUsed = 0
                        while (!rang && settings.soundEnabled &&
                            ScheduleEngine.ringRetryAllowed(retriesUsed)
                        ) {
                            Log.w(
                                "AlarmReceiver",
                                "ring start refused for dose $doseId; retrying once in " +
                                    "${ScheduleEngine.RING_RETRY_DELAY_MILLIS}ms",
                            )
                            try {
                                Thread.sleep(ScheduleEngine.RING_RETRY_DELAY_MILLIS)
                            } catch (ie: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            }
                            retriesUsed++
                            rang = ReminderAlarmService.ring(context, doseId)
                        }
                        if (!rang) {
                            if (settings.soundEnabled) {
                                Log.e(
                                    "AlarmReceiver",
                                    "ring start refused for dose $doseId after retry; " +
                                        "falling back to plain reminder (snoozeUntil=$snoozeUntil)",
                                )
                            }
                            repo.notifications.showDoseReminder(dose, session, overdue)
                        }
                    }
                }
                // v2.0: arm the missed-drop nag for this still-PENDING dose
                // on the same alarm slot (overwrites, never duplicates).
                // Anchored to dueAt, so the cadence never drifts. Silent
                // no-op when nagging is disabled or inside a snooze window.
                repo.armNag(dose, now)
            } catch (t: Throwable) {
                // v2.4: FAIL LOUD. In v2.3 this catch swallowed everything
                // — combined with the snooze expiry's single delivery
                // chance, one swallowed failure meant a silently missed
                // reminder (live failure 2026-10-22). If the dose was
                // loaded and is still PENDING, at least surface the plain
                // reminder so the fire is never invisible. The reconcile
                // nets (app start / boot) remain the final backstop.
                Log.w("AlarmReceiver", "dose fire path failed for dose $doseId", t)
                try {
                    val d = dose
                    if (d != null && d.status == DoseStatus.PENDING) {
                        if (isCallActiveNow(context)) {
                            // v2.10: a failure DURING a call must not
                            // surface anything — defer exactly like the
                            // RING branch above.
                            deferForCall(context, doseId)
                        } else {
                            val app = context.applicationContext as DropsApp
                            app.repository.notifications.showDoseReminder(d, session, overdue = true)
                        }
                    }
                } catch (t2: Throwable) {
                    // Nothing further is possible from here.
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * v2.10: true when a call is active (or ringing) right now.
     * AudioManager.getMode() needs NO permission, unlike
     * TelephonyManager listeners. Any read failure fails toward NOT
     * in a call (fail toward alerting, as everywhere): a missed
     * suppression costs the user one tap, a wrongly suppressed
     * reminder costs a late dose.
     */
    private fun isCallActiveNow(context: Context): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return false
        ScheduleEngine.isCallActive(am.mode)
    } catch (t: Throwable) {
        false
    }

    /**
     * v2.10: arms the post-call deferred re-fire for [doseId] at now +
     * the "call ke baad" gap and records the KV flag behind the
     * timeline's "📞 Call ke baad" note. Produces NO sound, NO
     * vibration, NO notification — and never touches the dose's
     * status (a call can never mark a dose MISSED).
     */
    private fun deferForCall(context: Context, doseId: Long) {
        try {
            val app = context.applicationContext as DropsApp
            val repo = app.repository
            val now = System.currentTimeMillis()
            val recheckAt = ScheduleEngine.nextCallRecheckAt(
                now, repo.getSettings().callDeferGapMillis
            )
            repo.alarms.scheduleCallDeferred(doseId, recheckAt)
            repo.setRawSetting(ScheduleEngine.callDeferKey(doseId), now.toString())
            Log.i("AlarmReceiver", "call active; dose $doseId reminder deferred to $recheckAt")
        } catch (t: Throwable) {
            Log.e("AlarmReceiver", "call-defer arming failed for dose $doseId", t)
        }
    }
}
