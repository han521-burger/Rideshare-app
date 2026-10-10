package com.example.ds04_monash_rideshare_application.backend

import java.time.ZoneId

// "Repeat this request on Mon/Wed for the next N weeks", expanded into concrete departures.
//
// THE RULES (product bounds, unchanged from before this was written down)
//  * repeatDaysOfWeek: ISO weekday numbers, 1 = Monday ... 7 = Sunday. Empty means "no repeat" (one occurrence).
//    Anything outside 1..7 is a validation error (400, INVALID_RECURRENCE) - the whole submission is refused before any
//    occurrence is processed.
//  * repeatWeeks: how many weeks ahead of the first date the series reaches. Optional; default 4; must be 1..12 (400,
//    INVALID_RECURRENCE otherwise). Ignored when there are no repeat days.
//  * The FIRST occurrence is the requested departure itself, whatever weekday it falls on. The others are every later
//    date, starting the day after, up to but not including (first date + repeatWeeks weeks), whose weekday is in
//    repeatDaysOfWeek - each at the SAME LOCAL TIME OF DAY as the first.
//  * "Local" is Australia/Melbourne (SchedulingConfig.SERVICE_ZONE), never the server JVM's zone, so "every Monday at
//    8:00" stays 8:00 on the wall clock across a daylight-saving change (the epoch gap becomes 23 or 25 hours). A wall
//    time that does not exist on some date (the hour skipped when clocks go forward) moves to the next valid time that day.
//  * ASAP has no schedule: repeat days never apply to it (it is always one occurrence).
//  * Occurrences are returned in chronological order, and that is the order they are processed in.
const val DEFAULT_REPEAT_WEEKS = 4
const val MAX_REPEAT_WEEKS = 12
const val CODE_INVALID_RECURRENCE = "INVALID_RECURRENCE"

class Recurrence(val days: Set<Int>, val weeks: Int)

// null = not a repeating request. Throws before anything is processed if the definition is invalid.
fun requireValidRecurrence(days: List<Int>, weeks: Int?): Recurrence? {
    if (days.isEmpty()) return null
    if (days.any { it !in 1..7 }) {
        throw ValidationException("repeatDaysOfWeek must be weekday numbers from 1 (Monday) to 7 (Sunday)", CODE_INVALID_RECURRENCE)
    }
    val resolved = weeks ?: DEFAULT_REPEAT_WEEKS
    if (resolved !in 1..MAX_REPEAT_WEEKS) {
        throw ValidationException("repeatWeeks must be between 1 and $MAX_REPEAT_WEEKS", CODE_INVALID_RECURRENCE)
    }
    return Recurrence(days.toSet(), resolved)
}

// Pure: no clock, no database. Chronological, first occurrence included.
fun expandOccurrences(first: Long, recurrence: Recurrence?, zone: ZoneId = SchedulingConfig.SERVICE_ZONE): List<Long> {
    if (recurrence == null) return listOf(first)
    val start = java.time.Instant.ofEpochMilli(first).atZone(zone)
    val timeOfDay = start.toLocalTime()
    val endDate = start.toLocalDate().plusWeeks(recurrence.weeks.toLong())
    val later = generateSequence(start.toLocalDate().plusDays(1)) { it.plusDays(1) }
        .takeWhile { it.isBefore(endDate) }
        .filter { it.dayOfWeek.value in recurrence.days }
        .map { it.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli() }
        .toList()
    return (listOf(first) + later).distinct().sorted()
}
