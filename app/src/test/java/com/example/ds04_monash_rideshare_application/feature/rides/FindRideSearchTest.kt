package com.example.ds04_monash_rideshare_application.feature.rides

import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import com.yandex.mapkit.geometry.Point
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FindRideSearchTest {
    private val origin = NamedLocation("Oakleigh Station", Point(-37.9002, 145.0886))
    private val destination = NamedLocation("Monash Clayton", Point(-37.9105, 145.1362))
    private val date = LocalDate.of(2030, 8, 26)
    private val time = LocalTime.of(8, 30)

    @Test
    fun `requires both route endpoints`() {
        val result = validateRideSearch(
            origin = origin,
            destination = null,
            date = date,
            time = time,
            seatsNeeded = 1,
            nowMillis = 0
        )

        assertNull(result.criteria)
        assertEquals("Choose both a pickup point and a drop-off point.", result.errorMessage)
    }

    @Test
    fun `rejects an identical pickup and drop-off`() {
        val result = validateRideSearch(
            origin = origin,
            destination = origin,
            date = date,
            time = time,
            seatsNeeded = 1,
            nowMillis = 0
        )

        assertNull(result.criteria)
        assertEquals("Pickup and drop-off points must be different.", result.errorMessage)
    }

    @Test
    fun `builds ranked search criteria from a valid form`() {
        val result = validateRideSearch(
            origin = origin,
            destination = destination,
            date = date,
            time = time,
            seatsNeeded = 2,
            nowMillis = 0
        )

        assertNotNull(result.criteria)
        val criteria = requireNotNull(result.criteria)
        assertNull(result.errorMessage)
        assertEquals(origin.point.latitude, criteria.originLat, 0.0)
        assertEquals(origin.point.longitude, criteria.originLng, 0.0)
        assertEquals(destination.point.latitude, criteria.destinationLat, 0.0)
        assertEquals(destination.point.longitude, criteria.destinationLng, 0.0)
        assertEquals(2, criteria.seatsNeeded)
    }

    @Test
    fun `rejects a past preferred time`() {
        val result = validateRideSearch(
            origin = origin,
            destination = destination,
            date = date,
            time = time,
            seatsNeeded = 1,
            nowMillis = Long.MAX_VALUE
        )

        assertNull(result.criteria)
        assertEquals("Choose a travel time in the future.", result.errorMessage)
    }
}
