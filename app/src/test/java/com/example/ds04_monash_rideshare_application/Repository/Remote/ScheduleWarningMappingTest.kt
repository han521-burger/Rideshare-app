package com.example.ds04_monash_rideshare_application.Repository.Remote

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// The wire shape the backend sends for GET /schedule-warnings (backend ScheduleWarningDto) and for a ride's completedAt.
class ScheduleWarningMappingTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> fakeApi(method: String, body: T): BackendApi {
        val call = Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { proxy, m, args ->
            if (m.name == "enqueue") {
                (args!![0] as Callback<T>).onResponse(proxy as Call<T>, Response.success(body)); null
            } else error("Unexpected call: ${m.name}")
        }
        return Proxy.newProxyInstance(BackendApi::class.java.classLoader, arrayOf(BackendApi::class.java)) { _, m, _ ->
            if (m.name == method) call else error("Unexpected API call: ${m.name}")
        } as BackendApi
    }

    private fun warnings(json: String): List<ScheduleWarning> {
        val dtos = Gson().fromJson(json, Array<ScheduleWarningDto>::class.java).toList()
        var result: List<ScheduleWarning>? = null
        RideApiServiceImpl(fakeApi("getScheduleWarnings", dtos)).fetchScheduleWarnings(object : RideApiService.Callback<List<ScheduleWarning>> {
            override fun onSuccess(result0: List<ScheduleWarning>) { result = result0 }
            override fun onFailure(error: Exception) { throw AssertionError(error) }
        })
        return requireNotNull(result)
    }

    private val one = """[{"warningId":"warn-1","type":"PREVIOUS_RIDE_FINISHED_LATE","subjectKind":"REQUEST","subjectId":"req-9","createdAt":1800000000000,
        "details":{"previousRideId":"ride-a","previousCompletedAt":1800000000000,"previousPlannedArrivalTime":1799998800000,
        "fromPointId":"monash-caulfield","fromPointName":"Monash Caulfield","toPointId":"monash-caulfield","toPointName":"Monash Caulfield",
        "repositioningSeconds":0,"transferBufferSeconds":600,"earliestFeasibleDeparture":1800000600000,"nextDepartureTime":1799999700000}}]"""

    @Test fun theBackendShapeIsReadIntoAWarning() {
        val w = warnings(one).single()
        assertEquals("warn-1", w.warningId)
        assertEquals(WarningSubject.REQUEST, w.subjectKind)
        assertEquals("req-9", w.subjectId)
        assertEquals("ride-a", w.previousRideId)
        assertEquals(1800000000000L, w.previousCompletedAt)
        assertEquals(1799998800000L, w.previousPlannedArrivalTime)
        assertEquals("Monash Caulfield", w.fromPointName)
        assertEquals(0L, w.repositioningSeconds)
        assertEquals(600L, w.transferBufferSeconds)
        assertEquals(1800000600000L, w.earliestFeasibleDeparture)
        assertEquals(1799999700000L, w.nextDepartureTime)
    }

    @Test fun anEmptyListAndAnEntryWithoutDetailsAreHandled() {
        assertTrue(warnings("[]").isEmpty())
        // Nothing to explain, so nothing to show.
        assertTrue(warnings("""[{"warningId":"w","type":"PREVIOUS_RIDE_FINISHED_LATE","subjectKind":"RIDE","subjectId":"r","createdAt":1}]""").isEmpty())
    }

    @Test fun theLifecycleRefusalsReachTheUserWithTheirCodeAndAFriendlyMessage() {
        fun failure(json: String) = NetworkErrorMapper.fromErrorBody(409, okhttp3.ResponseBody.create("application/json".toMediaTypeOrNull(), json))
        val seat = failure("""{"error":"This trip is completed when the driver completes the ride.","code":"RIDE_NOT_COMPLETED"}""")
        assertEquals(RIDE_NOT_COMPLETED, seat.code)
        assertEquals("This trip is completed when the driver completes the ride.", seat.message)
        val early = failure("""{"error":"This ride hasn't started yet, so it can't be completed.","code":"RIDE_NOT_STARTED"}""")
        assertEquals(RIDE_NOT_STARTED, early.code)
    }

    @Test fun aRideCarriesItsCompletionTimeWhenThereIsOne() {
        fun ride(extra: String) = Gson().fromJson(
            """{"rideId":"r","driverId":"d","originLat":1.0,"originLng":2.0,"destinationLat":3.0,"destinationLng":4.0,"distanceMeters":10.0,
            "estimatedDurationSeconds":60,"departureTime":1,"seatsTotal":3,"seatsAvailable":2,"status":"COMPLETED","createdAt":1$extra}""",
            RideDto::class.java
        )
        assertEquals(1800000000000L, ride(""","completedAt":1800000000000""").completedAt)
        // An older ride, or one the system completed on its own, has none - and the app must not invent one.
        assertNull(ride("").completedAt)
    }
}
