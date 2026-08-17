package com.example.ds04_monash_rideshare_application.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import com.example.ds04_monash_rideshare_application.BuildConfig
import com.example.ds04_monash_rideshare_application.core.state.RideshareUiState
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold

@Composable
fun SettingsScreen(
    uiState: RideshareUiState,
    onPassengerEnabledChange: (Boolean) -> Unit,
    onClearLocalCache: () -> Unit,
    onSignOut: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var cacheClearedMessage by remember { mutableStateOf<String?>(null) }

    RideshareScaffold(
        title = "Settings",
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
                modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                SettingsSection(title = "Account") {
                    SettingsRow(label = "Monash email", value = uiState.email)
                    SettingsRow(label = "Name", value = uiState.displayName)
                }

                SettingsSection(title = "Ride preferences") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Available as passenger", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Turn off if you only ever drive - hides you from other passengers' matches.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = uiState.passengerEnabled, onCheckedChange = onPassengerEnabledChange)
                    }
                }

                SettingsSection(title = "Data") {
                    Text(
                        "Rides and your profile are cached on this device for offline viewing; the " +
                            "backend server is always the source of truth.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            onClearLocalCache()
                            cacheClearedMessage = "Local cache cleared."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear local cache")
                    }
                    cacheClearedMessage?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }

                SettingsSection(title = "About") {
                    SettingsRow(label = "App version", value = BuildConfig.VERSION_NAME)
                    SettingsRow(label = "Backend", value = BuildConfig.BACKEND_BASE_URL)
                    Text(
                        "This is a trial build. Real Monash SSO and hosted backend infrastructure " +
                            "are tracked separately - see docs/RTM.md and docs/BACKEND.md.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign out")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "-" }, style = MaterialTheme.typography.bodyMedium)
    }
}
