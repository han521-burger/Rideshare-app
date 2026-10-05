package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.AuthResponse
import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.LoginRequest
import com.example.ds04_monash_rideshare_application.backend.RegisterRequest
import com.example.ds04_monash_rideshare_application.backend.Sessions
import com.example.ds04_monash_rideshare_application.backend.UnauthorizedException
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.bearerToken
import com.example.ds04_monash_rideshare_application.backend.hashPassword
import com.example.ds04_monash_rideshare_application.backend.newSessionToken
import com.example.ds04_monash_rideshare_application.backend.toUserDto
import com.example.ds04_monash_rideshare_application.backend.verifyPassword
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Locale
import java.util.UUID

// Sessions last 30 days; signing out deletes the session straight away.
private const val SESSION_LIFETIME_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val MIN_PASSWORD_LENGTH = 8
private val EMAIL_FORMAT = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun Route.authRoutes() {
    // Create an account with a Monash email + password, then sign straight in.
    post("/auth/register") {
        val body = call.receive<RegisterRequest>()
        val email = normalizeMonashEmail(body.email)
        val displayName = body.displayName.trim()
        if (displayName.length !in 1..128) throw ValidationException("Enter your name")
        if (body.password.length < MIN_PASSWORD_LENGTH) {
            throw ValidationException("Password must be at least $MIN_PASSWORD_LENGTH characters")
        }
        val passwordHash = hashPassword(body.password)

        val response = transaction {
            if (Users.selectAll().where { Users.emailAccount eq email }.count() > 0) {
                throw ConflictException("An account with this email already exists - sign in instead")
            }
            val userId = "user-${UUID.randomUUID()}"
            Users.insert {
                it[Users.userId] = userId
                it[externalSubjectId] = "local-$userId"
                it[emailAccount] = email
                it[Users.displayName] = displayName
                it[preferredName] = displayName.substringBefore(' ')
                it[Users.passwordHash] = passwordHash
                it[createdAt] = System.currentTimeMillis()
            }
            startSession(userId)
        }
        call.respond(response)
    }

    post("/auth/login") {
        val body = call.receive<LoginRequest>()
        val email = body.email.trim().lowercase(Locale.ROOT)
        val response = transaction {
            val user = Users.selectAll().where { Users.emailAccount eq email }.firstOrNull()
            val hash = user?.get(Users.passwordHash)
            // Same message whether the email or the password was wrong, so the API can't be used
            // to find out which Monash emails have accounts.
            if (user == null || hash == null || !verifyPassword(body.password, hash)) {
                throw UnauthorizedException("Incorrect email or password")
            }
            startSession(user[Users.userId])
        }
        call.respond(response)
    }

    post("/auth/logout") {
        val token = call.bearerToken()
        if (token != null) transaction { Sessions.deleteWhere { Sessions.token eq token } }
        call.respond(mapOf("signedOut" to true))
    }
}

// Must be called inside a transaction.
private fun startSession(userId: String): AuthResponse {
    val now = System.currentTimeMillis()
    val token = newSessionToken()
    Sessions.insert {
        it[Sessions.token] = token
        it[Sessions.userId] = userId
        it[createdAt] = now
        it[expiresAt] = now + SESSION_LIFETIME_MILLIS
    }
    val user = Users.selectAll().where { Users.userId eq userId }.first().toUserDto()
    return AuthResponse(token, user)
}

private fun normalizeMonashEmail(raw: String): String {
    val email = raw.trim().lowercase(Locale.ROOT)
    val monash = email.endsWith("@student.monash.edu") || email.endsWith("@monash.edu")
    if (!EMAIL_FORMAT.matches(email) || !monash) {
        throw ValidationException("Use your Monash email (@student.monash.edu or @monash.edu)")
    }
    return email
}
