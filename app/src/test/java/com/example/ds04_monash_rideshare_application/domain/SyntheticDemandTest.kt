package com.example.ds04_monash_rideshare_application.domain

import com.example.ds04_monash_rideshare_application.core.demand.DayBlock
import com.example.ds04_monash_rideshare_application.core.demand.DEMAND_ZONE
import com.example.ds04_monash_rideshare_application.core.demand.DemandAggregator
import com.example.ds04_monash_rideshare_application.core.demand.DemandPeriod
import com.example.ds04_monash_rideshare_application.core.demand.DemandSummary
import com.example.ds04_monash_rideshare_application.core.demand.HubRole
import com.example.ds04_monash_rideshare_application.core.demand.SyntheticDemand
import com.example.ds04_monash_rideshare_application.core.demand.SyntheticDemandDataSource
import com.example.ds04_monash_rideshare_application.ui.APPROVED_POINTS
import com.example.ds04_monash_rideshare_application.ui.MONASH_CAMPUSES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

// Domain logic (pure JVM): the SYNTHETIC cold-start demand history. These tests also guard the
// "prototype data, not real usage" rule: every figure below is computed from the generated events.
class SyntheticDemandTest {
    private val events = SyntheticDemand.generate()
    private val hubNames = APPROVED_POINTS.map { it.name }

    @Test fun theSameSeedGivesIdenticalData() {
        assertEquals(events, SyntheticDemand.generate())
        assertEquals(events, SyntheticDemandDataSource().events())
    }

    @Test fun aDifferentSeedGivesDifferentData() {
        assertNotEquals(events, SyntheticDemand.generate(seed = SyntheticDemand.SEED + 1))
    }

    @Test fun eventsCoverTheIntendedSeventyFiveDayWindowInOrder() {
        assertEquals(75, SyntheticDemand.DAYS)
        val dates = events.map { Instant.ofEpochMilli(it.timestampMillis).atZone(DEMAND_ZONE).toLocalDate() }
        assertTrue(dates.min() >= SyntheticDemand.END_DATE.minusDays(74))
        assertTrue(dates.max() <= SyntheticDemand.END_DATE)
        assertTrue("almost every day has demand", dates.toSet().size >= 70)
        assertEquals(events.map { it.timestampMillis }.sorted(), events.map { it.timestampMillis })
    }

    @Test fun eventCountIsInTheIntendedRange() {
        assertTrue("got ${events.size}", events.size in 1_000..3_000)
    }

    @Test fun onlyApprovedHubsAreUsedAndEveryApprovedHubIsModelled() {
        assertTrue(events.all { it.hubName in hubNames })
        assertEquals(hubNames.sorted(), SyntheticDemand.HUBS.map { it.hubName }.sorted())
        assertEquals(30, SyntheticDemand.HUBS.map { it.hubName }.toSet().size)
        assertEquals(MONASH_CAMPUSES.map { it.name }.toSet(), SyntheticDemand.HUBS.filter { it.role == HubRole.CAMPUS }.map { it.hubName }.toSet())
        assertTrue("every hub has some demand", hubNames.all { name -> events.any { it.hubName == name } })
    }

    @Test fun eventsCarryOnlyHubTimeAndAValidWeight() {
        assertTrue(events.all { it.weight == 1.0 || it.weight == 2.0 })
        assertTrue(events.all { it.timestampMillis > 0 })
    }

    @Test fun demandIsNotUniformAcrossHubs() {
        val perHub = events.groupBy { it.hubName }.mapValues { (_, list) -> list.sumOf { it.weight } }
        assertTrue("busiest hub should clearly out-draw the quietest", perHub.values.max() > 4 * perHub.values.min())
        assertEquals("Monash Clayton is the biggest campus", "Monash Clayton", perHub.maxByOrNull { it.value }!!.key)
    }

