package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverRequestNotification(
    request: RideRequestSummary,
    totalWaiting: Int,
    isActionInFlight: Boolean,
    errorMessage: String?,
    onReview: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    // Swiping only hides this banner locally - it does not decline the request, which stays
    // PENDING and reachable from the Requests tab.
    onSwipeDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                onSwipeDismiss()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth(),
        backgroundContent = {}
    ) {
        DriverRequestNotificationCard(request, totalWaiting, isActionInFlight, errorMessage, onReview, onAccept, onDecline)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriverRequestNotificationCard(
    request: RideRequestSummary,
    totalWaiting: Int,
    isActionInFlight: Boolean,
    errorMessage: String?,
    onReview: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
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
        modifier = Modifier.fillMaxWidth(),
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
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
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
                if (!largeText) WaitingRequestChip(totalWaiting)
            }
            // The badge must not be squeezed to zero width by enlarged heading text.
            if (largeText) WaitingRequestChip(totalWaiting)

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
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        enabled = !isActionInFlight,
                        modifier = (if (largeText) Modifier else Modifier.weight(1f)).heightIn(min = 48.dp)
                    ) { Text("Decline") }
                    TextButton(onClick = onReview, enabled = !isActionInFlight) { Text("Review") }
                    Button(
                        onClick = onAccept,
                        enabled = !isActionInFlight,
                        modifier = (if (largeText) Modifier else Modifier.weight(1f)).heightIn(min = 48.dp)
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

@Composable
private fun WaitingRequestChip(totalWaiting: Int) {
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
