package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateVehicleDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.VehicleSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehiclesScreen(
    vehicles: Resource<List<VehicleSummary>>,
    isSaving: Boolean,
    onAddVehicle: (CreateVehicleDraft, (String?) -> Unit) -> Unit,
    onUpdateVehicle: (String, CreateVehicleDraft, (String?) -> Unit) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vehicle = vehicles.data.orEmpty().firstOrNull()
    var editing by remember(vehicle?.vehicleId) { mutableStateOf(vehicle == null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    RideshareScaffold(title = if (vehicle == null) "Register your vehicle" else "My vehicle",
        canNavigateBack = true, onNavigateBack = onNavigateBack, modifier = modifier) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vehicles.isLoading) { CircularProgressIndicator(); return@Column }
            vehicles.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!editing && vehicle != null) {
                Text(vehicleSummary(vehicle), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { editing = true }) { Text("Edit vehicle") }
            } else {
                Text("To offer rides, register the vehicle you will use for carpooling.")
                var make by rememberSaveable(vehicle?.vehicleId) { mutableStateOf(vehicle?.make ?: "") }
                var model by rememberSaveable(vehicle?.vehicleId) { mutableStateOf(vehicle?.model ?: "") }
                var plate by rememberSaveable(vehicle?.vehicleId) { mutableStateOf(vehicle?.registrationMasked ?: "") }
                var colour by rememberSaveable(vehicle?.vehicleId) { mutableStateOf(vehicle?.colour ?: "") }
                var seats by rememberSaveable(vehicle?.vehicleId) { mutableStateOf(vehicle?.seatCapacity ?: 5) }
                var type by rememberSaveable(vehicle?.vehicleId) { mutableStateOf(vehicle?.vehicleType?.uppercase(Locale.ROOT) ?: "") }
                var fuel by rememberSaveable(vehicle?.vehicleId) { mutableStateOf(vehicle?.fuelType?.uppercase(Locale.ROOT) ?: "") }
                OutlinedTextField(make, { make = it }, label = { Text("Car brand") }, singleLine = true, enabled = !isSaving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("Car model") }, singleLine = true, enabled = !isSaving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(plate, { plate = it }, label = { Text("Registration plate") }, singleLine = true, enabled = !isSaving, modifier = Modifier.fillMaxWidth())
                Text("Vehicle type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vehicleTypes.forEach { value -> FilterChip(selected = type == value, onClick = { type = value }, enabled = !isSaving, label = { Text(vehicleLabel(value)) }) }
                }
                OutlinedTextField(colour, { colour = it }, label = { Text("Colour") }, singleLine = true, enabled = !isSaving, modifier = Modifier.fillMaxWidth())
                Text("Total seating capacity (including driver)")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { seats-- }, enabled = !isSaving && seats > 2) { Text("-") }
                    Text("$seats seats", modifier = Modifier.padding(top = 12.dp))
                    OutlinedButton(onClick = { seats++ }, enabled = !isSaving && seats < 12) { Text("+") }
                }
                Text("Up to ${seats - 1} passenger seats can be offered.")
                Text("Fuel type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fuelTypes.forEach { value -> FilterChip(selected = fuel == value, onClick = { fuel = value }, enabled = !isSaving, label = { Text(vehicleLabel(value)) }) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(enabled = !isSaving, modifier = Modifier.fillMaxWidth(), onClick = {
                    focus.clearFocus()
                    val draft = CreateVehicleDraft(vehicle?.year ?: 0, make.trim(), model.trim(), vehicle?.variant,
                        plate.trim().uppercase(Locale.ROOT), seats, fuel, type, colour.trim())
                    error = validateVehicle(draft)
                    if (error == null) {
                        val done: (String?) -> Unit = { message -> error = message; if(message == null) editing = false }
                        if (vehicle == null) onAddVehicle(draft, done) else onUpdateVehicle(vehicle.vehicleId, draft, done)
                    }
                }) { if (isSaving) CircularProgressIndicator() else Text(if (vehicle == null) "Register Vehicle" else "Save changes") }
                OutlinedButton(onClick = { if (vehicle == null) onNavigateBack() else editing = false }, enabled = !isSaving, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}
