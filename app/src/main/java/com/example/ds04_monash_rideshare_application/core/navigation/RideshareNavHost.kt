package com.example.ds04_monash_rideshare_application.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideStatus
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import com.example.ds04_monash_rideshare_application.core.ui.BottomNavScaffold
import com.example.ds04_monash_rideshare_application.feature.activity.ActivityScreen
import com.example.ds04_monash_rideshare_application.feature.activity.LeaderboardScreen
import com.example.ds04_monash_rideshare_application.feature.profile.VehiclesScreen
import com.example.ds04_monash_rideshare_application.feature.auth.LoginScreen
import com.example.ds04_monash_rideshare_application.feature.auth.SplashScreen
import com.example.ds04_monash_rideshare_application.feature.home.HomeScreen
import com.example.ds04_monash_rideshare_application.feature.messages.MessagesScreen
import com.example.ds04_monash_rideshare_application.feature.profile.ProfileScreen
import com.example.ds04_monash_rideshare_application.feature.requests.RequestDetailScreen
import com.example.ds04_monash_rideshare_application.feature.requests.RequestsScreen
import com.example.ds04_monash_rideshare_application.feature.rides.FindRideScreen
import com.example.ds04_monash_rideshare_application.feature.rides.OfferRideFlowScreen
import com.example.ds04_monash_rideshare_application.feature.rides.RequestRideFlowScreen
import com.example.ds04_monash_rideshare_application.feature.rides.RequestSeatFlowScreen
import com.example.ds04_monash_rideshare_application.feature.rides.RideDetailScreen
import kotlinx.coroutines.delay

