package com.example.ds04_monash_rideshare_application.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

// Typed API errors. StatusPages below maps each to a status code and a plain {"error": "..."}
// body - callers (including the Android app) never see a raw exception or stack trace (NFR:
// "friendly error handling ... no raw exceptions shown to users").
sealed class ApiException(message: String) : Exception(message)
class NotFoundException(message: String = "Not found") : ApiException(message)
class ForbiddenException(message: String = "You don't have permission to do that") : ApiException(message)
class UnauthorizedException(message: String = "Sign-in required") : ApiException(message)
class ValidationException(message: String) : ApiException(message)
// code lets the app react to a specific conflict (e.g. offer "cancel my own request and accept")
// instead of only showing the message.
class ConflictException(message: String, val code: String? = null) : ApiException(message)

private val logger = LoggerFactory.getLogger("Errors")

fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Not found"))
        }
        exception<ForbiddenException> { call, cause ->
            call.respond(HttpStatusCode.Forbidden, ErrorResponse(cause.message ?: "Forbidden"))
        }
        exception<UnauthorizedException> { call, cause ->
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse(cause.message ?: "Unauthorized"))
        }
        exception<ValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Invalid request"))
        }
        exception<ConflictException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "Conflict", cause.code))
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled error on ${call.request.path()}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Something went wrong on our end. Please try again shortly.")
            )
        }
    }
}
