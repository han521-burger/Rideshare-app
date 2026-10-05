package com.example.ds04_monash_rideshare_application.Auth

import android.content.Context
import android.content.SharedPreferences

// Who is signed in: the session token the network layer sends as a bearer token
// (RetrofitClient's AuthInterceptor), and that user's id. "Keep me signed in" also saves both
// on the device so the next app launch skips the sign-in screen; otherwise they live only
// in memory until the app closes.
object AuthSession {
    @Volatile
    var currentUserId: String? = null

    @Volatile
    var token: String? = null

    private var prefs: SharedPreferences? = null

    // Called once from MainActivity. Without it (e.g. unit tests) nothing is persisted.
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    }

    fun start(token: String, userId: String, remember: Boolean) {
        this.token = token
        currentUserId = userId
        prefs?.edit()?.apply {
            if (remember) putString(KEY_TOKEN, token).putString(KEY_USER_ID, userId) else clear()
        }?.apply()
    }

    // Loads a saved session into memory; true if there was one to try.
    fun restoreSaved(): Boolean {
        val savedToken = prefs?.getString(KEY_TOKEN, null) ?: return false
        val savedUser = prefs?.getString(KEY_USER_ID, null) ?: return false
        token = savedToken
        currentUserId = savedUser
        return true
    }

    fun clear() {
        token = null
        currentUserId = null
        prefs?.edit()?.clear()?.apply()
    }

    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
}
