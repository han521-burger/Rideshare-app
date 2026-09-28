package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideStatus

internal fun canCompleteRide(driverId: String, userId: String?, status: String): Boolean =
    userId != null && driverId == userId && status in setOf(RideStatus.OPEN, RideStatus.CONFIRMED)

@Composable
internal fun CompleteRideAction(
    rideId: String,
    driverId: String,
    userId: String?,
    status: String,
    inFlight: Boolean,
    onComplete: () -> Unit
) {
    if (!canCompleteRide(driverId, userId, status)) return
    var confirming by remember(rideId) { mutableStateOf(false) }
    Button(onClick = { confirming = true }, enabled = !inFlight, modifier = Modifier.fillMaxWidth()) {
        if (inFlight) CircularProgressIndicator() else Text("Complete ride")
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
