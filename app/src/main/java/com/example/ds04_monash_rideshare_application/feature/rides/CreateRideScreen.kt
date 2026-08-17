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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateRideDraft
import com.example.ds04_monash_rideshare_application.core.ui.DatePickerField
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.core.ui.TimePickerField
import com.example.ds04_monash_rideshare_application.core.ui.combineDateAndTime
import com.example.ds04_monash_rideshare_application.ui.MapScreen
import com.example.ds04_monash_rideshare_application.ui.RouteQueryResult
import java.time.LocalDate
import java.time.LocalTime

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
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf<LocalTime?>(null) }
    var seats by remember { mutableStateOf(1) }
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

        DatePickerField(
            label = "Departure date",
            selectedDate = date,
            onDateSelected = { date = it; validationError = null }
        )
        TimePickerField(
            label = "Departure time",
            selectedTime = time,
            onTimeSelected = { time = it; validationError = null }
        )
        Text("Seats available", style = MaterialTheme.typography.bodyMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = { if (seats > 1) { seats--; validationError = null } },
                enabled = seats > 1
            ) { Text("-") }
            Text(
                "$seats",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.widthIn(min = 32.dp),
                textAlign = TextAlign.Center
            )
            OutlinedButton(
                onClick = { if (seats < 8) { seats++; validationError = null } },
                enabled = seats < 8
            ) { Text("+") }
        }
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
                    val departureMillis = combineDateAndTime(date, time)
                    when {
                        departureMillis == null -> validationError = "Choose a departure date and time."
                        departureMillis <= System.currentTimeMillis() -> validationError = "Departure must be in the future."
                        else -> onPublish(
                            CreateRideDraft(
                                null,
                                route.originLat, route.originLng,
                                route.destinationLat, route.destinationLng,
                                route.distanceMeters, route.estimatedDurationSeconds,
                                departureMillis, seats,
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
