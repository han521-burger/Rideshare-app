package com.example.ds04_monash_rideshare_application

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.example.ds04_monash_rideshare_application.core.state.RideshareViewModel
import com.example.ds04_monash_rideshare_application.ui.theme.Ds04_monash_rideshare_applicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val rideshareViewModel = ViewModelProvider(this)[RideshareViewModel::class.java]
        setContent {
            Ds04_monash_rideshare_applicationTheme {
                RideshareApp(viewModel = rideshareViewModel)
            }
        }
    }
}
