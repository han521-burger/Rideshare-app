package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.matching.MatchingEngine
import com.example.ds04_monash_rideshare_application.backend.matching.RideCandidate
import com.example.ds04_monash_rideshare_application.backend.matching.SearchCriteria
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchingEngineTest {
    private val search = SearchCriteria(0.0, 0.0, 0.0, 1.0, 1_800_000_000_000L, 2)

    // Parallel eastbound route, same departure, but pickup is over 11 km away.
    // Even a capacity-eligible candidate must fail normal ranking's 3 km gate.
    private fun distantRide(seats: Int) = RideCandidate(
        "capacity-$seats", 0.1, 0.0, 0.1, 1.0, search.desiredDepartureTime, seats
    )

    private fun fallbackIds(seatsNeeded: Int, seatsAvailable: Int): List<String> {
        val criteria = search.copy(seatsNeeded = seatsNeeded)
        val ride = distantRide(seatsAvailable)
        assertTrue(MatchingEngine.rank(criteria, listOf(ride)).isEmpty())
        return MatchingEngine.fallback(criteria, listOf(ride)).map { it.ride.rideId }
    }

    @Test
    fun `full ride is excluded from fallback`() {
        assertEquals(emptyList(), fallbackIds(1, 0))
    }

    @Test
    fun `insufficient capacity is excluded from fallback`() {
        assertEquals(emptyList(), fallbackIds(2, 1))
    }

    @Test
    fun `exact capacity remains eligible for fallback`() {
        assertEquals(listOf("capacity-2"), fallbackIds(2, 2))
    }

    @Test
    fun `extra capacity remains eligible for fallback`() {
        assertEquals(listOf("capacity-3"), fallbackIds(2, 3))
    }

    @Test
    fun `every fallback result has sufficient capacity across seat counts`() {
        val candidates = (0..8).map(::distantRide)
        for (needed in 1..8) {
            val criteria = search.copy(seatsNeeded = needed)
            assertTrue(MatchingEngine.rank(criteria, candidates).isEmpty())
            val results = MatchingEngine.fallback(criteria, candidates)
            assertEquals((needed..8).map { "capacity-$it" }, results.map { it.ride.rideId })
            assertTrue(results.all { it.ride.seatsAvailable >= needed })
            assertTrue(results.all { it.score == MatchScoreDto(0.0, 0.0, 0.0, 0.0, 0.0) })
        }
    }

    @Test
    fun `normal ranking preserves capacity scoring and sorting`() {
        val exact = distantRide(2).copy(originLat = 0.0, destinationLat = 0.0)
        val later = exact.copy(rideId = "later", departureTime = search.desiredDepartureTime + 30 * 60_000)
        val full = exact.copy(rideId = "full", seatsAvailable = 0)
        val insufficient = exact.copy(rideId = "insufficient", seatsAvailable = 1)
        val results = MatchingEngine.rank(search, listOf(later, full, insufficient, exact))
        assertEquals(listOf("capacity-2", "later"), results.map { it.ride.rideId })
        assertEquals(MatchScoreDto(100.0, 100.0, 100.0, 100.0, 100.0), results.first().score)
    }

    @Test
    fun `fallback preserves direction date and time gates`() {
        val ride = distantRide(2)
        val incompatible = listOf(
            ride.copy(originLng = 1.0, destinationLng = 0.0),
            ride.copy(departureTime = search.desiredDepartureTime + 46 * 60_000),
            ride.copy(departureTime = search.desiredDepartureTime + 24 * 60 * 60_000)
        )
        assertTrue(MatchingEngine.fallback(search, incompatible).isEmpty())
        assertEquals(listOf(ride), MatchingEngine.fallback(search, listOf(ride)).map { it.ride })
    }

    @Test
    fun `nonpositive requested seats return no matches in either path`() {
        for (needed in listOf(Int.MIN_VALUE, -1, 0)) {
            val criteria = search.copy(seatsNeeded = needed)
            val rides = (0..3).map { distantRide(it).copy(originLat = 0.0, destinationLat = 0.0) }
            assertTrue(MatchingEngine.rank(criteria, rides).isEmpty())
            assertTrue(MatchingEngine.fallback(criteria, rides).isEmpty())
        }
    }
}
