package com.example.ds04_monash_rideshare_application.core.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Auth.AuthenticatedUser
import com.example.ds04_monash_rideshare_application.Auth.AuthenticationProvider
import com.example.ds04_monash_rideshare_application.Auth.FakeAuthenticationProvider
import com.example.ds04_monash_rideshare_application.Auth.TestIdentity
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateRideDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.DashboardSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.FavoriteDriverSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.LeaderboardResult
import com.example.ds04_monash_rideshare_application.Repository.Remote.MessageSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RatingSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiServiceImpl
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSearchCriteria
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.StandaloneRequestDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary

enum class AppMode { PASSENGER, DRIVER }

data class RideshareUiState(
    val isSigningIn: Boolean = false,
    val userId: String? = null,
    val email: String = "",
    val displayName: String = "",
    val preferredName: String = "",
    val passengerEnabled: Boolean = true,
    val errorMessage: String? = null
) {
    val isSignedIn: Boolean
        get() = userId != null

    val greetingName: String
        get() = preferredName.ifBlank { displayName.ifBlank { "Monash traveller" } }
}

// Generic loading/data/error holder for the network-backed state this ViewModel exposes.
data class Resource<T>(
    val isLoading: Boolean = false,
    val data: T? = null,
    val errorMessage: String? = null
)

class RideshareViewModel : ViewModel() {
    private val authenticationProvider: AuthenticationProvider = FakeAuthenticationProvider()
    private val apiService: RideApiService = RideApiServiceImpl()

    var uiState by mutableStateOf(RideshareUiState())
        private set

    // In-app toggle (Passenger teal / Driver amber) - not a login-time choice, and doesn't
    // partition data: a single account can be driving one ride and riding another at once.
    var activeMode by mutableStateOf(AppMode.PASSENGER)
        private set

    var selectedRideId by mutableStateOf<String?>(null)
    var selectedRequestId by mutableStateOf<String?>(null)

    var profileDetails by mutableStateOf(Resource<UserAccountSummary>())
        private set
    var myDriverRides by mutableStateOf(Resource<List<RideSummary>>())
        private set
    var myRequests by mutableStateOf(Resource<List<RideRequestSummary>>())
        private set
    var incomingRequests by mutableStateOf(Resource<List<RideRequestSummary>>())
        private set
    var openRides by mutableStateOf(Resource<List<RideSummary>>())
        private set
    var searchResults by mutableStateOf(Resource<List<RideSummary>>())
        private set
    var lastSearchCriteria by mutableStateOf<RideSearchCriteria?>(null)
        private set
    var rideDetails by mutableStateOf(Resource<RideSummary>())
        private set
    var messages by mutableStateOf(Resource<List<MessageSummary>>())
        private set
    var tripHistory by mutableStateOf(Resource<List<RideSummary>>())
        private set
    var dashboard by mutableStateOf(Resource<DashboardSummary>())
        private set
    var leaderboard by mutableStateOf(Resource<LeaderboardResult>())
        private set
    var ratings by mutableStateOf(Resource<List<RatingSummary>>())
        private set
    var favorites by mutableStateOf(Resource<List<FavoriteDriverSummary>>())
        private set
    var actionInFlight by mutableStateOf(false)
        private set
    var requestActionError by mutableStateOf<String?>(null)
        private set
    var lastCreatedRide by mutableStateOf<RideSummary?>(null)
        private set
    private var incomingRequestRefreshInFlight = false

    // Rides upcoming, already locked in - driving (CONFIRMED) or riding (your request ACCEPTED).
    // Computed client-side from data already loaded for Home/Requests rather than a dedicated
    // endpoint - there's no "confirmed" concept the backend needs to track separately.
    val confirmedRides: List<RideSummary>
        get() {
            val driving = myDriverRides.data.orEmpty().filter { it.status == RideStatus.CONFIRMED }
            val riding = myRequests.data.orEmpty()
                .filter { it.status == RequestStatus.ACCEPTED }
                .mapNotNull { it.ride }
                .filter { it.status == RideStatus.CONFIRMED }
            return (driving + riding).distinctBy { it.rideId }.sortedBy { it.departureTime }
        }

