package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.location.approvedRouteEndpoints
import com.example.ds04_monash_rideshare_application.core.location.geocodeText
import com.example.ds04_monash_rideshare_application.core.location.nearestApprovedPoints
import com.example.ds04_monash_rideshare_application.core.location.requestDrivingRoute
import com.example.ds04_monash_rideshare_application.feature.rides.directionSummary
import com.example.ds04_monash_rideshare_application.feature.rides.hubDistanceLabel
import com.example.ds04_monash_rideshare_application.feature.rides.nearestHeading
import com.example.ds04_monash_rideshare_application.feature.rides.routeKmLabel
import com.example.ds04_monash_rideshare_application.feature.rides.routeMinutesLabel
import com.example.ds04_monash_rideshare_application.feature.rides.searchCardTitle
import com.example.ds04_monash_rideshare_application.feature.rides.searchPointNoun
import com.example.ds04_monash_rideshare_application.ui.MONASH_CAMPUSES
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class RoutePickerResult(
    val origin: NamedLocation,
    val destination: NamedLocation,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Long
)

// Phase 8A/8B restyle: the state machine below is unchanged (campus -> direction -> search -> nearest 3
// approved points -> driving route). Only the presentation moved to cards, a direction segment and
// result rows. Request Ride and Offer Ride are its only consumers.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampusRoutePicker(onResult: (RoutePickerResult?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var fromMonash by remember { mutableStateOf(true) }
    var selectedCampus by remember { mutableStateOf<NamedLocation?>(null) }
    var query by remember { mutableStateOf("") }
    var queryLocation by remember { mutableStateOf<Point?>(null) }
    var selectedPoint by remember { mutableStateOf<NamedLocation?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchRequestId by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var routeResult by remember { mutableStateOf<RouteQueryResult?>(null) }
    var routing by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var routeAttempt by remember { mutableIntStateOf(0) }
    val campus = selectedCampus
    val approved = selectedPoint
    val choices = queryLocation?.let { nearestApprovedPoints(it.latitude, it.longitude, excluded = campus) }.orEmpty()
    val endpoints = if (campus != null && approved != null) approvedRouteEndpoints(campus, approved, fromMonash) else null
    // Never publish an old route with newly selected endpoints while a replacement request starts.
    val route = routeResult?.takeIf { result -> endpoints?.let { (origin, destination) ->
        result.originLat == origin.point.latitude && result.originLng == origin.point.longitude &&
            result.destinationLat == destination.point.latitude && result.destinationLng == destination.point.longitude
    } == true }

    LaunchedEffect(campus, approved, fromMonash, routeAttempt) {
        routeResult = null; routeError = null; routing = false
        if (endpoints != null) {
            routing = true
            try {
                routeResult = requestDrivingRoute(context, endpoints.first.point, endpoints.second.point)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { routeError = error.message ?: "Unable to calculate this route. Please try again." }
            finally { routing = false }
        }
    }
    LaunchedEffect(endpoints, route) {
        onResult(if (endpoints != null && route != null)
            RoutePickerResult(endpoints.first, endpoints.second, route.distanceMeters, route.estimatedDurationSeconds)
        else null)
    }

    // The explicit "Find" action (button or keyboard Search): identical steps to the original button.
    fun runSearch() {
        focus.clearFocus(); val requestId = ++searchRequestId; searchJob?.cancel(); queryLocation = null; selectedPoint = null
        routeResult = null; searchError = null; onResult(null)
        if (query.isBlank()) searchError = "Enter a postcode or suburb."
        else {
            val submitted = query.trim()
            searchJob = scope.launch {
                searching = true
                try {
                    queryLocation = geocodeText(context, submitted)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { searchError = error.message ?: "Location search is currently unavailable. Please try again." }
                finally { if (requestId == searchRequestId) searching = false }
            }
        }
    }

    val stacked = rememberLargeText()
    val colors = MaterialTheme.colorScheme
    val noun = searchPointNoun(fromMonash)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1 + 2: campus and direction
        FormSectionCard("Monash campus", Modifier.testTag("route-campus"), supporting = "Every trip starts or ends at a Monash campus.") {
            ChoiceChipGroup(
                options = MONASH_CAMPUSES, selected = campus, label = { it.name },
                onSelect = { location ->
                    if (selectedCampus != location) { onResult(null); selectedCampus = location; selectedPoint = null }
                },
                groupDescription = "Monash campus"
            )
            HorizontalDivider(color = colors.outlineVariant)
            Text("Direction", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            DirectionSegment(fromMonash, modifier = Modifier.testTag("route-direction"), onChange = { toFrom ->
                if (fromMonash != toFrom) { onResult(null); fromMonash = toFrom }
            })
            Text(directionSummary(campus?.name, fromMonash), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }

        // 3 + 4: search and nearest approved points
        FormSectionCard(
            searchCardTitle(fromMonash), Modifier.testTag("route-search"),
            supporting = "Search a postcode or suburb to see the nearest approved $noun points."
        ) {
            val field: @Composable (Modifier) -> Unit = { m ->
                FormTextField(
                    value = query,
                    onValueChange = {
                        searchRequestId++; searchJob?.cancel(); searching = false; query = it; queryLocation = null
                        selectedPoint = null; routeResult = null; searchError = null; onResult(null)
                    },
                    label = "Postcode or suburb",
                    modifier = m.testTag("route-query"),
                    errorText = searchError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (!searching) runSearch() }),
                    supportingText = "Enter a 4-digit postcode (e.g. 3000) or a suburb / place name (e.g. Glen Waverley)."
                )
            }
            val find: @Composable (Modifier) -> Unit = { m ->
                Button(
                    onClick = { if (!searching) runSearch() },
                    modifier = m.heightIn(min = 56.dp).testTag("route-find").semantics {
                        if (searching) { disabled(); stateDescription = "Searching" }
                    },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (searching) BusyLabel("Searching…", colors.onPrimary)
                    else {
                        Icon(Icons.Rounded.Search, null, Modifier.size(20.dp))
                        Text("Find", Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { field(Modifier.fillMaxWidth()); find(Modifier.fillMaxWidth()) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    // The outlined field reserves half its label height above the box; match it.
                    field(Modifier.weight(1f)); find(Modifier.padding(top = 8.dp))
                }
            }

            if (queryLocation != null) {
                HorizontalDivider(color = colors.outlineVariant)
                Text(nearestHeading(fromMonash), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose an approved Monash Rideshare point, nearest first. Distances are approximate, straight-line from your search location.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
                )
                Column(
                    Modifier.fillMaxWidth().selectableGroup().testTag("route-results")
                        .semantics { contentDescription = "Nearest approved $noun points" },
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    choices.forEachIndexed { index, choice ->
                        HubOptionRow(
                            name = choice.location.name,
                            roleLine = if (choice.location.isCampus) "Monash campus" else "Approved $noun point",
                            distanceLabel = hubDistanceLabel(choice.distanceMeters),
                            selected = approved == choice.location,
                            closest = index == 0,
                            onClick = { if (selectedPoint != choice.location) { onResult(null); selectedPoint = choice.location } }
                        )
                    }
                }
                if (choices.isEmpty()) FormInfoRow("No approved points available for this selection.")
            }
        }

        // 5 + 6: selected route context
        if (endpoints != null) {
            FormSectionCard("Your route", Modifier.testTag("route-card")) {
                RouteTimeline(endpoints.first.name, endpoints.second.name)
                if (routing) {
                    Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { BusyLabel("Calculating route…", colors.primary) }
                }
                routeError?.let {
                    FormBanner(it, FormBannerKind.Error, title = "Couldn’t calculate this route")
                    OutlinedButton(onClick = { routeAttempt++ }, Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Text("Retry route", Modifier.padding(start = 8.dp))
                    }
                }
                if (route != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FormStatusPill("Route ready", success = true, icon = { Icon(Icons.Rounded.Check, null, Modifier.size(14.dp)) })
                        FormStatusPill(routeKmLabel(route.distanceMeters))
                        FormStatusPill("${routeMinutesLabel(route.estimatedDurationSeconds)} drive")
                    }
                    RouteMiniMap(
                        originLat = endpoints.first.point.latitude, originLng = endpoints.first.point.longitude,
                        destinationLat = endpoints.second.point.latitude, destinationLng = endpoints.second.point.longitude,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp))
                    )
                }
            }
        }
    }
}
