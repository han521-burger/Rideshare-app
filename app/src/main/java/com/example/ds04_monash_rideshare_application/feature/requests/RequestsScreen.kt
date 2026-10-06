package com.example.ds04_monash_rideshare_application.feature.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.location.geocodeText
import com.example.ds04_monash_rideshare_application.core.location.requestDrivingRoute
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.launch

private const val TAB_LEFT = 0

@Composable
fun RequestsScreen(
    activeMode: AppMode,
    myRequests: List<RideRequestSummary>,
    incomingRequests: List<RideRequestSummary>,
    openRides: Resource<List<RideSummary>>,
    myOffers: List<RideSummary>,
    myRequestsResource: Resource<List<RideRequestSummary>>,
    incomingRequestsResource: Resource<List<RideRequestSummary>>,
    myOffersResource: Resource<List<RideSummary>>,
    onRetryOpenRides: () -> Unit,
    onOpenRequest: (String) -> Unit,
    onOpenRide: (String) -> Unit,
    onFindRide: () -> Unit,
    onRequestRide: () -> Unit,
    onOfferRide: () -> Unit,
    modifier: Modifier = Modifier
) {
    // One shared index across role changes, as before.
    var subTab by rememberSaveable { mutableStateOf(TAB_LEFT) }
    val isDriver = activeMode == AppMode.DRIVER
    val labels = if (isDriver) listOf("My Offers", "Requests") else listOf("Pending", "Rides I can join")
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Text("Requests", Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    val selected = subTab == index
                    Column(Modifier.weight(1f).selectable(selected, role = Role.Tab, onClick = { subTab = index })
                        .testTag("requests-tab-$index"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center) {
                            Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.fillMaxWidth().height(3.dp).background(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                if (isDriver) {
                    when (subTab) {
                        TAB_LEFT -> MyOffersList(myOffers, myOffersResource, onOpenRide)
                        else -> IncomingRequestsList(incomingRequests, incomingRequestsResource, onOpenRequest)
                    }
                } else {
                    when (subTab) {
                        TAB_LEFT -> PendingList(myRequests, myRequestsResource, onOpenRequest, onRequestRide)
                        else -> BrowseRidesList(openRides, onOpenRide, onFindRide, onRetryOpenRides)
                    }
                }
            }
        }
        if (subTab == TAB_LEFT) {
            FloatingActionButton(onClick = { if (isDriver) onOfferRide() else onRequestRide() },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("requests-create")) {
                Icon(Icons.Rounded.Add, if (isDriver) "Offer a ride" else "Request a ride")
            }
        }
    }
}

@Composable
private fun RequestsList(resource: Resource<*>, empty: Boolean, emptyTitle: String, emptyMessage: String,
    errorTitle: String, hasFab: Boolean = false, retry: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null, content: LazyListScope.() -> Unit) {
    val state = requestsState(resource)
    LazyColumn(Modifier.fillMaxSize().testTag("requests-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (hasFab) 96.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        header?.let { item(key = "controls") { it() } }
        state.notice?.let { notice ->
            item(key = "notice") {
                Text(notice, Modifier.testTag("requests-notice").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (state.content) {
            RequestsContent.LOADING -> item(key = "loading") { RequestsSkeletons() }
            RequestsContent.ERROR -> item(key = "error") {
                RequestsStateCard(errorTitle, if (retry != null) "We couldn’t load available rides. Please try again."
                    else "Please check again when your connection is available.", "requests-error", true, retry)
            }
            RequestsContent.UNAVAILABLE -> item(key = "unavailable") {
                RequestsStateCard("Data not available yet", "No list response is available to display.", "requests-unavailable")
            }
            RequestsContent.CONTENT -> {
                if (empty) {
                    // A cached empty collection with a refresh/failure is not a new successful empty response.
                    if (state.notice == null) item(key = "empty") { RequestsStateCard(emptyTitle, emptyMessage, "requests-empty") }
                } else content()
            }
        }
    }
}

@Composable
private fun PendingList(requests: List<RideRequestSummary>, resource: Resource<*>,
    onOpenRequest: (String) -> Unit, onRequestAgain: () -> Unit) {
    RequestsList(resource, requests.isEmpty(), "No pending requests",
        "Requests you send wait here while drivers decide. Use + to request a ride.", "Couldn’t load your requests", hasFab = true) {
        items(requests, key = { it.requestId }) { request ->
            RequestCard(request, false, { onOpenRequest(request.requestId) }, onRequestAgain = onRequestAgain)
        }
    }
}

@Composable
private fun MyOffersList(offers: List<RideSummary>, resource: Resource<*>, onOpenRide: (String) -> Unit) {
    RequestsList(resource, offers.isEmpty(), "No open offers",
        "Post a ride to offer seats to other Monash travellers. Your open rides appear here.", "Couldn’t load your offers", hasFab = true) {
        items(offers, key = { it.rideId }) { ride -> RequestsRideCard(ride, true) { onOpenRide(ride.rideId) } }
    }
}

@Composable
private fun BrowseRidesList(openRides: Resource<List<RideSummary>>, onOpenRide: (String) -> Unit,
    onFindRide: () -> Unit, onRetry: () -> Unit) {
    RequestsList(openRides, openRides.data.orEmpty().isEmpty(), "No rides available to join",
        "No eligible rides are available right now. You can search by route and time.", "Couldn’t load available rides",
        retry = onRetry, header = if (requestsState(openRides).content == RequestsContent.CONTENT) {
            { FilledTonalButton(onClick = onFindRide, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Search by route and time")
            } }
        } else null) {
        items(openRides.data.orEmpty(), key = { it.rideId }) { ride -> RequestsRideCard(ride, false) { onOpenRide(ride.rideId) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncomingRequestsList(requests: List<RideRequestSummary>, resource: Resource<*>, onOpenRequest: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Preserve the existing lifetime: these leave composition with Driver Requests, not with disclosure controls.
    var postcodeText by remember { mutableStateOf("") }
    var resolvedPoint by remember { mutableStateOf<Point?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var seatsFilter by remember { mutableStateOf<Int?>(null) }
    var scheduleFilter by remember { mutableStateOf<Boolean?>(null) }
    val routesByRequestId = remember { mutableStateMapOf<String, RouteQueryResult>() }
    var expanded by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(resolvedPoint, requests) {
        val point = resolvedPoint ?: return@LaunchedEffect
        for (request in requests) {
            val origin = requestOrigin(request) ?: continue
            scope.launch {
                try { routesByRequestId[request.requestId] = requestDrivingRoute(context, point, origin) }
                catch (_: Exception) { /* Missing route stays visible and sorts last, as before. */ }
            }
        }
    }
    val sorted = filterIncomingRequests(requests, seatsFilter, scheduleFilter, resolvedPoint != null,
        routesByRequestId.mapValues { it.value.distanceMeters })
    val fontScale = LocalDensity.current.fontScale

    RequestsList(resource, sorted.isEmpty(),
        if (requests.isEmpty()) "No incoming requests" else "No requests match these filters",
        if (requests.isEmpty()) "Passenger requests will appear here when available." else "Try Any time and Any seats.",
        "Couldn’t load incoming requests", header = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("filter-disclosure").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("Filter requests", fontWeight = FontWeight.SemiBold)
                        Text(filterSummary(scheduleFilter, seatsFilter, resolvedPoint != null),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (expanded) {
                    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
                        OutlinedTextField(postcodeText, { postcodeText = it }, label = { Text("Postcode or suburb") },
                            singleLine = true, modifier = fieldModifier)
                    }
                    val sort: @Composable () -> Unit = {
                        OutlinedButton(enabled = postcodeText.isNotBlank() && !isSearching, onClick = {
                            val query = postcodeText
                            scope.launch {
                                isSearching = true
                                searchError = null
                                try { resolvedPoint = geocodeText(context, query); routesByRequestId.clear() }
                                catch (e: Exception) { resolvedPoint = null; searchError = e.message ?: "Couldn’t find that postcode" }
                                finally { isSearching = false }
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (isSearching) "Searching…" else "Sort by distance") }
                    }
                    if (fontScale > 1.3f) { field(Modifier.fillMaxWidth()); sort() }
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        field(Modifier.weight(1f)); Box(Modifier.widthIn(max = 140.dp)) { sort() }
                    }
                    searchError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (resolvedPoint != null) TextButton(onClick = { resolvedPoint = null; routesByRequestId.clear() }) { Text("Clear distance sort") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null, true, false).forEach { value ->
                            FilterChip(scheduleFilter == value, { scheduleFilter = value }, label = {
                                Text(when (value) { null -> "Any time"; true -> "ASAP"; false -> "Scheduled" })
                            }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf<Int?>(null) + SEAT_FILTER_OPTIONS).forEach { seats ->
                            FilterChip(seatsFilter == seats, { seatsFilter = seats }, label = { Text(seatFilterLabel(seats)) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("seat-filter-${seats ?: "any"}"))
                        }
                    }
                }
            }
        }) {
        // Soonest first (unless sorting by distance), with repeating asks grouped into one card.
        items(groupIncomingRequests(sorted, keepOrder = resolvedPoint != null), key = { it.key }) { entry ->
            when (entry) {
                is RequestSeries -> RequestSeriesCard(entry, onOpenRequest)
                is SingleRequest -> {
                    val request = entry.request
                    val route = routesByRequestId[request.requestId]
                    RequestCard(request, true, { onOpenRequest(request.requestId) },
                        distanceLabel = if (resolvedPoint != null) route?.let { drivingDistance(it.distanceMeters, it.estimatedDurationSeconds) } else null)
                }
            }
        }
    }
}

private fun requestOrigin(request: RideRequestSummary): Point? {
    request.ride?.let { return Point(it.originLat, it.originLng) }
    val lat = request.originLat
    val lng = request.originLng
    return if (lat != null && lng != null) Point(lat, lng) else null
}
