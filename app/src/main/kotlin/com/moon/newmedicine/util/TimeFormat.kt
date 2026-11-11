package com.moon.newmedicine.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small time-formatting helpers shared by notifications and (later) the UI. */
object TimeFormat {

    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("h:mm a", Locale.getDefault())
    }

    /** "3:05 PM" style local time for an epoch-millis instant. */
    fun formatTime(millis: Long): String = timeFormat.get()!!.format(Date(millis))

    /**
     * Countdown rendering: "mm:ss" under an hour ("04:37"), "h:mm:ss" at or
     * above an hour ("1:04:37"). Never negative.
     */
    fun formatCountdown(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }
}
