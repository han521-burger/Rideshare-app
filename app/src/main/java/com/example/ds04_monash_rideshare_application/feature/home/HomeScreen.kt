package com.example.ds04_monash_rideshare_application.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.core.model.SessionRole
import com.example.ds04_monash_rideshare_application.core.navigation.AppDestination
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold

private data class HomeAction(
    val destination: AppDestination,
    val description: String
)

@Composable
fun HomeScreen(
    role: SessionRole,
    greetingName: String,
    onNavigate: (AppDestination) -> Unit,
    onChangeRole: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val actions = if (role == SessionRole.PASSENGER) passengerActions else driverActions

    RideshareScaffold(
        title = "${role.displayName} Home",
        actionLabel = "Sign out",
        onAction = onSignOut,
        canNavigateBack = true,
        onNavigateBack = onChangeRole,
        modifier = modifier
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Welcome, $greetingName",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (role == SessionRole.PASSENGER) {
                                "Where would you like to travel today?"
                            } else {
                                "Ready to share your next campus journey?"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        OutlinedButton(onClick = onChangeRole) {
                            Text("Change role")
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Quick actions",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(actions, key = { it.destination.route }) { action ->
                Card(
                    onClick = { onNavigate(action.destination) },
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = action.destination.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = action.description,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

private val passengerActions = listOf(
    HomeAction(AppDestination.FindRide, "Search by route, date, and preferred travel time."),
    HomeAction(AppDestination.MyRides, "See rides you've requested, at every stage - including messages."),
    HomeAction(AppDestination.Map, "Explore the current Yandex route-selection prototype."),
    HomeAction(AppDestination.TripHistory, "Review completed and cancelled trips."),
    HomeAction(AppDestination.SustainabilityDashboard, "See rides and estimated CO2 savings."),
    HomeAction(AppDestination.Profile, "Update your name and view your account."),
    HomeAction(AppDestination.Settings, "Manage app and notification preferences.")
)

private val driverActions = listOf(
    HomeAction(AppDestination.CreateRide, "Offer seats for an upcoming campus journey."),
    HomeAction(AppDestination.RideRequests, "Review passenger requests for your rides."),
    HomeAction(AppDestination.MyRides, "See rides you're driving, at every stage - including messages."),
    HomeAction(AppDestination.Map, "Explore the current Yandex route-selection prototype."),
    HomeAction(AppDestination.TripHistory, "Review completed and cancelled trips."),
    HomeAction(AppDestination.SustainabilityDashboard, "See rides and estimated CO2 savings."),
    HomeAction(AppDestination.Profile, "Update your name and view your account."),
    HomeAction(AppDestination.Settings, "Manage app and notification preferences.")
)
