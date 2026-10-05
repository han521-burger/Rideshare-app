package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.FavoriteDriverSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.VehicleSummary
import com.example.ds04_monash_rideshare_application.core.state.AppMode
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.state.RideshareUiState
import com.example.ds04_monash_rideshare_application.core.ui.BusyLabel
import com.example.ds04_monash_rideshare_application.core.ui.FormBanner
import com.example.ds04_monash_rideshare_application.core.ui.FormBannerKind
import com.example.ds04_monash_rideshare_application.core.ui.FormSectionCard
import com.example.ds04_monash_rideshare_application.core.ui.FormStatusPill
import com.example.ds04_monash_rideshare_application.core.ui.FormTextField
import com.example.ds04_monash_rideshare_application.core.ui.rememberLargeText

// Position of the Emergency contact card among the LazyColumn items below (title, details, ride
// preferences, vehicle, emergency contact). Used to scroll it into composition before focusing it.
private const val EMERGENCY_CONTACT_ITEM = 4

@Composable
fun ProfileScreen(
    uiState: RideshareUiState,
    profileDetails: Resource<UserAccountSummary>,
    favorites: Resource<List<FavoriteDriverSummary>>,
    onSave: (
        displayName: String,
        preferredName: String,
        passengerEnabled: Boolean,
        emergencyContactName: String?,
        emergencyContactPhone: String?,
        onDone: (String?) -> Unit
    ) -> Unit,
    onRemoveFavorite: (driverId: String) -> Unit,
    vehicle: VehicleSummary?,
    onOpenVehicles: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    activeMode: AppMode = AppMode.PASSENGER
) {
    var displayName by remember { mutableStateOf(uiState.displayName) }
    var preferredName by remember { mutableStateOf(uiState.preferredName) }
    var passengerEnabled by remember { mutableStateOf(uiState.passengerEnabled) }
    var emergencyContactName by remember { mutableStateOf(profileDetails.data?.emergencyContactName ?: "") }
    var emergencyContactPhone by remember { mutableStateOf(profileDetails.data?.emergencyContactPhone ?: "") }
    var feedback by remember { mutableStateOf<SaveFeedback?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    LaunchedEffect(profileDetails.data) {
        profileDetails.data?.let { profile ->
            displayName = profile.displayName
            preferredName = profile.preferredName
            passengerEnabled = profile.passengerEnabled
            emergencyContactName = profile.emergencyContactName ?: ""
            emergencyContactPhone = profile.emergencyContactPhone ?: ""
        }
    }

    val accountName = identityName(profileDetails.data?.displayName, uiState.displayName, uiState.greetingName)
    val accountEmail = profileDetails.data?.emailAccount ?: uiState.email
    val accountId = profileDetails.data?.userId ?: uiState.userId.orEmpty()
    val moveNext = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
    // "Next" from Preferred name must reach Emergency contact, not the switch or Manage vehicle in between,
    // so the keyboard stays up. That card may not be composed yet, hence scroll first, then focus.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val emergencyNameFocus = remember { FocusRequester() }
    val toEmergencyContact = KeyboardActions(onNext = {
        scope.launch {
            listState.animateScrollToItem(EMERGENCY_CONTACT_ITEM)
            runCatching { emergencyNameFocus.requestFocus() }
        }
    })

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Profile", Modifier.padding(vertical = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }

        item {
            FormSectionCard("Your details", Modifier.testTag("profile-details")) {
                IdentityHeader(accountName, accountEmail)
                FormTextField(
                    displayName, { displayName = it }, "Display name",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    keyboardActions = moveNext
                )
                FormTextField(
                    preferredName, { preferredName = it }, "Preferred name",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    keyboardActions = toEmergencyContact
                )
            }
        }

        item {
            FormSectionCard("Ride preferences") {
                PassengerAvailabilityRow(passengerEnabled) { passengerEnabled = it }
            }
        }

        item {
            FormSectionCard(
                "My vehicle", Modifier.testTag("profile-vehicle"),
                trailing = if (showReadyToOffer(activeMode, vehicle != null)) ({
                    FormStatusPill("Ready to offer rides", success = true, icon = {
                        Icon(Icons.Rounded.Check, null, Modifier.size(14.dp))
                    })
                }) else null
            ) {
                if (vehicle == null) {
                    NoVehicleState(onOpenVehicles)
                } else {
                    VehicleFactsBlock(vehicleFacts(vehicle))
                    Button(
                        onClick = onOpenVehicles, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) { Text("Manage vehicle") }
                }
            }
        }

        item {
            FormSectionCard("Emergency contact", supporting = "Used by the SOS button on a ride's detail screen.") {
                FormTextField(
                    emergencyContactName, { emergencyContactName = it }, "Contact name",
                    modifier = Modifier.focusRequester(emergencyNameFocus),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    keyboardActions = moveNext
                )
                FormTextField(
                    emergencyContactPhone, { emergencyContactPhone = it }, "Contact phone",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() })
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                feedback?.let { f ->
                    if (f.isError) FormBanner(f.message, FormBannerKind.Error, Modifier.testTag("profile-save-feedback"))
                    else FormBanner(f.message, FormBannerKind.Success, Modifier.testTag("profile-save-feedback"))
                }
                Button(
                    onClick = {
                        focus.clearFocus()
                        isSaving = true
                        onSave(
                            displayName.trim(), preferredName.trim(), passengerEnabled,
                            emergencyContactName.trim().takeIf { it.isNotBlank() },
                            emergencyContactPhone.trim().takeIf { it.isNotBlank() }
                        ) { error ->
                            isSaving = false
                            feedback = saveFeedback(error)
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().testTag("profile-save")
                ) {
                    if (isSaving) BusyLabel(saveButtonLabel(true), MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    else Text(saveButtonLabel(false))
                }
            }
        }

        item {
            Text("Favorite drivers", Modifier.padding(top = 8.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        val favoriteList = favorites.data.orEmpty()
        if (favoriteList.isEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No favorite drivers yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(favoriteList, key = { it.driverId }) { favorite ->
                FavoriteDriverRow(favorite) { onRemoveFavorite(favorite.driverId) }
            }
        }

        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Account ID · $accountId", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("profile-account-id")
                )
                OutlinedButton(
                    onClick = onSignOut, modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, null, Modifier.size(18.dp))
                    Text("Sign out", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun IdentityHeader(name: String, email: String) {
    val colors = MaterialTheme.colorScheme
    val avatar: @Composable () -> Unit = {
        Box(
            Modifier.size(60.dp).background(colors.primaryContainer, CircleShape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center
        ) {
            Text(avatarInitial(name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = colors.onPrimaryContainer)
        }
    }
    val details: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(email, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            // Neutral context only: the app does not verify anything beyond the signed-in Monash email.
            FormStatusPill("Monash account", Modifier.padding(top = 4.dp), icon = {
                Icon(Icons.Rounded.Shield, null, Modifier.size(14.dp))
            })
        }
    }
    if (rememberLargeText()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { avatar(); details(Modifier.fillMaxWidth()) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            avatar(); details(Modifier.weight(1f))
        }
    }
}

// The whole row toggles, so TalkBack sees one switch rather than a label plus a nested switch.
@Composable
private fun PassengerAvailabilityRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp).testTag("profile-passenger-switch"),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Available as passenger", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Lets other students find your seat requests when they offer a ride.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled, onCheckedChange = null,
            thumbContent = if (enabled) ({ Icon(Icons.Rounded.Check, null, Modifier.size(14.dp)) }) else null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary, checkedTrackColor = colors.primary,
                checkedIconColor = colors.primary,
                uncheckedThumbColor = colors.outline, uncheckedTrackColor = colors.surfaceVariant,
                uncheckedBorderColor = colors.outline
            )
        )
    }
}

@Composable
private fun NoVehicleState(onRegister: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(48.dp).background(colors.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DirectionsCar, null, tint = colors.onSurfaceVariant)
        }
        Text("No vehicle registered", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Register your vehicle to offer rides. You only need one to drive — passengers don’t.",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Button(onClick = onRegister, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("profile-register-vehicle")) {
            Text("Register vehicle")
        }
    }
}

@Composable
private fun FavoriteDriverRow(favorite: FavoriteDriverSummary, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(avatarInitial(favorite.displayName), color = colors.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
            }
            Text(favorite.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "Remove ${favorite.displayName} from favorite drivers" }
            ) { Text("Remove") }
        }
    }
}
