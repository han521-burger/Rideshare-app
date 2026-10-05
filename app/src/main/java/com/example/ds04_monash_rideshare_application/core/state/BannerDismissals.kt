package com.example.ds04_monash_rideshare_application.core.state

import android.content.Context
import android.content.SharedPreferences

// Remembers which driver-request banners each account has swiped away, on this device, so a
// dismissed banner stays dismissed after navigating around or restarting the app. Without init
// (e.g. unit tests) it just keeps nothing between runs.
object BannerDismissals {
    private var prefs: SharedPreferences? = null

    // Called once from MainActivity.
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("banner_dismissals", Context.MODE_PRIVATE)
    }

    fun load(userId: String): Set<String> = prefs?.getStringSet(key(userId), null)?.toSet().orEmpty()

    fun save(userId: String, requestIds: Set<String>) {
        prefs?.edit()?.putStringSet(key(userId), requestIds)?.apply()
    }

    private fun key(userId: String) = "dismissed_$userId"
}
