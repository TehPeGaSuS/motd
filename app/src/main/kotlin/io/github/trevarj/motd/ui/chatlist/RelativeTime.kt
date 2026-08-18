package io.github.trevarj.motd.ui.chatlist

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Telegram-style compact relative timestamp for chat-list rows.
 *
 * - < 1 min: "now"
 * - < 1 hour: "5m"
 * - same calendar day: "14:32" (or "2:32 PM" when [is24Hour] is false)
 * - within the last week: weekday abbreviation ("Mon")
 * - older: "12/03" (day/month)
 *
 * [is24Hour] only affects the same-day branch; defaults to true so existing callers/tests are
 * unaffected. It is independent of whether message timestamps are shown in the chat itself.
 */
fun relativeChatTime(timeMs: Long, nowMs: Long = System.currentTimeMillis(), is24Hour: Boolean = true): String {
    val delta = nowMs - timeMs
    if (delta < TimeUnit.MINUTES.toMillis(1)) return "now"
    if (delta < TimeUnit.HOURS.toMillis(1)) {
        return "${TimeUnit.MILLISECONDS.toMinutes(delta)}m"
    }

    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val then = Calendar.getInstance().apply { timeInMillis = timeMs }

    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) {
        return if (is24Hour) {
            String.format(
                Locale.getDefault(),
                "%02d:%02d",
                then.get(Calendar.HOUR_OF_DAY),
                then.get(Calendar.MINUTE),
            )
        } else {
            // Calendar.HOUR is 0-11 with 0 meaning 12 o'clock; the file's other strings are
            // hardcoded English, so "AM"/"PM" match that existing convention rather than using
            // AM_PM_STRINGS/locale-aware formatting.
            val hour12 = then.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            val marker = if (then.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
            String.format(
                Locale.getDefault(),
                "%d:%02d %s",
                hour12,
                then.get(Calendar.MINUTE),
                marker,
            )
        }
    }

    if (delta < TimeUnit.DAYS.toMillis(7)) {
        return then.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault())
            ?: dayMonth(then)
    }

    return dayMonth(then)
}

private fun dayMonth(cal: Calendar): String = String.format(
    Locale.getDefault(),
    "%02d/%02d",
    cal.get(Calendar.DAY_OF_MONTH),
    cal.get(Calendar.MONTH) + 1,
)
