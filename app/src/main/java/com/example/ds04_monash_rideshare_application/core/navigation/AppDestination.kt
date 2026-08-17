package com.example.ds04_monash_rideshare_application.core.navigation

sealed class AppDestination(val route: String, val title: String) {
    data object Splash : AppDestination("splash", "Monash Rideshare")
    data object Login : AppDestination("login", "Sign in")

    // Bottom-nav tabs. Same four routes regardless of active mode (Passenger/Driver) - the mode
    // toggle changes what each tab shows, it doesn't fork the navigation graph.
    data object Home : AppDestination("home", "Home")
    data object Requests : AppDestination("requests", "Requests")
    data object Activity : AppDestination("activity", "Activity")
    data object Profile : AppDestination("profile", "Profile")

    data object RideDetail : AppDestination("ride-detail", "Ride Detail")
    data object RequestRide : AppDestination("request-ride", "Request a Ride")
    data object OfferRide : AppDestination("offer-ride", "Offer a Ride")
    data object Messages : AppDestination("messages", "Messages")
    data object Leaderboard : AppDestination("leaderboard", "Leaderboard")

    companion object {
        val tabDestinations: List<AppDestination>
            get() = listOf(Home, Requests, Activity, Profile)

        val allDestinations: List<AppDestination>
            get() = listOf(
                Splash, Login, Home, Requests, Activity, Profile,
                RideDetail, RequestRide, OfferRide, Messages, Leaderboard
            )

        fun fromRoute(route: String?): AppDestination? =
            allDestinations.firstOrNull { it.route == route }
    }
}
