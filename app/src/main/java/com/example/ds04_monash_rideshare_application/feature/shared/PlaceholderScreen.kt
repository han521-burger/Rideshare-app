package com.example.ds04_monash_rideshare_application.feature.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.navigation.AppDestination
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold

@Composable
fun PlaceholderScreen(
    destination: AppDestination,
    onNavigateBack: () -> Unit,
    primaryActionLabel: String? = null,
    onPrimaryAction: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    RideshareScaffold(
        title = destination.title,
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = destination.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = descriptionFor(destination),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Navigation is ready. Feature behaviour will be added in its scheduled sprint.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    if (primaryActionLabel != null) {
                        Button(onClick = onPrimaryAction, modifier = Modifier.fillMaxWidth()) {
                            Text(primaryActionLabel)
                        }
                    }
                }
            }
        }
    }
}

private fun descriptionFor(destination: AppDestination): String = when (destination) {
    AppDestination.Settings -> "Notification, privacy, and application preferences will appear here."
    else -> "This screen is part of the planned Monash Rideshare flow."
}
