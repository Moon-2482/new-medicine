package com.moon.newmedicine.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.R
import com.moon.newmedicine.alarm.AlarmScheduler
import com.moon.newmedicine.alarm.DoseActionReceiver
import com.moon.newmedicine.alarm.WakeActionReceiver
import com.moon.newmedicine.core.Dose
import com.moon.newmedicine.core.DoseKind
import com.moon.newmedicine.core.DoseSession
import com.moon.newmedicine.core.DoseSettings
import com.moon.newmedicine.core.RingBehavior
import com.moon.newmedicine.core.ScheduleEngine
import com.moon.newmedicine.core.SessionType
import com.moon.newmedicine.util.TimeFormat

/**
 * All user-visible notifications for the app. Framework
 * [android.app.Notification.Builder] only (API 26+), no AndroidX.
 *
 * Each dose owns one stable notification id ([Dose.id]), so re-notifying the
 * same dose updates in place and a dose can never stack duplicates.
 */
class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "dose_reminders"
        private const val CHANNEL_NAME = "Dose reminders"

        /**
         * v2.1: channel for the ringing foreground service's notification.
         * Sound = NONE on purpose — [com.moon.newmedicine.alarm.ReminderAlarmService]
         * owns the audio (it loops the user's selected sound on the ALARM
         * stream until stopped); this channel only carries the visible
         * reminder while it rings. Vibration is kept.
         */
        const val CHANNEL_ALARM_ID = "dose_alarm"
        private const val CHANNEL_ALARM_NAME = "Dose alarm (ringing)"

        /**
         * v2.3: channel for the "Snoozed" tray note that replaces a dose
         * reminder while its snooze runs. Fully silent (no sound, no
         * vibration) on purpose — the whole point of snoozing is quiet
         * until the snooze alarm fires.
         */
        const val CHANNEL_SNOOZED_ID = "dose_snoozed"
        private const val CHANNEL_SNOOZED_NAME = "Snoozed reminders"

        /**
         * v2.6: landing channels for plain (non-service) dose-reminder
         * posts when the phone's ringer mode forbids sound. The plain
         * channel above deliberately carries a USAGE_ALARM sound, which
         * plays even on vibrate/silent — right for a ringing reminder,
         * wrong for a fallback post when the phone is on vibrate
         * (user's order: vibrate → vibration only, silent → no alert).
         * Both channels are sound-free; they differ only in vibration,
         * because on Android O+ vibration is a channel attribute, so one
         * channel cannot do both.
         */
        const val CHANNEL_QUIET_VIBRATE_ID = "dose_reminders_vibrate"
        private const val CHANNEL_QUIET_VIBRATE_NAME = "Dose reminders (vibrate only)"
        const val CHANNEL_MUTED_ID = "dose_reminders_muted"
        private const val CHANNEL_MUTED_NAME = "Dose reminders (muted)"
        // v2.0 (A10): moved to 900_001..900_004 — dose notifications use
        // dose.id.toInt(), which can eventually reach the old 9001..9004
        // range and collide with these stable ids.
        private const val CONFLICT_NOTIFICATION_ID = 900_001
        /**
         * Stable id for the morning "Drops start karein?" prompt: every
         * re-prompt replaces the same notification, so at most one wake
         * prompt is ever alive.
         */
        private const val WAKE_NOTIFICATION_ID = 900_002
        /**
         * Stable id for the v1.13 "session deferred" info: re-notifying
         * replaces in place, so at most one deferred notice is ever alive.
         */
        private const val DEFERRED_START_NOTIFICATION_ID = 900_004
        /**
         * Stable id for the manual "Test alarm sound" notification from
         * Settings: re-tapping replaces the previous test in place.
         */
        private const val TEST_NOTIFICATION_ID = 900_003
        private const val KEY_CHANNEL_CONFIG_HASH = "channelConfigHash"

        /** Distinct PendingIntent slot per (dose, action-kind) pair. */
        private fun actionRequestCode(doseId: Long, which: Int): Int =
            com.moon.newmedicine.core.ScheduleEngine.alarmRequestCode(doseId) + which
    }

    private fun manager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun areNotificationsEnabled(): Boolean = manager().areNotificationsEnabled()

    /**
     * URI of the bundled alarm tone shipped inside the APK
     * (`res/raw/alarm_tone.mp3`): always present, so the reminder sound
     * never depends on the device having a usable default alarm tone.
     */
    private fun bundledToneUri(): Uri =
        Uri.parse("android.resource://${context.packageName}/${R.raw.alarm_tone}")

    /**
     * Creates the channel, or deletes and recreates it when the
     * sound/vibration settings changed (the change is tracked in the settings
     * table under [KEY_CHANNEL_CONFIG_HASH]).
     */
    fun ensureChannel(settings: DoseSettings) {
        val hash = com.moon.newmedicine.core.NotificationSound.channelConfigHash(
            settings.soundEnabled,
            settings.vibrationEnabled,
        )
        val repo = (context.applicationContext as? DropsApp)?.repository
        val stored = try {
            repo?.getRawSetting(KEY_CHANNEL_CONFIG_HASH)
        } catch (e: Exception) {
            null
        }
        val existing = manager().getNotificationChannel(CHANNEL_ID)
        if (existing != null && stored == hash) return

        if (existing != null) manager().deleteNotificationChannel(CHANNEL_ID)
        // Alarm-grade sound: the bundled in-APK tone is the first candidate
        // (guaranteed present — the device's default alarm tone proved
        // unreliable on the user's phone), then the system alarm /
        // notification / ringtone defaults. The ALARM stream plays even
        // when the phone is on silent/vibrate.
        val soundUri = if (settings.soundEnabled) {
            val systemUri =
                com.moon.newmedicine.core.NotificationSound.firstAvailable(
                    intArrayOf(
                        RingtoneManager.TYPE_ALARM,
                        RingtoneManager.TYPE_NOTIFICATION,
                        RingtoneManager.TYPE_RINGTONE,
                    ),
                ) { type -> RingtoneManager.getDefaultUri(type) }
            com.moon.newmedicine.core.NotificationSound.firstAvailable(
                listOf(bundledToneUri(), systemUri),
            )
        } else {
            null
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(settings.vibrationEnabled)
            if (settings.vibrationEnabled) {
                vibrationPattern = longArrayOf(0, 600, 400, 600)
            }
            setSound(soundUri, audioAttributes)
        }
        manager().createNotificationChannel(channel)
        try {
            repo?.setRawSetting(KEY_CHANNEL_CONFIG_HASH, hash)
        } catch (e: Exception) {
            // Non-fatal: the channel itself was created.
        }
    }

    /** No-arg form: reads current settings from the repository. */
    fun ensureChannel() {
        // A15: a missing repository used to fail silently — log it so a
        // dead channel is diagnosable. Behavior is otherwise unchanged.
        val repo = (context.applicationContext as? DropsApp)?.repository
        if (repo == null) {
            Log.w("DropsNotif", "ensureChannel(): no repository; channel not (re)created")
            return
        }
        ensureChannel(repo.getSettings())
    }

    /**
     * v2.2: the number shown for [dose] in notification text — mapped
     * through [ScheduleEngine.displayNumberFor] so an afternoon/evening
     * last drop reads "7", never "6" ("6" always means the morning
     * special). When the owning session is unknown, the sequence number
     * is shown unchanged.
     */
    private fun displayNumber(dose: Dose, session: DoseSession?): Int =
        session?.let { ScheduleEngine.displayNumberFor(it.type, dose.dropNumber) }
            ?: dose.dropNumber

    private fun ringerMode(): Int =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode

    /**
     * v2.6: the channel a PLAIN dose-reminder post must land on so the
     * phone's ringer mode is respected (pure decision:
     * [ScheduleEngine.ringBehaviorFor]). NORMAL → [CHANNEL_ID], the
     * sounding channel, exactly as before; VIBRATE → the sound-free
     * vibrating channel (or the muted one when the in-app vibration
     * toggle is off); SILENT → the muted channel. Content, actions and
     * the stable notification id are identical on every channel — only
     * the alert form changes. Non-dose notifications (wake prompt,
     * conflict, deferred) deliberately do NOT go through here; see
     * TEST-REPORT §28 for that decision.
     */
    private fun doseChannelId(): String =
        when (ScheduleEngine.ringBehaviorFor(ringerMode())) {
            RingBehavior.SOUND_AND_VIBRATE -> CHANNEL_ID
            RingBehavior.VIBRATE_ONLY -> {
                val vibrationOn = try {
                    (context.applicationContext as? DropsApp)?.repository
                        ?.getSettings()?.vibrationEnabled
                } catch (e: Exception) {
                    null
                } ?: true
                if (vibrationOn) {
                    ensureQuietVibrateChannel()
                    CHANNEL_QUIET_VIBRATE_ID
                } else {
                    ensureMutedChannel()
                    CHANNEL_MUTED_ID
                }
            }
            RingBehavior.VISUAL_ONLY -> {
                ensureMutedChannel()
                CHANNEL_MUTED_ID
            }
        }

    /** Creates the sound-free, vibrating [CHANNEL_QUIET_VIBRATE_ID] channel (idempotent). */
    private fun ensureQuietVibrateChannel() {
        if (manager().getNotificationChannel(CHANNEL_QUIET_VIBRATE_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_QUIET_VIBRATE_ID,
            CHANNEL_QUIET_VIBRATE_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600)
        }
        manager().createNotificationChannel(channel)
    }

    /** Creates the sound-free, vibration-free [CHANNEL_MUTED_ID] channel (idempotent). */
    private fun ensureMutedChannel() {
        if (manager().getNotificationChannel(CHANNEL_MUTED_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_MUTED_ID,
            CHANNEL_MUTED_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        manager().createNotificationChannel(channel)
    }

    fun showDoseReminder(dose: Dose, session: DoseSession?, overdue: Boolean) {
        ensureChannel()
        manager().notify(dose.id.toInt(), buildDoseReminder(dose, session, overdue, doseChannelId(), null))
    }

    /**
     * v2.1: the dose reminder as posted by the ringing foreground service —
     * identical content and YES / NO / Skip actions as [showDoseReminder],
     * but on the silent [CHANNEL_ALARM_ID] (the service loops the sound
     * itself) and with a delete intent (dismissing it stops the ring).
     * Ongoing, because it backs a foreground service.
     */
    fun buildAlarmReminder(
        dose: Dose,
        session: DoseSession?,
        overdue: Boolean,
        deleteIntent: PendingIntent,
    ): Notification {
        ensureAlarmChannel()
        return buildDoseReminder(dose, session, overdue, CHANNEL_ALARM_ID, deleteIntent)
    }

    /**
     * v2.1: minimal foreground notification the ringing service posts the
     * instant it starts (API 31+ kills a foreground service that does not
     * call startForeground immediately); the full reminder content replaces
     * it in place (same id) a moment later.
     */
    fun buildRingPlaceholder(deleteIntent: PendingIntent): Notification {
        ensureAlarmChannel()
        return Notification.Builder(context, CHANNEL_ALARM_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New Medicine")
            .setContentText("Time for your drop")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(contentPendingIntent(null))
            .setAutoCancel(false)
            .setOngoing(true)
            .setDeleteIntent(deleteIntent)
            .build()
    }

    /**
     * v2.1: the Settings "Test alarm sound" notification as posted by the
     * ringing service — same title/text as [playTestAlarm], on the silent
     * alarm channel (the service loops the selected sound).
     */
    fun buildTestAlarmNotification(deleteIntent: PendingIntent): Notification {
        ensureAlarmChannel()
        val text = "This is the reminder sound. If you hear it, dose reminders will sound the same."
        return Notification.Builder(context, CHANNEL_ALARM_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("\uD83D\uDD14 Test alarm")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent(null))
            .setAutoCancel(false)
            .setOngoing(true)
            .setDeleteIntent(deleteIntent)
            .build()
    }

    /**
     * Creates the silent [CHANNEL_ALARM_ID] channel, recreating it when the
     * vibration toggle changed (channel attributes are immutable once
     * created, same as [ensureChannel]).
     */
    fun ensureAlarmChannel(settings: DoseSettings) {
        val existing = manager().getNotificationChannel(CHANNEL_ALARM_ID)
        if (existing != null && existing.shouldVibrate() == settings.vibrationEnabled) return
        if (existing != null) manager().deleteNotificationChannel(CHANNEL_ALARM_ID)
        val channel = NotificationChannel(
            CHANNEL_ALARM_ID,
            CHANNEL_ALARM_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            // The ringing service owns the audio — the channel stays silent.
            setSound(null, null)
            enableVibration(settings.vibrationEnabled)
            if (settings.vibrationEnabled) {
                vibrationPattern = longArrayOf(0, 600, 400, 600)
            }
        }
        manager().createNotificationChannel(channel)
    }

    /** No-arg form: reads current settings from the repository. */
    fun ensureAlarmChannel() {
        val repo = (context.applicationContext as? DropsApp)?.repository
        if (repo == null) {
            Log.w("DropsNotif", "ensureAlarmChannel(): no repository; channel not (re)created")
            return
        }
        ensureAlarmChannel(repo.getSettings())
    }

    /** Shared builder for [showDoseReminder] and [buildAlarmReminder]. */
    private fun buildDoseReminder(
        dose: Dose,
        session: DoseSession?,
        overdue: Boolean,
        channelId: String,
        deleteIntent: PendingIntent?,
    ): Notification {
        val title = buildString {
            append("Time for Drop ${displayNumber(dose, session)}")
            if (dose.kind == DoseKind.SPECIAL) append(" — Special daily drop")
            if (overdue) append(" (OVERDUE)")
        }
        val sessionLabel = session?.let { " · ${it.type.name.lowercase().replaceFirstChar { c -> c.uppercaseChar() }} session" } ?: ""
        val dueStr = TimeFormat.formatTime(dose.dueAt)
        val text = buildString {
            append("Have you taken it?")
            append(sessionLabel)
            if (overdue) {
                val mins = ((System.currentTimeMillis() - dose.dueAt) / 60000).coerceAtLeast(0)
                append(" — due at $dueStr, overdue by $mins min")
            } else {
                append(" — due at $dueStr")
            }
        }
        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(if (overdue) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent(dose.id))
            .setAutoCancel(false)
            .setOngoing(channelId == CHANNEL_ALARM_ID)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_save,
                    "YES — Taken",
                    actionPendingIntent(dose.id, AlarmScheduler.ACTION_YES, which = 1),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "NO — Not Yet",
                    actionPendingIntent(dose.id, AlarmScheduler.ACTION_NO, which = 2),
                ).build()
            )
            .addAction(
                // v2.0: "Skip" marks the drop missed and moves the chain to
                // the next drop (repo.skipDose).
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_delete,
                    "Skip",
                    AlarmScheduler.skipIntent(context, dose.id),
                ).build()
            )
            .addAction(
                // v2.3: "Snooze" opens the duration chooser (10/20/30/60
                // min) — an ACTIVITY intent, so it can show the picker.
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_recent_history,
                    "Snooze",
                    snoozePendingIntent(dose.id),
                ).build()
            )
        if (deleteIntent != null) builder.setDeleteIntent(deleteIntent)
        return builder.build()
    }

    /**
     * v2.3: replaces the dose reminder with a SILENT note while the dose is
     * snoozed (same stable id — the snooze-expiry fire replaces it again
     * with the ringing reminder). States plainly that it is snoozed and
     * when the reminder will fire. Keeps all four actions, so the drop can
     * still be answered (or re-snoozed) straight from the tray.
     */
    fun showSnoozed(dose: Dose, session: DoseSession?, snoozedUntil: Long) {
        ensureSnoozedChannel()
        val title = buildString {
            append("Drop ${displayNumber(dose, session)} snoozed")
            if (dose.kind == DoseKind.SPECIAL) append(" — Special daily drop")
        }
        val sessionLabel = session?.let {
            " · ${it.type.name.lowercase().replaceFirstChar { c -> c.uppercaseChar() }} session"
        } ?: ""
        val text = "Snoozed — reminder at ${TimeFormat.formatTime(snoozedUntil)}$sessionLabel"
        val notification = Notification.Builder(context, CHANNEL_SNOOZED_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent(dose.id))
            .setAutoCancel(false)
            .setOngoing(false)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_save,
                    "YES — Taken",
                    actionPendingIntent(dose.id, AlarmScheduler.ACTION_YES, which = 1),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "NO — Not Yet",
                    actionPendingIntent(dose.id, AlarmScheduler.ACTION_NO, which = 2),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_delete,
                    "Skip",
                    AlarmScheduler.skipIntent(context, dose.id),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_recent_history,
                    "Snooze",
                    snoozePendingIntent(dose.id),
                ).build()
            )
            .build()
        manager().notify(dose.id.toInt(), notification)
    }

    /** Creates the fully-silent [CHANNEL_SNOOZED_ID] channel (idempotent). */
    private fun ensureSnoozedChannel() {
        if (manager().getNotificationChannel(CHANNEL_SNOOZED_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_SNOOZED_ID,
            CHANNEL_SNOOZED_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        manager().createNotificationChannel(channel)
    }

    /** Refreshes the same notification after the user answered NO. */
    fun updateAfterNo(dose: Dose, session: DoseSession?) {
        ensureChannel()
        val repo = (context.applicationContext as? DropsApp)?.repository
        val retryMins = try {
            ((repo?.getSettings()?.retryIntervalMillis ?: 300_000L) / 60000).coerceAtLeast(1)
        } catch (e: Exception) {
            5L
        }
        val title = buildString {
            append("Time for Drop ${displayNumber(dose, session)}")
            if (dose.kind == DoseKind.SPECIAL) append(" — Special daily drop")
        }
        val text = "Still pending — next reminder in $retryMins min (retry ${dose.retryCount})"
        // v2.6: same ringer-mode gate as showDoseReminder — this refresh
        // is a dose reminder too and re-alerts on the sounding channel.
        val notification = Notification.Builder(context, doseChannelId())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent(dose.id))
            .setAutoCancel(false)
            .setOngoing(false)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_save,
                    "YES — Taken",
                    actionPendingIntent(dose.id, AlarmScheduler.ACTION_YES, which = 1),
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "NO — Not Yet",
                    actionPendingIntent(dose.id, AlarmScheduler.ACTION_NO, which = 2),
                ).build()
            )
            .build()
        manager().notify(dose.id.toInt(), notification)
    }

    /** Replaces the reminder with a short-lived "recorded" confirmation. */
    fun showTaken(dose: Dose, session: DoseSession?) {
        ensureChannel()
        val confirmedAt = dose.confirmedAt ?: System.currentTimeMillis()
        val text = "Drop ${displayNumber(dose, session)} recorded at ${TimeFormat.formatTime(confirmedAt)} ✓"
        // v2.6: dose-family post — ringer-mode gate, like showDoseReminder.
        val notification = Notification.Builder(context, doseChannelId())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Dose taken")
            .setContentText(text)
            .setContentIntent(contentPendingIntent(dose.id))
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .build()
        manager().notify(dose.id.toInt(), notification)
    }

    /**
     * v2.0: brief auto-cancel confirmation after the user taps "Skip" on a
     * reminder. Replaces the reminder in place (same stable id), mirroring
     * [showTaken].
     */
    fun showSkipped(dose: Dose, session: DoseSession, nextDueAt: Long?) {
        ensureChannel()
        val text = if (nextDueAt != null)
            "Drop ${displayNumber(dose, session)} skipped — next drop at ${TimeFormat.formatTime(nextDueAt)}"
        else
            "Drop ${displayNumber(dose, session)} skipped — session complete"
        // v2.6: dose-family post — ringer-mode gate, like showDoseReminder.
        val notification = Notification.Builder(context, doseChannelId())
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Drop skipped")
            .setContentText(text)
            .setContentIntent(contentPendingIntent(dose.id))
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .build()
        manager().notify(dose.id.toInt(), notification)
    }

    /** One-off notification for plan conflicts (e.g. session won't fit the cutoff). */
    fun showConflictNotification(reason: String) {        ensureChannel()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Schedule conflict")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setContentIntent(contentPendingIntent(null))
            .setAutoCancel(true)
            .build()
        manager().notify(CONFLICT_NOTIFICATION_ID, notification)
    }

    /**
     * v1.13: one-off info when an auto session start is deferred because
     * another session is still ACTIVE (single-active-session invariant).
     * Re-notifying replaces in place (stable [DEFERRED_START_NOTIFICATION_ID]).
     *
     * v2.3: once the blocker has cleared, [startAtMillis] carries the
     * planned start (clear + 30 min) and the text states it exactly, e.g.
     * "Evening session will start at 6:10 PM — after the Afternoon
     * session." While the blocker is still running, [startAtMillis] is
     * null and the original wording is used.
     */
    fun showDeferredStartNotification(type: SessionType, blockingType: SessionType?) {
        showDeferredStartNotification(type, blockingType, null)
    }

    fun showDeferredStartNotification(type: SessionType, blockingType: SessionType?, startAtMillis: Long?) {
        ensureChannel()
        val label = type.name.lowercase().replaceFirstChar { it.uppercaseChar() }
        val blockingLabel = blockingType?.name?.lowercase()?.replaceFirstChar { it.uppercaseChar() }
        val text = when {
            startAtMillis != null && blockingLabel != null ->
                "$label session will start at ${TimeFormat.formatTime(startAtMillis)} " +
                    "— after the $blockingLabel session."
            startAtMillis != null ->
                "$label session will start at ${TimeFormat.formatTime(startAtMillis)}."
            blockingLabel != null ->
                "$label session will start after the $blockingLabel session finishes."
            else ->
                "$label session will start when the current session finishes."
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Session deferred")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent(null))
            .setAutoCancel(true)
            .build()
        manager().notify(DEFERRED_START_NOTIFICATION_ID, notification)
    }

    fun cancelDeferredStartNotification() {
        manager().cancel(DEFERRED_START_NOTIFICATION_ID)
    }

    fun cancelDoseNotification(doseId: Long) {
        manager().cancel(doseId.toInt())
    }

    /**
     * Morning wake prompt: "Drops start karein?" with "Start karein" and
     * "15 min baad" actions. Re-notifying replaces the previous prompt in
     * place (stable [WAKE_NOTIFICATION_ID]), so dismissing it without action
     * starts nothing — the next wake tick simply re-prompts.
     */
    fun showWakePrompt() {
        ensureChannel()
        val startPi = PendingIntent.getBroadcast(
            context,
            0x6A4E10,
            Intent(context, WakeActionReceiver::class.java).setAction(AlarmScheduler.ACTION_WAKE_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozePi = PendingIntent.getBroadcast(
            context,
            0x6A4E11,
            Intent(context, WakeActionReceiver::class.java).setAction(AlarmScheduler.ACTION_WAKE_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Subah ke drops ka waqt ho gaya hai. Pehla drop abhi due karein?"
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Drops start karein?")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent(null))
            .setAutoCancel(false)
            .setOngoing(false)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_save,
                    "Start karein",
                    startPi,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "15 min baad",
                    snoozePi,
                ).build()
            )
            .build()
        manager().notify(WAKE_NOTIFICATION_ID, notification)
    }

    /**
     * Manual "Test alarm sound" from Settings: posts one notification on the
     * exact same [CHANNEL_ID] (after [ensureChannel]) that the dose
     * reminders use, so it exercises the identical sound/vibration path.
     * Lets the user verify the reminder sound immediately instead of waiting
     * for the next drop. Respects the sound/vibration toggles automatically
     * via [ensureChannel].
     */
    fun playTestAlarm() {
        val repo = (context.applicationContext as? DropsApp)?.repository
        playTestAlarm(repo?.getSettings() ?: DoseSettings())
    }

    /**
     * A13: test alarm with a pre-fetched settings snapshot — lets the caller
     * do the (blocking) settings read on a background thread instead of
     * doing DB I/O on the main thread.
     */
    fun playTestAlarm(settings: DoseSettings) {
        ensureChannel(settings)
        val text = "This is the reminder sound. If you hear it, dose reminders will sound the same."
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("\uD83D\uDD14 Test alarm")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent(null))
            .setAutoCancel(true)
            .build()
        manager().notify(TEST_NOTIFICATION_ID, notification)
    }

    fun cancelWakePrompt() {
        manager().cancel(WAKE_NOTIFICATION_ID)
    }

    // ------------------------------------------------------------ intents

    private fun actionPendingIntent(doseId: Long, action: String, which: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            actionRequestCode(doseId, which),
            Intent(context, DoseActionReceiver::class.java)
                .setAction(action)
                .putExtra(AlarmScheduler.EXTRA_DOSE_ID, doseId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * v2.3: PendingIntent for the reminder's "Snooze" action — opens the
     * duration chooser activity for this dose (which=4 slot, mirroring the
     * YES 1 / NO 2 / Skip 3 broadcast slots, but an ACTIVITY intent so the
     * chooser can render).
     */
    private fun snoozePendingIntent(doseId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            actionRequestCode(doseId, 4),
            Intent().apply {
                setClassName(context.packageName, "com.moon.newmedicine.ui.SnoozeActivity")
                putExtra(AlarmScheduler.EXTRA_DOSE_ID, doseId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun contentPendingIntent(doseId: Long?): PendingIntent =
        PendingIntent.getActivity(
            context,
            actionRequestCode(doseId ?: 0L, 0),
            Intent().apply {
                setClassName(context.packageName, "com.moon.newmedicine.ui.MainActivity")
                if (doseId != null) putExtra(AlarmScheduler.EXTRA_DOSE_ID, doseId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
