package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateRideDraft
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.ui.MapScreen
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Item 5: "Map-based ride creation flow (select origin/destination -> route -> summary ->
// publish)". Step 1 reuses the existing Yandex MapScreen for the route pick; step 2 is the
// departure/seats/notes form.
@Composable
fun CreateRideScreen(
    isPublishing: Boolean,
    onPublish: (CreateRideDraft, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onNavigateBack: () -> Unit,
    onPublished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmedRoute by remember { mutableStateOf<RouteQueryResult?>(null) }
    var submitError by remember { mutableStateOf<String?>(null) }

    RideshareScaffold(
        title = "Create Ride",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) { contentPadding ->
        val route = confirmedRoute
        if (route == null) {
            Column(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
                Text(
                    text = "Tap the map to choose your origin, then your destination.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
                MapScreen(
                    modifier = Modifier.fillMaxSize(),
                    onRouteConfirmed = { confirmedRoute = it }
                )
            }
        } else {
            RideDetailsForm(
                route = route,
                isPublishing = isPublishing,
                serverError = submitError,
                contentPadding = contentPadding,
                onChooseDifferentRoute = { confirmedRoute = null },
                onPublish = { draft ->
                    submitError = null
                    onPublish(draft, onPublished) { message -> submitError = message }
                }
            )
        }
    }
}

@Composable
private fun RideDetailsForm(
    route: RouteQueryResult,
    isPublishing: Boolean,
    serverError: String?,
    contentPadding: PaddingValues,
    onChooseDifferentRoute: () -> Unit,
    onPublish: (CreateRideDraft) -> Unit
) {
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var seats by remember { mutableStateOf("1") }
    var notes by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Route summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "%.1f km · about %d min".format(route.distanceMeters / 1000.0, route.estimatedDurationSeconds / 60),
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onChooseDifferentRoute) { Text("Choose a different route") }

        OutlinedTextField(
            value = date,
            onValueChange = { date = it; validationError = null },
            label = { Text("Departure date (YYYY-MM-DD)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = time,
            onValueChange = { time = it; validationError = null },
            label = { Text("Departure time, 24h (HH:mm)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = seats,
            onValueChange = { seats = it.filter(Char::isDigit); validationError = null },
            label = { Text("Seats available") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes (optional)") },
            modifier = Modifier.fillMaxWidth()
        )

        (validationError ?: serverError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !isPublishing,
                onClick = {
                    val departureMillis = parseDepartureMillis(date, time)
                    val seatCount = seats.toIntOrNull() ?: 0
                    when {
                        departureMillis == null -> validationError = "Enter a valid future date and time."
                        departureMillis <= System.currentTimeMillis() -> validationError = "Departure must be in the future."
                        seatCount <= 0 -> validationError = "Seats available must be at least 1."
                        else -> onPublish(
                            CreateRideDraft(
                                null,
                                route.originLat, route.originLng,
                                route.destinationLat, route.destinationLng,
                                route.distanceMeters, route.estimatedDurationSeconds,
                                departureMillis, seatCount,
                                notes.trim().ifBlank { null }
                            )
                        )
                    }
                }
            ) {
                Text(if (isPublishing) "Publishing..." else "Publish ride")
            }
        }
    }
}

private fun parseDepartureMillis(date: String, time: String): Long? {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        format.timeZone = TimeZone.getDefault()
        format.isLenient = false
        format.parse("$date $time")?.time
    } catch (e: Exception) {
        null
    }
}
