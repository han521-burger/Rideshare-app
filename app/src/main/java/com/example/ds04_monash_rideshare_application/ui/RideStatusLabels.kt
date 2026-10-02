package com.example.ds04_monash_rideshare_application.ui

import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideStatus

// Plain-language labels for the raw backend status strings (RideStatus/RequestStatus in
// RideModels.kt) - a tester should never see a bare status like "PENDING" or "OPEN" with no
// context for what it means.
fun rideStatusLabel(status: String): String = when (status) {
    RideStatus.OPEN -> "Open for requests"
    RideStatus.CONFIRMED -> "Confirmed"
    RideStatus.COMPLETED -> "Completed"
    RideStatus.CANCELLED -> "Cancelled"
    RideStatus.EXPIRED -> "Expired - nobody joined"
    else -> status
}

fun requestStatusLabel(status: String): String = when (status) {
    RequestStatus.PENDING -> "Waiting for driver"
    RequestStatus.ACCEPTED -> "Accepted"
    RequestStatus.REJECTED -> "Declined"
    RequestStatus.CANCELLED -> "Cancelled"
    RequestStatus.COMPLETED -> "Completed"
    RequestStatus.EXPIRED -> "Expired - no driver accepted in time"
    else -> status
}
