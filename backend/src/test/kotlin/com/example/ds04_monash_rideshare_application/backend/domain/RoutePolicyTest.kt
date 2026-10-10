package com.example.ds04_monash_rideshare_application.backend.domain

import com.example.ds04_monash_rideshare_application.backend.ApprovedPoint
import com.example.ds04_monash_rideshare_application.backend.CODE_CAMPUS_ENDPOINT_REQUIRED
import com.example.ds04_monash_rideshare_application.backend.CODE_SAME_ENDPOINT
import com.example.ds04_monash_rideshare_application.backend.CODE_UNAPPROVED_LOCATION
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.approvedPointById
import com.example.ds04_monash_rideshare_application.backend.requireApprovedRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// The production endpoint rule (RoutePolicy.kt): both ends approved, one a campus, the two different.
class RoutePolicyTest {
    private fun p(id: String) = approvedPointById(id)!!
    private val campus = p("monash-clayton")
    private val otherCampus = p("monash-caulfield")
    private val hub = p("huntingdale-station")
    private val otherHub = p("frankston-station")
    // Not an approved point: a street corner in Clayton (~1 km from the campus).
    private val nowhere = -37.9205 to 145.1362

    private fun route(from: Pair<Double, Double>, to: Pair<Double, Double>) = requireApprovedRoute(from.first, from.second, to.first, to.second)
    private fun coord(point: ApprovedPoint) = point.lat to point.lng

    private fun rejected(from: Pair<Double, Double>, to: Pair<Double, Double>): ValidationException =
        assertFailsWith<ValidationException> { route(from, to) }

    // --- approved / unapproved combinations ---

    @Test fun approvedToApprovedWithACampusIsAccepted() {
        val result = route(coord(hub), coord(campus))
        assertEquals("huntingdale-station", result.origin.id)
        assertEquals("monash-clayton", result.destination.id)
        route(coord(campus), coord(hub)) // either way round
    }

    @Test fun approvedToUnapprovedIsRejected() {
        val error = rejected(coord(campus), nowhere)
        assertEquals(CODE_UNAPPROVED_LOCATION, error.code)
        assertTrue(error.message!!.contains("drop-off"), error.message)
    }

    @Test fun unapprovedToApprovedIsRejected() {
        val error = rejected(nowhere, coord(campus))
        assertEquals(CODE_UNAPPROVED_LOCATION, error.code)
        assertTrue(error.message!!.contains("pickup"), error.message)
    }

    @Test fun unapprovedToUnapprovedIsRejected() {
        val error = rejected(nowhere, nowhere.first - 0.02 to nowhere.second)
        assertEquals(CODE_UNAPPROVED_LOCATION, error.code)
    }

    // --- the campus requirement and the distinct-ends requirement ---

    @Test fun campusToCampusIsAccepted() {
        route(coord(campus), coord(otherCampus))
    }

    @Test fun hubToHubIsRejectedBecauseNeitherEndIsACampus() {
        val error = rejected(coord(hub), coord(otherHub))
        assertEquals(CODE_CAMPUS_ENDPOINT_REQUIRED, error.code)
    }

    @Test fun theSamePlaceAtBothEndsIsRejected() {
        assertEquals(CODE_SAME_ENDPOINT, rejected(coord(campus), coord(campus)).code)
        assertEquals(CODE_SAME_ENDPOINT, rejected(coord(hub), coord(hub)).code)
    }

    @Test fun unapprovedIsReportedBeforeTheOtherRules() {
        // Hub + unapproved would also lack a campus, but the more fundamental problem is reported first.
        assertEquals(CODE_UNAPPROVED_LOCATION, rejected(coord(hub), nowhere).code)
    }

    @Test fun aHubNextToACampusIsStillAHubNotACampus() {
        // Caulfield Station is ~210 m from Monash Caulfield: the old "within 300 m of a campus" rule let a hub-to-hub
        // trip through here; identity-based approval does not.
        val station = p("caulfield-station")
        assertEquals(CODE_CAMPUS_ENDPOINT_REQUIRED, rejected(coord(station), coord(hub)).code)
        route(coord(station), coord(otherCampus))
    }

    @Test fun smallCoordinateNoiseAroundAnApprovedPointIsAbsorbed() {
        val noisy = campus.lat + 0.00003 to campus.lng - 0.00002
        assertEquals("monash-clayton", route(coord(hub), noisy).destination.id)
    }
}
