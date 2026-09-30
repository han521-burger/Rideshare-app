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
    data object RequestDetail : AppDestination("request-detail", "Request Detail")
    // Ranked search for an existing ride: criteria -> matches -> ride detail -> request seat.
    data object FindRide : AppDestination("find-ride", "Find a Ride")
    // Standalone ask - route + time, no driver picked yet (Search -> When -> Repeat -> Note -> Confirm).
    data object RequestRide : AppDestination("request-ride", "Request a Ride")
    // Requesting a seat on a ride already picked from Home/browsing (Repeat -> Note/Seats -> Confirm).
    data object RequestSeat : AppDestination("request-seat", "Request a Seat")
    data object OfferRide : AppDestination("offer-ride", "Offer a Ride")
    data object Messages : AppDestination("messages", "Messages")
    data object Leaderboard : AppDestination("leaderboard", "Leaderboard")
    data object Vehicles : AppDestination("vehicles", "My Vehicles")

    companion object {
        val tabDestinations: List<AppDestination>
            get() = listOf(Home, Requests, Activity, Profile)

        val allDestinations: List<AppDestination>
            get() = listOf(
                Splash, Login, Home, Requests, Activity, Profile,
                RideDetail, RequestDetail, FindRide, RequestRide, RequestSeat, OfferRide, Messages, Leaderboard, Vehicles
            )

        fun fromRoute(route: String?): AppDestination? =
            allDestinations.firstOrNull { it.route == route }
    }
}
