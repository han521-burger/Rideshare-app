package com.example.ds04_monash_rideshare_application.domain

import com.example.ds04_monash_rideshare_application.core.demand.DayBlock
import com.example.ds04_monash_rideshare_application.core.demand.DEMAND_ZONE
import com.example.ds04_monash_rideshare_application.core.demand.DemandAggregator
import com.example.ds04_monash_rideshare_application.core.demand.DemandDataSource
import com.example.ds04_monash_rideshare_application.core.demand.DemandEvent
import com.example.ds04_monash_rideshare_application.core.demand.DemandModel
import com.example.ds04_monash_rideshare_application.core.demand.DemandPeriod
import com.example.ds04_monash_rideshare_application.core.demand.DemandProvenance
import com.example.ds04_monash_rideshare_application.core.demand.disclosure
import com.example.ds04_monash_rideshare_application.ui.APPROVED_POINTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

// Domain logic (pure JVM): time bucketing, per-hub aggregation, normalisation and ranking.
// Events here are hand-built DemandEvents - the aggregator has no idea they are not "real".
class DemandAggregationTest {
    private val clayton = "Monash Clayton"
    private val huntingdale = "Huntingdale Station"
    private val chadstone = "Chadstone Shopping Centre"

    // A Monday (2026-09-28) and a Saturday (2026-09-26), in Melbourne time.
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(DEMAND_ZONE).toInstant().toEpochMilli()
    private val monday = LocalDate.of(2026, 9, 28)
    private val saturday = LocalDate.of(2026, 9, 26)
    private fun event(hub: String, date: LocalDate, hour: Int, weight: Double = 1.0) = DemandEvent(hub, at(date, hour), weight)
    private val weekdayMorning = DemandPeriod(false, DayBlock.MORNING)
    private fun raw(points: List<com.example.ds04_monash_rideshare_application.core.demand.DemandPoint>, hub: String) =
        points.single { it.hubName == hub }.rawDemand

    // --- time bucketing ------------------------------------------------------------------------

    @Test fun hoursFallIntoTheExpectedBlocks() {
        assertEquals(DayBlock.EVENING, DayBlock.ofHour(0)); assertEquals(DayBlock.EVENING, DayBlock.ofHour(4))
        assertEquals(DayBlock.MORNING, DayBlock.ofHour(5)); assertEquals(DayBlock.MORNING, DayBlock.ofHour(9))
        assertEquals(DayBlock.MIDDAY, DayBlock.ofHour(10)); assertEquals(DayBlock.MIDDAY, DayBlock.ofHour(14))
        assertEquals(DayBlock.AFTERNOON, DayBlock.ofHour(15)); assertEquals(DayBlock.AFTERNOON, DayBlock.ofHour(18))
        assertEquals(DayBlock.EVENING, DayBlock.ofHour(19)); assertEquals(DayBlock.EVENING, DayBlock.ofHour(23))
        assertEquals(24, (0..23).count { DayBlock.ofHour(it) in DayBlock.entries })
    }

    @Test fun everyTimestampBelongsToExactlyOneOfEightPeriods() {
        assertEquals(8, DemandPeriod.ALL.size)
        assertEquals(DemandPeriod(false, DayBlock.MORNING), DemandPeriod.of(at(monday, 8, 30)))
        assertEquals(DemandPeriod(true, DayBlock.MIDDAY), DemandPeriod.of(at(saturday, 12)))
        assertEquals("Weekday morning", DemandPeriod(false, DayBlock.MORNING).label)
        assertEquals("Weekend evening", DemandPeriod(true, DayBlock.EVENING).label)
    }

    @Test fun periodsUseMelbourneTimeNotTheDeviceZone() {
        // 22:30 UTC on Sunday is 08:30 Monday in Melbourne (AEST/AEDT both put it on Monday morning).
        val utcSundayNight = LocalDateTime.of(2026, 9, 27, 22, 30).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(DemandPeriod(false, DayBlock.MORNING), DemandPeriod.of(utcSundayNight))
    }

    // --- grouping and counting -------------------------------------------------------------------

    @Test fun eventsAreGroupedByHubWithCorrectRawCounts() {
        val events = listOf(
            event(clayton, monday, 8), event(clayton, monday, 9), event(clayton, monday, 7),
            event(huntingdale, monday, 8), event(huntingdale, monday, 9)
        )
        val points = DemandAggregator.aggregate(events, weekdayMorning)
        assertEquals(3.0, raw(points, clayton), 0.0)
        assertEquals(2.0, raw(points, huntingdale), 0.0)
        assertEquals(0.0, raw(points, chadstone), 0.0)
    }

