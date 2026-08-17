package com.example.ds04_monash_rideshare_application.core.state

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Auth.AuthenticatedUser
import com.example.ds04_monash_rideshare_application.Auth.AuthenticationProvider
import com.example.ds04_monash_rideshare_application.Auth.FakeAuthenticationProvider
import com.example.ds04_monash_rideshare_application.Auth.TestIdentity
import com.example.ds04_monash_rideshare_application.Database.AppDatabase
import com.example.ds04_monash_rideshare_application.Database.Messaging.Message
import com.example.ds04_monash_rideshare_application.Database.Passenger.SeatRequest
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer
import com.example.ds04_monash_rideshare_application.Database.User.UserAccount
import com.example.ds04_monash_rideshare_application.Database.User.UserProfile
import com.example.ds04_monash_rideshare_application.Repository.RideRepository
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateRideDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.DashboardSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RankedRide
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestWithRide
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiServiceImpl
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSearchCriteria
import com.example.ds04_monash_rideshare_application.Repository.RouteRepository
import com.example.ds04_monash_rideshare_application.Repository.UserRepository
import com.example.ds04_monash_rideshare_application.core.model.SessionRole
import java.util.concurrent.Executors

data class RideshareUiState(
    val isSigningIn: Boolean = false,
    val userId: String? = null,
    val email: String = "",
    val displayName: String = "",
    val preferredName: String = "",
    val activeRole: SessionRole? = null,
    val passengerEnabled: Boolean = true,
    val errorMessage: String? = null
) {
    val isSignedIn: Boolean
        get() = userId != null

    val greetingName: String
        get() = preferredName.ifBlank { displayName.ifBlank { "Monash traveller" } }
}

// Generic loading/data/error holder for the network-backed lists this ViewModel exposes -
// avoids repeating the same three fields on every screen's state.
data class Resource<T>(
    val isLoading: Boolean = false,
    val data: T? = null,
    val errorMessage: String? = null
)

class RideshareViewModel(application: Application) : AndroidViewModel(application) {
    private val authenticationProvider: AuthenticationProvider = FakeAuthenticationProvider()
    private val apiService: RideApiService = RideApiServiceImpl()
    private val ioExecutor = Executors.newFixedThreadPool(2)

    private val appDatabase = AppDatabase.getInstance(application)
    private val userRepository = UserRepository(appDatabase.userProfileDao(), apiService, ioExecutor)
    private val rideRepository = RideRepository(appDatabase.rideOfferDao(), apiService, ioExecutor)
    private val routeRepository = RouteRepository(appDatabase.routeSnapshotDao(), apiService, ioExecutor)

    var uiState by mutableStateOf(RideshareUiState())
        private set

    var selectedRideId: String? by mutableStateOf(null)

    var openRides by mutableStateOf(Resource<List<RideOffer>>())
        private set
    var searchResults by mutableStateOf(Resource<List<RankedRide>>())
        private set
    var rideDetails by mutableStateOf(Resource<RideOffer>())
        private set
    var driverRides by mutableStateOf(Resource<List<RideOffer>>())
        private set
    var incomingRequests by mutableStateOf(Resource<List<RequestWithRide>>())
        private set
    var myRequests by mutableStateOf(Resource<List<RequestWithRide>>())
        private set
    var messages by mutableStateOf(Resource<List<Message>>())
        private set
    var tripHistory by mutableStateOf(Resource<List<RideOffer>>())
        private set
    var dashboard by mutableStateOf(Resource<DashboardSummary>())
        private set
    var actionInFlight by mutableStateOf(false)
        private set
    var lastCreatedRide by mutableStateOf<RideOffer?>(null)
        private set

    fun signIn(email: String? = null, onSuccess: () -> Unit = {}) {
        if (uiState.isSigningIn) return

        uiState = uiState.copy(isSigningIn = true, errorMessage = null)
        authenticationProvider.login(object : AuthenticationProvider.AuthCallback {
            override fun onSuccess(user: AuthenticatedUser) {
                applySignedInUser(user, email)
                onSuccess()
            }

            override fun onFailure(error: Exception) {
                uiState = uiState.copy(
                    isSigningIn = false,
                    errorMessage = error.message ?: "Sign in was unsuccessful. Please try again."
                )
            }
        })
    }

