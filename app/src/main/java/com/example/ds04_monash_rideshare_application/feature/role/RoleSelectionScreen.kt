package com.example.ds04_monash_rideshare_application.feature.role

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.model.SessionRole
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold

@Composable
fun RoleSelectionScreen(
    greetingName: String,
    onRoleSelected: (SessionRole) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    RideshareScaffold(
        title = "Choose your role",
        actionLabel = "Sign out",
        onAction = onSignOut,
        modifier = modifier
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Hi, $greetingName",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Choose how you are travelling for this session. You can change roles later.",
                    style = MaterialTheme.typography.bodyLarge
                )
                RoleCard(
                    title = "Travel as a passenger",
                    description = "Find a campus ride, review details, and request a seat.",
                    buttonLabel = "Choose Passenger",
                    primary = true,
                    onClick = { onRoleSelected(SessionRole.PASSENGER) }
                )
                RoleCard(
                    title = "Travel as a driver",
                    description = "Create a ride, manage requests, and offer available seats.",
                    buttonLabel = "Choose Driver",
                    primary = false,
                    onClick = { onRoleSelected(SessionRole.DRIVER) }
                )
            }
        }
    }
}

@Composable
private fun RoleCard(
    title: String,
    description: String,
    buttonLabel: String,
    primary: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (primary) {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonLabel)
                }
            } else {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonLabel)
                }
            }
        }
    }
}