    @Test fun weightsAreSummedNotCounted() {
        val points = DemandAggregator.aggregate(listOf(event(clayton, monday, 8, 2.0), event(clayton, monday, 8, 1.0)), weekdayMorning)
        assertEquals(3.0, raw(points, clayton), 0.0)
    }

    @Test fun invalidWeightsAndUnknownHubsAreIgnored() {
        val events = listOf(
            event(clayton, monday, 8, 0.0), event(clayton, monday, 8, -1.0), event(clayton, monday, 8, Double.NaN),
            event("Atlantis Station", monday, 8), event(clayton, monday, 8)
        )
        val points = DemandAggregator.aggregate(events, weekdayMorning)
        assertEquals(1.0, raw(points, clayton), 0.0)
        assertTrue(points.none { it.hubName == "Atlantis Station" })
    }

    @Test fun olderHistoryCanBeCutOff() {
        val events = listOf(event(clayton, monday.minusDays(70), 8), event(clayton, monday, 8))
        assertEquals(2.0, raw(DemandAggregator.aggregate(events, weekdayMorning), clayton), 0.0)
        assertEquals(1.0, raw(DemandAggregator.aggregate(events, weekdayMorning, sinceMillis = at(monday.minusDays(30), 0)), clayton), 0.0)
    }

    // --- time filtering --------------------------------------------------------------------------

    @Test fun morningEventsInfluenceTheMorningViewOnly() {
        val events = listOf(event(clayton, monday, 8), event(chadstone, monday, 17))
        val morning = DemandAggregator.aggregate(events, weekdayMorning)
        val afternoon = DemandAggregator.aggregate(events, DemandPeriod(false, DayBlock.AFTERNOON))
        assertEquals(1.0, raw(morning, clayton), 0.0); assertEquals(0.0, raw(morning, chadstone), 0.0)
        assertEquals(0.0, raw(afternoon, clayton), 0.0); assertEquals(1.0, raw(afternoon, chadstone), 0.0)
    }

    @Test fun weekdayAndWeekendDoNotMix() {
        val events = listOf(event(clayton, monday, 8), event(clayton, saturday, 8))
        assertEquals(1.0, raw(DemandAggregator.aggregate(events, weekdayMorning), clayton), 0.0)
        assertEquals(1.0, raw(DemandAggregator.aggregate(events, DemandPeriod(true, DayBlock.MORNING)), clayton), 0.0)
    }

    @Test fun blockBoundariesBelongToTheLaterBlock() {
        val events = listOf(DemandEvent(clayton, at(monday, 9, 59)), DemandEvent(clayton, at(monday, 10, 0)))
        assertEquals(1.0, raw(DemandAggregator.aggregate(events, weekdayMorning), clayton), 0.0)
        assertEquals(1.0, raw(DemandAggregator.aggregate(events, DemandPeriod(false, DayBlock.MIDDAY)), clayton), 0.0)
    }

    // --- normalisation ---------------------------------------------------------------------------

    @Test fun intensitiesAreAlwaysWithinZeroAndOne() {
        val events = (1..40).map { event(clayton, monday, 8) } + (1..9).map { event(huntingdale, monday, 8) } + event(chadstone, monday, 8)
        DemandAggregator.aggregate(events, weekdayMorning).forEach {
            assertTrue("${it.hubName} ${it.normalizedIntensity}", it.normalizedIntensity in 0.0..1.0)
        }
    }

    @Test fun emptyDatasetIsSafeAndAllZero() {
        val points = DemandAggregator.aggregate(emptyList(), weekdayMorning)
        assertEquals(APPROVED_POINTS.size, points.size)
        assertTrue(points.all { it.rawDemand == 0.0 && it.normalizedIntensity == 0.0 })
        assertEquals(emptyList<Double>(), DemandAggregator.normalize(emptyList()))
    }

    @Test fun equalDemandGivesEqualFullIntensityAndNoDivisionByZero() {
        assertEquals(listOf(1.0, 1.0, 1.0), DemandAggregator.normalize(listOf(5.0, 5.0, 5.0)))
        assertEquals(listOf(0.0, 1.0, 0.0), DemandAggregator.normalize(listOf(0.0, 3.0, 0.0)))
    }

