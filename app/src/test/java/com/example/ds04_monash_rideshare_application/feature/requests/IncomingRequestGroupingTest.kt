package com.example.ds04_monash_rideshare_application.feature.requests

import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

// Driver inbox ordering: soonest first, and a passenger's repeating ask collapses into one series.
class IncomingRequestGroupingTest {
    private val zone = ZoneId.of("Australia/Melbourne")
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun ask(id: String, passenger: String, time: Long?, asap: Boolean = false, seats: Int = 1, lat: Double = -37.9) =
        RideRequestSummary(id, null, passenger, seats, RequestStatus.PENDING, null, 1, null,
            lat, 145.1, -37.91, 145.13, asap, time, emptyList(), null, null, null, null)

    @Test fun soonestFirstWithAsapAtTheTop() {
        val entries = groupIncomingRequests(
            listOf(ask("late", "a", at(29, 8)), ask("asap", "b", null, asap = true), ask("soon", "c", at(9, 17))),
            keepOrder = false, zone = zone
        )
        assertEquals(listOf("asap", "soon", "late"), entries.map { (it as SingleRequest).request.requestId })
    }

    @Test fun aWeeklyRepeatBecomesOneSeriesPlacedAtItsEarliestDate() {
        val entries = groupIncomingRequests(
            listOf(
                ask("ken-29", "ken", at(29, 8)), ask("ken-15", "ken", at(15, 8)), ask("ken-22", "ken", at(22, 8)),
                ask("amara", "amara", at(14, 17))
            ),
            keepOrder = false, zone = zone
        )
        assertEquals(2, entries.size)
        assertEquals("amara", (entries[0] as SingleRequest).request.requestId)
        val series = entries[1] as RequestSeries
        assertEquals(listOf("ken-15", "ken-22", "ken-29"), series.requests.map { it.requestId })
        assertTrue(seriesSummary(series, zone).startsWith("Every Thu"))
    }

    @Test fun differentTimeRouteOrSeatsAreNotOneSeries() {
        val entries = groupIncomingRequests(
            listOf(
                ask("a", "ken", at(15, 8)), ask("b", "ken", at(22, 9)),             // other time of day
                ask("c", "ken", at(23, 8), seats = 2),                             // other seat count
                ask("d", "ken", at(24, 8), lat = -38.1)                            // other route
            ),
            keepOrder = false, zone = zone
        )
        assertTrue(entries.all { it is SingleRequest })
    }

    @Test fun distanceSortOrderIsKept() {
        val entries = groupIncomingRequests(
            listOf(ask("far-but-first", "a", at(29, 8)), ask("near", "b", at(9, 8))),
            keepOrder = true, zone = zone
        )
        assertEquals(listOf("far-but-first", "near"), entries.map { (it as SingleRequest).request.requestId })
    }
}
