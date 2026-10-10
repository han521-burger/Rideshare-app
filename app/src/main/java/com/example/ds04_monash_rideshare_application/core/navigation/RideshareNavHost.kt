package com.example.ds04_monash_rideshare_application.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Snackbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.WarningSubject
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.RequestSubmitError
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import com.example.ds04_monash_rideshare_application.core.state.rideDetailScheduleWarning
import com.example.ds04_monash_rideshare_application.core.ui.BottomNavScaffold
import com.example.ds04_monash_rideshare_application.feature.activity.ActivityScreen
import com.example.ds04_monash_rideshare_application.feature.activity.ActivityTheme
import com.example.ds04_monash_rideshare_application.feature.activity.LeaderboardScreen
import com.example.ds04_monash_rideshare_application.feature.profile.VehiclesScreen
import com.example.ds04_monash_rideshare_application.feature.auth.LoginScreen
import com.example.ds04_monash_rideshare_application.feature.auth.SplashScreen
import com.example.ds04_monash_rideshare_application.feature.home.HomeScreen
import com.example.ds04_monash_rideshare_application.feature.messages.MessagesScreen
import com.example.ds04_monash_rideshare_application.feature.profile.ProfileScreen
import com.example.ds04_monash_rideshare_application.feature.requests.RequestDetailScreen
import com.example.ds04_monash_rideshare_application.feature.requests.RequestsScreen
import com.example.ds04_monash_rideshare_application.feature.requests.rideFitsRequestTime
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

    // "You've already sent this request" -> leave the form and open the request that already exists.
    fun viewExistingRequest(requestId: String) {
        navigateUp()
        viewModel.loadMyRequests()
        openRequest(requestId)
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
                // Accepting as a new ride caps its seats at the driver's own car.
                viewModel.loadVehicles()
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

    // While the Driver vehicle lookup runs, the shell's Driver segment shows "Checking vehicle…" (and
    // the Vehicles screen shows its hand-off banner) instead of a blocking dialog. Tapping Passenger
    // still cancels via updateActiveMode(PASSENGER) -> cancelDriverRegistration().
    // Accepting clashed with the driver's own ride request around the same time (backend
    // Lifecycle.kt) - let them choose to drive this passenger instead.
    viewModel.acceptConflict?.let { conflict ->
        AlertDialog(onDismissRequest = viewModel::dismissAcceptConflict,
            title = { Text("You have your own ride request then") }, text = { Text(conflict.message) },
            confirmButton = { TextButton(onClick = viewModel::confirmAcceptConflict) { Text("Drive them instead") } },
            dismissButton = { TextButton(onClick = viewModel::dismissAcceptConflict) { Text("Keep my request") } })
    }
    // A repeating request: which dates were made and which were not, in plain words.
    viewModel.submissionSummary?.let { summary ->
        AlertDialog(onDismissRequest = viewModel::dismissSubmissionSummary,
            title = { Text(summary.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    summary.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissSubmissionSummary) { Text("OK") } })
    }
    // A few seconds to take back an accidental Accept (e.g. a mis-tap on the banner).
    viewModel.undoableAcceptRequestId?.let { requestId ->
        LaunchedEffect(requestId) {
            delay(5_000)
            viewModel.clearUndoableAccept()
        }
        Popup(alignment = Alignment.BottomCenter) {
            Snackbar(
                action = { TextButton(onClick = viewModel::undoAccept) { Text("Undo") } },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
            ) { Text("Request accepted") }
        }
    }
    viewModel.driverModeError?.let { error ->
        // Same title, message and dismissal as before; only the surface styling follows the branded scheme.
        ActivityTheme {
            AlertDialog(onDismissRequest = viewModel::clearDriverModeError,
                shape = RoundedCornerShape(28.dp), containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface, textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                title = { Text("Unable to switch to Driver") }, text = { Text(error) },
                confirmButton = { TextButton(onClick = viewModel::clearDriverModeError) { Text("OK") } })
        }
    }

    if (currentDestination in AppDestination.tabDestinations) {
        val pendingDriverRequests = viewModel.incomingPendingRequests
        val newestDriverRequest = viewModel.driverBannerRequest
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
            onDismissDriverRequest = viewModel::dismissDriverBanner,
            onDeclineDriverRequest = { requestId ->
                viewModel.respondToRequest(requestId, RequestStatus.REJECTED)
            },
            greetingName = uiState.greetingName,
            driverChecking = viewModel.driverAccess.checking,
            modifier = modifier
        ) { contentPadding ->
            Box(modifier = Modifier.padding(contentPadding)) {
                when (currentDestination) {
                    AppDestination.Home -> HomeScreen(
                        activeMode = viewModel.activeMode,
                        currentUserId = uiState.userId,
                        confirmedRides = viewModel.confirmedRides,
                        myDriverRides = viewModel.myDriverRides,
                        myRequests = viewModel.myRequests,
                        onRetry = {
                            if (viewModel.activeMode == AppMode.PASSENGER) viewModel.loadMyRequests()
                            else viewModel.loadMyDriverRides()
                        },
                        onOpenRide = { rideId ->
                            viewModel.selectedRideId = rideId
                            navigate(AppDestination.RideDetail)
                        }
                    )

                    AppDestination.Requests -> RequestsScreen(
                        activeMode = viewModel.activeMode,
                        // Pending requests remain here until the driver accepts, rejects, or the
                        // passenger cancels. Accepted requests move to the Home confirmed list.
                        myRequests = viewModel.myPendingAndRecentlyEndedRequests,
                        incomingRequests = viewModel.incomingPendingRequests,
                        openRides = viewModel.openRides,
                        myRequestsResource = viewModel.myRequests,
                        incomingRequestsResource = viewModel.incomingRequests,
                        myOffersResource = viewModel.myDriverRides,
                        onRetryOpenRides = viewModel::loadOpenRides,
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
                        onRetryDashboard = { viewModel.loadDashboard() },
                        onRetryAnalytics = { viewModel.loadAnalytics() },
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
                        onSignOut = ::returnToLogin,
                        onChangePassword = viewModel::changePassword,
                        onDeleteAccount = { password, onDone ->
                            viewModel.deleteAccount(password) { error ->
                                onDone(error)
                                if (error == null) replaceStack(AppDestination.Login)
                            }
                        },
                        activeMode = viewModel.activeMode,
                        // The scaffold already reserves the bottom bar; without this, imePadding inside the screen
                        // would add the bar height again and leave a blank band above the keyboard.
                        modifier = Modifier.consumeWindowInsets(contentPadding)
                    )

                    else -> Unit
                }
            }
        }
        return
    }

    when (currentDestination) {
        // A session saved by "Keep me signed in" skips straight to Home if it's still valid.
        AppDestination.Splash -> SplashScreen(
            onFinished = {
                viewModel.restoreSession { signedIn ->
                    replaceStack(if (signedIn) AppDestination.Home else AppDestination.Login)
                }
            }
        )

        AppDestination.Login -> LoginScreen(
            uiState = uiState,
            onSignIn = { email, password, keepSignedIn ->
                viewModel.signIn(email, password, keepSignedIn) { replaceStack(AppDestination.Home) }
            },
            onSignUp = { name, email, password, keepSignedIn ->
                viewModel.signUp(name, email, password, keepSignedIn) { replaceStack(AppDestination.Home) }
            },
            onClearServerError = viewModel::clearAuthError,
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
            modifier = modifier,
            // The driver is told about their own later ride; a passenger about their own accepted seat on this one.
            scheduleWarning = rideDetailScheduleWarning(
                warnings = viewModel.scheduleWarnings.data,
                rideId = viewModel.selectedRideId,
                rideDriverId = viewModel.rideDetails.data?.driverId,
                userId = uiState.userId,
                myRequestIdForRide = viewModel.myRequests.data.orEmpty().firstOrNull { it.rideId == viewModel.selectedRideId }?.requestId
            )
        )

        AppDestination.RequestDetail -> {
            val request = (viewModel.myRequests.data.orEmpty() + viewModel.incomingRequests.data.orEmpty())
                .firstOrNull { it.requestId == viewModel.selectedRequestId }
            val minSeats = request?.seatsRequested ?: 1
            RequestDetailScreen(
                request = request,
                currentUserId = uiState.userId,
                // Only your rides leaving near the time they asked for (the backend allows 45 min
                // either side) - never another day's trip.
                myOpenRidesForAccept = viewModel.myDriverRides.data.orEmpty()
                    .filter {
                        it.status in listOf(RideStatus.OPEN, RideStatus.CONFIRMED) && it.seatsAvailable >= minSeats &&
                            request != null && rideFitsRequestTime(it, request)
                    },
                isActionInFlight = viewModel.actionInFlight,
                actionErrorMessage = viewModel.requestActionError,
                maxPassengerSeats = viewModel.vehicles.data.orEmpty().firstOrNull()?.let { it.seatCapacity - 1 },
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
                modifier = modifier,
                scheduleWarning = request?.takeIf { it.passengerId == uiState.userId }
                    ?.let { viewModel.warningMessageFor(WarningSubject.REQUEST, it.requestId) }
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
            onViewRequest = ::viewExistingRequest,
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
                    onDone(RequestSubmitError("No ride selected"))
                } else {
                    viewModel.requestSeat(rideId, seats, note, applyToRecurringGroup) { success, error ->
                        onDone(error)
                        if (success) navigateUp()
                    }
                }
            },
            onExit = ::navigateUp,
            onViewRequest = ::viewExistingRequest,
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

        AppDestination.Vehicles -> ActivityTheme {
            VehiclesScreen(
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
                modifier = modifier,
                driverSetup = registeringForDriver,
                driverChecking = viewModel.driverAccess.checking
            )
        }

        else -> Unit
    }
}