    @Test fun anExtremeOutlierDoesNotHideTheOtherHubs() {
        // Min/max with a 1000-count hub would give the 2-count hub 0.002; the percentile reference
        // (the 18-count hub here) keeps every hub visible and simply caps the outlier at 1.0.
        val raw = listOf(2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 1000.0)
        val intensity = DemandAggregator.normalize(raw)
        assertEquals(1.0, intensity.last(), 0.0)
        assertEquals(1.0, intensity[8], 0.0)
        assertTrue("smallest hub stays visible: ${intensity.first()}", intensity.first() > 0.3)
        assertTrue(intensity.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun normalisationKeepsTheOrderOfRawDemand() {
        val raw = listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0)
        val intensity = DemandAggregator.normalize(raw)
        assertEquals(intensity.sorted(), intensity)
        assertTrue(intensity.zipWithNext().all { (a, b) -> a < b || b == 1.0 })
    }

    @Test fun nonFiniteInputNeverProducesNaN() {
        val out = DemandAggregator.normalize(listOf(Double.NaN, Double.POSITIVE_INFINITY, 2.0, -3.0))
        assertTrue(out.all { it.isFinite() && it in 0.0..1.0 })
    }

    // --- ranking ---------------------------------------------------------------------------------

    @Test fun theBusiestHubRanksFirstAndRanksAreConsecutive() {
        val events = (1..5).map { event(huntingdale, monday, 8) } + (1..2).map { event(clayton, monday, 8) }
        val points = DemandAggregator.aggregate(events, weekdayMorning)
        assertEquals(huntingdale, points.first().hubName)
        assertEquals((1..APPROVED_POINTS.size).toList(), points.map { it.rank })
        assertTrue(points.zipWithNext().all { (a, b) -> a.rawDemand >= b.rawDemand })
    }

    @Test fun tiesAreBrokenByNameSoTheOrderIsStable() {
        val tied = listOf(event(huntingdale, monday, 8), event(clayton, monday, 8))
        val first = DemandAggregator.aggregate(tied, weekdayMorning).take(2).map { it.hubName }
        assertEquals(listOf(huntingdale, clayton).sorted(), first)
        assertEquals(first, DemandAggregator.aggregate(tied.reversed(), weekdayMorning).take(2).map { it.hubName })
    }

    // --- approved hubs and real-data compatibility ---------------------------------------------------

    @Test fun everyDemandPointIsAnApprovedHubWithItsOwnCoordinates() {
        val points = DemandAggregator.aggregate(listOf(event(clayton, monday, 8)), weekdayMorning)
        assertEquals(APPROVED_POINTS.map { it.name }.sorted(), points.map { it.hubName }.sorted())
        points.forEach { point ->
            val hub = APPROVED_POINTS.single { it.name == point.hubName }
            assertEquals(hub.point.latitude, point.latitude, 0.0); assertEquals(hub.point.longitude, point.longitude, 0.0)
            assertEquals(hub.isCampus, point.isCampus)
        }
    }

    @Test fun anyDemandDataSourceWorksAndOnlySyntheticOnesAreLabelled() {
        val observed = object : DemandDataSource {
            override val provenance = DemandProvenance.OBSERVED
            override fun events() = listOf(event(clayton, monday, 8), event(clayton, monday, 9))
        }
        val synthetic = object : DemandDataSource {
            override val provenance = DemandProvenance.SYNTHETIC
            override fun events() = observed.events()
        }
        assertNull(observed.disclosure)
        assertEquals("Prototype demand · synthetic data", synthetic.disclosure)
        assertEquals(2.0, DemandModel(observed).pointsFor(weekdayMorning).first().rawDemand, 0.0)
        assertFalse(DemandModel(observed).isSynthetic); assertTrue(DemandModel(synthetic).isSynthetic)
    }

    @Test fun theModelCachesPerPeriodAndDoesNotReReadTheSource() {
        var reads = 0
        val source = object : DemandDataSource {
            override val provenance = DemandProvenance.SYNTHETIC
            override fun events(): List<DemandEvent> { reads++; return listOf(event(clayton, monday, 8)) }
        }
        val model = DemandModel(source)
        repeat(5) { model.pointsFor(weekdayMorning) }
        assertEquals(1, reads)
        model.pointsFor(DemandPeriod(true, DayBlock.MIDDAY))
        assertEquals(2, reads)
    }
}
