package com.example.ds04_monash_rideshare_application.ui

import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer

// Plain-language labels for the raw backend status strings (RideOffer.STATUS_* on the ride side,
// backend RideRequestStatus on the request side) - a tester should never see a bare enum name
// like "PENDING" or "OPEN" with no context for what it means.
fun rideStatusLabel(status: String): String = when (status) {
    RideOffer.STATUS_OPEN -> "Open for requests"
    RideOffer.STATUS_CONFIRMED -> "Confirmed"
    RideOffer.STATUS_COMPLETED -> "Completed"
    RideOffer.STATUS_CANCELLED -> "Cancelled"
    else -> status
}

fun requestStatusLabel(status: String): String = when (status) {
    "PENDING" -> "Waiting for driver"
    "ACCEPTED" -> "Accepted"
    "REJECTED" -> "Declined"
    "CANCELLED" -> "Cancelled"
    "COMPLETED" -> "Completed"
    else -> status
}
