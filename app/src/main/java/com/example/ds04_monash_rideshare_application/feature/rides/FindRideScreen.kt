package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSearchCriteria
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.DatePickerField
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.core.ui.TimePickerField
import com.example.ds04_monash_rideshare_application.core.ui.combineDateAndTime
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import com.example.ds04_monash_rideshare_application.ui.haversineMeters
import com.yandex.mapkit.geometry.Point
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val RIDE_SEARCH_LOCATIONS = listOf(
    NamedLocation("Melbourne CBD", Point(-37.8136, 144.9631)),
    NamedLocation("Oakleigh Station", Point(-37.9002, 145.0886)),
    NamedLocation("Huntingdale Station", Point(-37.9112, 145.1029)),
    NamedLocation("Clayton Station", Point(-37.9246, 145.1203)),
    NamedLocation("Glen Waverley Station", Point(-37.8795, 145.1621)),
    NamedLocation("Chadstone", Point(-37.8862, 145.0733)),
    NamedLocation("Monash Clayton", Point(-37.9105, 145.1362))
)

private val MONASH_CLAYTON = RIDE_SEARCH_LOCATIONS.last()

internal data class RideSearchFormResult(
    val criteria: RideSearchCriteria? = null,
    val errorMessage: String? = null
)

internal fun validateRideSearch(
    origin: NamedLocation?,
    destination: NamedLocation?,
    date: LocalDate?,
    time: LocalTime?,
    seatsNeeded: Int,
    nowMillis: Long = System.currentTimeMillis()
): RideSearchFormResult {
    if (origin == null || destination == null) {
        return RideSearchFormResult(errorMessage = "Choose both a pickup point and a drop-off point.")
    }
    if (origin.point.latitude == destination.point.latitude &&
        origin.point.longitude == destination.point.longitude
    ) {
        return RideSearchFormResult(errorMessage = "Pickup and drop-off points must be different.")
    }
    if (date == null || time == null) {
        return RideSearchFormResult(errorMessage = "Choose your preferred travel date and time.")
    }
    if (seatsNeeded !in 1..8) {
        return RideSearchFormResult(errorMessage = "Passenger count must be between 1 and 8.")
    }

    val departureTime = combineDateAndTime(date, time)
        ?: return RideSearchFormResult(errorMessage = "Choose a valid travel date and time.")
    if (departureTime <= nowMillis) {
        return RideSearchFormResult(errorMessage = "Choose a travel time in the future.")
    }

    return RideSearchFormResult(
        criteria = RideSearchCriteria(
            originLat = origin.point.latitude,
            originLng = origin.point.longitude,
            destinationLat = destination.point.latitude,
            destinationLng = destination.point.longitude,
            departureTime = departureTime,
            seatsNeeded = seatsNeeded
        )
    )
}