    val myPendingRequests: List<RideRequestSummary>
        get() = myRequests.data.orEmpty().filter { it.status == RequestStatus.PENDING }

    val incomingPendingRequests: List<RideRequestSummary>
        get() = incomingRequests.data.orEmpty().filter { it.status == RequestStatus.PENDING }

    fun updateActiveMode(mode: AppMode) {
        activeMode = mode
    }

    fun signIn(email: String? = null, onSuccess: () -> Unit = {}) {
        if (uiState.isSigningIn) return
        uiState = uiState.copy(isSigningIn = true, errorMessage = null)
        authenticationProvider.login(object : AuthenticationProvider.AuthCallback {
            override fun onSuccess(user: AuthenticatedUser) {
                applySignedInUser(user, email)
                onSuccess()
            }

            override fun onFailure(error: Exception) {
                uiState = uiState.copy(isSigningIn = false, errorMessage = error.message ?: "Sign in was unsuccessful. Please try again.")
            }
        })
    }

    // Trial-only: signs in directly as one of the other seeded test accounts (backend/Database.kt),
    // bypassing FakeAuthenticationProvider (which only ever returns fake-user-001). Without this
    // there's no way to test both sides of a request/accept/message flow.
    fun signInAsTestUser(identity: TestIdentity, onSuccess: () -> Unit = {}) {
        if (uiState.isSigningIn) return
        val user = AuthenticatedUser(identity.userId, identity.externalSubjectId, identity.email, identity.displayName)
        applySignedInUser(user, identity.email)
        onSuccess()
    }

    private fun applySignedInUser(user: AuthenticatedUser, emailOverride: String?) {
        AuthSession.currentUserId = user.userId
        uiState = uiState.copy(
            isSigningIn = false,
            userId = user.userId,
            email = emailOverride?.trim()?.takeIf { it.isNotBlank() } ?: user.emailAccount,
            displayName = user.displayName,
            preferredName = user.displayName
        )
        loadProfile()
    }

    fun signOut(onComplete: () -> Unit = {}) {
        authenticationProvider.logout(object : AuthenticationProvider.AuthCallback {
            override fun onSuccess(user: AuthenticatedUser?) {
                AuthSession.currentUserId = null
                uiState = RideshareUiState()
                activeMode = AppMode.PASSENGER
                profileDetails = Resource(); myDriverRides = Resource(); myRequests = Resource()
                incomingRequests = Resource(); openRides = Resource(); searchResults = Resource()
                lastSearchCriteria = null
                rideDetails = Resource(); messages = Resource(); tripHistory = Resource()
                dashboard = Resource(); leaderboard = Resource(); ratings = Resource(); favorites = Resource()
                selectedRideId = null; selectedRequestId = null; requestActionError = null
                actionInFlight = false; incomingRequestRefreshInFlight = false; lastCreatedRide = null
                onComplete()
            }

            override fun onFailure(error: Exception) {
                uiState = uiState.copy(errorMessage = error.message ?: "Sign out was unsuccessful. Please try again.")
            }
        })
    }

    fun loadProfile() {
        profileDetails = Resource(isLoading = true)
        apiService.fetchMyProfile(object : RideApiService.Callback<UserAccountSummary> {
            override fun onSuccess(result: UserAccountSummary) {
                profileDetails = Resource(data = result)
                uiState = uiState.copy(
                    displayName = result.displayName,
                    preferredName = result.preferredName,
                    passengerEnabled = result.passengerEnabled
                )
            }

            override fun onFailure(error: Exception) {
                profileDetails = Resource(errorMessage = error.message)
            }
        })
    }

