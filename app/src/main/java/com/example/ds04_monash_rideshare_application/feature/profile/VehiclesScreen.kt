package com.example.ds04_monash_rideshare_application.feature.profile

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateVehicleDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.VehicleSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold

// Matches Emissions.kt's CO2_G_PER_KM keys exactly (backend, uppercase) - keeping this a fixed
// chip list rather than free text means a saved vehicle always drives a real per-fuel-type CO2
// estimate instead of silently falling back to the petrol default over a typo.
private val FUEL_TYPES = listOf("PETROL", "DIESEL", "HYBRID", "PHEV", "ELECTRIC")
private val VEHICLE_TYPES = listOf("SEDAN", "SUV", "HATCHBACK", "WAGON", "VAN", "UTE", "COUPE", "OTHER")

// A driver registers exactly one vehicle (backend enforces this too - see requireSingleVehicle in
// VehicleRoutes.kt), required before they can offer a ride. It's auto-attached to every ride they
// post, so this is a register/edit-in-place screen, not a fleet manager.
@Composable
fun VehiclesScreen(
    vehicles: Resource<List<VehicleSummary>>,
    isSaving: Boolean,
    onAddVehicle: (CreateVehicleDraft, onDone: (String?) -> Unit) -> Unit,
    onUpdateVehicle: (vehicleId: String, CreateVehicleDraft, onDone: (String?) -> Unit) -> Unit,
    onDeleteVehicle: (vehicleId: String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vehicle = vehicles.data.orEmpty().firstOrNull()
    var isEditing by remember(vehicle?.vehicleId) { mutableStateOf(vehicle == null) }

    RideshareScaffold(title = "My Vehicle", canNavigateBack = true, onNavigateBack = onNavigateBack, modifier = modifier) { contentPadding ->
        LazyColumn(
            modifier = Modifier.padding(contentPadding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "One vehicle per driver, required before offering a ride - its seat capacity " +
                        "caps how many seats you can offer, its fuel type drives your CO2/fuel-saved " +
                        "figures, and passengers see its details once a ride is confirmed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (vehicles.isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            vehicles.errorMessage?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }

            if (!isEditing && vehicle != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "${vehicle.year} ${vehicle.make} ${vehicle.model}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                listOfNotNull(
                                    vehicle.variant, vehicle.vehicleType, vehicle.colour,
                                    vehicle.fuelType, vehicle.registrationMasked, "${vehicle.seatCapacity} seats"
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { isEditing = true }, modifier = Modifier.weight(1f)) { Text("Edit") }
                                OutlinedButton(onClick = { onDeleteVehicle(vehicle.vehicleId) }, modifier = Modifier.weight(1f)) { Text("Remove") }
                            }
                        }
                    }
                }
            } else {
                item {
                    VehicleForm(
                        initial = vehicle,
                        isSaving = isSaving,
                        showCancel = vehicle != null,
                        onCancel = { isEditing = false },
                        onSubmit = { draft ->
                            if (vehicle != null) {
                                onUpdateVehicle(vehicle.vehicleId, draft) { error -> if (error == null) isEditing = false }
                            } else {
                                onAddVehicle(draft) {}
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VehicleForm(
    initial: VehicleSummary?,
    isSaving: Boolean,
    showCancel: Boolean,
    onCancel: () -> Unit,
    onSubmit: (CreateVehicleDraft) -> Unit
) {
    var year by remember { mutableStateOf(initial?.year?.toString() ?: "") }
    var make by remember { mutableStateOf(initial?.make ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var variant by remember { mutableStateOf(initial?.variant ?: "") }
    var registration by remember { mutableStateOf(initial?.registrationMasked ?: "") }
    var seatCapacity by remember { mutableStateOf(initial?.seatCapacity?.toString() ?: "4") }
    var colour by remember { mutableStateOf(initial?.colour ?: "") }
    var fuelType by remember { mutableStateOf(initial?.fuelType) }
    var vehicleType by remember { mutableStateOf(initial?.vehicleType) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (initial == null) "Register your vehicle" else "Edit your vehicle",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = year, onValueChange = { year = it.filter(Char::isDigit).take(4) },
                    label = { Text("Year") }, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = seatCapacity, onValueChange = { seatCapacity = it.filter(Char::isDigit).take(1) },
                    label = { Text("Seats") }, modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = make, onValueChange = { make = it },
                label = { Text("Make") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("Model") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = variant, onValueChange = { variant = it },
                label = { Text("Variant (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = colour, onValueChange = { colour = it },
                    label = { Text("Colour") }, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = registration, onValueChange = { registration = it },
                    label = { Text("Registration plate") }, modifier = Modifier.weight(1f)
                )
            }

            Text("Vehicle type", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(VEHICLE_TYPES) { type ->
                    FilterChip(selected = vehicleType == type, onClick = { vehicleType = type }, label = { Text(type) })
                }
            }

            Text("Fuel type", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(FUEL_TYPES) { type ->
                    FilterChip(selected = fuelType == type, onClick = { fuelType = type }, label = { Text(type) })
                }
            }

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showCancel) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                }
                Button(
                    enabled = !isSaving,
                    onClick = {
                        val yearInt = year.toIntOrNull()
                        val seats = seatCapacity.toIntOrNull()
                        val fuel = fuelType
                        val body = vehicleType
                        errorMessage = when {
                            yearInt == null || yearInt < 1900 || yearInt > 2100 -> "Enter a valid year"
                            make.isBlank() -> "Make is required"
                            model.isBlank() -> "Model is required"
                            colour.isBlank() -> "Colour is required"
                            seats == null || seats <= 0 -> "Enter a valid seat count"
                            body == null -> "Pick a vehicle type"
                            fuel == null -> "Pick a fuel type"
                            else -> null
                        }
                        if (errorMessage == null) {
                            onSubmit(
                                CreateVehicleDraft(
                                    year = yearInt!!, make = make.trim(), model = model.trim(),
                                    variant = variant.trim().takeIf { it.isNotBlank() },
                                    registrationMasked = registration.trim().takeIf { it.isNotBlank() },
                                    seatCapacity = seats!!, fuelType = fuel!!, vehicleType = body!!, colour = colour.trim()
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    if (isSaving) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text("Save")
                }
            }
        }
    }
}