@Composable
fun RideshareNavHost(
    viewModel: RideshareViewModel,
    modifier: Modifier = Modifier
) {
    val uiState = viewModel.uiState
    var backStack by rememberSaveable {
        mutableStateOf(listOf(AppDestination.Splash.route))
    }

    var registeringForDriver by rememberSaveable { mutableStateOf(false) }

    fun navigate(destination: AppDestination) {
        if (backStack.lastOrNull() != destination.route) {
            backStack = backStack + destination.route
        }
    }

    fun replaceStack(destination: AppDestination) {
        backStack = listOf(destination.route)
    }

    fun navigateUp() {
        if (backStack.lastOrNull() == AppDestination.Vehicles.route && registeringForDriver) {
            registeringForDriver = false
            viewModel.cancelDriverRegistration()
        }
        if (backStack.size > 1) {
            backStack = backStack.dropLast(1)
        }
    }

    fun switchTab(destination: AppDestination) {
        viewModel.clearRequestActionError()
        backStack = listOf(destination.route)
    }

    fun returnToLogin() {
        viewModel.signOut {
            replaceStack(AppDestination.Login)
        }
    }

    // A request always opens its own decision screen. Attached requests include the ride summary;
    // standalone ones also let a driver select or create a suitable ride before accepting.
    fun openRequest(requestId: String) {
        viewModel.clearRequestActionError()
        viewModel.selectedRequestId = requestId
        navigate(AppDestination.RequestDetail)
    }

    BackHandler(enabled = backStack.size > 1) {
        navigateUp()
    }

    val currentDestination = AppDestination.fromRoute(backStack.lastOrNull()) ?: AppDestination.Splash
    val currentTab = AppDestination.tabDestinations.firstOrNull { it.route == backStack.firstOrNull() }
        ?: AppDestination.Home

    var resumeRefresh by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalView.current.findViewTreeLifecycleOwner()
    DisposableEffect(lifecycleOwner) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) paused = true
            if (event == Lifecycle.Event.ON_RESUME && paused) {
                paused = false
                resumeRefresh++
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    // Central place to (re)load data whenever a data-driven destination becomes active, so every
    // entry point into it (tab switch, drilling into a ride, mode toggle, etc.) stays in sync
    // without each caller having to remember to trigger the right load itself.
    LaunchedEffect(currentDestination, viewModel.selectedRideId, viewModel.selectedRequestId, viewModel.activeMode, resumeRefresh) {
        when (currentDestination) {
            AppDestination.Home -> {
                viewModel.loadMyDriverRides()
                viewModel.loadMyRequests()
            }
            AppDestination.Requests -> {
                viewModel.loadMyRequests()
                viewModel.loadIncomingRequests()
                if (viewModel.activeMode == AppMode.PASSENGER) {
                    viewModel.loadOpenRides()
                } else {
                    viewModel.loadMyDriverRides()
                    // Kept fresh here (not just on the Vehicles screen itself) so the Offer-a-Ride
                    // FAB's "you need a vehicle first" gate has up-to-date data before it's tapped.
                    viewModel.loadVehicles()
                }
            }
            AppDestination.Activity -> {
                viewModel.loadDashboard()
                viewModel.loadTripHistory()
                viewModel.loadAnalytics()
            }
            AppDestination.Profile -> {
                viewModel.loadProfile()
                viewModel.loadFavorites()
                viewModel.loadVehicles()
            }
            AppDestination.RideDetail -> {
                viewModel.selectedRideId?.let { viewModel.loadRideDetails(it) }
                viewModel.loadIncomingRequests()
                viewModel.loadMyRequests()
            }
            AppDestination.RequestSeat -> {
                viewModel.selectedRideId?.let { viewModel.loadRideDetails(it) }
            }
            AppDestination.RequestDetail -> {
                viewModel.loadMyRequests()
                viewModel.loadIncomingRequests()
                viewModel.loadMyDriverRides()
            }
            AppDestination.Messages -> viewModel.selectedRideId?.let { viewModel.loadMessages(it) }
            AppDestination.Leaderboard -> viewModel.loadLeaderboard()
            AppDestination.Vehicles -> viewModel.loadVehicles()
            else -> Unit
        }
    }

    // The trial backend does not have push notifications yet. While a signed-in driver is on any
    // main tab, refresh quietly so a newly-arrived request can animate into view without requiring
    // a tab switch or manual refresh. The ViewModel prevents overlapping network calls.
    LaunchedEffect(uiState.userId, viewModel.activeMode, currentDestination) {
        if (uiState.isSignedIn && viewModel.activeMode == AppMode.DRIVER &&
            currentDestination in AppDestination.tabDestinations
        ) {
            while (true) {
                viewModel.loadIncomingRequests(showLoading = false, requireFresh = false)
                delay(8_000)
            }
        }
    }

    if (viewModel.driverAccess.checking) {
        AlertDialog(onDismissRequest = viewModel::cancelDriverRegistration,
            title = { Text("Checking your vehicle") }, text = { CircularProgressIndicator() },
            confirmButton = { TextButton(onClick = viewModel::cancelDriverRegistration) { Text("Cancel") } })
    }
    viewModel.driverModeError?.let { error ->
        AlertDialog(onDismissRequest = viewModel::clearDriverModeError,
            title = { Text("Unable to switch to Driver") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = viewModel::clearDriverModeError) { Text("OK") } })
    }

    if (currentDestination in AppDestination.tabDestinations) {
        val pendingDriverRequests = viewModel.incomingPendingRequests
        val newestDriverRequest = pendingDriverRequests.maxByOrNull { it.createdAt }
        BottomNavScaffold(
            activeMode = viewModel.activeMode,
            onModeChange = { mode ->
                viewModel.clearRequestActionError()
                // Mirrors Driver -> Passenger: stay on whichever tab you're already on. Only the
                // post-registration flow below (coming from the Vehicles screen, not a tab) jumps
                // to Home, since there's nothing meaningful to return to there.
                viewModel.updateActiveMode(mode,
                    onRegistrationRequired = { registeringForDriver = true; navigate(AppDestination.Vehicles) })
            },
            currentTab = currentTab,
            onTabSelected = ::switchTab,
            requestsBadgeCount = if (viewModel.activeMode == AppMode.DRIVER) {
                pendingDriverRequests.size
            } else {
                viewModel.myPendingRequests.size
            },
            driverRequest = newestDriverRequest,
            driverRequestCount = pendingDriverRequests.size,
            isRequestActionInFlight = viewModel.actionInFlight,
            requestActionError = viewModel.requestActionError,
            onReviewDriverRequest = ::openRequest,
            onAcceptDriverRequest = { requestId ->
                viewModel.respondToRequest(requestId, RequestStatus.ACCEPTED)
            },
            onDeclineDriverRequest = { requestId ->
                viewModel.respondToRequest(requestId, RequestStatus.REJECTED)
            },
            greetingName = uiState.greetingName,
            modifier = modifier
        ) { contentPadding ->
            Box(modifier = Modifier.padding(contentPadding)) {
                when (currentDestination) {
                    AppDestination.Home -> HomeScreen(
                        activeMode = viewModel.activeMode,
                        currentUserId = uiState.userId,
                        confirmedRides = viewModel.confirmedRides,
                        myDriverRides = viewModel.myDriverRides,
                        onOpenRide = { rideId ->
                            viewModel.selectedRideId = rideId
                            navigate(AppDestination.RideDetail)
                        }
                    )

                    AppDestination.Requests -> RequestsScreen(
                        activeMode = viewModel.activeMode,
                        // Pending requests remain here until the driver accepts, rejects, or the
                        // passenger cancels. Accepted requests move to the Home confirmed list.
                        myRequests = viewModel.myRequests.data.orEmpty().filter { it.status == RequestStatus.PENDING },
                        incomingRequests = viewModel.incomingRequests.data.orEmpty().filter { it.status == RequestStatus.PENDING },
                        openRides = viewModel.openRides,
                        // OPEN offers wait for their first approved passenger; confirmed rides
                        // already appear on Home and remain reachable from there.
                        myOffers = viewModel.myDriverRides.data.orEmpty().filter { it.status == RideStatus.OPEN },
                        onOpenRequest = ::openRequest,
                        onOpenRide = { rideId ->
                            viewModel.selectedRideId = rideId
                            navigate(AppDestination.RideDetail)
                        },
                        onFindRide = { navigate(AppDestination.FindRide) },
                        onRequestRide = { navigate(AppDestination.RequestRide) },
                        onOfferRide = {
                            // A driver needs at least one saved vehicle before offering a ride, so
                            // CO2/fuel-saved figures reflect what they actually drive rather than
                            // always falling back to the petrol-default estimate.
                            if (viewModel.vehicles.data.orEmpty().isEmpty()) {
                                navigate(AppDestination.Vehicles)
                            } else {
                                navigate(AppDestination.OfferRide)
                            }
                        }
                    )

                    AppDestination.Activity -> ActivityScreen(
                        currentUserId = uiState.userId,
                        dashboard = viewModel.dashboard,
                        analytics = viewModel.analytics,
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
                        vehicle = viewModel.vehicles.data.orEmpty().firstOrNull(),
                        onOpenVehicles = { navigate(AppDestination.Vehicles) },
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
            requestActionError = viewModel.requestActionError,
            emergencyContactPhone = viewModel.profileDetails.data?.emergencyContactPhone,
            onRequestSeat = { navigate(AppDestination.RequestSeat) },
            onCancelMyRequest = { requestId -> viewModel.respondToRequest(requestId, RequestStatus.CANCELLED) },
            onAcceptRequest = { requestId -> viewModel.respondToRequest(requestId, RequestStatus.ACCEPTED) },
            onRejectRequest = { requestId -> viewModel.respondToRequest(requestId, RequestStatus.REJECTED) },
            onSetEnRoute = { enRoute ->
                viewModel.selectedRideId?.let { viewModel.setDriverEnRoute(it, enRoute) }
            },
            onCancelRide = {
                viewModel.selectedRideId?.let { viewModel.cancelRide(it) }
            },
            onCompleteRide = {
                viewModel.selectedRideId?.let { viewModel.completeRide(it) }
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

        AppDestination.RequestDetail -> {
            val request = (viewModel.myRequests.data.orEmpty() + viewModel.incomingRequests.data.orEmpty())
                .firstOrNull { it.requestId == viewModel.selectedRequestId }
            val minSeats = request?.seatsRequested ?: 1
            RequestDetailScreen(
                request = request,
                currentUserId = uiState.userId,
                myOpenRidesForAccept = viewModel.myDriverRides.data.orEmpty()
                    .filter {
                        it.status in listOf(RideStatus.OPEN, RideStatus.CONFIRMED) && it.seatsAvailable >= minSeats
                    },
                isActionInFlight = viewModel.actionInFlight,
                actionErrorMessage = viewModel.requestActionError,
                onAcceptAttached = {
                    request?.let {
                        viewModel.respondToRequest(it.requestId, RequestStatus.ACCEPTED) { success, _ ->
                            if (success) navigateUp()
                        }
                    }
                },
                onAcceptStandalone = { targetRideId ->
                    request?.let {
                        viewModel.respondToRequest(it.requestId, RequestStatus.ACCEPTED, targetRideId) { success, _ ->
                            if (success) navigateUp()
                        }
                    }
                },
                onCreateRideAndAccept = { seatsTotal ->
                    request?.let {
                        viewModel.respondToRequest(it.requestId, RequestStatus.ACCEPTED, targetRideId = null, newRideSeatsTotal = seatsTotal) { success, _ ->
                            if (success) navigateUp()
                        }
                    }
                },
                onDecline = {
                    request?.let {
                        viewModel.respondToRequest(it.requestId, RequestStatus.REJECTED) { success, _ ->
                            if (success) navigateUp()
                        }
                    }
                },
                onCancel = {
                    request?.let {
                        viewModel.respondToRequest(it.requestId, RequestStatus.CANCELLED) { success, _ ->
                            if (success) navigateUp()
                        }
                    }
                },
                onNavigateBack = ::navigateUp,
                modifier = modifier
            )
        }

        AppDestination.RequestRide -> RequestRideFlowScreen(
            isSubmitting = viewModel.actionInFlight,
            onSubmit = { draft, onDone ->
                viewModel.createStandaloneRequest(draft) { success, error ->
                    onDone(error)
                    if (success) navigateUp()
                }
            },
            onExit = ::navigateUp,
            modifier = modifier
        )

        AppDestination.FindRide -> FindRideScreen(
            searchResults = viewModel.searchResults,
            initialCriteria = viewModel.lastSearchCriteria,
            onSearch = viewModel::searchRides,
            onOpenRide = { rideId ->
                viewModel.selectedRideId = rideId
                navigate(AppDestination.RideDetail)
            },
            onPostRequest = { navigate(AppDestination.RequestRide) },
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        AppDestination.RequestSeat -> RequestSeatFlowScreen(
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
            maxSeats = viewModel.vehicles.data.orEmpty().firstOrNull()?.let { it.seatCapacity - 1 } ?: 1,
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

        AppDestination.Vehicles -> VehiclesScreen(
            vehicles = viewModel.vehicles,
            isSaving = viewModel.vehicleSaving,
            onAddVehicle = { draft, onDone ->
                viewModel.createVehicle(draft) { error ->
                    onDone(error)
                    if (error == null && registeringForDriver) {
                        viewModel.updateActiveMode(AppMode.DRIVER,
                            onDriverActivated = { registeringForDriver = false; replaceStack(AppDestination.Home) })
                    }
                }
            },
            onUpdateVehicle = { vehicleId, draft, onDone -> viewModel.updateVehicle(vehicleId, draft, onDone) },
            onNavigateBack = ::navigateUp,
            modifier = modifier
        )

        else -> Unit
    }
}
