package com.example.ds04_monash_rideshare_application.feature.activity

import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestFunnelCounts
import org.junit.Assert.*
import org.junit.Test

class ActivityPresentationTest {
    @Test fun outcomesUseTotalIncludingExpired() {
        val outcomes = outcomeDistribution(RequestFunnelCounts(2, 3, 0, 1, 4, 2))
        assertEquals(listOf("Pending", "Accepted", "Completed", "Rejected", "Cancelled", "Expired", "Schedule conflict"), outcomes.map { it.label })
        assertEquals(2f / 12, outcomes[5].fraction, .00001f)
        assertEquals(4f / 12, outcomes[2].fraction, .00001f)
        assertEquals(1f, outcomes.sumOf { it.fraction.toDouble() }.toFloat(), .00001f)
    }
    // The outcome card indexes its colours by position. A funnel status without a colour crashed the Activity tab for everyone
    // (index 6 of a six-colour palette), so the palettes must always cover every outcome, and each outcome stays distinguishable.
    @Test fun everyOutcomeHasItsOwnColourInBothThemes() {
        val outcomes = outcomeDistribution(RequestFunnelCounts(1, 1, 1, 1, 1, 1, 1))
        for (palette in listOf(OUTCOME_COLORS_LIGHT, OUTCOME_COLORS_DARK)) {
            assertTrue("${palette.size} colours for ${outcomes.size} outcomes", palette.size >= outcomes.size)
            assertEquals(palette.size, palette.toSet().size)
        }
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
