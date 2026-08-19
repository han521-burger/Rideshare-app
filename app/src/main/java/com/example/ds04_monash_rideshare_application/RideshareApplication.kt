package com.example.ds04_monash_rideshare_application

import android.app.Application
import com.yandex.mapkit.MapKitFactory

class RideshareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.YANDEX_MAPKIT_API_KEY.isNotBlank()) {
            MapKitFactory.setApiKey(BuildConfig.YANDEX_MAPKIT_API_KEY)
        }
    }
}
