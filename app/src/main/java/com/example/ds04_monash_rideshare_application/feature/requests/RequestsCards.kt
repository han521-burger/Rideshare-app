package com.example.ds04_monash_rideshare_application.feature.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestStatus
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.ui.describeLocation

@Composable
private fun RequestsCard(tag: String, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun RequestsChip(text: String, expired: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp),
        color = if (expired) colors.secondaryContainer else colors.surfaceVariant,
        contentColor = if (expired) colors.onSecondaryContainer else colors.onSurfaceVariant) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CompactRoute(places: Pair<String, String>) {
    val colors = MaterialTheme.colorScheme
    Column {
        listOf(places.first, places.second).forEachIndexed { index, place ->
            Text(place, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().drawBehind {
                    val p = Offset(5.dp.toPx(), 9.dp.toPx())
                    if (index == 0) {
                        drawLine(colors.outlineVariant, p, Offset(p.x, size.height + p.y), 2.dp.toPx())
                        drawCircle(colors.primary, 4.dp.toPx(), p)
                    } else drawCircle(colors.primary, 4.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                }.padding(start = 22.dp, bottom = if (index == 0) 12.dp else 0.dp)
                    .semantics { contentDescription = "${if (index == 0) "From" else "To"} $place" })
        }
    }
}

@Composable
private fun CardAffordance(text: String) {
    // The Card owns navigation; this visual label deliberately has no second click action.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RequestCard(request: RideRequestSummary, incoming: Boolean, onClick: () -> Unit,
    distanceLabel: String? = null, onRequestAgain: (() -> Unit)? = null) {
    val expired = request.status == RequestStatus.EXPIRED
    val conflict = conflictSupport(request)
    RequestsCard("request-${request.requestId}", onClick) {
        if (incoming) request.passenger?.let { Text(it.displayName, fontWeight = FontWeight.SemiBold) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RequestsChip(requestsStatus(request.status, incoming), expired)
            Text(requestTime(request), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        requestPlaces(request)?.let { CompactRoute(it) }
        if (expired) Text(EXPIRED_SUPPORT, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (conflict != null) Text(conflict, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (incoming) Text(if (request.ride != null) "Attached to your ride" else "Standalone request",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else request.ride?.let { ride ->
            val context = listOfNotNull(ride.driver?.displayName?.let { "Driver $it" },
                ride.vehicle?.let { "${it.make} ${it.model}" }).joinToString(" · ")
            if (context.isNotBlank()) Text(context, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        distanceLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        if ((request.matchScoreTotal ?: Double.NEGATIVE_INFINITY) >= 50) {
            Text("Roughly matches one of your posted routes", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary)
        }
        request.note?.takeIf(String::isNotBlank)?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${request.seatsRequested} ${if (request.seatsRequested == 1) "seat" else "seats"} requested",
                style = MaterialTheme.typography.bodySmall)
            CardAffordance(if (incoming) "Review" else "Details")
        }
        if ((expired || conflict != null) && onRequestAgain != null) {
            OutlinedButton(onClick = onRequestAgain, modifier = Modifier.heightIn(min = 48.dp)) { Text("Request again") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RequestsRideCard(ride: RideSummary, offer: Boolean, onClick: () -> Unit) {
    RequestsCard("ride-${ride.rideId}", onClick) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RequestsChip(if (offer) "Open for requests" else "${ride.seatsAvailable} ${if (ride.seatsAvailable == 1) "seat" else "seats"} free")
            Text(requestsDate(ride.departureTime), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CompactRoute(describeLocation(ride.originLat, ride.originLng) to describeLocation(ride.destinationLat, ride.destinationLng))
        if (!offer) ride.driver?.let { Text(it.displayName, style = MaterialTheme.typography.bodySmall) }
        ride.vehicle?.let { Text("${it.make} ${it.model}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (offer) "${ride.seatsTotal} seats open" else routeDistance(ride.distanceMeters),
                style = MaterialTheme.typography.bodySmall)
            CardAffordance("View ride")
        }
    }
}

@Composable
internal fun RequestsStateCard(title: String, message: String, tag: String, error: Boolean = false,
    retry: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        color = if (error) colors.errorContainer else colors.surfaceVariant,
        contentColor = if (error) colors.onErrorContainer else colors.onSurfaceVariant) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading(); if (error) liveRegion = LiveRegionMode.Polite })
            Text(message, style = MaterialTheme.typography.bodyMedium)
            retry?.let { Button(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") } }
        }
    }
}

@Composable
internal fun RequestsSkeletons() {
    Column(Modifier.testTag("requests-loading").clearAndSetSemantics {
        contentDescription = "Loading requests content"; liveRegion = LiveRegionMode.Polite
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(.4f, .85f, .7f).forEach { width ->
                        Box(Modifier.fillMaxWidth(width).height(16.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
                    }
                }
            }
        }
    }
}

// A passenger's repeating ask as one card. Tapping it shows each date; each date opens its own
// request, so the driver can still accept some dates and leave the rest.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RequestSeriesCard(series: RequestSeries, onOpenRequest: (String) -> Unit) {
    val first = series.requests.first()
    var expanded by rememberSaveable(series.key) { mutableStateOf(false) }
    RequestsCard(series.key, onClick = { expanded = !expanded }) {
        first.passenger?.let { Text(it.displayName, fontWeight = FontWeight.SemiBold) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RequestsChip("Repeats · ${series.requests.size} dates")
            Text(seriesSummary(series), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        requestPlaces(first)?.let { CompactRoute(it) }
        first.note?.takeIf(String::isNotBlank)?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (expanded) {
            series.requests.forEach { request ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "Review this date") { onOpenRequest(request.requestId) }
                        .testTag("series-date-${request.requestId}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(requestTime(request), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    CardAffordance("Review")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${first.seatsRequested} ${if (first.seatsRequested == 1) "seat" else "seats"} each",
                style = MaterialTheme.typography.bodySmall)
            CardAffordance(if (expanded) "Hide dates" else "Show dates")
        }
    }
}
