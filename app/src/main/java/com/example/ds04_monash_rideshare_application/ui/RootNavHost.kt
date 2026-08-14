package com.example.ds04_monash_rideshare_application.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ds04_monash_rideshare_application.Auth.FakeAuthenticationProvider

private object Routes {
    const val LOGIN = "login"
    const val ROLE_SELECTION = "role_selection"
    const val PASSENGER_DASHBOARD = "passenger_dashboard"
    const val DRIVER_DASHBOARD = "driver_dashboard"
    const val MAP = "map"
}

//Navigation skeleton: Login -> Role selection -> Passenger/Driver dashboard -> Map.
//Uses FakeAuthenticationProvider until Monash SSO access is approved (see Auth package).
@Composable
fun RootNavHost() {
    val navController = rememberNavController()
    val authProvider = remember { FakeAuthenticationProvider() }

    NavHost(navController = navController, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(
                authProvider = authProvider,
                onLoggedIn = {
                    navController.navigate(Routes.ROLE_SELECTION) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.ROLE_SELECTION) {
            RoleSelectionScreen(
                onPassengerSelected = {
                    navController.navigate(Routes.PASSENGER_DASHBOARD) {
                        popUpTo(Routes.ROLE_SELECTION) { inclusive = true }
                    }
                },
                onDriverSelected = {
                    navController.navigate(Routes.DRIVER_DASHBOARD) {
                        popUpTo(Routes.ROLE_SELECTION) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.PASSENGER_DASHBOARD) {
            PassengerDashboardScreen(
                authProvider = authProvider,
                onFindRide = { navController.navigate(Routes.MAP) }
            )
        }
        composable(Routes.DRIVER_DASHBOARD) {
            DriverDashboardScreen(
                authProvider = authProvider,
                onOfferRide = { navController.navigate(Routes.MAP) }
            )
        }
        composable(Routes.MAP) {
            MapRouteScreen(onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapRouteScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        MapScreen(modifier = Modifier.padding(innerPadding))
    }
}
