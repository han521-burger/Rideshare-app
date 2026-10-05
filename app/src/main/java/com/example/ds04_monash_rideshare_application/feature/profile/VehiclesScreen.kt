package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateVehicleDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.VehicleSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.ChoiceChipGroup
import com.example.ds04_monash_rideshare_application.core.ui.FormActionBar
import com.example.ds04_monash_rideshare_application.core.ui.FormBanner
import com.example.ds04_monash_rideshare_application.core.ui.FormBannerKind
import com.example.ds04_monash_rideshare_application.core.ui.FormSectionCard
import com.example.ds04_monash_rideshare_application.core.ui.FormTextField
import com.example.ds04_monash_rideshare_application.core.ui.SeatStepper
import java.util.Locale

// [driverSetup] is true only while the user reached this screen by choosing Driver without a
// vehicle (NavHost's registeringForDriver); [driverChecking] mirrors driverAccess.checking so the
// post-registration hand-off can be shown. Both are presentation-only.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VehiclesScreen(
    vehicles: Resource<List<VehicleSummary>>,
    isSaving: Boolean,
    onAddVehicle: (CreateVehicleDraft, (String?) -> Unit) -> Unit,
    onUpdateVehicle: (String, CreateVehicleDraft, (String?) -> Unit) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    driverSetup: Boolean = false,
    driverChecking: Boolean = false
) {
    val vehicle = vehicles.data.orEmpty().firstOrNull()
    var editing by remember(vehicle?.vehicleId) { mutableStateOf(vehicle == null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<VehicleValidation?>(null) }
    var failedSubmits by remember { mutableIntStateOf(0) }
    // The form state is hoisted above the scroller so the sticky bar can read it. Leaving the form
    // (Cancel, or a successful save) bumps this epoch so the next edit starts from the saved vehicle,
    // exactly as when the form state used to live inside the editing branch.
    var formEpoch by rememberSaveable { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    val showForm = editing || vehicle == null
    val requesters = remember { VehicleField.entries.associateWith { BringIntoViewRequester() } }

    // A failed submit scrolls to the field that owns the (single) validation message.
    LaunchedEffect(failedSubmits) {
        validation?.let { requesters[it.field]?.bringIntoView() }
    }
    // A save/server message lives at the top of the form, so bring it into view.
    LaunchedEffect(saveError) { if (saveError != null) scroll.animateScrollTo(0) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // Insets are handled explicitly: status bar in the top bar, navigation bar + IME in the action bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { VehicleTopBar(vehicleScreenTitle(vehicle != null), onNavigateBack) }
    ) { padding ->
        // The sticky action bar needs the form state, so the screen body hosts it below the scroller.
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Form state is declared here so the scrolling body and the sticky bar share it.
            var make by rememberSaveable(vehicle?.vehicleId, formEpoch) { mutableStateOf(vehicle?.make ?: "") }
            var model by rememberSaveable(vehicle?.vehicleId, formEpoch) { mutableStateOf(vehicle?.model ?: "") }
            var plate by rememberSaveable(vehicle?.vehicleId, formEpoch) { mutableStateOf(vehicle?.registrationMasked ?: "") }
            var colour by rememberSaveable(vehicle?.vehicleId, formEpoch) { mutableStateOf(vehicle?.colour ?: "") }
            var seats by rememberSaveable(vehicle?.vehicleId, formEpoch) { mutableStateOf(vehicle?.seatCapacity ?: DEFAULT_SEATS) }
            var type by rememberSaveable(vehicle?.vehicleId, formEpoch) { mutableStateOf(vehicle?.vehicleType?.uppercase(Locale.ROOT) ?: "") }
            var fuel by rememberSaveable(vehicle?.vehicleId, formEpoch) { mutableStateOf(vehicle?.fuelType?.uppercase(Locale.ROOT) ?: "") }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                    .then(if (showForm && !vehicles.isLoading) Modifier else Modifier.navigationBarsPadding())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (vehicles.isLoading) {
                    VehicleLoadingPlaceholder()
                    return@Column
                }
                vehicles.errorMessage?.let {
                    FormBanner(it, FormBannerKind.Error, title = "Couldn’t load your vehicle")
                }
                if (!showForm && vehicle != null) {
                    VehicleReadOnly(
                        vehicle = vehicle,
                        handOff = showDriverHandOff(driverSetup, driverChecking, hasVehicle = true, editing = false),
                        onEdit = { editing = true }
                    )
                } else {
                    saveError?.let {
                        FormBanner(
                            it, FormBannerKind.Error, Modifier.testTag("vehicle-save-error"),
                            title = if (vehicle == null) "Couldn’t register your vehicle" else "Couldn’t save your changes"
                        )
                    }
                    VehicleIntroBlock(vehicleIntro(vehicle != null, driverSetup))
                    val onNext = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
                    FormSectionCard("Vehicle details", Modifier.testTag("vehicle-details")) {
                        FormTextField(make, { make = it; validation = validation.clearedFor(VehicleField.MAKE) }, "Car brand",
                            Modifier.bringIntoViewRequester(requesters.getValue(VehicleField.MAKE)),
                            enabled = !isSaving, errorText = validation.messageFor(VehicleField.MAKE), singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            keyboardActions = onNext)
                        FormTextField(model, { model = it; validation = validation.clearedFor(VehicleField.MODEL) }, "Car model",
                            Modifier.bringIntoViewRequester(requesters.getValue(VehicleField.MODEL)),
                            enabled = !isSaving, errorText = validation.messageFor(VehicleField.MODEL), singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            keyboardActions = onNext)
                        FormTextField(plate, { plate = it; validation = validation.clearedFor(VehicleField.PLATE) }, "Registration plate",
                            Modifier.bringIntoViewRequester(requesters.getValue(VehicleField.PLATE)),
                            enabled = !isSaving, errorText = validation.messageFor(VehicleField.PLATE), singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                            keyboardActions = onNext)
                        FormTextField(colour, { colour = it; validation = validation.clearedFor(VehicleField.COLOUR) }, "Colour",
                            Modifier.bringIntoViewRequester(requesters.getValue(VehicleField.COLOUR)),
                            enabled = !isSaving, errorText = validation.messageFor(VehicleField.COLOUR), singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
                    }
                    FormSectionCard("Vehicle type", Modifier.bringIntoViewRequester(requesters.getValue(VehicleField.TYPE)).testTag("vehicle-type")) {
                        ChoiceChipGroup(vehicleTypes, type.takeIf { it.isNotEmpty() }, ::vehicleLabel,
                            { type = it; validation = validation.clearedFor(VehicleField.TYPE) }, "Vehicle type",
                            enabled = !isSaving, errorText = validation.messageFor(VehicleField.TYPE))
                    }
                    FormSectionCard(
                        "Total seating capacity", Modifier.bringIntoViewRequester(requesters.getValue(VehicleField.SEATS)).testTag("vehicle-seats"),
                        supporting = "Including the driver · between $MIN_SEATS and $MAX_SEATS seats"
                    ) {
                        SeatStepper(seats, MIN_SEATS, MAX_SEATS, { seats = it; validation = validation.clearedFor(VehicleField.SEATS) },
                            supporting = passengerSeatsText(seats), enabled = !isSaving)
                        validation.messageFor(VehicleField.SEATS)?.let {
                            com.example.ds04_monash_rideshare_application.core.ui.FieldMessage(it, isError = true)
                        }
                    }
                    FormSectionCard("Fuel type", Modifier.bringIntoViewRequester(requesters.getValue(VehicleField.FUEL)).testTag("vehicle-fuel")) {
                        ChoiceChipGroup(fuelTypes, fuel.takeIf { it.isNotEmpty() }, ::vehicleLabel,
                            { fuel = it; validation = validation.clearedFor(VehicleField.FUEL) }, "Fuel type",
                            enabled = !isSaving, errorText = validation.messageFor(VehicleField.FUEL))
                    }
                }
            }

            if (!vehicles.isLoading && showForm) {
                FormActionBar(
                    primaryLabel = vehiclePrimaryLabel(vehicle != null),
                    busyLabel = vehicleBusyLabel(vehicle != null),
                    busy = isSaving,
                    onPrimary = {
                        focus.clearFocus()
                        val draft = CreateVehicleDraft(vehicle?.year ?: 0, make.trim(), model.trim(), vehicle?.variant,
                            plate.trim().uppercase(Locale.ROOT), seats, fuel, type, colour.trim())
                        validation = validateVehicleDetailed(draft)
                        saveError = null
                        if (validation == null) {
                            val done: (String?) -> Unit = { message -> saveError = message; if (message == null) { editing = false; formEpoch++ } }
                            if (vehicle == null) onAddVehicle(draft, done) else onUpdateVehicle(vehicle.vehicleId, draft, done)
                        } else failedSubmits++
                    },
                    secondaryLabel = "Cancel",
                    onSecondary = {
                        if (vehicle == null) onNavigateBack()
                        else { editing = false; formEpoch++; validation = null; saveError = null }
                    }
                )
            }
        }
    }
}

private fun VehicleValidation?.messageFor(field: VehicleField): String? = this?.takeIf { it.field == field }?.message

// Editing a field dismisses only that field's message; the validator itself is unchanged.
private fun VehicleValidation?.clearedFor(field: VehicleField): VehicleValidation? = if (this?.field == field) null else this

@Composable
private fun VehicleTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(onClick = onBack, Modifier.size(48.dp).testTag("vehicle-back")) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun VehicleIntroBlock(intro: VehicleIntro) {
    val colors = MaterialTheme.colorScheme
    when (intro) {
        VehicleIntro.NONE -> Unit
        VehicleIntro.NEUTRAL -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DirectionsCar, null, tint = colors.onPrimaryContainer)
            }
            Text("To offer rides, register the vehicle you will use for carpooling.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f).testTag("vehicle-intro-neutral"))
        }
        // Informational, not an error: explains the requirement and what Back does.
        VehicleIntro.DRIVER_GATE -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.primaryContainer).padding(16.dp).testTag("vehicle-driver-hero"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.surface), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DirectionsCar, null, tint = colors.primary)
                }
                Text("DRIVER MODE NEEDS A VEHICLE", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
            }
            Text("Register your vehicle to start driving", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer, modifier = Modifier.semantics { heading() })
            Text("Your vehicle sets how many passenger seats you can offer. Rides can be offered once it’s registered — it only takes a minute.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
            Text("Not now? Back returns you to Passenger mode.", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, color = colors.onPrimaryContainer)
        }
    }
}

@Composable
private fun VehicleReadOnly(vehicle: VehicleSummary, handOff: Boolean, onEdit: () -> Unit) {
    if (handOff) {
        FormBanner("Switching you to Driver mode…", FormBannerKind.Success, Modifier.testTag("vehicle-handoff"), title = "Vehicle registered") {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    FormSectionCard("Your vehicle", Modifier.testTag("vehicle-summary")) {
        VehicleFactsBlock(vehicleFacts(vehicle))
        Button(
            onClick = onEdit, enabled = !handOff, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("vehicle-edit"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) { Text("Edit vehicle") }
    }
}

@Composable
private fun VehicleLoadingPlaceholder() {
    val block = MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "Loading your vehicle" }.testTag("vehicle-loading"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(3) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.fillMaxWidth(.4f).height(16.dp).clip(RoundedCornerShape(8.dp)).background(block))
                Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(block))
                Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).background(block))
            }
        }
    }
}
