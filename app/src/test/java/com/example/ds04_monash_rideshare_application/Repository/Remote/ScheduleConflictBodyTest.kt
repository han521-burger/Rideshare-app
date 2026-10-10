package com.example.ds04_monash_rideshare_application.Repository.Remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// The backend's SCHEDULE_INFEASIBLE answer carries a structured "conflict" object; the app must read it as data.
class ScheduleConflictBodyTest {
    private fun error(json: String) = NetworkErrorMapper.fromErrorBody(409, json.toResponseBody("application/json".toMediaType()))

    private val body = """{"error":"This doesn't fit your schedule","code":"SCHEDULE_INFEASIBLE","existingRequestId":null,
        "conflict":{"type":"TOO_SOON_AFTER_PREVIOUS","conflictingId":"req-1","conflictingKind":"OPEN_REQUEST",
        "conflictingDepartureTime":1800000000000,"conflictingArrivalTime":1800001800000,
        "fromPointId":"monash-clayton","fromPointName":"Monash Clayton","toPointId":"clayton-station","toPointName":"Clayton Station",
        "repositioningSeconds":1200,"transferBufferSeconds":600,"earliestFeasibleDeparture":1800003600000,
        "proposedDepartureTime":1800002700000,"proposedArrivalTime":1800004500000}}"""

    @Test fun theConflictDetailsAreParsedIntoValues() {
        val failure = error(body)
        assertEquals(SCHEDULE_INFEASIBLE, failure.code)
        val c = failure.scheduleConflict!!
        assertEquals("TOO_SOON_AFTER_PREVIOUS", c.type)
        assertEquals("req-1", c.conflictingId)
        assertEquals("Monash Clayton", c.fromPointName); assertEquals("Clayton Station", c.toPointName)
        assertEquals(1200L, c.repositioningSeconds); assertEquals(600L, c.transferBufferSeconds)
        assertEquals(1800003600000L, c.earliestFeasibleDeparture)
        assertNull(c.latestFeasibleDeparture)
        assertEquals(1800002700000L, c.proposedDepartureTime)
    }

    @Test fun aDuplicateOrOrdinaryErrorHasNoConflictDetails() {
        assertNull(error("""{"error":"x","code":"DUPLICATE_OPEN_REQUEST","existingRequestId":"req-9"}""").scheduleConflict)
        assertNull(error("""{"error":"Not enough seats"}""").scheduleConflict)
    }

    @Test fun anOlderBackendThatSendsNoConflictFieldStillParses() {
        val failure = error("""{"error":"This doesn't fit your schedule","code":"SCHEDULE_INFEASIBLE"}""")
        assertEquals(SCHEDULE_INFEASIBLE, failure.code)
        assertNull(failure.scheduleConflict)
        assertNotNull(failure.message)
    }
}
