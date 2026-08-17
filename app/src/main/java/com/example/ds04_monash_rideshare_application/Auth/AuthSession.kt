package com.example.ds04_monash_rideshare_application.Auth

// Holds the current signed-in userId so the network layer (AuthInterceptor) can attach it as a
// bearer token without every call site having to thread it through manually. Trial-only stand-in
// for a real token store (see backend/Auth.kt for why the token IS just the userId right now).
object AuthSession {
    @Volatile
    var currentUserId: String? = null
}
