package com.moon.newmedicine.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Fragment
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.moon.newmedicine.DropsApp
import com.moon.newmedicine.R
import com.moon.newmedicine.alarm.WakeUsage
import com.moon.newmedicine.core.BackupJson
import com.moon.newmedicine.core.DoseSettings
import com.moon.newmedicine.core.ScheduleEngine
import com.moon.newmedicine.update.UpdateInstaller
import com.moon.newmedicine.update.UpdatePrompt
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** All user settings: schedule times, intervals, special drop, alerts, backup, permissions. */
class SettingsFragment : Fragment() {

    companion object {
        private const val REQ_EXPORT = 1001
        private const val REQ_IMPORT = 1002
        private const val REQ_SOUND_PICK = 1003
    }

    private var settings: DoseSettings = DoseSettings()
    private var bindingUi: Boolean = false

    private lateinit var btnAfternoonStart: Button
    private lateinit var btnEveningStart: Button
    private lateinit var btnDoseInterval: Button
    private lateinit var btnRetryInterval: Button
    private lateinit var btnCutoff: Button
    private lateinit var switchAutoAfternoon: Switch
    private lateinit var switchAutoEvening: Switch
    private lateinit var switchSound: Switch
    private lateinit var switchVibration: Switch
    private lateinit var switchNag: Switch
    private lateinit var btnNagInterval: Button
    private lateinit var btnCallDeferGap: Button
    private lateinit var btnReminderSound: Button
    private lateinit var btnTestAlarm: Button
    private lateinit var btnExport: Button
    private lateinit var btnImport: Button
    private lateinit var btnClearHistory: Button
    private lateinit var btnResetSettings: Button
    private lateinit var tvNotifStatus: TextView
    private lateinit var btnNotifSettings: Button
    private lateinit var tvAlarmStatus: TextView
    private lateinit var btnAlarmSettings: Button
    private lateinit var btnWakeStart: Button
    private lateinit var btnWakeEnd: Button
    private lateinit var btnWakeInterval: Button
    private lateinit var tvUsageStatus: TextView
    private lateinit var btnUsageSettings: Button
    private lateinit var tvWakeDiagnostics: TextView
    private lateinit var tvAppVersion: TextView
    private lateinit var btnCheckUpdates: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.fragment_settings, container, false)
        btnAfternoonStart = v.findViewById(R.id.btn_afternoon_start)
        btnEveningStart = v.findViewById(R.id.btn_evening_start)
        btnDoseInterval = v.findViewById(R.id.btn_dose_interval)
        btnRetryInterval = v.findViewById(R.id.btn_retry_interval)
        btnCutoff = v.findViewById(R.id.btn_cutoff)
        switchAutoAfternoon = v.findViewById(R.id.switch_auto_afternoon)
        switchAutoEvening = v.findViewById(R.id.switch_auto_evening)
        switchSound = v.findViewById(R.id.switch_sound)
        switchVibration = v.findViewById(R.id.switch_vibration)
        switchNag = v.findViewById(R.id.switch_nag)
        btnNagInterval = v.findViewById(R.id.btn_nag_interval)
        btnCallDeferGap = v.findViewById(R.id.btn_call_defer_gap)
        btnReminderSound = v.findViewById(R.id.btn_reminder_sound)
        btnTestAlarm = v.findViewById(R.id.btn_test_alarm)
        btnExport = v.findViewById(R.id.btn_export)
        btnImport = v.findViewById(R.id.btn_import)
        btnClearHistory = v.findViewById(R.id.btn_clear_history)
        btnResetSettings = v.findViewById(R.id.btn_reset_settings)
        tvNotifStatus = v.findViewById(R.id.tv_notif_status)
        btnNotifSettings = v.findViewById(R.id.btn_notif_settings)
        tvAlarmStatus = v.findViewById(R.id.tv_alarm_status)
        btnAlarmSettings = v.findViewById(R.id.btn_alarm_settings)
        btnWakeStart = v.findViewById(R.id.btn_wake_start)
        btnWakeEnd = v.findViewById(R.id.btn_wake_end)
        btnWakeInterval = v.findViewById(R.id.btn_wake_interval)
        tvUsageStatus = v.findViewById(R.id.tv_usage_status)
        btnUsageSettings = v.findViewById(R.id.btn_usage_settings)
        tvWakeDiagnostics = v.findViewById(R.id.tv_wake_diagnostics)
        tvAppVersion = v.findViewById(R.id.tv_app_version)
        btnCheckUpdates = v.findViewById(R.id.btn_check_updates)

        btnAfternoonStart.setOnClickListener {
            pickTime("Afternoon start", settings.afternoonStartMinutesOfDay) { mins ->
                updateSettings({ it.copy(afternoonStartMinutesOfDay = mins) })
            }
        }
        btnEveningStart.setOnClickListener {
            pickTime("Evening start", settings.eveningStartMinutesOfDay) { mins ->
                updateSettings({ it.copy(eveningStartMinutesOfDay = mins) })
            }
        }
        // New Medicine: the gap between drops cycles the fixed presets
        // (5 / 7 / 10 / 15 min), like the "Call ke baad" gap button.
        btnDoseInterval.setOnClickListener {
            val presets = ScheduleEngine.DOSE_INTERVAL_PRESETS_MILLIS
            val cur = ScheduleEngine.sanitizeDoseInterval(settings.doseIntervalMillis)
            val next = presets[(presets.indexOf(cur) + 1) % presets.size]
            updateSettings({ it.copy(doseIntervalMillis = next) })
        }
        btnRetryInterval.setOnClickListener {
            pickNumber("Retry interval (minutes)", 1, 30, (settings.retryIntervalMillis / 60000).toInt()) { mins ->
                updateSettings({ it.copy(retryIntervalMillis = mins * 60000L) })
            }
        }
        btnCutoff.setOnClickListener {
            pickTime("Daily cutoff", settings.cutoffMinutesOfDay) { mins ->
                updateSettings({ it.copy(cutoffMinutesOfDay = mins) })
            }
        }

        switchAutoAfternoon.setOnCheckedChangeListener { _, checked ->
            if (!bindingUi) updateSettings({ it.copy(autoStartAfternoon = checked) })
        }
        switchAutoEvening.setOnCheckedChangeListener { _, checked ->
            if (!bindingUi) updateSettings({ it.copy(autoStartEvening = checked) })
        }
        switchSound.setOnCheckedChangeListener { _, checked ->
            if (!bindingUi) updateSettings({ it.copy(soundEnabled = checked) }, refreshChannel = true)
        }
        switchVibration.setOnCheckedChangeListener { _, checked ->
            if (!bindingUi) updateSettings({ it.copy(vibrationEnabled = checked) }, refreshChannel = true)
        }
        switchNag.setOnCheckedChangeListener { _, checked ->
            if (!bindingUi) updateSettings({ it.copy(nagEnabled = checked) })
        }
        btnNagInterval.setOnClickListener {
            pickNumber("Nag interval (minutes)", 5, 60, (settings.nagIntervalMillis / 60000).toInt()) { mins ->
                updateSettings({ it.copy(nagIntervalMillis = mins * 60000L) })
            }
        }
        // v2.10: "Call ke baad" gap — cycles the fixed presets
        // (30 sec / 1 min / 2 min / 5 min).
        btnCallDeferGap.setOnClickListener {
            val presets = ScheduleEngine.CALL_DEFER_GAP_PRESETS_MILLIS
            val cur = ScheduleEngine.sanitizeCallDeferGap(settings.callDeferGapMillis)
            val next = presets[(presets.indexOf(cur) + 1) % presets.size]
            updateSettings({ it.copy(callDeferGapMillis = next) })
        }

        btnReminderSound.setOnClickListener { onReminderSoundClick() }

        btnTestAlarm.setOnClickListener {
            val act = activity ?: return@setOnClickListener
            val app = act.application as DropsApp
            // v2.1: the test now RINGS via the foreground service with the
            // SELECTED sound (real reminder behavior), so the user verifies
            // exactly what a dose reminder will sound like. Falls back to
            // the old channel-sound test when the service start is refused
            // or the sound toggle is off. Settings/KV reads stay off the
            // main thread (A13).
            Thread({
                val snapshot = app.repository.getSettings()
                val rang = snapshot.soundEnabled &&
                    com.moon.newmedicine.alarm.ReminderAlarmService.ringTest(act)
                if (!rang) {
                    app.notifications.playTestAlarm(snapshot)
                }
                act.runOnUiThread {
                    if (isAdded) {
                        Toast.makeText(
                            act,
                            if (rang) "Test alarm ringing — dismiss its notification to stop"
                            else "Test alarm sent",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }, "drops-test-alarm").apply { isDaemon = true }.start()
        }

        btnExport.setOnClickListener { onExport() }
        btnImport.setOnClickListener { onImport() }
        btnClearHistory.setOnClickListener { onClearHistory() }
        btnResetSettings.setOnClickListener { onResetSettings() }

        btnNotifSettings.setOnClickListener {
            val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
            startActivity(i)
        }
        btnAlarmSettings.setOnClickListener {
            (activity.application as DropsApp).alarms.openExactAlarmSettings()
        }

        btnWakeStart.setOnClickListener {
            pickTime("Wake window start", settings.wakeWindowStartMinutesOfDay) { mins ->
                updateSettings({ it.copy(wakeWindowStartMinutesOfDay = mins) }, rearmWake = true)
            }
        }
        btnWakeEnd.setOnClickListener {
            pickTime("Wake window end", settings.wakeWindowEndMinutesOfDay) { mins ->
                updateSettings({ it.copy(wakeWindowEndMinutesOfDay = mins) }, rearmWake = true)
            }
        }
        btnWakeInterval.setOnClickListener {
            pickNumber("Wake check interval (minutes)", 5, 60, settings.wakeCheckIntervalMinutes) { mins ->
                updateSettings({ it.copy(wakeCheckIntervalMinutes = mins) }, rearmWake = true)
            }
        }
        btnUsageSettings.setOnClickListener {
            WakeUsage.openUsageAccessSettings(activity)
        }
        btnCheckUpdates.setOnClickListener {
            val act = activity ?: return@setOnClickListener
            UpdatePrompt.checkManual(act)
        }
        return v
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val act = activity ?: return
        val repo = (act.application as DropsApp).repository
        Thread {
            try {
                val s = repo.getSettings()
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    settings = s
                    render()
                    refreshSoundName()
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (isAdded) Toast.makeText(act, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Rebinds every control from the cached [settings]; fast system calls run on the UI thread. */
    private fun render() {
        val act = activity ?: return
        val app = act.application as DropsApp
        val notifOn = app.notifications.areNotificationsEnabled()
        val alarmOk = app.alarms.canScheduleExact()
        bindingUi = true
        try {
            btnAfternoonStart.text = "Afternoon start: ${fmtMinutes(settings.afternoonStartMinutesOfDay)}"
            btnEveningStart.text = "Evening start: ${fmtMinutes(settings.eveningStartMinutesOfDay)}"
            btnDoseInterval.text = "Dose interval: ${settings.doseIntervalMillis / 60000} min"
            btnRetryInterval.text = "Retry interval: ${settings.retryIntervalMillis / 60000} min"
            btnCutoff.text = "Daily cutoff: ${fmtMinutes(settings.cutoffMinutesOfDay)}"
            switchAutoAfternoon.isChecked = settings.autoStartAfternoon
            switchAutoEvening.isChecked = settings.autoStartEvening
            switchSound.isChecked = settings.soundEnabled
            switchVibration.isChecked = settings.vibrationEnabled
            switchNag.isChecked = settings.nagEnabled
            btnNagInterval.text = "Nag interval: ${settings.nagIntervalMillis / 60000} min"
            btnCallDeferGap.text = "Call ke baad gap: ${fmtGap(settings.callDeferGapMillis)}"
            tvNotifStatus.text = if (notifOn) "Notifications: enabled" else "Notifications: DISABLED"
            tvAlarmStatus.text = if (alarmOk) "Exact alarms: allowed" else "Exact alarms: NOT ALLOWED"
            btnWakeStart.text = "Wake window start: ${fmtMinutes(settings.wakeWindowStartMinutesOfDay)}"
            btnWakeEnd.text = "Wake window end: ${fmtMinutes(settings.wakeWindowEndMinutesOfDay)}"
            btnWakeInterval.text = "Wake check every: ${settings.wakeCheckIntervalMinutes} min"
            val usageOk = try {
                WakeUsage.hasUsageAccess(act)
            } catch (e: Exception) {
                false
            }
            tvUsageStatus.text = if (usageOk) getString(R.string.usage_access_granted)
            else getString(R.string.usage_access_denied)
            tvAppVersion.text =
                "Version ${UpdateInstaller.installedVersionName(act)} " +
                    "(${UpdateInstaller.installedVersionCode(act)})"
            refreshWakeDiagnostics()
        } finally {
            bindingUi = false
        }
    }

    /**
     * Loads the read-only wake-health snapshot off the UI thread (it touches
     * the database) and renders it into the diagnostics row.
     */
    private fun refreshWakeDiagnostics() {
        val act = activity ?: return
        val app = act.application as DropsApp
        tvWakeDiagnostics.text = "Wake status: loading…"
        Thread({
            val text = try {
                val d = app.repository.wakeDiagnostics()
                "Wake status (subah ka notification):\n" +
                    d.format(java.time.ZoneId.systemDefault())
            } catch (e: Exception) {
                "Wake status: unavailable (${e.message})"
            }
            act.runOnUiThread {
                if (isAdded) tvWakeDiagnostics.text = text
            }
        }, "drops-wake-diag").apply { isDaemon = true }.start()
    }

    private fun updateSettings(
        mut: (DoseSettings) -> DoseSettings,
        refreshChannel: Boolean = false,
        rearmWake: Boolean = false,
    ) {
        val act = activity ?: return
        val app = act.application as DropsApp
        val before = settings
        settings = mut(settings)
        val snapshot = settings
        render()
        Thread {
            app.repository.saveSettings(snapshot)
            // v2.0: nag triggers are anchored to the old interval/enabled
            // flag — drop them so the next reconcile recomputes from the new
            // settings (a stale trigger would otherwise nag on the old
            // cadence, or nag while disabled).
            if (before.nagEnabled != snapshot.nagEnabled ||
                before.nagIntervalMillis != snapshot.nagIntervalMillis
            ) {
                app.repository.clearNagTriggers()
            }
            if (refreshChannel) app.notifications.ensureChannel(snapshot)
            if (rearmWake) app.repository.rearmWakeChain(System.currentTimeMillis())
            // v2.11: a session-start time (or auto-start toggle) change must
            // re-arm the auto-start alarms IMMEDIATELY — before v2.11 the new
            // time was only saved, so the button/home screen showed it while
            // the alarm kept firing at the old time until the next app start
            // or midnight rollover (user report 2026-10-29).
            if (before.afternoonStartMinutesOfDay != snapshot.afternoonStartMinutesOfDay ||
                before.eveningStartMinutesOfDay != snapshot.eveningStartMinutesOfDay ||
                before.autoStartAfternoon != snapshot.autoStartAfternoon ||
                before.autoStartEvening != snapshot.autoStartEvening
            ) {
                val nowSs = System.currentTimeMillis()
                app.repository.rearmSessionStarts(app.repository.todayKey(nowSs), nowSs)
            }
        }.start()
    }

    private fun pickTime(title: String, currentMinutes: Int, onPick: (Int) -> Unit) {
        val act = activity ?: return
        TimePickerDialog(
            act,
            { _, h, m -> onPick(h * 60 + m) },
            (currentMinutes / 60).coerceIn(0, 23),
            (currentMinutes % 60).coerceIn(0, 59),
            false,
        ).apply { setTitle(title) }.show()
    }

    private fun pickNumber(title: String, min: Int, max: Int, current: Int, onPick: (Int) -> Unit) {
        val act = activity ?: return
        val np = NumberPicker(act).apply {
            minValue = min
            maxValue = max
            value = current.coerceIn(min, max)
        }
        AlertDialog.Builder(act)
            .setTitle(title)
            .setView(np)
            .setPositiveButton(R.string.ok) { _, _ -> onPick(np.value) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onExport() {
        val name = "drops-backup-" +
            LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".json"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun onImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(i, REQ_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SOUND_PICK) {
            // The ringtone picker returns the URI in an extra (data.data is
            // null), so this branch runs before the file-picker logic.
            if (resultCode == Activity.RESULT_OK) {
                val picked = data?.getParcelableExtra<android.net.Uri>(
                    android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                )
                if (picked != null) saveReminderSound(picked.toString())
            }
            return
        }
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val act = activity ?: return
        val app = act.application as DropsApp
        when (requestCode) {
            REQ_EXPORT -> Thread {
                try {
                    val json = app.repository.exportBackup()
                    act.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                    act.runOnUiThread {
                        if (isAdded) Toast.makeText(act, R.string.backup_exported, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    act.runOnUiThread {
                        if (isAdded) Toast.makeText(act, "${getString(R.string.export_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            REQ_IMPORT -> Thread {
                try {
                    val text = act.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                    val result = app.repository.importBackup(text)
                    act.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        when (result) {
                            is BackupJson.ImportResult.Ok -> {
                                Toast.makeText(
                                    act,
                                    "Restored ${result.data.doses.size} doses",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                load()
                            }
                            is BackupJson.ImportResult.Error -> {
                                AlertDialog.Builder(act)
                                    .setTitle(R.string.import_failed_title)
                                    .setMessage(result.message)
                                    .setPositiveButton(R.string.ok, null)
                                    .show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    act.runOnUiThread {
                        if (isAdded) Toast.makeText(act, "${getString(R.string.import_failed_title)}: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun onClearHistory() {
        val act = activity ?: return
        val repo = (act.application as DropsApp).repository
        AlertDialog.Builder(act)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.confirm_clear_history)
            .setPositiveButton(R.string.delete) { _, _ ->
                Thread {
                    repo.clearHistory()
                    act.runOnUiThread {
                        if (isAdded) Toast.makeText(act, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onResetSettings() {
        val act = activity ?: return
        val app = act.application as DropsApp
        AlertDialog.Builder(act)
            .setTitle(R.string.reset_settings)
            .setMessage(R.string.confirm_reset_settings)
            .setPositiveButton(R.string.reset) { _, _ ->
                settings = DoseSettings()
                render()
                Thread {
                    app.repository.saveSettings(DoseSettings())
                    // v2.0: a reset can re-enable/change nagging — drop stale
                    // triggers so they are recomputed from the defaults.
                    app.repository.clearNagTriggers()
                }.start()
                Toast.makeText(act, R.string.settings_reset, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * v2.1: "Reminder sound" row — Built-in alarm (bundled tone, the
     * default), the system alarm tone, or any alarm tone picked from the
     * phone. The choice persists as the raw `reminderSoundUri` setting;
     * empty = built-in (see NotificationSound.selectSound for resolution).
     */
    private fun onReminderSoundClick() {
        val act = activity ?: return
        AlertDialog.Builder(act)
            .setTitle("Reminder sound")
            .setItems(arrayOf("Built-in alarm", "System alarm tone", "Choose from phone…")) { _, which ->
                when (which) {
                    0 -> saveReminderSound("")
                    1 -> saveReminderSound(
                        android.media.RingtoneManager.getDefaultUri(
                            android.media.RingtoneManager.TYPE_ALARM,
                        )?.toString() ?: "",
                    )
                    2 -> {
                        val i = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(
                                android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                                android.media.RingtoneManager.TYPE_ALARM,
                            )
                            putExtra(
                                android.media.RingtoneManager.EXTRA_RINGTONE_TITLE,
                                "Choose reminder sound",
                            )
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                        }
                        startActivityForResult(i, REQ_SOUND_PICK)
                    }
                }
            }
            .show()
    }

    private fun saveReminderSound(value: String) {
        val act = activity ?: return
        val app = act.application as DropsApp
        Thread({
            app.repository.setRawSetting(
                com.moon.newmedicine.repo.DropsRepository.KEY_REMINDER_SOUND, value,
            )
            act.runOnUiThread { if (isAdded) refreshSoundName() }
        }, "drops-sound-save").apply { isDaemon = true }.start()
    }

    /** Renders the current sound choice's name into the row (off-thread read). */
    private fun refreshSoundName() {
        val act = activity ?: return
        val app = act.application as DropsApp
        Thread({
            val name = try {
                val stored = app.repository.getRawSetting(
                    com.moon.newmedicine.repo.DropsRepository.KEY_REMINDER_SOUND,
                )
                if (stored.isNullOrBlank()) {
                    "Built-in alarm"
                } else {
                    val uri = android.net.Uri.parse(stored)
                    android.media.RingtoneManager.getRingtone(act, uri)?.getTitle(act)
                        ?: "Custom sound"
                }
            } catch (e: Exception) {
                "Built-in alarm"
            }
            act.runOnUiThread {
                if (isAdded) btnReminderSound.text = "Reminder sound: $name"
            }
        }, "drops-sound-name").apply { isDaemon = true }.start()
    }

    private fun fmtMinutes(minutes: Int): String =
        LocalTime.of(
            (minutes / 60).coerceIn(0, 23),
            (minutes % 60).coerceIn(0, 59),
        ).format(DateTimeFormatter.ofPattern("h:mm a"))

    /** v2.10: renders the "call ke baad" gap preset ("30 sec", "1 min", …). */
    private fun fmtGap(millis: Long): String = when (millis) {
        30_000L -> "30 sec"
        60_000L -> "1 min"
        120_000L -> "2 min"
        300_000L -> "5 min"
        else -> "${ScheduleEngine.sanitizeCallDeferGap(millis) / 1000} sec"
    }
}
