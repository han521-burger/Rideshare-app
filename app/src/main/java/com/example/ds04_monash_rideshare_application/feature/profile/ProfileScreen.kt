package com.example.ds04_monash_rideshare_application.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.state.RideshareUiState
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold

@Composable
fun ProfileScreen(
    uiState: RideshareUiState,
    onNavigateBack: () -> Unit,
    onSave: (displayName: String, preferredName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var displayName by rememberSaveable(uiState.userId) { mutableStateOf(uiState.displayName) }
    var preferredName by rememberSaveable(uiState.userId) { mutableStateOf(uiState.preferredName) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    RideshareScaffold(
        title = "Profile",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Your Monash profile",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Only the minimum information needed for the rideshare service is shown here.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = uiState.email,
                    onValueChange = {},
                    label = { Text("Monash email") },
                    supportingText = { Text("Provided by the authentication service") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        savedMessage = null
                    },
                    label = { Text("Full name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = preferredName,
                    onValueChange = {
                        preferredName = it
                        savedMessage = null
                    },
                    label = { Text("Preferred name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uiState.activeRole?.displayName ?: "Choose a role for this session",
                    onValueChange = {},
                    label = { Text("Current session role") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onSave(displayName, preferredName)
                        savedMessage = "Profile saved on this device for the current session."
                    },
                    enabled = displayName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save profile")
                }
                savedMessage?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "Remote profile persistence is a Sprint 2 integration task.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
