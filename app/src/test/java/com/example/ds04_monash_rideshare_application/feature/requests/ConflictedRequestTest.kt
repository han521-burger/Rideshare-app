package com.example.ds04_monash_rideshare_application.feature.requests

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.AuthResult
import com.example.ds04_monash_rideshare_application.Repository.Remote.FakeRideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestFunnelCounts
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestDto
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.ScheduleConflict
import com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import com.example.ds04_monash_rideshare_application.core.state.SCHEDULE_CONFLICT_TOO_SOON_AFTER_PREVIOUS
import com.example.ds04_monash_rideshare_application.feature.activity.outcomeDistribution
import com.example.ds04_monash_rideshare_application.ui.requestStatusLabel
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// CONFLICTED: a request another, stronger trip made physically impossible. The app only ever displays it - it is
// final, never actionable, and never sent back to the backend as a status.
class ConflictedRequestTest {
    private val conflict = ScheduleConflict(
        type = SCHEDULE_CONFLICT_TOO_SOON_AFTER_PREVIOUS, conflictingId = "req-1", conflictingKind = "OPEN_REQUEST",
        conflictingDepartureTime = 1_800_000_000_000L, conflictingArrivalTime = 1_800_000_600_000L,
        fromPointName = "Monash Clayton", toPointName = "Glen Waverley Station", repositioningSeconds = 1200, transferBufferSeconds = 600,
        earliestFeasibleDeparture = 1_800_002_400_000L, proposedDepartureTime = 1_800_001_000_000L, proposedArrivalTime = 1_800_002_200_000L
    )

    private fun request(id: String, status: String, responded: Long? = null, passenger: String = "other", details: ScheduleConflict? = null) =
        RideRequestSummary(id, null, passenger, 1, status, null, 1, responded, -37.9, 145.1, -37.91, 145.13, false, 1000,
            emptyList(), null, null, null, null, details)