    fun updateProfile(
        displayName: String,
        preferredName: String,
        passengerEnabled: Boolean,
        emergencyContactName: String?,
        emergencyContactPhone: String?,
        onDone: (String?) -> Unit = {}
    ) {
        apiService.updateProfile(displayName, preferredName, passengerEnabled, emergencyContactName, emergencyContactPhone,
            object : RideApiService.Callback<UserAccountSummary> {
                override fun onSuccess(result: UserAccountSummary) {
                    profileDetails = Resource(data = result)
                    uiState = uiState.copy(displayName = result.displayName, preferredName = result.preferredName, passengerEnabled = result.passengerEnabled)
                    onDone(null)
                }

                override fun onFailure(error: Exception) {
                    onDone(error.message ?: "Couldn't save your profile. Please try again.")
                }
            })
    }

    fun loadOpenRides() {
        openRides = Resource(isLoading = true)
        apiService.fetchOpenRides(object : RideApiService.Callback<List<RideSummary>> {
            override fun onSuccess(result: List<RideSummary>) { openRides = Resource(data = result) }
            override fun onFailure(error: Exception) { openRides = Resource(errorMessage = error.message) }
        })
    }

    fun searchRides(criteria: RideSearchCriteria) {
        lastSearchCriteria = criteria
        searchResults = Resource(isLoading = true)
        apiService.searchRides(criteria, object : RideApiService.Callback<List<RideSummary>> {
            override fun onSuccess(result: List<RideSummary>) { searchResults = Resource(data = result) }
            override fun onFailure(error: Exception) { searchResults = Resource(errorMessage = error.message) }
        })
    }

    fun loadRideDetails(rideId: String) {
        rideDetails = Resource(isLoading = true)
        apiService.fetchRide(rideId, object : RideApiService.Callback<RideSummary> {
            override fun onSuccess(result: RideSummary) { rideDetails = Resource(data = result) }
            override fun onFailure(error: Exception) { rideDetails = Resource(errorMessage = error.message) }
        })
    }

    fun createRide(draft: CreateRideDraft, onSuccess: (RideSummary) -> Unit, onError: (String) -> Unit) {
        actionInFlight = true
        apiService.createRide(draft, object : RideApiService.Callback<RideSummary> {
            override fun onSuccess(result: RideSummary) {
                actionInFlight = false
                lastCreatedRide = result
                loadMyDriverRides()
                onSuccess(result)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                onError(error.message ?: "Couldn't publish the ride. Please try again.")
            }
        })
    }

    fun cancelRide(rideId: String, onDone: (String?) -> Unit = {}) {
        actionInFlight = true
        apiService.cancelRide(rideId, object : RideApiService.Callback<RideSummary> {
            override fun onSuccess(result: RideSummary) {
                actionInFlight = false
                rideDetails = Resource(data = result)
                loadMyDriverRides()
                onDone(null)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                onDone(error.message ?: "Couldn't cancel the ride. Please try again.")
            }
        })
    }

    fun completeRide(rideId: String) {
        if (actionInFlight) return
        actionInFlight = true
        requestActionError = null
        apiService.completeRide(rideId, object : RideApiService.Callback<RideSummary> {
            override fun onSuccess(result: RideSummary) {
                actionInFlight = false
                if (rideDetails.data?.rideId == rideId) rideDetails = Resource(data = result)
                loadMyDriverRides()
                loadIncomingRequests()
                loadMyRequests()
                loadTripHistory()
                loadDashboard()
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                requestActionError = error.message ?: "Couldn't complete the ride. Please try again."
                // Refresh stale terminal state (for example a 409) without discarding the
                // current detail on a second network failure or hiding the action error.
                apiService.fetchRide(rideId, object : RideApiService.Callback<RideSummary> {
                    override fun onSuccess(result: RideSummary) {
                        if (rideDetails.data?.rideId == rideId) rideDetails = Resource(data = result)
                    }
                    override fun onFailure(error: Exception) = Unit
                })
            }
        })
    }

