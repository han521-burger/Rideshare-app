package com.example.ds04_monash_rideshare_application

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ds04_monash_rideshare_application.ui.RootNavHost
import com.example.ds04_monash_rideshare_application.ui.theme.Ds04_monash_rideshare_applicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ds04_monash_rideshare_applicationTheme {
                RootNavHost()
            }
        }
    }
}