    private class Api : RideApiService by FakeRideApiService() {
        val own = mutableListOf<RideApiService.Callback<List<RideRequestSummary>>>()
        val incoming = mutableListOf<RideApiService.Callback<List<RideRequestSummary>>>()
        override fun fetchMyRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) { own += callback }
        override fun fetchIncomingRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) { incoming += callback }
        override fun fetchMyProfile(callback: RideApiService.Callback<UserAccountSummary>) { }
        override fun signIn(email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
            callback.onSuccess(AuthResult("token-me", UserAccountSummary("me", "Me", "Me", email, null, null, true, null, null)))
        }
    }

    @After fun clearSession() { AuthSession.clear() }

    // ---- what the passenger sees ----
    @Test fun itHasItsOwnFriendlyLabelNotTheRawStatus() {
        assertEquals("Schedule conflict", requestStatusLabel(RequestStatus.CONFLICTED))
        assertEquals("Schedule conflict", requestsStatus(RequestStatus.CONFLICTED, incoming = false))
        assertEquals("Schedule conflict", requestsStatus(RequestStatus.CONFLICTED, incoming = true))
        assertEquals("the machine status is unchanged", "CONFLICTED", RequestStatus.CONFLICTED)
    }

    @Test fun theReasonIsExplainedFromTheStructuredDetails() {
        val text = conflictSupport(request("r", RequestStatus.CONFLICTED, details = conflict))!!
        assertTrue(text, text.startsWith("You already have a ride ending at Monash Clayton"))
        assertTrue(text, text.contains("Glen Waverley Station"))
    }

    @Test fun withoutDetailsThereIsStillAnExplanation() {
        assertEquals(CONFLICTED_SUPPORT, conflictSupport(request("r", RequestStatus.CONFLICTED)))
    }

    @Test fun onlyAConflictedRequestHasAConflictExplanation() {
        listOf(RequestStatus.PENDING, RequestStatus.ACCEPTED, RequestStatus.CANCELLED, RequestStatus.EXPIRED, RequestStatus.REJECTED, RequestStatus.COMPLETED)
            .forEach { assertNull(it, conflictSupport(request("r", it, details = conflict))) }
    }

    @Test fun theFinalOutcomeIsNotConfusedWithCancelledOrExpired() {
        val statuses = setOf(RequestStatus.CONFLICTED, RequestStatus.CANCELLED, RequestStatus.EXPIRED, RequestStatus.REJECTED)
        assertEquals(4, statuses.size)
        assertEquals("each ending reads differently", 4, statuses.map { requestStatusLabel(it) }.toSet().size)
    }

    // ---- where it shows up ----
    @Test fun aRecentlyConflictedRequestStaysVisibleToItsPassengerAndAnOldOneAges() {
        val api = Api(); val vm = RideshareViewModel(api)
        val now = System.currentTimeMillis()
        vm.loadMyRequests()
        api.own.single().onSuccess(listOf(
            request("pending", RequestStatus.PENDING),
            request("recent", RequestStatus.CONFLICTED, responded = now - 3_600_000, details = conflict),
            request("old", RequestStatus.CONFLICTED, responded = now - 172_800_000),
            request("accepted", RequestStatus.ACCEPTED)
        ))
        assertEquals(listOf("pending", "recent"), vm.myPendingAndRecentlyEndedRequests.map { it.requestId })
        assertEquals("a conflicted request is not still waiting", listOf("pending"), vm.myPendingRequests.map { it.requestId })
        assertEquals("and it stays in the full list", "recent", vm.myRequests.data!!.first { it.status == RequestStatus.CONFLICTED }.requestId)
    }

    @Test fun aConflictedRequestIsNeverAnActionableIncomingRequest() {
        val api = Api(); val vm = RideshareViewModel(api)
        vm.signIn("me@student.monash.edu", "password", keepSignedIn = false)
        vm.loadIncomingRequests()
        api.incoming.single().onSuccess(listOf(
            request("waiting", RequestStatus.PENDING),
            request("gone", RequestStatus.CONFLICTED).copy(rideId = "ride"),
            request("standalone-gone", RequestStatus.CONFLICTED)
        ))
        assertEquals(listOf("waiting"), vm.incomingPendingRequests.map { it.requestId })
        assertNull(vm.driverBannerRequest?.takeIf { it.requestId != "waiting" })
    }

    // ---- activity funnel ----
    @Test fun theOutcomeChartCountsConflictedRequestsSoTheTotalStaysWhole() {
        val counts = RequestFunnelCounts(1, 2, 0, 1, 3, 1, conflicted = 3)
        assertEquals(11, counts.total)
        val outcomes = outcomeDistribution(counts)
        assertEquals("Schedule conflict", outcomes.last().label)
        assertEquals(3, outcomes.last().count)
        assertEquals(1f, outcomes.sumOf { it.fraction.toDouble() }.toFloat(), .00001f)
    }

    @Test fun anOlderBackendWithNoConflictedCountStillAddsUp() {
        assertEquals(7, RequestFunnelCounts(1, 2, 0, 1, 3, 0).total)
    }

    // ---- what the backend sends ----
    @Test fun theBackendsConflictedRequestParsesIncludingItsReason() {
        val json = """{"requestId":"r1","passengerId":"p","seatsRequested":1,"status":"CONFLICTED","createdAt":1,"respondedAt":2,
            "conflict":{"type":"TOO_SOON_AFTER_PREVIOUS","conflictingId":"o1","conflictingKind":"OPEN_REQUEST",
            "conflictingDepartureTime":1800000000000,"conflictingArrivalTime":1800000600000,"fromPointName":"Monash Clayton",
            "toPointName":"Glen Waverley Station","repositioningSeconds":1200,"transferBufferSeconds":600,
            "earliestFeasibleDeparture":1800002400000,"proposedDepartureTime":1800001000000,"proposedArrivalTime":1800002200000}}"""
        val dto = Gson().fromJson(json, RideRequestDto::class.java)
        assertEquals(RequestStatus.CONFLICTED, dto.status)
        assertNotNull(dto.conflict)
        assertEquals("Glen Waverley Station", dto.conflict!!.toPointName)
    }

    @Test fun aDriversCopyHasNoReasonAndAnOlderBackendHasNone() {
        val dto = Gson().fromJson("""{"requestId":"r1","passengerId":"p","seatsRequested":1,"status":"CONFLICTED","createdAt":1,"respondedAt":2}""", RideRequestDto::class.java)
        assertNull(dto.conflict)
        assertFalse(conflictSupport(request("r1", RequestStatus.CONFLICTED))!!.isBlank())
    }
}
