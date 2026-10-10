package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.DemoHistoryGenerator
import com.example.ds04_monash_rideshare_application.backend.DemoRole
import com.example.ds04_monash_rideshare_application.backend.MONASH_CAMPUSES
import com.example.ds04_monash_rideshare_application.backend.NAMED_FIXED_POINTS
import com.example.ds04_monash_rideshare_application.backend.resolveApprovedPoint
import com.example.ds04_monash_rideshare_application.backend.tripWindow
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Domain logic: the synthetic completed-history PLAN, before anything touches a database.
class DemoHistoryGeneratorTest {
    private val zone = ZoneId.of("Australia/Melbourne")
    private val anchor = ZonedDateTime.of(2026, 6, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val plan = DemoHistoryGenerator.generate(anchor, zone, maxPassengerSeats = 3)

    @Test fun `the same inputs always give the identical plan`() {
        assertEquals(plan, DemoHistoryGenerator.generate(anchor, zone, 3))
    }

    @Test fun `totals are stable for the fixed seed`() {
        assertEquals(32, plan.size)
        assertEquals(DemoHistoryGenerator.TRIPS_PER_WEEK.sum(), plan.size)
        assertEquals(plan.size, plan.count { it.role == DemoRole.DRIVER } + plan.count { it.role == DemoRole.PASSENGER })
        assertEquals(plan.sumOf { it.distanceMeters }, DemoHistoryGenerator.generate(anchor, zone, 3).sumOf { it.distanceMeters })
    }

    @Test fun `trips span about ten weeks, all in the past, on weekdays`() {
        val startOfToday = Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        plan.forEach {
            assertTrue(it.departureTime + it.durationSeconds * 1000 < startOfToday, "trip ${it.index} is not safely in the past")
            assertTrue(Instant.ofEpochMilli(it.departureTime).atZone(zone).dayOfWeek.value <= 5, "trip ${it.index} is on a weekend")
        }
        val weeks = (startOfToday - plan.first().departureTime) / (7 * 86_400_000.0)
        assertTrue(weeks in 8.0..10.0, "history spans $weeks weeks")
        assertEquals(plan.sortedBy { it.departureTime }, plan)
        assertEquals((1..plan.size).toList(), plan.map { it.index })
    }

    @Test fun `every route runs between one approved hub or campus and a Monash campus`() {
        val hubNames = NAMED_FIXED_POINTS.map { it.name }.toSet()
        val campusNames = MONASH_CAMPUSES.map { it.name }.toSet()
        plan.forEach {
            assertTrue(it.toMonash != (it.origin.name in campusNames), "trip ${it.index} direction flag disagrees with its endpoints")
            val campus = if (it.toMonash) it.destination else it.origin
            val hub = if (it.toMonash) it.origin else it.destination
            assertTrue(campus.name in campusNames, "${campus.name} is not a campus")
            assertTrue(hub.name in hubNames, "${hub.name} is not an approved hub")
            assertTrue(resolveApprovedPoint(campus.lat, campus.lng)?.isCampus == true)
        }
    }

    @Test fun `seat counts are always possible`() {
        plan.forEach {
            assertTrue(it.seatsTotal in 1..3, "trip ${it.index}: total ${it.seatsTotal}")
            assertTrue(it.seatsFilled in 1..it.seatsTotal, "trip ${it.index}: filled ${it.seatsFilled} of ${it.seatsTotal}")
            when (it.role) {
                DemoRole.DRIVER -> assertEquals(0, it.targetSeats)
                DemoRole.PASSENGER -> assertTrue(it.targetSeats in 1..it.seatsFilled)
            }
            assertTrue(it.distanceMeters > 0 && it.durationSeconds > 0)
        }
    }

    @Test fun `a one passenger seat vehicle never gets more than one seat`() {
        val small = DemoHistoryGenerator.generate(anchor, zone, maxPassengerSeats = 1)
        small.filter { it.role == DemoRole.DRIVER }.forEach { assertEquals(1, it.seatsTotal); assertEquals(1, it.seatsFilled) }
    }

    @Test fun `no two trips overlap or share a commute slot`() {
        val windows = plan.map { tripWindow(it.departureTime, it.durationSeconds) }
        for (i in windows.indices) for (j in i + 1 until windows.size) {
            assertFalse(windows[i].overlaps(windows[j]), "trips ${plan[i].index} and ${plan[j].index} overlap")
        }
    }

    @Test fun `the history tells a varied commuting story`() {
        assertTrue(plan.count { it.role == DemoRole.DRIVER } > plan.count { it.role == DemoRole.PASSENGER }, "driving should be the majority")
        assertTrue(plan.any { it.role == DemoRole.PASSENGER })
        assertTrue(plan.any { it.toMonash } && plan.any { !it.toMonash }, "both directions")
        val campusOf = { t: com.example.ds04_monash_rideshare_application.backend.DemoTrip -> if (t.toMonash) t.destination.name else t.origin.name }
        val byCampus = plan.groupingBy(campusOf).eachCount()
        assertEquals("Monash Clayton", byCampus.maxByOrNull { it.value }!!.key, "Clayton should be the busiest campus")
        assertTrue(byCampus.size >= 4, "several campuses should appear: $byCampus")
        val hubs = plan.map { if (it.toMonash) it.origin.name else it.destination.name }.toSet()
        assertTrue(hubs.size >= 8, "expected varied hubs, got $hubs")
        assertTrue(plan.map { it.seatsFilled }.toSet().size >= 2, "occupancy should vary")
        val hours = plan.map { Instant.ofEpochMilli(it.departureTime).atZone(zone).hour }
        assertTrue(hours.any { it < 12 } && hours.any { it >= 15 }, "morning and afternoon commutes")
    }

    @Test
    fun `each account gets its own history, and the same one every time`() {
        fun planFor(userId: String) =
            DemoHistoryGenerator.generate(anchor, zone, 3, DemoHistoryGenerator.seedFor(userId))
        val first = planFor("user-a")
        assertEquals(first, planFor("user-a"))
        assertFalse(first == planFor("user-b"), "two accounts must not get identical demo histories")
        assertEquals(DemoHistoryGenerator.TRIPS_PER_WEEK.sum(), planFor("user-b").size)
    }
}
