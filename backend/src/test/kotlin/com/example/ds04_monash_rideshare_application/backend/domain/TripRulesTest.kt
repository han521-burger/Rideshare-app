package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.RideRequestStatus
import com.example.ds04_monash_rideshare_application.backend.TRIP_CONFLICT_BUFFER_MILLIS
import com.example.ds04_monash_rideshare_application.backend.TripWindow
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.estimatedTripWindow
import com.example.ds04_monash_rideshare_application.backend.requireBookableDeparture
import com.example.ds04_monash_rideshare_application.backend.tripWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Domain logic: booking lead time, trip-overlap detection and the request state machine.
class TripRulesTest {
    private val minute = 60_000L

    @Test fun `scheduled departures need the minimum lead time`() {
        val now = System.currentTimeMillis()
        requireBookableDeparture(now + 60 * minute)
        assertFailsWith<ValidationException> { requireBookableDeparture(now + 10 * minute) }
        assertFailsWith<ValidationException> { requireBookableDeparture(now - minute) }
    }

    @Test fun `trips overlap when they are closer than the conflict buffer`() {
        val first = tripWindow(0, 20 * 60)
        assertTrue(first.overlaps(TripWindow(first.end + TRIP_CONFLICT_BUFFER_MILLIS - 1, first.end + 3_600_000)))
        assertFalse(first.overlaps(TripWindow(first.end + TRIP_CONFLICT_BUFFER_MILLIS, first.end + 3_600_000)))
    }

    @Test fun `overlap is symmetric`() {
        val a = tripWindow(0, 600); val b = tripWindow(15 * minute, 600); val c = tripWindow(10 * 60 * minute, 600)
        assertEquals(a.overlaps(b), b.overlaps(a)); assertEquals(a.overlaps(c), c.overlaps(a))
        assertTrue(a.overlaps(b)); assertFalse(a.overlaps(c))
    }

    @Test fun `an estimated window runs forward from the start and is longer for longer routes`() {
        val short = estimatedTripWindow(1_000, -37.9105, 145.1362, -37.9112, 145.1029)
        val long = estimatedTripWindow(1_000, -37.9105, 145.1362, -37.8216, 144.9471)
        assertEquals(1_000, short.start)
        assertTrue(short.end > short.start)
        assertTrue(long.end - long.start > short.end - short.start)
    }

    @Test fun `terminal request states cannot move and only the documented moves exist`() {
        val t = RideRequestStatus.transitions
        listOf(RideRequestStatus.REJECTED, RideRequestStatus.CANCELLED, RideRequestStatus.COMPLETED, RideRequestStatus.EXPIRED)
            .forEach { assertTrue(t.getValue(it).isEmpty(), "$it must be terminal") }
        assertEquals(setOf("ACCEPTED", "REJECTED", "CANCELLED"), t.getValue(RideRequestStatus.PENDING))
        assertEquals(setOf("COMPLETED", "CANCELLED"), t.getValue(RideRequestStatus.ACCEPTED))
        // A request can only be COMPLETED after being ACCEPTED - never straight from pending.
        assertFalse(RideRequestStatus.COMPLETED in t.getValue(RideRequestStatus.PENDING))
    }
}
