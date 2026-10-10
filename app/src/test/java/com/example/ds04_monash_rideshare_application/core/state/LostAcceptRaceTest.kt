package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.BackendException
import com.example.ds04_monash_rideshare_application.Repository.Remote.FakeRideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.REQUEST_ALREADY_ACCEPTED
import com.example.ds04_monash_rideshare_application.Repository.Remote.REQUEST_UNAVAILABLE
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Two drivers press Accept on the same open request: one wins, the other is told it has gone. The loser's app shows the
// backend's plain answer AND drops the request from the inbox straight away.
class LostAcceptRaceTest {
    private class Api : RideApiService by FakeRideApiService() {
        var failure: Exception? = null
        var incomingFetches = 0
        override fun respondToRequest(
            requestId: String, newStatus: String, targetRideId: String?, newRideSeatsTotal: Int?,
            cancelMyConflictingRequests: Boolean, callback: RideApiService.Callback<RideRequestSummary>
        ) { callback.onFailure(failure!!) }
        override fun fetchIncomingRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) {
            incomingFetches++
            callback.onSuccess(emptyList())
        }
    }

    private val api = Api()
    private val vm = RideshareViewModel(api)

    @After fun clearSession() { AuthSession.clear() }

    private fun accept(status: String = RequestStatus.ACCEPTED): String? {
        var message: String? = null
        vm.respondToRequest("req-1", status) { _, m -> message = m }
        return message
    }

    @Test fun aDriverWhoLostTheRaceIsToldSoAndTheInboxRefreshesAtOnce() {
        api.failure = BackendException("This request has already been accepted by another driver.", REQUEST_ALREADY_ACCEPTED)
        val before = api.incomingFetches
        assertEquals("This request has already been accepted by another driver.", accept())
        assertEquals("This request has already been accepted by another driver.", vm.requestActionError)
        assertEquals(before + 1, api.incomingFetches)
        assertEquals(false, vm.actionInFlight)
    }

    @Test fun aRequestThatEndedSomeOtherWayAlsoRefreshesTheInbox() {
        api.failure = BackendException("This request is no longer available.", REQUEST_UNAVAILABLE)
        val before = api.incomingFetches
        accept()
        assertEquals(before + 1, api.incomingFetches)
    }

    @Test fun anOrdinaryFailureDoesNotRefreshAnything() {
        api.failure = BackendException("Can't reach the server.")
        val before = api.incomingFetches
        accept()
        assertEquals(before, api.incomingFetches)
    }

    @Test fun onlyAnAcceptRefreshesNotADecline() {
        api.failure = BackendException("This request is no longer available.", REQUEST_UNAVAILABLE)
        val before = api.incomingFetches
        accept(RequestStatus.REJECTED)
        assertEquals(before, api.incomingFetches)
        assertNull(vm.acceptConflict)
    }
}
