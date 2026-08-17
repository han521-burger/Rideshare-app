package com.example.ds04_monash_rideshare_application.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.DashboardSummary
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold

// Item 8: CO2 avoided, monthly trip summary, total rides completed. CO2 is explicitly labelled
// as an estimate throughout - distance x vehicle emission factor, not a measurement (see
// backend/Emissions.kt).
@Composable
fun SustainabilityDashboardScreen(
    dashboard: Resource<DashboardSummary>,
    onRefresh: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    RideshareScaffold(
        title = "Sustainability Dashboard",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        actionLabel = "Refresh",
        onAction = onRefresh,
        modifier = modifier
    ) { contentPadding ->
        when {
            dashboard.isLoading -> Column(
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            dashboard.errorMessage != null -> Text(
                dashboard.errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(contentPadding).padding(16.dp)
            )

            dashboard.data != null -> {
                val summary = dashboard.data
                Column(
                    modifier = Modifier.padding(contentPadding).fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StatCard("Rides completed", "${summary.totalRidesCompleted}")
                    StatCard(
                        "Estimated CO2 avoided",
                        "%.1f kg".format(summary.totalCo2AvoidedKg),
                        note = "Estimate only: distance x vehicle emission factor."
                    )
                    Text(
                        "Monthly summary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (summary.monthlyTripSummary.isEmpty()) {
                        Text("No completed trips yet.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        summary.monthlyTripSummary.forEach { month ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(month.monthLabel, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${month.tripsCompleted} trip(s) - %.1f kg CO2 avoided (est.)".format(month.co2AvoidedKg),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, note: String? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
