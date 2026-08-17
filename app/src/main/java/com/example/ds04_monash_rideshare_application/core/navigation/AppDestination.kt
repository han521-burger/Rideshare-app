package com.example.ds04_monash_rideshare_application.core.navigation

sealed class AppDestination(val route: String, val title: String) {
    data object Splash : AppDestination("splash", "Monash Rideshare")
    data object Login : AppDestination("login", "Sign in")
    data object RoleSelection : AppDestination("role-selection", "Choose your role")
    data object PassengerHome : AppDestination("passenger-home", "Passenger Home")
    data object DriverHome : AppDestination("driver-home", "Driver Home")
    data object Profile : AppDestination("profile", "Profile")
    data object MyRides : AppDestination("my-rides", "My Rides")
    data object CreateRide : AppDestination("create-ride", "Create Ride")
    data object FindRide : AppDestination("find-ride", "Find Ride")
    data object RideDetails : AppDestination("ride-details", "Ride Details")
    data object RideRequests : AppDestination("ride-requests", "Ride Requests")
    data object ConfirmedRide : AppDestination("confirmed-ride", "Confirmed Ride")
    data object Messages : AppDestination("messages", "Messages")
    data object TripHistory : AppDestination("trip-history", "Trip History")
    data object SustainabilityDashboard :
        AppDestination("sustainability-dashboard", "Sustainability Dashboard")
    data object Settings : AppDestination("settings", "Settings")
    data object Map : AppDestination("map", "Route Map")

    companion object {
        val sprintOneDestinations: List<AppDestination>
            get() = listOf(
                Splash,
                Login,
                RoleSelection,
                PassengerHome,
                DriverHome,
                Profile,
                MyRides,
                CreateRide,
                FindRide,
                RideDetails,
                RideRequests,
                ConfirmedRide,
                Messages,
                TripHistory,
                SustainabilityDashboard,
                Settings,
                Map
            )

        // Nothing left as a placeholder - every Sprint 1 destination has a real screen now.
        val placeholders: List<AppDestination>
            get() = emptyList()

        fun fromRoute(route: String?): AppDestination? =
            sprintOneDestinations.firstOrNull { it.route == route }
    }
}
