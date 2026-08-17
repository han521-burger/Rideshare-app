package com.example.ds04_monash_rideshare_application.backend

import com.example.ds04_monash_rideshare_application.backend.routes.dashboardRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.healthRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.messageRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.rideRequestRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.rideRoutes
import com.example.ds04_monash_rideshare_application.backend.routes.userRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    initDatabase()

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

    routing {
        healthRoutes()
        userRoutes()
        rideRoutes()
        rideRequestRoutes()
        messageRoutes()
        dashboardRoutes()
    }
}
