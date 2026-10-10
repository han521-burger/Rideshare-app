package com.example.ds04_monash_rideshare_application.Repository.Remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

// How a finished HTTP response reaches the screens. The key promise: a request the backend
// accepted is never reported as failed, whatever the app does afterwards.
class DeliverResponseTest {
    private class Recorder<T> : RideApiService.Callback<T> {
        val successes = mutableListOf<T>()
        val failures = mutableListOf<Exception>()
        var throwOnSuccess: Exception? = null
        override fun onSuccess(result: T) {
            successes += result
            throwOnSuccess?.let { throw it }
        }
        override fun onFailure(error: Exception) { failures += error }
    }

    private fun errorBody(json: String) = json.toResponseBody("application/json".toMediaType())

    @Test fun aSuccessfulResponseIsMappedAndDelivered() {
        val callback = Recorder<Int>()
        deliverResponse(Response.success("41"), callback, { it.toInt() + 1 })
        assertEquals(listOf(42), callback.successes)
        assertTrue(callback.failures.isEmpty())
    }

    @Test fun anExceptionInTheSuccessHandlerIsNotReportedAsAFailedRequest() {
        val callback = Recorder<String>().apply { throwOnSuccess = IllegalStateException("screen blew up") }
        val contained = mutableListOf<Throwable>()

        deliverResponse(Response.success("saved"), callback, { it }, onSuccessHandlerError = { contained += it })

        assertEquals(listOf("saved"), callback.successes)
        // No "Something went wrong" for a request that was actually saved.
        assertTrue("onFailure must not run after a successful response", callback.failures.isEmpty())
        assertEquals(1, contained.size)
        assertEquals("screen blew up", contained.single().message)
    }

    @Test fun theDefaultHandlerAlsoContainsItWithoutAnAndroidRuntime() {
        // Plain JVM tests have no android.util.Log; containing the error must not itself throw.
        val callback = Recorder<String>().apply { throwOnSuccess = RuntimeException("boom") }
        deliverResponse(Response.success("saved"), callback, { it })
        assertTrue(callback.failures.isEmpty())
    }

    @Test fun aResponseThatCannotBeMappedIsAFailure() {
        val callback = Recorder<Int>()
        deliverResponse(Response.success("not a number"), callback, { it.toInt() })
        assertTrue(callback.successes.isEmpty())
        assertEquals(1, callback.failures.size)
        assertEquals("Something went wrong. Please try again shortly.", callback.failures.single().message)
    }

    @Test fun anEmptyBodyIsAFailure() {
        val callback = Recorder<String>()
        deliverResponse(Response.success<String>(null), callback, { it })
        assertEquals("Empty response from server", callback.failures.single().message)
    }

    @Test fun aDuplicateRefusalKeepsItsCodeAndTheExistingRequestId() {
        val callback = Recorder<String>()
        val body = errorBody("""{"error":"You've already sent this request","code":"DUPLICATE_OPEN_REQUEST","existingRequestId":"req-123"}""")
        deliverResponse(Response.error<String>(409, body), callback, { it })

        val failure = callback.failures.single() as BackendException
        assertEquals(DUPLICATE_OPEN_REQUEST, failure.code)
        assertEquals("req-123", failure.existingRequestId)
        assertTrue(callback.successes.isEmpty())
    }

    @Test fun anOrdinaryRefusalHasNoExistingRequestId() {
        val callback = Recorder<String>()
        deliverResponse(Response.error<String>(409, errorBody("""{"error":"Not enough seats left","code":null}""")), callback, { it })
        val failure = callback.failures.single() as BackendException
        assertEquals("Not enough seats left", failure.message)
        assertNull(failure.code)
        assertNull(failure.existingRequestId)
    }

    @Test fun aTravelTimeUnavailableRefusalShowsTheBackendsMessageAndKeepsItsCodeInsteadOfAGenericServerError() {
        val callback = Recorder<String>()
        val message = "We couldn't work out the travel time for this trip right now, so it can't be requested yet. Please try again later."
        deliverResponse(Response.error<String>(503, errorBody("""{"error":"$message","code":"TRAVEL_TIME_UNAVAILABLE"}""")), callback, { it })
        val failure = callback.failures.single() as BackendException
        assertEquals(message, failure.message)
        assertEquals("TRAVEL_TIME_UNAVAILABLE", failure.code)
    }

    @Test fun anUnapprovedLocationRefusalKeepsItsCodeAndMessage() {
        val callback = Recorder<String>()
        deliverResponse(
            Response.error<String>(400, errorBody("""{"error":"The pickup isn't an approved pickup/drop-off point","code":"UNAPPROVED_LOCATION"}""")),
            callback, { it }
        )
        val failure = callback.failures.single() as BackendException
        assertEquals("The pickup isn't an approved pickup/drop-off point", failure.message)
        assertEquals("UNAPPROVED_LOCATION", failure.code)
    }

    @Test fun aServerErrorWithNoBodyStillGetsTheFriendlyGenericMessage() {
        val callback = Recorder<String>()
        deliverResponse(Response.error<String>(503, errorBody("")), callback, { it })
        assertEquals("The server had a problem. Please try again shortly.", callback.failures.single().message)
    }

    @Test fun anOlderBackendErrorBodyWithoutTheNewFieldStillParses() {
        val callback = Recorder<String>()
        deliverResponse(Response.error<String>(409, errorBody("""{"error":"Pick another ride","code":"OWN_PENDING_REQUEST"}""")), callback, { it })
        val failure = callback.failures.single() as BackendException
        assertEquals(CONFLICT_OWN_PENDING_REQUEST, failure.code)
        assertNull(failure.existingRequestId)
    }
}
