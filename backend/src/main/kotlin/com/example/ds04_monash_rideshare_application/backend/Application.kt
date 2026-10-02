package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.dashboardRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.favoriteRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.healthRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.messageRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.ratingRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.rideRequestRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.rideRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.userRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.vehicleRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module() }.start(wait = true)
}

fun Application.module(databaseUrl: String = "jdbc:sqlite:./rideshare-backend.db") {
    initDatabase(databaseUrl)

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CallLogging)
    install(CORS) {
        anyHost() // Trial-only: local dev/emulator convenience. Lock this down before real hosting.
        allowHeader("Authorization")
        allowHeader("Content-Type")
    }
    installErrorHandling()

    // No scheduler in this trial - expire/complete stale requests and rides before each call so
    // every response already reflects the current time (see Lifecycle.kt).
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.path() != "/health") expireStaleRecords()
    }

    routing {
        healthRoutes()
        userRoutes()
        vehicleRoutes()
        rideRoutes()
        rideRequestRoutes()
        messageRoutes()
        dashboardRoutes()
        ratingRoutes()
        favoriteRoutes()
    }
}
