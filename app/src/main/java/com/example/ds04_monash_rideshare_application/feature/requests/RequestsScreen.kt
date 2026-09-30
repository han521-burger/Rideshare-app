package com.example.ds04_monash_rideshare_application.feature.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.location.geocodeText
import com.example.ds04_monash_rideshare_application.core.location.requestDrivingRoute
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.requestStatusLabel
import com.yandex.mapkit.geometry.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private const val TAB_LEFT = 0
private const val TAB_RIGHT = 1

// Two sub-tabs, same shape in both modes: left is "mine" (a passenger's own asks / a driver's
// own still-empty offers), right is "others'" (rides to browse and join / requests to accept).
// The "+" only ever lives on the left tab, and only does the thing relevant to the active mode -
// posting a standalone request (passenger) or opening Offer-a-Ride (driver).
@Composable
fun RequestsScreen(
    activeMode: AppMode,
    myRequests: List<RideRequestSummary>,
    incomingRequests: List<RideRequestSummary>,
    openRides: Resource<List<RideSummary>>,
    myOffers: List<RideSummary>,
    onOpenRequest: (requestId: String) -> Unit,
    onOpenRide: (rideId: String) -> Unit,
    onFindRide: () -> Unit,
    onRequestRide: () -> Unit,
    onOfferRide: () -> Unit,
    modifier: Modifier = Modifier
) {
    var subTab by rememberSaveable { mutableStateOf(TAB_LEFT) }
    val isDriver = activeMode == AppMode.DRIVER
    val leftLabel = if (isDriver) "My Offers" else "Pending"
    val rightLabel = if (isDriver) "Requests" else "Rides I can join"

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = subTab) {
                Tab(selected = subTab == TAB_LEFT, onClick = { subTab = TAB_LEFT }, text = { Text(leftLabel) })
                Tab(selected = subTab == TAB_RIGHT, onClick = { subTab = TAB_RIGHT }, text = { Text(rightLabel) })
            }

            if (isDriver) {
                when (subTab) {
                    TAB_LEFT -> MyOffersList(myOffers, onOpenRide)
                    else -> IncomingRequestsList(incomingRequests, onOpenRequest)
                }
            } else {
                when (subTab) {
                    TAB_LEFT -> PendingList(myRequests, "No pending rides - tap + to request one!", onOpenRequest)
                    else -> BrowseRidesList(openRides, onOpenRide, onFindRide)
                }
            }
        }

        if (subTab == TAB_LEFT) {
            FloatingActionButton(
                onClick = { if (isDriver) onOfferRide() else onRequestRide() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        }
    }
}

@Composable
private fun PendingList(requests: List<RideRequestSummary>, emptyMessage: String, onOpenRequest: (requestId: String) -> Unit) {
    if (requests.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(requests, key = { it.requestId }) { request ->
            RequestRow(request, onClick = { onOpenRequest(request.requestId) })
        }
        item {} // trailing space so the FAB doesn't sit on top of the last card
    }
}

private val SEAT_FILTER_OPTIONS = listOf(1, 2, 3)

// Driver-only view of incoming requests: a postcode search bar sorts every pending request by
// real driving distance/ETA from that point (nearest first, nothing hidden), plus seats/ASAP
// filters - all just narrowing/reordering the same list the driver already sees, so a request
// never disappears just because it's far away.
@Composable
private fun IncomingRequestsList(requests: List<RideRequestSummary>, onOpenRequest: (requestId: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var postcodeText by remember { mutableStateOf("") }
    var resolvedPoint by remember { mutableStateOf<Point?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var seatsFilter by remember { mutableStateOf<Int?>(null) }
    var scheduleFilter by remember { mutableStateOf<Boolean?>(null) }

    val routesByRequestId = remember { mutableStateMapOf<String, RouteQueryResult>() }

    LaunchedEffect(resolvedPoint, requests) {
        val point = resolvedPoint ?: return@LaunchedEffect
        for (request in requests) {
            val origin = requestOrigin(request) ?: continue
            scope.launch {
                try {
                    routesByRequestId[request.requestId] = requestDrivingRoute(context, point, origin)
                } catch (_: Exception) {
                    // Leave this request without a distance - it still shows, just unsorted.
                }
            }
        }
    }

    val filtered = requests.filter { request ->
        (seatsFilter == null || request.seatsRequested == seatsFilter) &&
            (scheduleFilter == null || request.isAsap == scheduleFilter)
    }
    val sorted = if (resolvedPoint != null) {
        filtered.sortedBy { routesByRequestId[it.requestId]?.distanceMeters ?: Double.MAX_VALUE }
    } else {
        filtered
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = postcodeText,
                    onValueChange = { postcodeText = it },
                    label = { Text("Your postcode or suburb") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = postcodeText.isNotBlank() && !isSearching,
                    onClick = {
                        val query = postcodeText
                        scope.launch {
                            isSearching = true
                            searchError = null
                            try {
                                resolvedPoint = geocodeText(context, query)
                                routesByRequestId.clear()
                            } catch (e: Exception) {
                                resolvedPoint = null
                                searchError = e.message ?: "Couldn't find that postcode"
                            } finally {
                                isSearching = false
                            }
                        }
                    }
                ) {
                    if (isSearching) CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp) else Text("Sort by distance")
                }
            }
            searchError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (resolvedPoint != null) {
                TextButton(onClick = { resolvedPoint = null; routesByRequestId.clear() }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Clear distance sort")
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = scheduleFilter == null, onClick = { scheduleFilter = null }, label = { Text("Any time") })
                FilterChip(selected = scheduleFilter == true, onClick = { scheduleFilter = true }, label = { Text("ASAP") })
                FilterChip(selected = scheduleFilter == false, onClick = { scheduleFilter = false }, label = { Text("Scheduled") })
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                item {
                    FilterChip(selected = seatsFilter == null, onClick = { seatsFilter = null }, label = { Text("Any seats") })
                }
                items(SEAT_FILTER_OPTIONS) { seats ->
                    FilterChip(
                        selected = seatsFilter == seats,
                        onClick = { seatsFilter = seats },
                        label = { Text(if (seats == SEAT_FILTER_OPTIONS.last()) "$seats+" else "$seats") }
                    )
                }
            }
        }

        if (sorted.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No one's waiting on you right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(sorted, key = { it.requestId }) { request ->
                val route = routesByRequestId[request.requestId]
                val distanceLabel = route?.let { "%.1f km • %d min drive".format(it.distanceMeters / 1000.0, it.estimatedDurationSeconds / 60) }
                RequestRow(request, onClick = { onOpenRequest(request.requestId) }, distanceLabel = distanceLabel)
            }
            item {} // trailing space so the FAB doesn't sit on top of the last card
        }
    }
}

