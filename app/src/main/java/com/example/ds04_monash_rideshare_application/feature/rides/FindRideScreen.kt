package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.example.ds04_monash_rideshare_application.Repository.Remote.RankedRide
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSearchCriteria
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.ui.MapScreen
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
import java.text.SimpleDateFormat
import java.util.Locale

// Item 5/6: passenger search form, ranked by the backend's matching pipeline (FR-04).
@Composable
fun FindRideScreen(
    results: Resource<List<RankedRide>>,
    onSearch: (RideSearchCriteria) -> Unit,
    onOpenRide: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var route by remember { mutableStateOf<RouteQueryResult?>(null) }
    var timeText by remember { mutableStateOf("") }

    RideshareScaffold(
        title = "Find Ride",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) { contentPadding ->
        val selectedRoute = route
        if (selectedRoute == null) {
            Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
                Text(
                    "Tap the map to choose where you're travelling from and to.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
                MapScreen(modifier = Modifier.fillMaxSize(), onRouteConfirmed = { route = it })
            }
        } else {
            SearchAndResults(
                route = selectedRoute,
                timeText = timeText,
                onTimeTextChange = { timeText = it },
                results = results,
                contentPadding = contentPadding,
                onChooseDifferentRoute = { route = null },
                onSearch = {
                    val departureMillis = parseTimeToday(timeText) ?: (System.currentTimeMillis() + 60 * 60_000)
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
}

@Composable
private fun SearchAndResults(
    route: RouteQueryResult,
    timeText: String,
    onTimeTextChange: (String) -> Unit,
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
            OutlinedTextField(
                value = timeText,
                onValueChange = onTimeTextChange,
                label = { Text("Preferred departure time, 24h (HH:mm)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
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
                        "No matching rides yet. Try a wider time window.",
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

private fun parseTimeToday(time: String): Long? {
    if (time.isBlank()) return null
    return try {
        val parts = time.split(":")
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, parts[0].toInt())
        calendar.set(java.util.Calendar.MINUTE, parts[1].toInt())
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        calendar.timeInMillis
    } catch (e: Exception) {
        null
    }
}
