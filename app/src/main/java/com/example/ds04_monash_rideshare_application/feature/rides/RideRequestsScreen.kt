package com.example.ds04_monash_rideshare_application.feature.rides

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestWithRide
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.RideshareScaffold
import com.example.ds04_monash_rideshare_application.ui.describeLocation
import java.text.SimpleDateFormat
import java.util.Locale

// Driver view: every PENDING request across all of their rides, with accept/reject (item 6/7).
@Composable
fun RideRequestsScreen(
    requests: Resource<List<RequestWithRide>>,
    onRespond: (requestId: String, newStatus: String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    RideshareScaffold(
        title = "Ride Requests",
        canNavigateBack = true,
        onNavigateBack = onNavigateBack,
        actionLabel = "Refresh",
        onAction = onRefresh,
        modifier = modifier
    ) { contentPadding ->
        when {
            requests.isLoading -> Column(
                modifier = Modifier.padding(contentPadding).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            requests.errorMessage != null -> Text(
                requests.errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(contentPadding).padding(16.dp)
            )

            requests.data != null -> {
                val pending = requests.data.filter { it.request.status == "PENDING" }
                if (pending.isEmpty()) {
                    Text(
                        "No pending requests right now.",
                        modifier = Modifier.padding(contentPadding).padding(16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(contentPadding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(pending, key = { it.request.requestId }) { requestWithRide ->
                            RequestCard(requestWithRide, onRespond)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestCard(requestWithRide: RequestWithRide, onRespond: (String, String) -> Unit) {
    val request = requestWithRide.request
    val ride = requestWithRide.ride
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${request.seatsRequested} seat(s) requested",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (ride != null) {
                Text(
                    SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(ride.departureTime),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${describeLocation(ride.originLat, ride.originLng)} -> ${describeLocation(ride.destinationLat, ride.destinationLng)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onRespond(request.requestId, "ACCEPTED") }) { Text("Accept") }
                OutlinedButton(onClick = { onRespond(request.requestId, "REJECTED") }) { Text("Reject") }
            }
        }
    }
}