@Composable
fun FindRideScreen(
    searchResults: Resource<List<RideSummary>>,
    initialCriteria: RideSearchCriteria?,
    onSearch: (RideSearchCriteria) -> Unit,
    onOpenRide: (rideId: String) -> Unit,
    onPostRequest: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val initialDate = initialCriteria?.departureTime?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
    }
    val initialTime = initialCriteria?.departureTime?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalTime()
    }

    var origin by remember(initialCriteria) {
        mutableStateOf(initialCriteria?.let { closestSearchLocation(it.originLat, it.originLng) })
    }
    var destination by remember(initialCriteria) {
        mutableStateOf(initialCriteria?.let { closestSearchLocation(it.destinationLat, it.destinationLng) })
    }
    var date by remember(initialCriteria) { mutableStateOf(initialDate) }
    var time by remember(initialCriteria) { mutableStateOf(initialTime) }
    var seatsNeeded by remember(initialCriteria) { mutableIntStateOf(initialCriteria?.seatsNeeded ?: 1) }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    RideshareScaffold(
        title = "Find a ride",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Search existing offers before posting a new request.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Where are you travelling?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text("Direction", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = destination == MONASH_CLAYTON,
                                onClick = {
                                    destination = MONASH_CLAYTON
                                    if (origin == MONASH_CLAYTON) origin = null
                                },
                                label = { Text("To Monash") }
                            )
                            FilterChip(
                                selected = origin == MONASH_CLAYTON,
                                onClick = {
                                    origin = MONASH_CLAYTON
                                    if (destination == MONASH_CLAYTON) destination = null
                                },
                                label = { Text("From Monash") }
                            )
                        }

                        LocationPicker(
                            label = "Pickup / closest station",
                            selected = origin,
                            onSelected = { origin = it }
                        )
                        LocationPicker(
                            label = "Drop-off point",
                            selected = destination,
                            onSelected = { destination = it }
                        )

                        DatePickerField("Travel date", date, onDateSelected = { date = it })
                        TimePickerField("Preferred pickup time", time, onTimeSelected = { time = it })

                        Text("Passengers", fontWeight = FontWeight.SemiBold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            OutlinedButton(
                                onClick = { if (seatsNeeded > 1) seatsNeeded-- }
                            ) { Text("−") }
                            Text(
                                seatsNeeded.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            OutlinedButton(
                                onClick = { if (seatsNeeded < 8) seatsNeeded++ }
                            ) { Text("+") }
                        }

                        validationMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }

                        Button(
                            onClick = {
                                val validation = validateRideSearch(
                                    origin = origin,
                                    destination = destination,
                                    date = date,
                                    time = time,
                                    seatsNeeded = seatsNeeded
                                )
                                validationMessage = validation.errorMessage
                                validation.criteria?.let(onSearch)
                            },
                            enabled = !searchResults.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Find matching rides")
                        }
                    }
                }
            }

            if (initialCriteria != null || searchResults.isLoading ||
                searchResults.data != null || searchResults.errorMessage != null
            ) {
                item {
                    Text(
                        "Matching rides",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                when {
                    searchResults.isLoading -> item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    searchResults.errorMessage != null -> item {
                        Text(searchResults.errorMessage, color = MaterialTheme.colorScheme.error)
                    }
                    searchResults.data.isNullOrEmpty() -> item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("No matching rides were found.")
                                Text(
                                    "You can post a pending request so a driver can respond.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = onPostRequest,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Post a ride request")
                                }
                            }
                        }
                    }
                    else -> items(searchResults.data.orEmpty(), key = { it.rideId }) { ride ->
                        MatchRideCard(ride = ride, onOpenRide = { onOpenRide(ride.rideId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationPicker(
    label: String,
    selected: NamedLocation?,
    onSelected: (NamedLocation) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        selected?.let {
            Text(
                "Selected: ${it.name}",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RIDE_SEARCH_LOCATIONS, key = { it.name }) { location ->
                FilterChip(
                    selected = selected == location,
                    onClick = { onSelected(location) },
                    label = { Text(location.name) }
                )
            }
        }
    }
}

@Composable
private fun MatchRideCard(
    ride: RideSummary,
    onOpenRide: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ride.driver?.displayName ?: "Monash driver",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                ride.matchScoreTotal?.let { score ->
                    Text(
                        "%.0f%% match".format(score),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                "${describeLocation(ride.originLat, ride.originLng)} → " +
                    describeLocation(ride.destinationLat, ride.destinationLng)
            )
            Text(
                SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
                    .format(Date(ride.departureTime))
            )
            Text(
                "${ride.seatsAvailable} seat(s) free · %.1f km · about ${ride.estimatedDurationSeconds / 60} min"
                    .format(ride.distanceMeters / 1000.0),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onOpenRide, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("View details and request a seat")
            }
        }
    }
}

private fun closestSearchLocation(lat: Double, lng: Double): NamedLocation =
    RIDE_SEARCH_LOCATIONS.minBy { location ->
        haversineMeters(lat, lng, location.point.latitude, location.point.longitude)
    }
