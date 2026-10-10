package com.example.ds04_monash_rideshare_application.backend

// TEST-ONLY. SYNTHETIC numbers - these are NOT routing data and must never be copied into
// src/main/resources/travel-times.json. They exist so tests can exercise everything that needs "a trip
// duration" without the real (generated) matrix.
//
// Complete (every ordered pair of different approved points has a value), deterministic, positive, and
// deliberately DIRECTIONAL (A -> B generally differs from B -> A), between 10 and 21 minutes.
object SyntheticTravelTimes : TravelTimeProvider {
    private val index = APPROVED_POINTS.withIndex().associate { it.value.id to it.index }

    fun seconds(fromPointId: String, toPointId: String): Long {
        val i = index.getValue(fromPointId)
        val j = index.getValue(toPointId)
        return 600L + ((i * 7 + j * 13) % 12) * 60L
    }

    override fun travelSeconds(fromPointId: String, toPointId: String): Long? =
        if (fromPointId == toPointId) 0L else seconds(fromPointId, toPointId)
}

// What module(...) is given in tests, in place of the production matrix.
fun testTravelTimes(): TravelTimeProvider = SyntheticTravelTimes
