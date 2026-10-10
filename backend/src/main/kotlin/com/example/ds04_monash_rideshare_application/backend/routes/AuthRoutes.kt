package com.example.ds04_monash_rideshare_application.backend.routes

import com.example.ds04_monash_rideshare_application.backend.readTransaction
import com.example.ds04_monash_rideshare_application.backend.AuthResponse
import com.example.ds04_monash_rideshare_application.backend.ChangePasswordRequest
import com.example.ds04_monash_rideshare_application.backend.ConflictException
import com.example.ds04_monash_rideshare_application.backend.DeleteAccountRequest
import com.example.ds04_monash_rideshare_application.backend.LoginRequest
import com.example.ds04_monash_rideshare_application.backend.LoginThrottle
import com.example.ds04_monash_rideshare_application.backend.RegisterRequest
import com.example.ds04_monash_rideshare_application.backend.Sessions
import com.example.ds04_monash_rideshare_application.backend.UnauthorizedException
import com.example.ds04_monash_rideshare_application.backend.Users
import com.example.ds04_monash_rideshare_application.backend.ValidationException
import com.example.ds04_monash_rideshare_application.backend.bearerToken
import com.example.ds04_monash_rideshare_application.backend.deleteAccount
import com.example.ds04_monash_rideshare_application.backend.requireUserId
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
        requireValidNewPassword(body.password)
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
        LoginThrottle.checkAllowed(email)
        val user = readTransaction { Users.selectAll().where { Users.emailAccount eq email }.firstOrNull() }
        val hash = user?.get(Users.passwordHash)
        // Same message whether the email or the password was wrong, so the API can't be used
        // to find out which Monash emails have accounts.
        if (user == null || hash == null || !verifyPassword(body.password, hash)) {
            LoginThrottle.recordFailure(email)
            throw UnauthorizedException("Incorrect email or password")
        }
        LoginThrottle.recordSuccess(email)
        call.respond(transaction { startSession(user[Users.userId]) })
    }

    // Change your password (you must know the current one). Signs you out on every other device;
    // the device making the change stays signed in.
    post("/auth/change-password") {
        val userId = call.requireUserId()
        val currentToken = call.bearerToken()!!
        val body = call.receive<ChangePasswordRequest>()
        requireCurrentPassword(userId, body.currentPassword)
        requireValidNewPassword(body.newPassword)
        if (body.newPassword == body.currentPassword) {
            throw ValidationException("Choose a different password from your current one")
        }
        val newHash = hashPassword(body.newPassword)
        transaction {
            Users.update({ Users.userId eq userId }) { it[passwordHash] = newHash }
            Sessions.deleteWhere { (Sessions.userId eq userId) and (Sessions.token neq currentToken) }
        }
        call.respond(mapOf("changed" to true))
    }

    // Delete your own account - see AccountAdmin.kt's deleteAccount for what happens to your trips.
    post("/auth/delete-account") {
        val userId = call.requireUserId()
        val body = call.receive<DeleteAccountRequest>()
        requireCurrentPassword(userId, body.password)
        transaction { deleteAccount(userId, System.currentTimeMillis()) }
        call.respond(mapOf("deleted" to true))
    }

    post("/auth/logout") {
        val token = call.bearerToken()
        if (token != null) transaction { Sessions.deleteWhere { Sessions.token eq token } }
        call.respond(mapOf("signedOut" to true))
    }
}

private fun requireValidNewPassword(password: String) {
    if (password.length < MIN_PASSWORD_LENGTH) {
        throw ValidationException("Password must be at least $MIN_PASSWORD_LENGTH characters")
    }
}

// For actions that re-confirm the signed-in user's password. A 400 rather than a 401, so the app
// shows it as a wrong-password message instead of treating it as being signed out.
private fun requireCurrentPassword(userId: String, password: String) {
    val hash = readTransaction { Users.selectAll().where { Users.userId eq userId }.first()[Users.passwordHash] }
    if (hash == null || !verifyPassword(password, hash)) {
        throw ValidationException("Your current password is incorrect")
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
