package com.example.ds04_monash_rideshare_application

import android.os.Bundle
import com.yandex.mapkit.MapKitFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import com.example.ds04_monash_rideshare_application.ui.theme.Ds04_monash_rideshare_applicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.YANDEX_MAPKIT_API_KEY.isNotBlank()) MapKitFactory.initialize(this)
        enableEdgeToEdge()
        val rideshareViewModel = ViewModelProvider(this)[RideshareViewModel::class.java]
        setContent {
            Ds04_monash_rideshare_applicationTheme {
                RideshareApp(viewModel = rideshareViewModel)
            }
        }
    }

    // Search and directions are also used on screens without a MapView.
    override fun onStart() {
        super.onStart()
        if (BuildConfig.YANDEX_MAPKIT_API_KEY.isNotBlank()) MapKitFactory.getInstance().onStart()
    }

    override fun onStop() {
        if (BuildConfig.YANDEX_MAPKIT_API_KEY.isNotBlank()) MapKitFactory.getInstance().onStop()
        super.onStop()
    }
}
