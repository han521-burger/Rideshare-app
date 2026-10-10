package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.CODE_INVALID_RECURRENCE
import com.example.ds04_monash_rideshare_application.backend.DEFAULT_REPEAT_WEEKS
import com.example.ds04_monash_rideshare_application.backend.MAX_REPEAT_WEEKS
import com.example.ds04_monash_rideshare_application.backend.SchedulingConfig
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.expandOccurrences
import com.example.ds04_monash_rideshare_application.backend.requireValidRecurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// How "repeat on these weekdays for N weeks" becomes concrete departures (RecurrenceExpansion.kt): pure, no clock, no
// database - so the dates below are fixed and the daylight-saving cases always run the same way.
class RecurrenceExpansionTest {
    private val melbourne: ZoneId = SchedulingConfig.SERVICE_ZONE
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) = date.atTime(hour, minute).atZone(melbourne).toInstant().toEpochMilli()
    private fun local(millis: Long): ZonedDateTime = java.time.Instant.ofEpochMilli(millis).atZone(melbourne)
    private fun expand(first: Long, days: List<Int>, weeks: Int? = null) = expandOccurrences(first, requireValidRecurrence(days, weeks))

    // ---------------------------------------------------------------- definition and validation
    @Test fun noRepeatDaysMeansOneOccurrenceAtTheRequestedTime() {
        val first = at(LocalDate.of(2027, 5, 3), 8)
        assertEquals(listOf(first), expand(first, emptyList()))
        assertNull(requireValidRecurrence(emptyList(), 12))
    }

    @Test fun theBoundsAreTheDocumentedOnes() {
        assertEquals(4, DEFAULT_REPEAT_WEEKS); assertEquals(12, MAX_REPEAT_WEEKS)
        assertEquals(4, requireValidRecurrence(listOf(1), null)!!.weeks, "weeks defaults to 4")
        assertEquals(1, requireValidRecurrence(listOf(1), 1)!!.weeks); assertEquals(12, requireValidRecurrence(listOf(1), 12)!!.weeks)
    }

    @Test fun anInvalidDefinitionIsRefusedBeforeAnythingIsExpanded() {
        for (days in listOf(listOf(0), listOf(8), listOf(1, 9), listOf(-1))) {
            assertEquals(CODE_INVALID_RECURRENCE, assertFailsWith<ValidationException> { requireValidRecurrence(days, 4) }.code, "$days")
        }
        for (weeks in listOf(0, 13, -2, 100)) {
            assertEquals(CODE_INVALID_RECURRENCE, assertFailsWith<ValidationException> { requireValidRecurrence(listOf(1), weeks) }.code, "$weeks weeks")
        }
    }

    @Test fun weeksAreNotCheckedWhenThereAreNoRepeatDays() {
        assertNull(requireValidRecurrence(emptyList(), 99))
    }

    // ---------------------------------------------------------------- which dates
    @Test fun theFirstOccurrenceIsTheRequestedDepartureWhateverItsWeekday() {
        val monday = LocalDate.of(2027, 5, 3)                                          // a Monday
        val first = at(monday, 8)
        val result = expand(first, listOf(DayOfWeek.WEDNESDAY.value), weeks = 2)
        assertEquals(first, result.first(), "the Monday request itself is always an occurrence")
        assertEquals(listOf(monday, monday.plusDays(2), monday.plusDays(9)), result.map { local(it).toLocalDate() })
    }

    @Test fun furtherDatesStartTheDayAfterAndStopBeforeTheEndOfTheLastWeek() {
        val monday = LocalDate.of(2027, 5, 3)
        val all = expand(at(monday, 8), (1..7).toList(), weeks = 1).map { local(it).toLocalDate() }
        assertEquals((0L..6L).map { monday.plusDays(it) }, all, "7 days: the first plus the next six, not the 8th")
        // The same weekday as the first is a repeat too, one week on - but not at the very end of the range.
        val weekly = expand(at(monday, 8), listOf(1), weeks = 3).map { local(it).toLocalDate() }
        assertEquals(listOf(monday, monday.plusWeeks(1), monday.plusWeeks(2)), weekly)
    }

    @Test fun aSeriesOfWeeksAndDaysHasTheExpectedCount() {
        val first = at(LocalDate.of(2027, 5, 3), 8)
        assertEquals(1 + 12 * 2 - 1, expand(first, listOf(1, 3), weeks = 12).size, "Mon+Wed over 12 weeks; the first Monday is the first occurrence")
        assertEquals(4, expand(first, listOf(1)).size, "default 4 weeks, weekly")
    }

    @Test fun datesAreChronologicalAndNeverRepeated() {
        val result = expand(at(LocalDate.of(2027, 5, 5), 8), listOf(1, 2, 3, 4, 5, 6, 7, 3), weeks = 4)
        assertEquals(result.sorted(), result)
        assertEquals(result.distinct(), result)
    }

    // ---------------------------------------------------------------- Australia/Melbourne, daylight saving
    @Test fun theWallClockTimeSurvivesTheEndOfDaylightSaving() {
        // Melbourne clocks go back on Sunday 4 April 2027 (03:00 AEDT -> 02:00 AEST): the days before are UTC+11, after UTC+10.
        val start = LocalDate.of(2027, 3, 31)
        val result = expand(at(start, 8), (1..7).toList(), weeks = 1)
        assertEquals(7, result.size)
        result.forEach { assertEquals(LocalTime.of(8, 0), local(it).toLocalTime(), "still 8:00 on the wall clock") }
        val offsets = result.map { local(it).offset.totalSeconds / 3600 }
        assertEquals(listOf(11, 11, 11, 11, 10, 10, 10), offsets)
        // The real elapsed time between Saturday and Sunday 8:00 is 25 hours, not 24.
        val saturday = result[3]; val sunday = result[4]
        assertEquals(25L * 3600_000L, sunday - saturday)
    }

    @Test fun theWallClockTimeSurvivesTheStartOfDaylightSaving() {
        // Clocks go forward on Sunday 3 October 2027 (02:00 AEST -> 03:00 AEDT): the elapsed time Sat -> Sun 8:00 is 23 hours.
        val start = LocalDate.of(2027, 10, 1)
        val result = expand(at(start, 8), (1..7).toList(), weeks = 1)
        result.forEach { assertEquals(LocalTime.of(8, 0), local(it).toLocalTime()) }
        assertEquals(listOf(10, 10, 11, 11, 11, 11, 11), result.map { local(it).offset.totalSeconds / 3600 })
        assertEquals(23L * 3600_000L, result[2] - result[1])
    }

    @Test fun aWallTimeThatDoesNotExistOnSomeDateMovesToTheNextValidTimeThatDay() {
        // 02:30 does not exist on Sunday 3 October 2027 in Melbourne (clocks jump 02:00 -> 03:00).
        val result = expand(at(LocalDate.of(2027, 10, 1), 2, 30), listOf(7), weeks = 1)
        val sunday = result.map(::local).single { it.dayOfWeek == DayOfWeek.SUNDAY }
        assertEquals(LocalDateTime.of(2027, 10, 3, 3, 30), sunday.toLocalDateTime(), "shifted forward by the skipped hour, same day")
        assertTrue(result.sorted() == result)
    }

    @Test fun anAmbiguousWallTimeUsesTheEarlierOffset() {
        // 02:30 happens twice on Sunday 4 April 2027 (clocks go 03:00 -> 02:00): the first, summer-time one is used.
        val result = expand(at(LocalDate.of(2027, 4, 2), 2, 30), listOf(7), weeks = 1)
        val sunday = result.map(::local).single { it.dayOfWeek == DayOfWeek.SUNDAY }
        assertEquals(LocalDateTime.of(2027, 4, 4, 2, 30), sunday.toLocalDateTime())
        assertEquals(11, sunday.offset.totalSeconds / 3600)
    }

    @Test fun itNeverDependsOnTheServersOwnZone() {
        val previous = java.util.TimeZone.getDefault()
        try {
            val first = at(LocalDate.of(2027, 3, 31), 8)
            val expected = expand(first, (1..7).toList(), weeks = 1)
            for (zone in listOf("UTC", "America/Los_Angeles", "Asia/Kolkata")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                assertEquals(expected, expand(first, (1..7).toList(), weeks = 1), "JVM zone $zone changed the result")
            }
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }
}
