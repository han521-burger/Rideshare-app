package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer
import com.example.ds04_monash_rideshare_application.Repository.Remote.RankedRide
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSearchCriteria
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.DatePickerField
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.core.ui.TimePickerField
import com.example.ds04_monash_rideshare_application.core.ui.combineDateAndTime
import com.example.ds04_monash_rideshare_application.ui.MapScreen
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

// Item 5/6: primary flow is a plain browsable list of every OPEN ride (GET /rides, no filters) -
// tap one and you're done. "Search by route & time" is an optional, secondary flow for narrowing
// results by the matching pipeline (FR-04) when the plain list is too long to scan.
@Composable
fun FindRideScreen(
    openRides: Resource<List<RideOffer>>,
    searchResults: Resource<List<RankedRide>>,
    currentUserId: String?,
    onSearch: (RideSearchCriteria) -> Unit,
    onOpenRide: (String) -> Unit,
    onRefreshOpenRides: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showRouteSearch by remember { mutableStateOf(false) }

    RideshareScaffold(
        title = "Find Ride",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        actionLabel = if (showRouteSearch) null else "Refresh",
        onAction = onRefreshOpenRides,
        modifier = modifier
    ) { contentPadding ->
        if (showRouteSearch) {
            RouteSearchFlow(
                results = searchResults,
                onSearch = onSearch,
                onOpenRide = onOpenRide,
                onBackToList = { showRouteSearch = false },
                contentPadding = contentPadding
            )
        } else {
            OpenRideList(
                openRides = openRides,
                currentUserId = currentUserId,
                onOpenRide = onOpenRide,
                onSearchByRoute = { showRouteSearch = true },
                contentPadding = contentPadding
            )
        }
    }
}

@Composable
private fun OpenRideList(
    openRides: Resource<List<RideOffer>>,
    currentUserId: String?,
    onOpenRide: (String) -> Unit,
    onSearchByRoute: () -> Unit,
    contentPadding: PaddingValues
) {
    Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
        OutlinedButton(
            onClick = onSearchByRoute,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Text("Search by specific route & time instead")
        }

        when {
            openRides.isLoading -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            openRides.errorMessage != null -> Text(
                openRides.errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )

            else -> {
                val rides = openRides.data.orEmpty().filter { it.driverId != currentUserId }
                if (rides.isEmpty()) {
                    Text("No open rides right now.", modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(rides, key = { it.rideId }) { ride ->
                            OpenRideCard(ride, onClick = { onOpenRide(ride.rideId) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenRideCard(ride: RideOffer, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(ride.departureTime),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${describeLocation(ride.originLat, ride.originLng)} -> ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("${ride.seatsAvailable} seat(s) available", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RouteSearchFlow(
    results: Resource<List<RankedRide>>,
    onSearch: (RideSearchCriteria) -> Unit,
    onOpenRide: (String) -> Unit,
    onBackToList: () -> Unit,
    contentPadding: PaddingValues
) {
    var route by remember { mutableStateOf<RouteQueryResult?>(null) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf<LocalTime?>(null) }

    val selectedRoute = route
    if (selectedRoute == null) {
        Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            Row {
                OutlinedButton(onClick = onBackToList, modifier = Modifier.padding(16.dp)) {
                    Text("Back to ride list")
                }
            }
            Text(
                "Tap the map to choose where you're travelling from and to.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            MapScreen(modifier = Modifier.fillMaxSize(), onRouteConfirmed = { route = it })
        }
    } else {
        SearchAndResults(
            route = selectedRoute,
            date = date,
            onDateChange = { date = it },
            time = time,
            onTimeChange = { time = it },
            results = results,
            contentPadding = contentPadding,
            onChooseDifferentRoute = { route = null },
            onSearch = {
                val departureMillis = combineDateAndTime(date, time) ?: (System.currentTimeMillis() + 60 * 60_000)
                onSearch(
                    RideSearchCriteria(
                        selectedRoute.originLat, selectedRoute.originLng,
                        selectedRoute.destinationLat, selectedRoute.destinationLng,
                        departureMillis, 1
                    )
                )
            },
            onOpenRide = onOpenRide
        )
    }
}

@Composable
private fun SearchAndResults(
    route: RouteQueryResult,
    date: LocalDate?,
    onDateChange: (LocalDate) -> Unit,
    time: LocalTime?,
    onTimeChange: (LocalTime) -> Unit,
    results: Resource<List<RankedRide>>,
    contentPadding: PaddingValues,
    onChooseDifferentRoute: () -> Unit,
    onSearch: () -> Unit,
    onOpenRide: (String) -> Unit
) {
    Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "%.1f km route selected".format(route.distanceMeters / 1000.0),
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = onChooseDifferentRoute) { Text("Choose a different route") }
            DatePickerField(label = "Preferred departure date", selectedDate = date, onDateSelected = onDateChange)
            TimePickerField(label = "Preferred departure time", selectedTime = time, onTimeSelected = onTimeChange)
            Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) { Text("Search rides") }
        }

        when {
            results.isLoading -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            results.errorMessage != null -> Text(
                results.errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )

            results.data != null -> {
                if (results.data.isEmpty()) {
                    Text(
                        "No matching rides yet. Try a wider time window or a different date.",
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(results.data, key = { it.ride.rideId }) { ranked ->
                            RankedRideCard(ranked, onClick = { onOpenRide(ranked.ride.rideId) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RankedRideCard(ranked: RankedRide, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Departs " + SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(ranked.ride.departureTime),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${describeLocation(ranked.ride.originLat, ranked.ride.originLng)} -> ${describeLocation(ranked.ride.destinationLat, ranked.ride.destinationLng)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("${ranked.ride.seatsAvailable} seat(s) available", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Match score: %.0f%%%s".format(
                    ranked.matchScore,
                    if (ranked.fromFallbackMatch) " (nearby match)" else ""
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
