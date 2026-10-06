package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.AuthResult
import com.example.ds04_monash_rideshare_application.Repository.Remote.FakeRideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A swiped-away driver banner must stay gone on later inbox refreshes (it used to come back
// after opening any non-tab screen, because the dismissal lived in the tab shell).
class DriverBannerDismissalTest {
    private class Api : RideApiService by FakeRideApiService() {
        var inbox: List<RideRequestSummary> = emptyList()
        override fun signIn(email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
            callback.onSuccess(AuthResult("token", UserAccountSummary("driver-1", "Ben", "Ben", email, null, null, true, null, null)))
        }
        override fun fetchIncomingRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) = callback.onSuccess(inbox)
    }

    private fun request(id: String, createdAt: Long, status: String = RequestStatus.PENDING) = RideRequestSummary(
        requestId = id, rideId = null, passengerId = "passenger-$id", seatsRequested = 1, status = status,
        note = null, createdAt = createdAt, respondedAt = null, originLat = null, originLng = null,
        destinationLat = null, destinationLng = null, isAsap = true, desiredDepartureTime = null,
        repeatDaysOfWeek = emptyList(), repeatWeeks = null, ride = null, passenger = null, matchScoreTotal = null
    )

    private val api = Api()
    private val vm = RideshareViewModel(api).apply { signIn("ben@student.monash.edu", "pw", keepSignedIn = false) }

    @After fun clear() = AuthSession.clear()

    @Test fun `swiping clears the banner for everything waiting, until a new request arrives`() {
        api.inbox = listOf(request("old", 1), request("new", 2))
        vm.loadIncomingRequests()
        assertEquals("new", vm.driverBannerRequest?.requestId)

        // The older waiting request must not pop up in its place.
        vm.dismissDriverBanner("new")
        assertNull(vm.driverBannerRequest)

        vm.loadIncomingRequests()
        assertNull(vm.driverBannerRequest)
        // Still listed for the Requests tab - dismissing only hides the banner.
        assertEquals(2, vm.incomingPendingRequests.size)

        api.inbox = listOf(request("old", 1), request("new", 2), request("newer", 3))
        vm.loadIncomingRequests()
        assertEquals("newer", vm.driverBannerRequest?.requestId)
    }

    @Test fun `dismissals for requests that are no longer pending are forgotten`() {
        api.inbox = listOf(request("a", 1))
        vm.loadIncomingRequests()
        vm.dismissDriverBanner("a")

        api.inbox = listOf(request("a", 1, RequestStatus.EXPIRED))
        vm.loadIncomingRequests()
        assertEquals(emptySet<String>(), vm.dismissedBannerRequestIds)
    }
}
