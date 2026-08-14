package com.example.ds04_monash_rideshare_application.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Auth.AuthenticatedUser
import com.example.ds04_monash_rideshare_application.Auth.AuthenticationProvider

@Composable
fun LoginScreen(authProvider: AuthenticationProvider, onLoggedIn: () -> Unit) {
    var statusMessage by remember { mutableStateOf("Not signed in") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Monash Rideshare", style = MaterialTheme.typography.headlineSmall)
        Text(text = statusMessage)
        Button(onClick = {
            statusMessage = "Signing in..."
            authProvider.login(object : AuthenticationProvider.AuthCallback {
                override fun onSuccess(user: AuthenticatedUser) {
                    statusMessage = "Signed in as ${user.displayName}"
                    onLoggedIn()
                }

                override fun onFailure(error: Exception) {
                    statusMessage = "Sign-in failed: ${error.message}"
                }
            })
        }) {
            Text("Sign in with Monash SSO (placeholder)")
        }
    }
}
