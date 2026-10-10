package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.BackendException
import com.example.ds04_monash_rideshare_application.Repository.Remote.DUPLICATE_OPEN_REQUEST
import com.example.ds04_monash_rideshare_application.Repository.Remote.FakeRideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.OccurrenceOutcome
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestSubmissionResult
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.ScheduleWarning
import com.example.ds04_monash_rideshare_application.Repository.Remote.StandaloneRequestDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.WarningSubject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// When an action already tells the app that state changed, the screens that show that state refresh at once - not on some later
// poll - and exactly the lists that can have changed are reloaded (no more, so there is no extra polling). One call is counted per
// endpoint; each action is checked for what it must refresh and what it must leave alone.
class RefreshAfterActionTest {
    private class CountingApi : RideApiService by FakeRideApiService() {
        val calls = mutableMapOf<String, Int>()
        fun count(name: String) = calls[name] ?: 0
        private fun hit(name: String) { calls[name] = count(name) + 1 }
        var failure: Exception? = null
        override fun signIn(email: String, password: String, callback: RideApiService.Callback<com.example.ds04_monash_rideshare_application.Repository.Remote.AuthResult>) {
            val user = com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary(email, email, email, email, null, null, true, null, null)
            callback.onSuccess(com.example.ds04_monash_rideshare_application.Repository.Remote.AuthResult("token-$email", user))
        }
        override fun fetchMyRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) { hit("myRequests"); callback.onSuccess(emptyList()) }
        override fun fetchIncomingRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) { hit("incoming"); callback.onSuccess(emptyList()) }
        override fun fetchMyDriverRides(callback: RideApiService.Callback<List<RideSummary>>) { hit("driverRides"); callback.onSuccess(emptyList()) }
        override fun fetchScheduleWarnings(callback: RideApiService.Callback<List<ScheduleWarning>>) { hit("warnings"); callback.onSuccess(emptyList()) }
        override fun fetchTripHistory(callback: RideApiService.Callback<List<RideSummary>>) { hit("history"); callback.onSuccess(emptyList()) }
        override fun fetchRide(rideId: String, callback: RideApiService.Callback<RideSummary>) { hit("ride"); callback.onSuccess(ride(rideId)) }
        override fun completeRide(rideId: String, callback: RideApiService.Callback<RideSummary>) {
            hit("complete"); failure?.let(callback::onFailure) ?: callback.onSuccess(ride(rideId, "COMPLETED"))
        }
        override fun respondToRequest(
            requestId: String, newStatus: String, targetRideId: String?, newRideSeatsTotal: Int?,
            cancelMyConflictingRequests: Boolean, callback: RideApiService.Callback<RideRequestSummary>
        ) { hit("respond"); failure?.let(callback::onFailure) ?: callback.onSuccess(request(requestId, newStatus)) }
        override fun createStandaloneRequest(draft: StandaloneRequestDraft, callback: RideApiService.Callback<RequestSubmissionResult>) {
            hit("create")
            failure?.let(callback::onFailure) ?: callback.onSuccess(
                RequestSubmissionResult(1, 1, 0, listOf(request("req-new", "PENDING")), listOf(OccurrenceOutcome(1000L, true, null, "req-new", null, null, null, null)))
            )
        }
        override fun fetchDashboard(callback: RideApiService.Callback<com.example.ds04_monash_rideshare_application.Repository.Remote.DashboardSummary>) {
            hit("dashboard"); callback.onSuccess(com.example.ds04_monash_rideshare_application.Repository.Remote.DashboardSummary(0, 0.0, 0.0, emptyList()))
        }
    }

    private val api = CountingApi()
    private val vm = RideshareViewModel(api)
    @After fun clearSession() { AuthSession.clear() }

    private val draft = StandaloneRequestDraft(-37.9112, 145.1029, -37.9105, 145.1362, false, 1_900_000_000_000L, 1, null, emptyList(), null, null)

    @Test fun aNewRequestRefreshesMyRequestsAndWarningsAndNothingElse() {
        vm.createStandaloneRequest(draft) { _, _ -> }
        assertEquals(1, api.count("myRequests")); assertEquals(1, api.count("warnings"))
        assertEquals(0, api.count("incoming")); assertEquals(0, api.count("driverRides")); assertEquals(0, api.count("history"))
    }

    @Test fun aDuplicateAnswerBringsTheExistingRequestOnScreenAtOnce() {
        api.failure = BackendException("You already have a live request for this journey.", DUPLICATE_OPEN_REQUEST, existingRequestId = "req-old")
        var failure: RequestSubmitError? = null
        vm.createStandaloneRequest(draft) { _, error -> failure = error }
        assertNotNull(failure); assertEquals(true, failure?.isDuplicate)
        assertEquals(1, api.count("myRequests"))
    }

    @Test fun anOrdinaryCreateFailureRefreshesNothing() {
        api.failure = BackendException("Can't reach the server.")
        vm.createStandaloneRequest(draft) { _, _ -> }
        assertEquals(0, api.count("myRequests")); assertEquals(0, api.count("warnings"))
    }

    @Test fun anAcceptRefreshesTheInboxMyRequestsAndTheDriversRides() {
        vm.respondToRequest("req-1", RequestStatus.ACCEPTED)
        assertEquals(1, api.count("incoming")); assertEquals(1, api.count("myRequests")); assertEquals(1, api.count("driverRides"))
        assertEquals("accepting reloads the warnings with the requests and the rides", 2, api.count("warnings"))
        assertEquals(0, api.count("history"))
    }

    @Test fun aCancellationRefreshesTheSameListsSoItsWarningDisappears() {
        vm.respondToRequest("req-1", RequestStatus.CANCELLED)
        assertEquals(1, api.count("incoming")); assertEquals(1, api.count("myRequests")); assertEquals(1, api.count("driverRides"))
        assertEquals(2, api.count("warnings"))
    }

    @Test fun aDeclineDoesNotReloadTheRide() {
        vm.respondToRequest("req-1", RequestStatus.REJECTED)
        assertEquals(1, api.count("incoming")); assertEquals(1, api.count("myRequests")); assertEquals(0, api.count("driverRides"))
    }

    @Test fun completingARideRefreshesEverythingItCanChange() {
        vm.signIn("driver@student.monash.edu", "password", keepSignedIn = false)
        vm.selectedRideId = "ride-1"
        vm.completeRide("ride-1")
        // Requests (this person's own may now be CONFLICTED), the driver's rides, the warnings, the trip history and the
        // dashboard - and not the ride itself again: the completion answer already carries it.
        assertEquals(1, api.count("incoming")); assertEquals(1, api.count("myRequests")); assertEquals(1, api.count("driverRides"))
        assertEquals(2, api.count("warnings")); assertEquals(1, api.count("history")); assertEquals(1, api.count("dashboard"))
        assertEquals(0, api.count("ride"))
        assertEquals("COMPLETED", vm.rideDetails.data?.status)
    }

    @Test fun aRefusedCompletionShowsTheReasonAndReloadsTheRideNotTheWholeApp() {
        api.failure = BackendException("This ride hasn't started yet, so it can't be completed.", "RIDE_NOT_STARTED")
        vm.selectedRideId = "ride-1"
        vm.completeRide("ride-1")
        assertEquals("This ride hasn't started yet, so it can't be completed.", vm.requestActionError)
        assertEquals(1, api.count("ride")); assertEquals(0, api.count("history")); assertEquals(0, api.count("myRequests"))
        assertEquals(false, vm.actionInFlight)
    }

    @Test fun anAcceptedRequestWithAWarningAndAConflictedOneAreDistinctInWhatTheScreenGets() {
        // The warning is looked up by the request's id; a conflicted request has none and shows its own conflict text instead.
        val warning = ScheduleWarning("w", "PREVIOUS_RIDE_FINISHED_LATE", WarningSubject.REQUEST, "req-accepted", 1L, "ride-a", 2_000L, null,
            "Monash Caulfield", "Monash Caulfield", 0, 600, 3_000L, 2_500L)
        assertNotNull(scheduleWarningFor(listOf(warning), WarningSubject.REQUEST, "req-accepted"))
        assertNull(scheduleWarningFor(listOf(warning), WarningSubject.REQUEST, "req-conflicted"))
    }

    // Which warning Ride details shows, for whom.
    private val ride = ScheduleWarning("w-ride", "PREVIOUS_RIDE_FINISHED_LATE", WarningSubject.RIDE, "ride-9", 1L, "ride-a", 2_000L, null, null, null, 0, 600, 3_000L, 2_500L)
    private val seat = ScheduleWarning("w-seat", "PREVIOUS_RIDE_FINISHED_LATE", WarningSubject.REQUEST, "req-5", 1L, "ride-a", 2_000L, null, null, null, 0, 600, 3_000L, 2_500L)

    @Test fun theDriverSeesTheWarningAboutTheirOwnRide() {
        assertNotNull(rideDetailScheduleWarning(listOf(ride, seat), "ride-9", rideDriverId = "me", userId = "me", myRequestIdForRide = null))
    }

    @Test fun aPassengerSeesOnlyTheWarningAboutTheirOwnSeatNeverTheRidesDriverWarning() {
        // Not the driver: the ride-level warning (the driver's) is never looked up, even if it were in the list.
        assertNull(rideDetailScheduleWarning(listOf(ride), "ride-9", rideDriverId = "driver", userId = "me", myRequestIdForRide = null))
        assertNotNull(rideDetailScheduleWarning(listOf(ride, seat), "ride-9", rideDriverId = "driver", userId = "me", myRequestIdForRide = "req-5"))
        // ... and someone else's seat id gives nothing.
        assertNull(rideDetailScheduleWarning(listOf(seat), "ride-9", rideDriverId = "driver", userId = "me", myRequestIdForRide = "req-other"))
    }

    @Test fun signedOutOrNoWarningsMeansNothing() {
        assertNull(rideDetailScheduleWarning(emptyList(), "ride-9", "me", "me", null))
        assertNull(rideDetailScheduleWarning(listOf(ride), "ride-9", "me", null, null))
    }
}

private fun ride(id: String, status: String = "CONFIRMED") = RideSummary(
    id, "driver", null, -37.9112, 145.1029, -37.9105, 145.1362, 4000.0, 600L, 1_900_000_000_000L,
    3, 3, null, status, 0L, false, null, emptyList(), null, null, null
)

private fun request(id: String, status: String) = RideRequestSummary(
    id, "ride-1", "p", 1, status, null, 0L, null, null, null, null, null, false, null, emptyList(), null, null, null, null
)
