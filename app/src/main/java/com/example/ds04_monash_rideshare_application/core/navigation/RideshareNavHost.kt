package com.example.ds04_monash_rideshare_application.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.ds04_monash_rideshare_application.core.model.SessionRole
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.feature.auth.LoginScreen
import com.example.ds04_monash_rideshare_application.feature.auth.SplashScreen
import com.example.ds04_monash_rideshare_application.feature.dashboard.SustainabilityDashboardScreen
import com.example.ds04_monash_rideshare_application.feature.dashboard.TripHistoryScreen
import com.example.ds04_monash_rideshare_application.feature.home.HomeScreen
import com.example.ds04_monash_rideshare_application.feature.messages.MessagesScreen
import com.example.ds04_monash_rideshare_application.feature.profile.ProfileScreen
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer
import com.example.ds04_monash_rideshare_application.feature.rides.ConfirmedRideScreen
import com.example.ds04_monash_rideshare_application.feature.rides.CreateRideScreen
import com.example.ds04_monash_rideshare_application.feature.rides.FindRideScreen
import com.example.ds04_monash_rideshare_application.feature.rides.MyRidesScreen
import com.example.ds04_monash_rideshare_application.feature.rides.RideDetailsScreen
import com.example.ds04_monash_rideshare_application.feature.rides.RideRequestsScreen
import com.example.ds04_monash_rideshare_application.feature.role.RoleSelectionScreen
import com.example.ds04_monash_rideshare_application.feature.settings.SettingsScreen
import com.example.ds04_monash_rideshare_application.ui.MapScreen

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

    fun returnToRoleSelection() {
        val roleIndex = backStack.indexOfLast { it == AppDestination.RoleSelection.route }
        backStack = if (roleIndex >= 0) {
            backStack.take(roleIndex + 1)
        } else {
            listOf(AppDestination.RoleSelection.route)
        }
    }

    fun returnToLogin() {
        viewModel.signOut {
            replaceStack(AppDestination.Login)
        }
    }

    BackHandler(enabled = backStack.size > 1) {
        navigateUp()
    }

    val currentDestination = AppDestination.fromRoute(backStack.lastOrNull())
        ?: AppDestination.Splash

    // Central place to (re)load data whenever a data-driven destination becomes active, so every
    // entry point into it (home menu, search results, accept/reject, etc.) stays in sync without
    // each caller having to remember to trigger the right load itself.
    LaunchedEffect(currentDestination, viewModel.selectedRideId) {
        when (currentDestination) {
            AppDestination.MyRides -> {
                viewModel.loadDriverRides()
                viewModel.loadMyRequests()
            }
            AppDestination.FindRide -> viewModel.loadOpenRides()
            AppDestination.RideRequests -> viewModel.loadIncomingRequests()
            AppDestination.TripHistory -> viewModel.loadTripHistory()
            AppDestination.SustainabilityDashboard -> viewModel.loadDashboard()
            AppDestination.RideDetails, AppDestination.ConfirmedRide ->
                viewModel.selectedRideId?.let { viewModel.loadRideDetails(it) }
            AppDestination.Messages ->
                viewModel.selectedRideId?.let { viewModel.loadMessages(it) }
            else -> Unit
        }
    }

    when (currentDestination) {
        AppDestination.Splash -> SplashScreen(
            onFinished = { replaceStack(AppDestination.Login) }
        )

        AppDestination.Login -> LoginScreen(
            uiState = uiState,
            onSignIn = { email ->
                viewModel.signIn(email) {
                    replaceStack(AppDestination.RoleSelection)
                }
            },
            onSignInAsTestUser = { identity ->
                viewModel.signInAsTestUser(identity) {
                    replaceStack(AppDestination.RoleSelection)
                }
            },
            modifier = modifier
        )

        AppDestination.RoleSelection -> RoleSelectionScreen(
            greetingName = uiState.greetingName,
            onRoleSelected = { role ->
                viewModel.selectRole(role)
                navigate(
                    if (role == SessionRole.PASSENGER) {
                        AppDestination.PassengerHome
                    } else {
                        AppDestination.DriverHome
                    }
                )
            },
            onSignOut = ::returnToLogin,
            modifier = modifier
        )

        AppDestination.PassengerHome -> HomeScreen(
            role = SessionRole.PASSENGER,
            greetingName = uiState.greetingName,
            onNavigate = ::navigate,
            onChangeRole = ::returnToRoleSelection,
            onSignOut = ::returnToLogin,
            modifier = modifier
        )

        AppDestination.DriverHome -> HomeScreen(
            role = SessionRole.DRIVER,
            greetingName = uiState.greetingName,
            onNavigate = ::navigate,
            onChangeRole = ::returnToRoleSelection,
            onSignOut = ::returnToLogin,
            modifier = modifier
        )

        AppDestination.Profile -> ProfileScreen(
            uiState = uiState,
            onNavigateBack = ::navigateUp,
            onSave = viewModel::updateProfile,
            modifier = modifier
        )

        AppDestination.Map -> RideshareScaffold(
            title = AppDestination.Map.title,
            canNavigateBack = true,
            onNavigateBack = ::navigateUp,
            modifier = modifier
        ) { contentPadding ->
            MapScreen(modifier = Modifier.padding(contentPadding))
        }

        AppDestination.MyRides -> MyRidesScreen(
            driverRides = viewModel.driverRides,
            myRequests = viewModel.myRequests,
            onOpenDriverRide = { ride ->
                viewModel.selectedRideId = ride.rideId
                navigate(
                    if (ride.status == RideOffer.STATUS_CONFIRMED || ride.status == RideOffer.STATUS_COMPLETED) {
                        AppDestination.ConfirmedRide
                    } else {
                        AppDestination.RideDetails
                    }
                )
            },
            onOpenRequest = { request ->
                viewModel.selectedRideId = request.rideId
                navigate(
                    if (request.status == "ACCEPTED" || request.status == "COMPLETED") {
                        AppDestination.ConfirmedRide
                    } else {
                        AppDestination.RideDetails
                    }
                )
            },
            onRefresh = {
                viewModel.loadDriverRides()
                viewModel.loadMyRequests()
            },
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        AppDestination.CreateRide -> CreateRideScreen(
            isPublishing = viewModel.actionInFlight,
            onPublish = { draft, onSuccess, onError ->
                viewModel.createRide(draft, { onSuccess() }, onError)
            },
            onNavigateBack = ::navigateUp,
            onPublished = { navigateUp() },
            modifier = modifier
        )

        AppDestination.FindRide -> FindRideScreen(
            openRides = viewModel.openRides,
            searchResults = viewModel.searchResults,
            currentUserId = uiState.userId,
            onSearch = viewModel::searchRides,
            onOpenRide = { rideId ->
                viewModel.selectedRideId = rideId
                navigate(AppDestination.RideDetails)
            },
            onRefreshOpenRides = viewModel::loadOpenRides,
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        AppDestination.RideDetails -> RideDetailsScreen(
            rideDetails = viewModel.rideDetails,
            currentUserId = uiState.userId,
            canRequestSeat = uiState.activeRole == SessionRole.PASSENGER,
            isRequesting = viewModel.actionInFlight,
            onRequestSeat = { rideId, onDone -> viewModel.requestSeat(rideId, 1, onDone) },
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        AppDestination.RideRequests -> RideRequestsScreen(
            requests = viewModel.incomingRequests,
            onRespond = { requestId, status ->
                if (status == "ACCEPTED") {
                    viewModel.incomingRequests.data
                        ?.firstOrNull { it.request.requestId == requestId }
                        ?.let { viewModel.selectedRideId = it.request.rideId }
                }
                viewModel.respondToRequest(requestId, status)
            },
            onRefresh = viewModel::loadIncomingRequests,
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        AppDestination.ConfirmedRide -> ConfirmedRideScreen(
            rideDetails = viewModel.rideDetails,
            onOpenMessages = { navigate(AppDestination.Messages) },
            onNavigateBack = ::navigateUp,
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

        AppDestination.TripHistory -> TripHistoryScreen(
            trips = viewModel.tripHistory,
            onOpenRide = { rideId ->
                viewModel.selectedRideId = rideId
                navigate(AppDestination.RideDetails)
            },
            onRefresh = viewModel::loadTripHistory,
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        AppDestination.SustainabilityDashboard -> SustainabilityDashboardScreen(
            dashboard = viewModel.dashboard,
            onRefresh = viewModel::loadDashboard,
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        AppDestination.Settings -> SettingsScreen(
            uiState = uiState,
            onPassengerEnabledChange = viewModel::updatePassengerEnabled,
            onClearLocalCache = viewModel::clearLocalCache,
            onSignOut = ::returnToLogin,
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )
    }
}
