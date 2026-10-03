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
import com.example.ds04_monash_rideshare_application.Repository.Remote.BackendException
import com.example.ds04_monash_rideshare_application.Repository.Remote.CONFLICT_OWN_PENDING_REQUEST
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateRideDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateVehicleDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.AnalyticsSummary
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
import com.example.ds04_monash_rideshare_application.Repository.Remote.VehicleSummary

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

class RideshareViewModel(
    private val apiService: RideApiService = RideApiServiceImpl(),
    private val authenticationProvider: AuthenticationProvider = FakeAuthenticationProvider()
) : ViewModel() {

    var uiState by mutableStateOf(RideshareUiState())
        private set

    // In-app toggle (Passenger teal / Driver amber) - not a login-time choice, and doesn't
    // partition data: a single account can be driving one ride and riding another at once.
    internal var driverAccess by mutableStateOf(DriverAccessState())
        private set
    val activeMode: AppMode get() = driverAccess.mode
    var driverModeError by mutableStateOf<String?>(null)
        private set
    var vehicleSaving by mutableStateOf(false)
        private set
    private var driverAttempt = 0
    private var vehicleVersion = 0

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
    var analytics by mutableStateOf(Resource<AnalyticsSummary>())
        private set
    var leaderboard by mutableStateOf(Resource<LeaderboardResult>())
        private set
    var ratings by mutableStateOf(Resource<List<RatingSummary>>())
        private set
    var favorites by mutableStateOf(Resource<List<FavoriteDriverSummary>>())
        private set
    var vehicles by mutableStateOf(Resource<List<VehicleSummary>>())
        private set
    var actionInFlight by mutableStateOf(false)
        private set
    var requestActionError by mutableStateOf<String?>(null)
        private set
    // Accept refused because the driver has their own ride request around then - the UI asks
    // "drive this passenger instead?" and confirmAcceptConflict() retries, cancelling that request.
    var acceptConflict by mutableStateOf<AcceptConflict?>(null)
        private set
    // The request just accepted, while the UI still offers Undo for it.
    var undoableAcceptRequestId by mutableStateOf<String?>(null)
        private set
    var lastCreatedRide by mutableStateOf<RideSummary?>(null)
        private set
    private var incomingRequestRefresh = RequestRefreshGate()
    private var rideDetailsVersion = 0
    private var myRequestsVersion = 0
    private var driverRidesVersion = 0
    private var analyticsVersion = 0L
    private var dashboardVersion = 0L

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

    // Passenger's Pending tab: still-waiting asks plus ones that expired in the last day, so they
    // can see what happened and ask again instead of the request silently disappearing.
    val myPendingAndRecentlyExpiredRequests: List<RideRequestSummary>
        get() {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            return myRequests.data.orEmpty().filter {
                it.status == RequestStatus.PENDING ||
                    (it.status == RequestStatus.EXPIRED && (it.respondedAt ?: 0L) >= cutoff)
            }
        }

    val myPendingRequests: List<RideRequestSummary>
        get() = myRequests.data.orEmpty().filter { it.status == RequestStatus.PENDING }

    val incomingPendingRequests: List<RideRequestSummary>
        // Never offer a driver their own request - one account can be both passenger and driver.
        get() = incomingRequests.data.orEmpty().filter {
            it.status == RequestStatus.PENDING && it.passengerId != uiState.userId
        }

    fun updateActiveMode(mode: AppMode, onRegistrationRequired: () -> Unit = {}, onDriverActivated: () -> Unit = {}) {
        if (mode == AppMode.PASSENGER) { cancelDriverRegistration(); return }
        if (driverAccess.checking) return
        val user = uiState.userId ?: return
        val attempt = ++driverAttempt
        val version = ++vehicleVersion
        driverAccess = driverAccess.beginCheck()
        driverModeError = null
        apiService.fetchMyVehicles(object : RideApiService.Callback<List<VehicleSummary>> {
            override fun onSuccess(result: List<VehicleSummary>) {
                if (attempt != driverAttempt || uiState.userId != user) return
                if (version == vehicleVersion) vehicles = Resource(data = result)
                driverAccess = driverAccess.resolve(result.isNotEmpty())
                if (driverAccess.needsRegistration) onRegistrationRequired() else onDriverActivated()
            }
            override fun onFailure(error: Exception) {
                if (attempt != driverAttempt || uiState.userId != user) return
                driverAccess = driverAccess.cancel()
                driverModeError = "Couldn't check your vehicle. Please try Driver again."
            }
        })
    }

    fun cancelDriverRegistration() {
        driverAttempt++
        driverAccess = driverAccess.cancel()
        driverModeError = null
    }

    fun clearDriverModeError() { driverModeError = null }

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
        cancelDriverRegistration()
        // Switching straight to another test account must not carry over the previous account's
        // requests - otherwise its incoming-request banner shows up for the new user.
        if (uiState.userId != user.userId) clearUserData()
        vehicleVersion++
        vehicles = Resource()
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

    private fun clearUserData() {
        analyticsVersion++
        dashboardVersion++
        vehicleVersion++
        vehicleSaving = false
        profileDetails = Resource(); myDriverRides = Resource(); myRequests = Resource()
        incomingRequests = Resource(); openRides = Resource(); searchResults = Resource()
        lastSearchCriteria = null
        rideDetails = Resource(); messages = Resource(); tripHistory = Resource()
        dashboard = Resource(); analytics = Resource(); leaderboard = Resource(); ratings = Resource(); favorites = Resource()
        vehicles = Resource()
        selectedRideId = null; selectedRequestId = null; requestActionError = null
        acceptConflict = null; undoableAcceptRequestId = null
        actionInFlight = false; incomingRequestRefresh = RequestRefreshGate(); lastCreatedRide = null
        rideDetailsVersion++; myRequestsVersion++; driverRidesVersion++
    }

    fun signOut(onComplete: () -> Unit = {}) {
        authenticationProvider.logout(object : AuthenticationProvider.AuthCallback {
            override fun onSuccess(user: AuthenticatedUser?) {
                AuthSession.currentUserId = null
                uiState = RideshareUiState()
                cancelDriverRegistration()
                clearUserData()
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
        val version = ++rideDetailsVersion
        val previous = rideDetails.data?.takeIf { it.rideId == rideId }
        rideDetails = Resource(isLoading = true, data = previous)
        apiService.fetchRide(rideId, object : RideApiService.Callback<RideSummary> {
            override fun onSuccess(result: RideSummary) {
                if (version == rideDetailsVersion) rideDetails = Resource(data = result)
            }
            override fun onFailure(error: Exception) {
                if (version == rideDetailsVersion) rideDetails = Resource(data = previous, errorMessage = error.message)
            }
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
                refreshRideLifecycleState(rideId, updatedRide = result)
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
                refreshRideLifecycleState(rideId, updatedRide = result, refreshActivity = true)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                requestActionError = error.message ?: "Couldn't complete the ride. Please try again."
                // Refresh stale terminal state (for example a 409) without discarding the
                // current detail on a second network failure or hiding the action error.
                if (selectedRideId != rideId) return
                val version = ++rideDetailsVersion
                apiService.fetchRide(rideId, object : RideApiService.Callback<RideSummary> {
                    override fun onSuccess(result: RideSummary) {
                        if (version == rideDetailsVersion && selectedRideId == rideId) {
                            rideDetails = Resource(data = result)
                        }
                    }
                    override fun onFailure(error: Exception) {
                        if (version == rideDetailsVersion) {
                            rideDetails = rideDetails.copy(isLoading = false)
                        }
                    }
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
        val version = ++driverRidesVersion
        myDriverRides = Resource(isLoading = true, data = myDriverRides.data)
        apiService.fetchMyDriverRides(object : RideApiService.Callback<List<RideSummary>> {
            override fun onSuccess(result: List<RideSummary>) {
                if (version == driverRidesVersion) myDriverRides = Resource(data = result)
            }
            override fun onFailure(error: Exception) {
                if (version == driverRidesVersion) myDriverRides = Resource(data = myDriverRides.data, errorMessage = error.message ?: "Could not load your rides")
            }
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
        val version = ++myRequestsVersion
        myRequests = Resource(isLoading = true, data = myRequests.data)
        apiService.fetchMyRequests(object : RideApiService.Callback<List<RideRequestSummary>> {
            override fun onSuccess(result: List<RideRequestSummary>) {
                if (version == myRequestsVersion) myRequests = Resource(data = result)
            }
            override fun onFailure(error: Exception) {
                if (version == myRequestsVersion) myRequests = Resource(data = myRequests.data, errorMessage = error.message ?: "Could not load your requests")
            }
        })
    }

    fun loadIncomingRequests(showLoading: Boolean = true, requireFresh: Boolean = true) {
        val refresh = incomingRequestRefresh
        if (!refresh.start(requireFresh)) return
        if (showLoading) incomingRequests = Resource(isLoading = true, data = incomingRequests.data)
        apiService.fetchIncomingRequests(object : RideApiService.Callback<List<RideRequestSummary>> {
            override fun onSuccess(result: List<RideRequestSummary>) {
                if (refresh !== incomingRequestRefresh) return
                if (refresh.finish()) loadIncomingRequests(showLoading = false)
                else incomingRequests = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                if (refresh !== incomingRequestRefresh) return
                if (refresh.finish()) loadIncomingRequests(showLoading = false)
                else incomingRequests = Resource(data = incomingRequests.data, errorMessage = error.message)
            }
        })
    }

    private fun refreshRideLifecycleState(
        rideId: String?,
        updatedRide: RideSummary? = null,
        refreshRide: Boolean = true,
        refreshActivity: Boolean = false
    ) {
        // Mutation responses already contain the authoritative ride; do not fetch it again.
        if (rideId != null && rideId == selectedRideId) {
            if (updatedRide != null) {
                rideDetailsVersion++ // An older GET must not overwrite successful terminal state.
                rideDetails = Resource(data = updatedRide)
            } else if (refreshRide) loadRideDetails(rideId)
        }
        loadIncomingRequests()
        loadMyRequests()
        if (refreshRide) loadMyDriverRides()
        if (refreshActivity) {
            loadTripHistory()
            loadDashboard()
        }
    }

    fun clearRequestActionError() {
        requestActionError = null
    }

    fun respondToRequest(
        requestId: String,
        newStatus: String,
        targetRideId: String? = null,
        newRideSeatsTotal: Int? = null,
        cancelMyConflictingRequests: Boolean = false,
        onDone: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        requestActionError = null
        actionInFlight = true
        apiService.respondToRequest(requestId, newStatus, targetRideId, newRideSeatsTotal, cancelMyConflictingRequests, object : RideApiService.Callback<RideRequestSummary> {
            override fun onSuccess(result: RideRequestSummary) {
                actionInFlight = false
                // A deliberate "drive instead" was already confirmed in a dialog - no Undo for it.
                if (newStatus == RequestStatus.ACCEPTED && !cancelMyConflictingRequests) {
                    undoableAcceptRequestId = result.requestId
                }
                refreshRideLifecycleState(
                    result.rideId,
                    refreshRide = newStatus != RequestStatus.REJECTED,
                    refreshActivity = newStatus == RequestStatus.COMPLETED
                )
                onDone(true, null)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                if (newStatus == RequestStatus.ACCEPTED && (error as? BackendException)?.code == CONFLICT_OWN_PENDING_REQUEST) {
                    acceptConflict = AcceptConflict(requestId, targetRideId, newRideSeatsTotal, error.message.orEmpty(), onDone)
                    return
                }
                val message = error.message ?: "Couldn't update that request. Please try again."
                requestActionError = message
                onDone(false, message)
            }
        })
    }

    fun confirmAcceptConflict() {
        val conflict = acceptConflict ?: return
        acceptConflict = null
        respondToRequest(
            conflict.requestId, RequestStatus.ACCEPTED, conflict.targetRideId, conflict.newRideSeatsTotal,
            cancelMyConflictingRequests = true, onDone = conflict.onDone
        )
    }

    fun dismissAcceptConflict() {
        val conflict = acceptConflict ?: return
        acceptConflict = null
        conflict.onDone(false, null)
    }

    fun clearUndoableAccept() { undoableAcceptRequestId = null }

    fun undoAccept() {
        val requestId = undoableAcceptRequestId ?: return
        undoableAcceptRequestId = null
        actionInFlight = true
        apiService.undoAccept(requestId, object : RideApiService.Callback<RideRequestSummary> {
            override fun onSuccess(result: RideRequestSummary) {
                actionInFlight = false
                refreshRideLifecycleState(result.rideId)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                requestActionError = error.message ?: "Couldn't undo that. Please try again."
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
        val version = ++dashboardVersion
        val userId = uiState.userId ?: return
        dashboard = Resource(isLoading = true)
        apiService.fetchDashboard(object : RideApiService.Callback<DashboardSummary> {
            override fun onSuccess(result: DashboardSummary) {
                if (version == dashboardVersion && userId == uiState.userId) dashboard = Resource(data = result)
            }
            override fun onFailure(error: Exception) {
                if (version == dashboardVersion && userId == uiState.userId) dashboard = Resource(errorMessage = error.message)
            }
        })
    }

    fun loadAnalytics() {
        val version = ++analyticsVersion
        val userId = uiState.userId ?: return
        analytics = Resource(isLoading = true)
        apiService.fetchAnalytics(object : RideApiService.Callback<AnalyticsSummary> {
            override fun onSuccess(result: AnalyticsSummary) {
                if (version == analyticsVersion && userId == uiState.userId) analytics = Resource(data = result)
            }
            override fun onFailure(error: Exception) {
                if (version == analyticsVersion && userId == uiState.userId) analytics = Resource(errorMessage = error.message)
            }
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

    fun loadVehicles(onDone: (String?) -> Unit = {}) {
        val version = ++vehicleVersion
        val user = uiState.userId
        vehicles = Resource(isLoading = true, data = vehicles.data)
        apiService.fetchMyVehicles(object : RideApiService.Callback<List<VehicleSummary>> {
            override fun onSuccess(result: List<VehicleSummary>) {
                if (uiState.userId != user) return
                if (version == vehicleVersion) vehicles = Resource(data = result)
                onDone(null)
            }
            override fun onFailure(error: Exception) {
                if (uiState.userId != user) return
                val message = error.message ?: "Couldn't load your vehicle. Please try again."
                if (version == vehicleVersion) vehicles = Resource(data = vehicles.data, errorMessage = message)
                onDone(message)
            }
        })
    }

    fun createVehicle(draft: CreateVehicleDraft, onDone: (String?) -> Unit) = saveVehicle(null, draft, onDone)

    fun updateVehicle(vehicleId: String, draft: CreateVehicleDraft, onDone: (String?) -> Unit) = saveVehicle(vehicleId, draft, onDone)

    private fun saveVehicle(vehicleId: String?, draft: CreateVehicleDraft, onDone: (String?) -> Unit) {
        if (vehicleSaving) return
        val user = uiState.userId ?: return
        vehicleSaving = true
        val callback = object : RideApiService.Callback<VehicleSummary> {
            override fun onSuccess(result: VehicleSummary) {
                if (uiState.userId != user) return
                vehicles = Resource(data = listOf(result))
                loadVehicles { error ->
                    vehicleSaving = false
                    onDone(error)
                }
            }
            override fun onFailure(error: Exception) {
                if (uiState.userId != user) return
                vehicleSaving = false
                onDone(error.message ?: "Couldn't save your vehicle. Please try again.")
            }
        }
        if (vehicleId == null) apiService.createVehicle(draft, callback)
        else apiService.updateVehicle(vehicleId, draft, callback)
    }

    fun deleteVehicle(vehicleId: String, onDone: (String?) -> Unit) {
        apiService.deleteVehicle(vehicleId, object : RideApiService.Callback<Boolean> {
            override fun onSuccess(result: Boolean) { loadVehicles(); onDone(null) }
            override fun onFailure(error: Exception) { onDone(error.message ?: "Couldn't remove that vehicle. Please try again.") }
        })
    }
}

data class AcceptConflict(
    val requestId: String,
    val targetRideId: String?,
    val newRideSeatsTotal: Int?,
    val message: String,
    val onDone: (Boolean, String?) -> Unit
)
