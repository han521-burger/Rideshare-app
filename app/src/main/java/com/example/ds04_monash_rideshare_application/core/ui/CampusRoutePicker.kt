package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.location.geocodeText
import com.example.ds04_monash_rideshare_application.core.location.requestDrivingRoute
import com.example.ds04_monash_rideshare_application.ui.FIXED_POINTS
import com.example.ds04_monash_rideshare_application.ui.MONASH_CAMPUSES
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
import com.example.ds04_monash_rideshare_application.ui.nearestFixedPoint
import kotlinx.coroutines.launch

data class RoutePickerResult(
    val origin: NamedLocation,
    val destination: NamedLocation,
    val distanceMeters: Double,
    val estimatedDurationSeconds: Long
)

// The one origin/destination picker shared by Offer-a-Ride and Request-a-Ride: every route in
// this app must start or end at a Monash campus, so campus selection always comes first, and the
// other end is found by postcode/suburb search rather than an unrestricted map tap. Reports the
// completed selection (with a real Yandex driving distance/ETA already resolved) via onResult,
// and null whenever the selection is incomplete - callers gate their wizard's "Next" on that.
@Composable
fun CampusRoutePicker(
    onResult: (RoutePickerResult?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var campusIsPickup by remember { mutableStateOf(true) }
    var selectedCampus by remember { mutableStateOf<NamedLocation?>(null) }
    var postcodeText by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var recommendedPoint by remember { mutableStateOf<NamedLocation?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var showManualPicker by remember { mutableStateOf(false) }

    var routeResult by remember { mutableStateOf<RouteQueryResult?>(null) }
    var isRouting by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }

    val campus = selectedCampus
    val other = recommendedPoint

    LaunchedEffect(campus, other, campusIsPickup) {
        routeResult = null
        routeError = null
        if (campus == null || other == null) return@LaunchedEffect
        isRouting = true
        try {
            val origin = if (campusIsPickup) campus.point else other.point
            val destination = if (campusIsPickup) other.point else campus.point
            routeResult = requestDrivingRoute(context, origin, destination)
        } catch (e: Exception) {
            routeError = e.message ?: "Couldn't calculate a route"
        } finally {
            isRouting = false
        }
    }

    LaunchedEffect(campus, other, routeResult, campusIsPickup) {
        val route = routeResult
        if (campus != null && other != null && route != null) {
            val origin = if (campusIsPickup) campus else other
            val destination = if (campusIsPickup) other else campus
            onResult(RoutePickerResult(origin, destination, route.distanceMeters, route.estimatedDurationSeconds))
        } else {
            onResult(null)
        }
    }

    Column(modifier = modifier) {
        Text("Which Monash campus?", fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(MONASH_CAMPUSES, key = { it.name }) { location ->
                FilterChip(
                    selected = selectedCampus == location,
                    onClick = { selectedCampus = location },
                    label = { Text(location.name) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = campusIsPickup,
                onClick = { campusIsPickup = true },
                label = { Text("Campus is pickup") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = !campusIsPickup,
                onClick = { campusIsPickup = false },
                label = { Text("Campus is drop-off") },
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            if (campusIsPickup) "Where are you dropping off? Enter a postcode or suburb."
            else "Where are you picking up from? Enter a postcode or suburb.",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = postcodeText,
                onValueChange = { postcodeText = it },
                label = { Text("Postcode or suburb") },
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
                        showManualPicker = false
                        try {
                            val point = geocodeText(context, query)
                            recommendedPoint = nearestFixedPoint(point.latitude, point.longitude)
                        } catch (e: Exception) {
                            recommendedPoint = null
                            searchError = e.message ?: "Couldn't find that postcode"
                        } finally {
                            isSearching = false
                        }
                    }
                }
            ) {
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                } else {
                    Text("Find")
                }
            }
        }
        searchError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        recommendedPoint?.let { point ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Nearest point: ${point.name}", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { showManualPicker = !showManualPicker }) { Text("Not right?") }
            }
        }

        if (showManualPicker) {
            Text(
                "Pick a different point:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                items(FIXED_POINTS, key = { it.name }) { location ->
                    FilterChip(
                        selected = recommendedPoint == location,
                        onClick = {
                            recommendedPoint = location
                            showManualPicker = false
                        },
                        label = { Text(location.name) }
                    )
                }
            }
        }

        if (isRouting) {
            Row(
                modifier = Modifier.padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                Text("Calculating route...")
            }
        }
        routeError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }

        val route = routeResult
        if (campus != null && other != null && route != null) {
            val origin = if (campusIsPickup) campus else other
            val destination = if (campusIsPickup) other else campus
            RouteMiniMap(
                originLat = origin.point.latitude,
                originLng = origin.point.longitude,
                destinationLat = destination.point.latitude,
                destinationLng = destination.point.longitude,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "${origin.name} → ${destination.name}  •  %.1f km  •  %d min".format(
                    route.distanceMeters / 1000.0,
                    route.estimatedDurationSeconds / 60
                ),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
