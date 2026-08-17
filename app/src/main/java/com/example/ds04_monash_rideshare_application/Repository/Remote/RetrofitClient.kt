package com.example.ds04_monash_rideshare_application.Repository.Remote

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

// Attaches the current signed-in userId as a bearer token (see backend/Auth.kt for why the
// token is just the userId, trial-only, until real Monash SSO is wired up).
private object AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val userId = AuthSession.currentUserId
        val request = if (userId != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $userId")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}

object RetrofitClient {
    val backendApi: BackendApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor)
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BackendApi::class.java)
    }
}
