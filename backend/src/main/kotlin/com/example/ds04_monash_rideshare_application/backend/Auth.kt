package com.example.ds04_monash_rideshare_application.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll

// The app signs in with a Monash email + password (routes/AuthRoutes.kt) and gets back a random
// session token, sent on every call as "Authorization: Bearer <token>". Every protected route
// calls requireUserId() to resolve "who is calling", and route handlers are responsible for
// checking that userId owns whatever resource it's trying to modify (NFR: "users can't modify
// other users' profiles or rides they don't own"). Swap this for real Monash SSO (OIDC/JWT)
// once that's unblocked (RTM FR-01, Gate G1).
fun ApplicationCall.requireUserId(): String {
    val token = bearerToken() ?: throw UnauthorizedException()
    val now = System.currentTimeMillis()
    val sessionUser = readTransaction {
        Sessions.selectAll().where { Sessions.token eq token }.firstOrNull()
            ?.takeIf { it[Sessions.expiresAt] > now }
            ?.get(Sessions.userId)
    }
    if (sessionUser != null) return sessionUser

    // Backend tests only (set in backend/build.gradle's test task): let a test call as a seeded
    // user by sending its userId as the token, without signing in first. Never set in a real run.
    if (System.getProperty(TRUST_USER_ID_TOKENS_PROPERTY) == "true") {
        val exists = readTransaction { Users.selectAll().where { Users.userId eq token }.count() > 0 }
        if (exists) return token
    }
    throw UnauthorizedException("Please sign in again")
}

fun ApplicationCall.bearerToken(): String? =
    request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }

const val TRUST_USER_ID_TOKENS_PROPERTY = "rideshare.test.trustUserIdTokens"
