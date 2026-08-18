package io.github.trevarj.motd.ui.chatlist

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    // Fixed "now" so same-day/older-day branches are deterministic regardless of the run's clock.
    private val now = calendarAt(2024, Calendar.MARCH, 15, 18, 30).timeInMillis

    @Test fun `under a minute reads now`() {
        assertEquals("now", relativeChatTime(now - 10_000, now))
    }

    @Test fun `under an hour reads minutes`() {
        assertEquals("5m", relativeChatTime(now - 5 * 60_000, now))
    }

    @Test fun `same day defaults to 24-hour`() {
        val then = calendarAt(2024, Calendar.MARCH, 15, 9, 5).timeInMillis
        assertEquals("09:05", relativeChatTime(then, now))
    }

    @Test fun `same day 24-hour is explicit`() {
        val then = calendarAt(2024, Calendar.MARCH, 15, 14, 32).timeInMillis
        assertEquals("14:32", relativeChatTime(then, now, is24Hour = true))
    }

    @Test fun `within the last week reads a weekday abbreviation`() {
        val then = calendarAt(2024, Calendar.MARCH, 13, 9, 0).timeInMillis
        assertEquals("Wed", relativeChatTime(then, now))
    }

    @Test fun `older than a week reads day-month`() {
        val then = calendarAt(2024, Calendar.JANUARY, 3, 9, 0).timeInMillis
        assertEquals("03/01", relativeChatTime(then, now))
    }

    @Test fun `12-hour morning`() {
        val then = calendarAt(2024, Calendar.MARCH, 15, 9, 5).timeInMillis
        assertEquals("9:05 AM", relativeChatTime(then, now, is24Hour = false))
    }

    @Test fun `12-hour noon`() {
        val then = calendarAt(2024, Calendar.MARCH, 15, 12, 0).timeInMillis
        assertEquals("12:00 PM", relativeChatTime(then, now, is24Hour = false))
    }

    @Test fun `12-hour midnight`() {
        val then = calendarAt(2024, Calendar.MARCH, 15, 0, 0).timeInMillis
        assertEquals("12:00 AM", relativeChatTime(then, now, is24Hour = false))
    }

    @Test fun `12-hour afternoon`() {
        val then = calendarAt(2024, Calendar.MARCH, 15, 14, 32).timeInMillis
        assertEquals("2:32 PM", relativeChatTime(then, now, is24Hour = false))
    }

    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }
}