    // Trial-only: signs in directly as one of the other seeded test accounts (backend/Database.kt),
    // bypassing FakeAuthenticationProvider (which only ever returns fake-user-001). Without this
    // there's no way to test both sides of a request/accept/message flow - every request you make
    // against another seeded driver's ride would be invisible to you forever, since nobody could
    // ever sign in as that driver to accept it.
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
            preferredName = user.displayName,
            activeRole = null
        )
        // UserProfile has a foreign key onto user_account(userId) - the account row must exist in
        // Room before refreshUserProfile's cache write can succeed, so upsert it first, on the
        // same background thread, before triggering the profile fetch.
        ioExecutor.execute {
            appDatabase.userAccountDao().upsert(
                UserAccount(user.userId, user.externalSubjectId, user.emailAccount, "ACTIVE", System.currentTimeMillis())
            )
            userRepository.refreshUserProfile(user.userId)
        }
    }

    fun selectRole(role: SessionRole) {
        uiState = uiState.copy(activeRole = role)
    }

    fun updateProfile(displayName: String, preferredName: String) {
        val userId = uiState.userId ?: return
        uiState = uiState.copy(displayName = displayName.trim(), preferredName = preferredName.trim())
        apiService.updateUserProfile(userId, displayName.trim(), preferredName.trim(), true, object : RideApiService.Callback<UserProfile> {
            override fun onSuccess(result: UserProfile) {
                ioExecutor.execute { appDatabase.userProfileDao().upsert(result) }
            }

            override fun onFailure(error: Exception) {
                uiState = uiState.copy(errorMessage = error.message)
            }
        })
    }

    fun updatePassengerEnabled(enabled: Boolean) {
        val userId = uiState.userId ?: return
        uiState = uiState.copy(passengerEnabled = enabled)
        apiService.updateUserProfile(userId, uiState.displayName, uiState.preferredName, enabled, object : RideApiService.Callback<UserProfile> {
            override fun onSuccess(result: UserProfile) {
                ioExecutor.execute { appDatabase.userProfileDao().upsert(result) }
            }

            override fun onFailure(error: Exception) {
                uiState = uiState.copy(errorMessage = error.message)
            }
        })
    }

    // Wipes the Room cache only - the backend is still the source of truth, so this just forces
    // the next screen visit to fetch fresh data instead of showing whatever was cached locally.
    fun clearLocalCache() {
        ioExecutor.execute { appDatabase.clearAllTables() }
    }

    fun signOut(onComplete: () -> Unit = {}) {
        authenticationProvider.logout(object : AuthenticationProvider.AuthCallback {
            override fun onSuccess(user: AuthenticatedUser?) {
                AuthSession.currentUserId = null
                uiState = RideshareUiState()
                openRides = Resource()
                searchResults = Resource()
                driverRides = Resource()
                incomingRequests = Resource()
                myRequests = Resource()
                messages = Resource()
                tripHistory = Resource()
                dashboard = Resource()
                onComplete()
            }

            override fun onFailure(error: Exception) {
                uiState = uiState.copy(errorMessage = error.message ?: "Sign out was unsuccessful. Please try again.")
            }
        })
    }

    fun loadOpenRides() {
        openRides = Resource(isLoading = true)
        apiService.fetchRides(object : RideApiService.Callback<List<RideOffer>> {
            override fun onSuccess(result: List<RideOffer>) {
                openRides = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                openRides = Resource(errorMessage = error.message)
            }
        })
    }

    fun searchRides(criteria: RideSearchCriteria) {
        searchResults = Resource(isLoading = true)
        apiService.searchRides(criteria, object : RideApiService.Callback<List<RankedRide>> {
            override fun onSuccess(result: List<RankedRide>) {
                searchResults = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                searchResults = Resource(errorMessage = error.message)
            }
        })
    }

    fun loadRideDetails(rideId: String) {
        rideDetails = Resource(isLoading = true)
        apiService.fetchRide(rideId, object : RideApiService.Callback<RideOffer> {
            override fun onSuccess(result: RideOffer) {
                rideDetails = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                rideDetails = Resource(errorMessage = error.message)
            }
        })
    }

    fun createRide(draft: CreateRideDraft, onSuccess: (RideOffer) -> Unit, onError: (String) -> Unit) {
        actionInFlight = true
        apiService.createRide(draft, object : RideApiService.Callback<RideOffer> {
            override fun onSuccess(result: RideOffer) {
                actionInFlight = false
                lastCreatedRide = result
                rideRepository.refreshRides()
                routeRepository.refreshRouteSummaries(result.rideId)
                onSuccess(result)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                onError(error.message ?: "Couldn't create the ride. Please try again.")
            }
        })
    }

    fun loadDriverRides() {
        driverRides = Resource(isLoading = true)
        apiService.fetchMyDriverRides(object : RideApiService.Callback<List<RideOffer>> {
            override fun onSuccess(result: List<RideOffer>) {
                driverRides = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                driverRides = Resource(errorMessage = error.message)
            }
        })
    }

    fun loadIncomingRequests() {
        incomingRequests = Resource(isLoading = true)
        apiService.fetchRequestsForMyRides(object : RideApiService.Callback<List<RequestWithRide>> {
            override fun onSuccess(result: List<RequestWithRide>) {
                incomingRequests = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                incomingRequests = Resource(errorMessage = error.message)
            }
        })
    }

    fun loadMyRequests() {
        myRequests = Resource(isLoading = true)
        apiService.fetchMyRequests(object : RideApiService.Callback<List<RequestWithRide>> {
            override fun onSuccess(result: List<RequestWithRide>) {
                myRequests = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                myRequests = Resource(errorMessage = error.message)
            }
        })
    }

    fun requestSeat(rideId: String, seats: Int, onDone: (Boolean, String?) -> Unit) {
        actionInFlight = true
        apiService.requestSeat(rideId, seats, object : RideApiService.Callback<SeatRequest> {
            override fun onSuccess(result: SeatRequest) {
                actionInFlight = false
                onDone(true, null)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                onDone(false, error.message ?: "Couldn't request that seat. Please try again.")
            }
        })
    }

    fun respondToRequest(requestId: String, newStatus: String, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        actionInFlight = true
        apiService.respondToRequest(requestId, newStatus, object : RideApiService.Callback<SeatRequest> {
            override fun onSuccess(result: SeatRequest) {
                actionInFlight = false
                loadIncomingRequests()
                rideRepository.refreshRides()
                onDone(true, null)
            }

            override fun onFailure(error: Exception) {
                actionInFlight = false
                onDone(false, error.message ?: "Couldn't update that request. Please try again.")
            }
        })
    }

    fun loadMessages(rideId: String) {
        messages = Resource(isLoading = true)
        apiService.fetchMessages(rideId, object : RideApiService.Callback<List<Message>> {
            override fun onSuccess(result: List<Message>) {
                messages = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                messages = Resource(errorMessage = error.message)
            }
        })
    }

    fun sendMessage(rideId: String, body: String, onDone: (String?) -> Unit = {}) {
        apiService.sendMessage(rideId, body, object : RideApiService.Callback<Message> {
            override fun onSuccess(result: Message) {
                loadMessages(rideId)
                onDone(null)
            }

            override fun onFailure(error: Exception) {
                onDone(error.message ?: "Message couldn't be sent. Please try again.")
            }
        })
    }

    fun loadTripHistory() {
        tripHistory = Resource(isLoading = true)
        apiService.fetchTripHistory(object : RideApiService.Callback<List<RideOffer>> {
            override fun onSuccess(result: List<RideOffer>) {
                tripHistory = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                tripHistory = Resource(errorMessage = error.message)
            }
        })
    }

    fun loadDashboard() {
        dashboard = Resource(isLoading = true)
        apiService.fetchDashboard(object : RideApiService.Callback<DashboardSummary> {
            override fun onSuccess(result: DashboardSummary) {
                dashboard = Resource(data = result)
            }

            override fun onFailure(error: Exception) {
                dashboard = Resource(errorMessage = error.message)
            }
        })
    }
}
