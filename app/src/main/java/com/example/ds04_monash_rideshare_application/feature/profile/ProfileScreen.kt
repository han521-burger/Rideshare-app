package com.example.ds04_monash_rideshare_application.feature.profile

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.FavoriteDriverSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.state.RideshareUiState

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
    vehicleCount: Int,
    onOpenVehicles: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var displayName by remember { mutableStateOf(uiState.displayName) }
    var preferredName by remember { mutableStateOf(uiState.preferredName) }
    var passengerEnabled by remember { mutableStateOf(uiState.passengerEnabled) }
    var emergencyContactName by remember { mutableStateOf(profileDetails.data?.emergencyContactName ?: "") }
    var emergencyContactPhone by remember { mutableStateOf(profileDetails.data?.emergencyContactPhone ?: "") }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(profileDetails.data) {
        profileDetails.data?.let { profile ->
            displayName = profile.displayName
            preferredName = profile.preferredName
            passengerEnabled = profile.passengerEnabled
            emergencyContactName = profile.emergencyContactName ?: ""
            emergencyContactPhone = profile.emergencyContactPhone ?: ""
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Your details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Monash email: ${profileDetails.data?.emailAccount ?: uiState.email}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "User ID: ${profileDetails.data?.userId ?: uiState.userId.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = displayName, onValueChange = { displayName = it },
                        label = { Text("Display name") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    )
                    OutlinedTextField(
                        value = preferredName, onValueChange = { preferredName = it },
                        label = { Text("Preferred name") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Available as passenger", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Lets other students find your seat requests when they offer a ride.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = passengerEnabled, onCheckedChange = { passengerEnabled = it })
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("My vehicles", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (vehicleCount == 0) "None saved yet - required before offering a ride" else "$vehicleCount saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onOpenVehicles) { Text("Manage") }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Emergency contact", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Used by the SOS button on a ride's detail screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = emergencyContactName, onValueChange = { emergencyContactName = it },
                        label = { Text("Contact name") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    )
                    OutlinedTextField(
                        value = emergencyContactPhone, onValueChange = { emergencyContactPhone = it },
                        label = { Text("Contact phone") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        }

        item {
            saveMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button(
                onClick = {
                    isSaving = true
                    onSave(
                        displayName.trim(), preferredName.trim(), passengerEnabled,
                        emergencyContactName.trim().takeIf { it.isNotBlank() },
                        emergencyContactPhone.trim().takeIf { it.isNotBlank() }
                    ) { error ->
                        isSaving = false
                        saveMessage = error ?: "Saved."
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSaving) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text("Save changes")
            }
        }

        item { Text("Favorite drivers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        val favoriteList = favorites.data.orEmpty()
        if (favoriteList.isEmpty()) {
            item { Text("No favorite drivers yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(favoriteList, key = { it.driverId }) { favorite ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(favorite.displayName)
                        OutlinedButton(onClick = { onRemoveFavorite(favorite.driverId) }) { Text("Remove") }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Sign out")
            }
        }
    }
}