    // "I'm coming" - a status flag, not live GPS (docs/RTM.md / backend Tables.kt).
    fun setDriverEnRoute(rideId: String, enRoute: Boolean, onDone: (String?) -> Unit = {}) {
        apiService.setDriverEnRoute(rideId, enRoute, object : RideApiService.Callback<RideSummary> {
            override fun onSuccess(result: RideSummary) {
                rideDetails = Resource(data = result)
                loadMyDriverRides()
                onDone(null)
            }

            override fun onFailure(error: Exception) {
                onDone(error.message ?: "Couldn't update status. Please try again.")
            }
        })
    }

    fun loadMyDriverRides() {
        myDriverRides = Resource(isLoading = true)
        apiService.fetchMyDriverRides(object : RideApiService.Callback<List<RideSummary>> {
            override fun onSuccess(result: List<RideSummary>) { myDriverRides = Resource(data = result) }
            override fun onFailure(error: Exception) { myDriverRides = Resource(errorMessage = error.message) }
        })
    }

    fun loadTripHistory() {
        tripHistory = Resource(isLoading = true)
        apiService.fetchTripHistory(object : RideApiService.Callback<List<RideSummary>> {
            override fun onSuccess(result: List<RideSummary>) { tripHistory = Resource(data = result) }
            override fun onFailure(error: Exception) { tripHistory = Resource(errorMessage = error.message) }
        })
    }

    fun requestSeat(rideId: String, seats: Int, note: String?, applyToRecurringGroup: Boolean, onDone: (Boolean, String?) -> Unit) {
        actionInFlight = true
        apiService.requestSeat(rideId, seats, note, applyToRecurringGroup, object : RideApiService.Callback<List<RideRequestSummary>> {
            override fun onSuccess(result: List<RideRequestSummary>) {
                actionInFlight = false
                loadMyRequests()
                onDone(true, null)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                onDone(false, error.message ?: "Couldn't request that seat. Please try again.")
            }
        })
    }

    fun createStandaloneRequest(draft: StandaloneRequestDraft, onDone: (Boolean, String?) -> Unit) {
        actionInFlight = true
        apiService.createStandaloneRequest(draft, object : RideApiService.Callback<RideRequestSummary> {
            override fun onSuccess(result: RideRequestSummary) {
                actionInFlight = false
                loadMyRequests()
                onDone(true, null)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                onDone(false, error.message ?: "Couldn't send that request. Please try again.")
            }
        })
    }

    fun loadMyRequests() {
        myRequests = Resource(isLoading = true)
        apiService.fetchMyRequests(object : RideApiService.Callback<List<RideRequestSummary>> {
            override fun onSuccess(result: List<RideRequestSummary>) { myRequests = Resource(data = result) }
            override fun onFailure(error: Exception) { myRequests = Resource(errorMessage = error.message) }
        })
    }

    fun loadIncomingRequests(showLoading: Boolean = true) {
        if (incomingRequestRefreshInFlight) return
        incomingRequestRefreshInFlight = true
        if (showLoading) incomingRequests = Resource(isLoading = true, data = incomingRequests.data)
        apiService.fetchIncomingRequests(object : RideApiService.Callback<List<RideRequestSummary>> {
            override fun onSuccess(result: List<RideRequestSummary>) {
                incomingRequestRefreshInFlight = false
                incomingRequests = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                incomingRequestRefreshInFlight = false
                incomingRequests = Resource(data = incomingRequests.data, errorMessage = error.message)
            }
        })
    }

    fun clearRequestActionError() {
        requestActionError = null
    }