    @Test fun regionsFollowTheClusterStory() {
        val perHub = events.groupBy { it.hubName }.mapValues { (_, list) -> list.sumOf { it.weight } }
        fun cluster(vararg names: String) = names.sumOf { perHub.getValue(it) }
        val clayton = cluster("Monash Clayton", "Huntingdale Station", "Clayton Station", "Westall Station", "Glen Waverley Station")
        val caulfield = cluster("Monash Caulfield", "Caulfield Station", "Carnegie Station", "Malvern Central")
        val parkville = cluster("Monash Parkville", "Parkville Station", "Royal Park Station", "Barkly Square")
        val peninsula = cluster("Monash Peninsula", "Frankston Station", "Leawarra Station", "Bayside Shopping Centre", "Karingal Hub")
        assertTrue(clayton > caulfield && caulfield > parkville && caulfield > peninsula)
    }

    @Test fun weekdayMorningFavoursCampusesAndAfternoonFavoursStationsAndShops() {
        val morning = DemandAggregator.aggregate(events, DemandPeriod(false, DayBlock.MORNING))
        val afternoon = DemandAggregator.aggregate(events, DemandPeriod(false, DayBlock.AFTERNOON))
        val campuses = MONASH_CAMPUSES.map { it.name }.toSet()
        fun share(points: List<com.example.ds04_monash_rideshare_application.core.demand.DemandPoint>, wantCampus: Boolean) =
            points.filter { (it.hubName in campuses) == wantCampus }.sumOf { it.rawDemand } / points.sumOf { it.rawDemand }
        assertTrue("morning campus share ${share(morning, true)}", share(morning, true) > share(afternoon, true))
        assertTrue(share(afternoon, false) > share(morning, false))
        assertEquals("Monash Clayton", morning.first().hubName)
    }

    @Test fun weekendsAreMuchQuieterThanWeekdays() {
        val perDay = { weekend: Boolean ->
            val days = events.filter { DemandPeriod.of(it.timestampMillis).weekend == weekend }
            days.sumOf { it.weight } / days.map { Instant.ofEpochMilli(it.timestampMillis).atZone(DEMAND_ZONE).toLocalDate() }.toSet().size
        }
        assertTrue("weekday ${perDay(false)} vs weekend ${perDay(true)}", perDay(false) > 2 * perDay(true))
    }

    @Test fun coldStartProducesNonZeroDemandInEveryTimeBucket() {
        DemandPeriod.ALL.forEach { period ->
            val points = DemandAggregator.aggregate(events, period)
            assertTrue("${period.label} has demand", points.any { it.rawDemand > 0 })
            assertTrue(points.first().normalizedIntensity > 0.0)
        }
    }

    @Test fun theTimeBucketChangesWhichHubsAreHottest() {
        val tops = DemandPeriod.ALL.map { DemandAggregator.aggregate(events, it).take(3).map { p -> p.hubName } }
        assertTrue("different periods should not all rank the same", tops.toSet().size > 1)
    }

    @Test fun aSummaryIsComputedFromTheDataNotTyped() {
        val summary = DemandSummary.of(events, hubNames)
        assertEquals(events.size, summary.totalEvents)
        assertEquals(events.sumOf { it.weight }, summary.totalWeight, 1e-9)
        assertEquals(5, summary.topHubs.size)
        assertEquals(3, summary.lowestHubs.size)
        assertTrue(summary.topHubs.zipWithNext().all { (a, b) -> a.second >= b.second })
        assertTrue(summary.topHubs.last().second >= summary.lowestHubs.first().second)
        assertEquals(1.0, summary.weekdayShare + summary.weekendShare, 1e-9)
        assertTrue(summary.weekdayShare > summary.weekendShare)
        assertTrue(summary.peakPeriod != null && summary.peakHour != null)
        // Visible in the test report so the figures can be quoted in demos: ./gradlew :app:testDebugUnitTest --info
        summary.lines().forEach(::println)
    }

    @Test fun generationIsFastEnoughToRunOnFirstUse() {
        val start = System.nanoTime()
        SyntheticDemand.generate(endDate = LocalDate.of(2026, 9, 30))
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", (System.nanoTime() - start) / 1_000_000 < 2_000)
    }
}
