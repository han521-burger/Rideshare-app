package com.example.ds04_monash_rideshare_application

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.ds04_monash_rideshare_application.core.navigation.RideshareNavHost
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import com.example.ds04_monash_rideshare_application.feature.activity.LocalAppMode
import com.example.ds04_monash_rideshare_application.feature.activity.activityColorScheme

@Composable
fun RideshareApp(viewModel: RideshareViewModel) {
    // Every screen's colours follow the Passenger/Driver toggle (activityColorScheme).
    // The whole app uses the brand palette, so no screen falls back to the old theme.
    CompositionLocalProvider(LocalAppMode provides viewModel.activeMode) {
        MaterialTheme(colorScheme = activityColorScheme(), typography = MaterialTheme.typography) {
            RideshareNavHost(viewModel = viewModel)
        }
    }
}