    fun respondToRequest(
        requestId: String,
        newStatus: String,
        targetRideId: String? = null,
        newRideSeatsTotal: Int? = null,
        onDone: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        requestActionError = null
        actionInFlight = true
        apiService.respondToRequest(requestId, newStatus, targetRideId, newRideSeatsTotal, object : RideApiService.Callback<RideRequestSummary> {
            override fun onSuccess(result: RideRequestSummary) {
                actionInFlight = false
                loadIncomingRequests()
                loadMyDriverRides()
                loadMyRequests()
                result.rideId?.takeIf { it == selectedRideId }?.let(::loadRideDetails)
                onDone(true, null)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                val message = error.message ?: "Couldn't update that request. Please try again."
                requestActionError = message
                onDone(false, message)
            }
        })
    }

    fun loadMessages(rideId: String) {
        messages = Resource(isLoading = true)
        apiService.fetchMessages(rideId, object : RideApiService.Callback<List<MessageSummary>> {
            override fun onSuccess(result: List<MessageSummary>) { messages = Resource(data = result) }
            override fun onFailure(error: Exception) { messages = Resource(errorMessage = error.message) }
        })
    }

    fun sendMessage(rideId: String, body: String, onDone: (String?) -> Unit = {}) {
        apiService.sendMessage(rideId, body, object : RideApiService.Callback<MessageSummary> {
            override fun onSuccess(result: MessageSummary) { loadMessages(rideId); onDone(null) }
            override fun onFailure(error: Exception) { onDone(error.message ?: "Message couldn't be sent. Please try again.") }
        })
    }

    fun loadDashboard() {
        dashboard = Resource(isLoading = true)
        apiService.fetchDashboard(object : RideApiService.Callback<DashboardSummary> {
            override fun onSuccess(result: DashboardSummary) { dashboard = Resource(data = result) }
            override fun onFailure(error: Exception) { dashboard = Resource(errorMessage = error.message) }
        })
    }

    fun loadLeaderboard() {
        leaderboard = Resource(isLoading = true)
        apiService.fetchLeaderboard(20, object : RideApiService.Callback<LeaderboardResult> {
            override fun onSuccess(result: LeaderboardResult) { leaderboard = Resource(data = result) }
            override fun onFailure(error: Exception) { leaderboard = Resource(errorMessage = error.message) }
        })
    }

    fun submitRating(rideId: String, ratedUserId: String, stars: Int, tags: List<String>, comment: String?, onDone: (String?) -> Unit) {
        apiService.submitRating(rideId, ratedUserId, stars, tags, comment, object : RideApiService.Callback<RatingSummary> {
            override fun onSuccess(result: RatingSummary) { loadRatings(rideId); onDone(null) }
            override fun onFailure(error: Exception) { onDone(error.message ?: "Couldn't submit that rating. Please try again.") }
        })
    }

    fun loadRatings(rideId: String) {
        ratings = Resource(isLoading = true)
        apiService.fetchRatings(rideId, object : RideApiService.Callback<List<RatingSummary>> {
            override fun onSuccess(result: List<RatingSummary>) { ratings = Resource(data = result) }
            override fun onFailure(error: Exception) { ratings = Resource(errorMessage = error.message) }
        })
    }

    fun loadFavorites() {
        favorites = Resource(isLoading = true)
        apiService.fetchFavorites(object : RideApiService.Callback<List<FavoriteDriverSummary>> {
            override fun onSuccess(result: List<FavoriteDriverSummary>) { favorites = Resource(data = result) }
            override fun onFailure(error: Exception) { favorites = Resource(errorMessage = error.message) }
        })
    }

    fun toggleFavorite(driverId: String, currentlyFavorite: Boolean, onDone: (String?) -> Unit = {}) {
        val callback = object : RideApiService.Callback<Boolean> {
            override fun onSuccess(result: Boolean) { loadFavorites(); onDone(null) }
            override fun onFailure(error: Exception) { onDone(error.message ?: "Couldn't update favorites. Please try again.") }
        }
        if (currentlyFavorite) apiService.removeFavorite(driverId, callback) else apiService.addFavorite(driverId, callback)
    }
}
