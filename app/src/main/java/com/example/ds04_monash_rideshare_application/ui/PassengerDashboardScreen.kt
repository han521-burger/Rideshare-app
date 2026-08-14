package com.example.ds04_monash_rideshare_application.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Auth.AuthenticationProvider

@Composable
fun PassengerDashboardScreen(authProvider: AuthenticationProvider, onFindRide: () -> Unit) {
    val user = authProvider.currentUser

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Passenger dashboard", style = MaterialTheme.typography.headlineSmall)
        Text(text = "Signed in as ${user?.displayName ?: "Unknown"}")
        Text(text = "Available rides, confirmed rides, and trip history will show here.")
        Button(onClick = onFindRide) { Text("Find a Ride") }
    }
}
