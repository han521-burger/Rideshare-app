package com.example.ds04_monash_rideshare_application.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import com.example.ds04_monash_rideshare_application.feature.home.HomeScreen
import com.example.ds04_monash_rideshare_application.feature.profile.ProfileScreen
import com.example.ds04_monash_rideshare_application.feature.role.RoleSelectionScreen
import com.example.ds04_monash_rideshare_application.feature.shared.PlaceholderScreen
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

    when (currentDestination) {
        AppDestination.Splash -> SplashScreen(
            onFinished = { replaceStack(AppDestination.Login) }
        )

        AppDestination.Login -> LoginScreen(
            uiState = uiState,
            onSignIn = {
                viewModel.signIn {
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

        AppDestination.CreateRide,
        AppDestination.FindRide,
        AppDestination.RideDetails,
        AppDestination.RideRequests,
        AppDestination.ConfirmedRide,
        AppDestination.Messages,
        AppDestination.TripHistory,
        AppDestination.SustainabilityDashboard,
        AppDestination.Settings -> {
            val action = placeholderAction(currentDestination, ::navigate)
            PlaceholderScreen(
                destination = currentDestination,
                onNavigateBack = ::navigateUp,
                primaryActionLabel = action?.first,
                onPrimaryAction = action?.second ?: {},
                modifier = modifier
            )
        }
    }
}

private fun placeholderAction(
    destination: AppDestination,
    onNavigate: (AppDestination) -> Unit
): Pair<String, () -> Unit>? = when (destination) {
    AppDestination.CreateRide -> "Choose a route on the map" to {
        onNavigate(AppDestination.Map)
    }
    AppDestination.FindRide -> "Open sample ride details" to {
        onNavigate(AppDestination.RideDetails)
    }
    else -> null
}
