package com.example.ds04_monash_rideshare_application.Repository.Remote

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.ResponseBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// Turns network/backend failures into short, friendly messages - never a raw exception message
// or stack trace reaches the UI (NFR: "no raw exceptions shown to users").
// code is set for errors the app reacts to specifically (e.g. CONFLICT_OWN_PENDING_REQUEST).
class BackendException(message: String, val code: String? = null) : Exception(message)

// Backend error codes (backend Lifecycle.kt).
const val CONFLICT_OWN_PENDING_REQUEST = "OWN_PENDING_REQUEST"

private data class BackendErrorBody(val error: String?, val code: String?)

object NetworkErrorMapper {
    fun fromThrowable(error: Throwable): BackendException = when (error) {
        is UnknownHostException, is SocketTimeoutException ->
            BackendException("Can't reach the server. Check your connection and try again.")
        is IOException ->
            BackendException("A network problem interrupted that request. Please try again.")
        else ->
            BackendException("Something went wrong. Please try again shortly.")
    }

    // Reads the backend's {"error": "..."} body (see backend Errors.kt) when present, so a
    // deliberate 4xx (e.g. "Not enough seats available") shows its real, still-friendly message
    // instead of a generic one.
    fun fromErrorBody(code: Int, body: ResponseBody?): BackendException {
        val parsed = body?.string()?.let {
            try {
                Gson().fromJson(it, BackendErrorBody::class.java)
            } catch (e: JsonSyntaxException) {
                null
            }
        }
        parsed?.error?.let { return BackendException(it, parsed.code) }
        return when (code) {
            401 -> BackendException("Please sign in again.")
            403 -> BackendException("You don't have permission to do that.")
            404 -> BackendException("That couldn't be found.")
            in 500..599 -> BackendException("The server had a problem. Please try again shortly.")
            else -> BackendException("That request couldn't be completed.")
        }
    }
}
