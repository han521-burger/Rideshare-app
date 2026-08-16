package com.example.ds04_monash_rideshare_application

import androidx.compose.runtime.Composable
import com.example.ds04_monash_rideshare_application.core.navigation.RideshareNavHost
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel

@Composable
fun RideshareApp(viewModel: RideshareViewModel) {
    RideshareNavHost(viewModel = viewModel)
}
