package com.example.ds04_monash_rideshare_application

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.ds04_monash_rideshare_application.core.navigation.RideshareNavHost
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import com.example.ds04_monash_rideshare_application.feature.activity.LocalAppMode

@Composable
fun RideshareApp(viewModel: RideshareViewModel) {
    // Every screen's colours follow the Passenger/Driver toggle (activityColorScheme).
    CompositionLocalProvider(LocalAppMode provides viewModel.activeMode) {
        RideshareNavHost(viewModel = viewModel)
    }
}
