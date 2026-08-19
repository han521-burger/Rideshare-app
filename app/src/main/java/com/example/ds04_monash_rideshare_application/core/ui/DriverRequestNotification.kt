package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// An in-app notification for the active driver. It is intentionally a compact Monash-styled
// banner rather than a copy of any commercial rideshare card: enough context to make a safe
// decision, with the full request still one tap away.
@Composable
fun DriverRequestNotification(
    request: RideRequestSummary,
    totalWaiting: Int,
    isActionInFlight: Boolean,
    errorMessage: String?,
    onReview: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ride = request.ride
    val routeLabel = when {
        ride != null -> "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}"
        request.originLat != null && request.originLng != null &&
            request.destinationLat != null && request.destinationLng != null ->
            "${describeLocation(request.originLat, request.originLng)} → ${describeLocation(request.destinationLat, request.destinationLng)}"
        else -> "Campus ride request"
    }
    val departureTime = ride?.departureTime ?: request.desiredDepartureTime
    val canQuickRespond = request.rideId != null

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(9.dp)
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = "New passenger request",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = request.passenger?.displayName ?: "Monash passenger",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = if (totalWaiting > 1) "$totalWaiting WAITING" else "NEW",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = routeLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append("${request.seatsRequested} seat")
                    if (request.seatsRequested != 1) append("s")
                    departureTime?.let {
                        append("  •  ")
                        append(SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(it)))
                    }
                    if (request.isAsap) append("  •  ASAP")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            request.note?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "“$it”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            errorMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (canQuickRespond) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        enabled = !isActionInFlight,
                        modifier = Modifier.weight(1f)
                    ) { Text("Decline") }
                    TextButton(onClick = onReview, enabled = !isActionInFlight) { Text("Review") }
                    Button(
                        onClick = onAccept,
                        enabled = !isActionInFlight,
                        modifier = Modifier.weight(1f)
                    ) { Text("Accept") }
                }
            } else {
                Button(
                    onClick = onReview,
                    enabled = !isActionInFlight,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Review matching request") }
            }
        }
    }
}
