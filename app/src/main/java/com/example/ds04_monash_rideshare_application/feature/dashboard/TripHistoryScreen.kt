package com.example.ds04_monash_rideshare_application.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Database.RideShare.RideOffer
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun TripHistoryScreen(
    trips: Resource<List<RideOffer>>,
    onRefresh: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    RideshareScaffold(
        title = "Trip History",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        actionLabel = "Refresh",
        onAction = onRefresh,
        modifier = modifier
    ) { contentPadding ->
        when {
            trips.isLoading -> Column(
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            trips.errorMessage != null -> Text(
                trips.errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(contentPadding).padding(16.dp)
            )

            else -> {
                val list = trips.data.orEmpty()
                if (list.isEmpty()) {
                    Text(
                        "No completed trips yet.",
                        modifier = Modifier.padding(contentPadding).padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(contentPadding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(list, key = { it.rideId }) { ride ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault()).format(ride.departureTime),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text("Completed", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
