package com.example.ds04_monash_rideshare_application.feature.activity

import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestFunnelCounts
import org.junit.Assert.*
import org.junit.Test

class ActivityPresentationTest {
    @Test fun outcomesUseTotalIncludingExpired() {
        val outcomes = outcomeDistribution(RequestFunnelCounts(2, 3, 0, 1, 4, 2))
        assertEquals(listOf("Pending", "Accepted", "Completed", "Rejected", "Cancelled", "Expired"), outcomes.map { it.label })
        assertEquals(2f / 12, outcomes.last().fraction, .00001f)
        assertEquals(4f / 12, outcomes[2].fraction, .00001f)
        assertEquals(1f, outcomes.sumOf { it.fraction.toDouble() }.toFloat(), .00001f)
    }
    @Test fun zeroStatusHasNoVisualWidth() {
        assertEquals(0f, outcomeDistribution(RequestFunnelCounts(1, 0, 0, 0, 0))[1].fraction, 0f)
    }
    @Test fun emptyFunnelHasNoInventedRate() {
        assertTrue(outcomeDistribution(RequestFunnelCounts(0, 0, 0, 0, 0)).all { it.fraction == 0f })
    }
    @Test fun weekdaysPreserveOrderAndRealZeros() {
        val days = weekdayDistribution(listOf(0, 2, 4, 0, 1, 0, 3))
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), days.map { it.label })
        assertEquals(0f, days[0].fraction, 0f)
        assertEquals(.5f, days[1].fraction, 0f)
        assertEquals(1f, days[2].fraction, 0f)
    }
    @Test fun emptyWeekNeverDividesByZero() {
        assertEquals(7, weekdayDistribution(emptyList()).size)
        assertTrue(weekdayDistribution(List(7) { 0 }).all { it.fraction == 0f })
    }
    @Test fun nullResponseRemainsUnknown() { assertNull(responseTimeLabel(null)) }
    @Test fun realZeroResponseIsAvailable() { assertNotNull(responseTimeLabel(0.0)) }
    @Test fun historyFilterClassifiesByAuthenticatedDriver() {
        assertTrue(HistoryFilter.ALL.includes("other", "me"))
        assertTrue(HistoryFilter.DRIVING.includes("me", "me"))
        assertFalse(HistoryFilter.DRIVING.includes("other", "me"))
        assertTrue(HistoryFilter.RIDING.includes("other", "me"))
        assertFalse(HistoryFilter.RIDING.includes("me", "me"))
    }
}
