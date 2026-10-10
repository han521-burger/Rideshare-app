package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideStatus

internal fun canCompleteRide(driverId: String, userId: String?, status: String): Boolean =
    userId != null && driverId == userId && status in setOf(RideStatus.OPEN, RideStatus.CONFIRMED)

/**
 * A ride can only be completed once it has started: completing records the real arrival time (the server's own clock) that
 * the next journeys are judged by, so the backend refuses a ride that has not left yet (RIDE_NOT_STARTED). The button is held
 * back for the same reason, so the refusal is not the first the driver hears of it. The server clock is the authority; this
 * only avoids a button that would be refused.
 */
internal fun hasRideStarted(departureTime: Long, now: Long = System.currentTimeMillis()): Boolean = now >= departureTime

internal const val COMPLETE_AFTER_START_HINT = "You can complete this ride once it has started."

@Composable
internal fun CompleteRideAction(
    rideId: String,
    driverId: String,
    userId: String?,
    status: String,
    inFlight: Boolean,
    // Default 0 = "already started", so a caller that does not know the departure behaves as before. Kept ahead of onComplete
    // so the action can still be passed as the trailing lambda.
    departureTime: Long = 0L,
    onComplete: () -> Unit
) {
    if (!canCompleteRide(driverId, userId, status)) return
    var confirming by remember(rideId) { mutableStateOf(false) }
    val started = hasRideStarted(departureTime)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { confirming = true }, enabled = started && !inFlight, modifier = Modifier.fillMaxWidth()) {
            if (inFlight) CircularProgressIndicator() else Text("Complete ride")
        }
        if (!started) Text(COMPLETE_AFTER_START_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirming) AlertDialog(
        onDismissRequest = { if (!inFlight) confirming = false },
        title = { Text("Complete this ride?") },
        text = { Text("Accepted passenger bookings will be marked as completed. Requests still waiting for approval will be cancelled. This cannot be undone.") },
        confirmButton = {
            TextButton(enabled = !inFlight, onClick = {
                if (confirming && !inFlight) {
                    confirming = false
                    onComplete()
                }
            }) { Text("Complete ride") }
        },
        dismissButton = {
            TextButton(enabled = !inFlight, onClick = { confirming = false }) { Text("Cancel") }
        }
    )
}
