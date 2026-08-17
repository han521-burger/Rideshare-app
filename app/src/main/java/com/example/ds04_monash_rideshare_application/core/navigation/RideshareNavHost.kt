package com.example.ds04_monash_rideshare_application.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import com.example.ds04_monash_rideshare_application.core.ui.BottomNavScaffold
import com.example.ds04_monash_rideshare_application.feature.activity.ActivityScreen
import com.example.ds04_monash_rideshare_application.feature.activity.LeaderboardScreen
import com.example.ds04_monash_rideshare_application.feature.auth.LoginScreen
import com.example.ds04_monash_rideshare_application.feature.auth.SplashScreen
import com.example.ds04_monash_rideshare_application.feature.home.HomeScreen
import com.example.ds04_monash_rideshare_application.feature.messages.MessagesScreen
import com.example.ds04_monash_rideshare_application.feature.profile.ProfileScreen
import com.example.ds04_monash_rideshare_application.feature.requests.RequestsScreen
import com.example.ds04_monash_rideshare_application.feature.rides.OfferRideFlowScreen
import com.example.ds04_monash_rideshare_application.feature.rides.RequestRideFlowScreen
import com.example.ds04_monash_rideshare_application.feature.rides.RideDetailScreen

@Composable
fun RideshareNavHost(
    viewModel: RideshareViewModel,
    modifier: Modifier = Modifier
) {
    val uiState = viewModel.uiState
    var backStack by rememberSaveable {
        mutableStateOf(listOf(AppDestination.Splash.route))
    }

    fun navigate(destination: AppDestination) {
        if (backStack.lastOrNull() != destination.route) {
            backStack = backStack + destination.route
        }
    }

    fun replaceStack(destination: AppDestination) {
        backStack = listOf(destination.route)
    }

    fun navigateUp() {
        if (backStack.size > 1) {
            backStack = backStack.dropLast(1)
        }
    }

    fun switchTab(destination: AppDestination) {
        backStack = listOf(destination.route)
    }

    fun returnToLogin() {
        viewModel.signOut {
            replaceStack(AppDestination.Login)
        }
    }

    BackHandler(enabled = backStack.size > 1) {
        navigateUp()
    }

    val currentDestination = AppDestination.fromRoute(backStack.lastOrNull()) ?: AppDestination.Splash
    val currentTab = AppDestination.tabDestinations.firstOrNull { it.route == backStack.firstOrNull() }
        ?: AppDestination.Home

    // Central place to (re)load data whenever a data-driven destination becomes active, so every
    // entry point into it (tab switch, drilling into a ride, mode toggle, etc.) stays in sync
    // without each caller having to remember to trigger the right load itself.
    LaunchedEffect(currentDestination, viewModel.selectedRideId, viewModel.activeMode) {
        when (currentDestination) {
            AppDestination.Home -> {
                viewModel.loadMyDriverRides()
                viewModel.loadMyRequests()
                if (viewModel.activeMode == AppMode.PASSENGER) viewModel.loadOpenRides()
            }
            AppDestination.Requests -> {
                viewModel.loadMyRequests()
                viewModel.loadIncomingRequests()
            }
            AppDestination.Activity -> {
                viewModel.loadDashboard()
                viewModel.loadTripHistory()
            }
            AppDestination.Profile -> {
                viewModel.loadProfile()
                viewModel.loadFavorites()
            }
            AppDestination.RideDetail, AppDestination.RequestRide -> {
                viewModel.selectedRideId?.let { viewModel.loadRideDetails(it) }
                viewModel.loadIncomingRequests()
                viewModel.loadMyRequests()
            }
            AppDestination.Messages -> viewModel.selectedRideId?.let { viewModel.loadMessages(it) }
            AppDestination.Leaderboard -> viewModel.loadLeaderboard()
            else -> Unit
        }
    }

    if (currentDestination in AppDestination.tabDestinations) {
        BottomNavScaffold(
            activeMode = viewModel.activeMode,
            onModeChange = viewModel::updateActiveMode,
            currentTab = currentTab,
            onTabSelected = ::switchTab,
            requestsBadgeCount = viewModel.myPendingRequests.size + viewModel.incomingPendingRequests.size,
            greetingName = uiState.greetingName,
            modifier = modifier
        ) { contentPadding ->
            Box(modifier = Modifier.padding(contentPadding)) {
                when (currentDestination) {
                    AppDestination.Home -> HomeScreen(
                        activeMode = viewModel.activeMode,
                        currentUserId = uiState.userId,
                        confirmedRides = viewModel.confirmedRides,
                        openRides = viewModel.openRides,
                        myDriverRides = viewModel.myDriverRides,
                        onOpenRide = { rideId ->
                            viewModel.selectedRideId = rideId
                            navigate(AppDestination.RideDetail)
                        },
                        onOfferRide = { navigate(AppDestination.OfferRide) }
                    )

                    AppDestination.Requests -> RequestsScreen(
                        activeMode = viewModel.activeMode,
                        myRequests = viewModel.myRequests.data.orEmpty(),
                        incomingRequests = viewModel.incomingRequests.data.orEmpty(),
                        onOpenRide = { rideId ->
                            viewModel.selectedRideId = rideId
                            navigate(AppDestination.RideDetail)
                        }
                    )

                    AppDestination.Activity -> ActivityScreen(
                        dashboard = viewModel.dashboard,
                        tripHistory = viewModel.tripHistory,
                        onOpenLeaderboard = { navigate(AppDestination.Leaderboard) },
                        onOpenRide = { rideId ->
                            viewModel.selectedRideId = rideId
                            navigate(AppDestination.RideDetail)
                        }
                    )

                    AppDestination.Profile -> ProfileScreen(
                        uiState = uiState,
                        profileDetails = viewModel.profileDetails,
                        favorites = viewModel.favorites,
                        onSave = viewModel::updateProfile,
                        onRemoveFavorite = { driverId -> viewModel.toggleFavorite(driverId, currentlyFavorite = true) },
                        onSignOut = ::returnToLogin
                    )

                    else -> Unit
                }
            }
        }
        return
    }

    when (currentDestination) {
        AppDestination.Splash -> SplashScreen(
            onFinished = { replaceStack(AppDestination.Login) }
        )

        AppDestination.Login -> LoginScreen(
            uiState = uiState,
            onSignIn = { email ->
                viewModel.signIn(email) { replaceStack(AppDestination.Home) }
            },
            onSignInAsTestUser = { identity ->
                viewModel.signInAsTestUser(identity) { replaceStack(AppDestination.Home) }
            },
            modifier = modifier
        )

        AppDestination.RideDetail -> RideDetailScreen(
            rideDetails = viewModel.rideDetails,
            currentUserId = uiState.userId,
            myRequestForRide = viewModel.myRequests.data.orEmpty().firstOrNull { it.rideId == viewModel.selectedRideId },
            incomingRequestsForRide = viewModel.incomingRequests.data.orEmpty().filter { it.rideId == viewModel.selectedRideId },
            isActionInFlight = viewModel.actionInFlight,
            emergencyContactPhone = viewModel.profileDetails.data?.emergencyContactPhone,
            onRequestSeat = { navigate(AppDestination.RequestRide) },
            onCancelMyRequest = { requestId -> viewModel.respondToRequest(requestId, RequestStatus.CANCELLED) },
            onRespondToRequest = { requestId, status -> viewModel.respondToRequest(requestId, status) },
            onSetEnRoute = { enRoute ->
                viewModel.selectedRideId?.let { viewModel.setDriverEnRoute(it, enRoute) }
            },
            onCancelRide = {
                viewModel.selectedRideId?.let { viewModel.cancelRide(it) }
            },
            onOpenChat = { navigate(AppDestination.Messages) },
            onSubmitRating = { ratedUserId, stars, comment ->
                viewModel.selectedRideId?.let { rideId ->
                    viewModel.submitRating(rideId, ratedUserId, stars, emptyList(), comment) {}
                }
            },
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        AppDestination.RequestRide -> RequestRideFlowScreen(
            rideDetails = viewModel.rideDetails,
            isSubmitting = viewModel.actionInFlight,
            onSubmit = { seats, note, applyToRecurringGroup, onDone ->
                val rideId = viewModel.selectedRideId
                if (rideId == null) {
                    onDone("No ride selected")
                } else {
                    viewModel.requestSeat(rideId, seats, note, applyToRecurringGroup) { success, error ->
                        onDone(error)
                        if (success) navigateUp()
                    }
                }
            },
            onExit = ::navigateUp,
            modifier = modifier
        )

        AppDestination.OfferRide -> OfferRideFlowScreen(
            isPublishing = viewModel.actionInFlight,
            onPublish = { draft, onSuccess, onError ->
                viewModel.createRide(draft, onSuccess, onError)
            },
            onExit = ::navigateUp,
            onPublished = { ride ->
                viewModel.selectedRideId = ride.rideId
                replaceStack(AppDestination.Home)
                navigate(AppDestination.RideDetail)
            },
            modifier = modifier
        )

        AppDestination.Messages -> MessagesScreen(
            messages = viewModel.messages,
            currentUserId = uiState.userId,
            onSend = { body, onDone ->
                viewModel.selectedRideId?.let { rideId -> viewModel.sendMessage(rideId, body, onDone) }
                    ?: onDone("No ride selected")
            },
            onRefresh = {
                viewModel.selectedRideId?.let { viewModel.loadMessages(it) }
            },
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        AppDestination.Leaderboard -> LeaderboardScreen(
            leaderboard = viewModel.leaderboard,
            currentUserId = uiState.userId,
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        else -> Unit
    }
}