// The point a driver would drive to for this request - the ride's own origin if one's already
// attached, otherwise the passenger's standalone ask origin.
private fun requestOrigin(request: RideRequestSummary): Point? {
    val ride = request.ride
    if (ride != null) return Point(ride.originLat, ride.originLng)
    val lat = request.originLat
    val lng = request.originLng
    return if (lat != null && lng != null) Point(lat, lng) else null
}

@Composable
private fun MyOffersList(offers: List<RideSummary>, onOpenRide: (rideId: String) -> Unit) {
    if (offers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No open offers yet - tap + to post a ride.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(offers, key = { it.rideId }) { ride -> MyOfferRow(ride, onClick = { onOpenRide(ride.rideId) }) }
        item {} // trailing space so the FAB doesn't sit on top of the last card
    }
}

@Composable
private fun MyOfferRow(ride: RideSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                fontWeight = FontWeight.SemiBold
            )
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
            Text("Waiting for passengers - ${ride.seatsTotal} seat(s) open", color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(
                progress = { 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun BrowseRidesList(
    openRides: Resource<List<RideSummary>>,
    onOpenRide: (rideId: String) -> Unit,
    onFindRide: () -> Unit
) {
    when {
        openRides.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        openRides.errorMessage != null -> Text(openRides.errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        else -> {
            val rides = openRides.data.orEmpty()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Button(onClick = onFindRide, modifier = Modifier.fillMaxWidth()) {
                        Text("Search by route and time")
                    }
                }
                if (rides.isEmpty()) {
                    item { Text("No open rides posted right now - check back soon.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(rides, key = { it.rideId }) { ride -> OpenRideRow(ride, onClick = { onOpenRide(ride.rideId) }) }
                }
                item {} // trailing space so the FAB doesn't sit on top of the last card
            }
        }
    }
}

@Composable
private fun OpenRideRow(ride: RideSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                fontWeight = FontWeight.SemiBold
            )
            Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
            Text("${ride.seatsAvailable} seat(s) free · %.1f km".format(ride.distanceMeters / 1000.0))
        }
    }
}

@Composable
private fun RequestRow(request: RideRequestSummary, onClick: () -> Unit, distanceLabel: String? = null) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            val ride = request.ride
            when {
                ride != null -> {
                    Text(
                        "${describeLocation(ride.originLat, ride.originLng)} → ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ride.departureTime)))
                }
                request.originLat != null && request.originLng != null &&
                    request.destinationLat != null && request.destinationLng != null -> {
                    Text(
                        "${describeLocation(request.originLat, request.originLng)} → ${describeLocation(request.destinationLat, request.destinationLng)}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (request.isAsap) "ASAP" else request.desiredDepartureTime?.let {
                            SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(it))
                        } ?: "No time set"
                    )
                }
            }
            request.passenger?.let { passenger -> Text("From ${passenger.displayName}") }
            Text("${request.seatsRequested} seat(s) · ${requestStatusLabel(request.status)}", color = MaterialTheme.colorScheme.primary)
            distanceLabel?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            request.matchScoreTotal?.let { score ->
                if (score >= 50) {
                    Text(
                        "Roughly matches one of your posted routes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            request.note?.takeIf { it.isNotBlank() }?.let { Text("\"$it\"") }
        }
    }
}
