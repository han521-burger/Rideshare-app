package com.example.ds04_monash_rideshare_application.feature.activity

import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestFunnelCounts
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import java.util.Locale

internal enum class HistoryFilter(val label: String) { ALL("All"), DRIVING("Driving"), RIDING("Riding") }
internal data class Outcome(val label: String, val count: Int, val fraction: Float)
internal fun outcomeDistribution(counts: RequestFunnelCounts): List<Outcome> {
    val values = listOf("Pending" to counts.pending, "Accepted" to counts.accepted,
        "Completed" to counts.completed, "Rejected" to counts.rejected,
        "Cancelled" to counts.cancelled, "Expired" to counts.expired, "Schedule conflict" to counts.conflicted)
    return values.map { (name, count) -> Outcome(name, count, magnitude(count, counts.total)) }
}
internal fun magnitude(value: Int, total: Int): Float =
    if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
internal val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
internal data class Weekday(val label: String, val count: Int, val fraction: Float)
internal fun weekdayDistribution(counts: List<Int>): List<Weekday> {
    val week = List(7) { counts.getOrElse(it) { 0 }.coerceAtLeast(0) }
    val maximum = week.maxOrNull() ?: 0
    return WEEKDAYS.mapIndexed { i, day -> Weekday(day, week[i], magnitude(week[i], maximum)) }
}
internal fun HistoryFilter.includes(driverId: String, userId: String?): Boolean = when (this) {
    HistoryFilter.ALL -> true
    HistoryFilter.DRIVING -> driverId == userId
    HistoryFilter.RIDING -> driverId != userId
}
internal fun filteredHistory(trips: List<RideSummary>, userId: String?, filter: HistoryFilter) =
    trips.filter { filter.includes(it.driverId, userId) }
internal fun decimal(value: Double) = String.format(Locale.getDefault(), "%.1f", value)
internal fun responseTimeLabel(minutes: Double?): String? = minutes?.let {
    when {
        it < 60 -> "${decimal(it)} min"
        it < 1440 -> "${decimal(it / 60)} hr"
        else -> "${decimal(it / 1440)} days"
    }
}
