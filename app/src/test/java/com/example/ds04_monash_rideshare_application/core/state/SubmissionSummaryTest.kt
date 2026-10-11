package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.AuthResult
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateRequestsResultDto
import com.example.ds04_monash_rideshare_application.Repository.Remote.DUPLICATE_OPEN_REQUEST
import com.example.ds04_monash_rideshare_application.Repository.Remote.FakeRideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.OccurrenceOutcome
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestSubmissionResult
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.SCHEDULE_INFEASIBLE
import com.example.ds04_monash_rideshare_application.Repository.Remote.StandaloneRequestDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

// After a repeating request, the passenger is told what each date came to - in words, never in backend codes - and a
// one-off request stays the plain success / ordinary error it always was.
class SubmissionSummaryTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")
    private fun ms(day: Int, hour: Int = 8) = LocalDateTime.of(2027, 5, day, hour, 0).atZone(melbourne).toInstant().toEpochMilli()

    private fun made(day: Int) = OccurrenceOutcome(ms(day), true, null, "req-$day", null, null, null, null)
    private fun refused(day: Int, code: String?) = OccurrenceOutcome(ms(day), false, null, null, code, "x", null, null)
    private fun result(vararg occurrences: OccurrenceOutcome) = RequestSubmissionResult(
        occurrences.size, occurrences.count { it.created }, occurrences.count { !it.created }, emptyList(), occurrences.toList()
    )
    private fun summarise(r: RequestSubmissionResult) = summariseSubmission(r, melbourne, Locale.ENGLISH)

    // ---------------------------------------------------------------- the words
    @Test fun threeOfFourReadsLikeTheProductExample() {
        val s = summarise(result(made(3), refused(10, SCHEDULE_INFEASIBLE), refused(17, DUPLICATE_OPEN_REQUEST), made(24)))
        assertEquals("2 of 4 ride requests created.", s.title)
        assertEquals(
            listOf(
                "Mon 3 May, 8:00 AM – Created",
                "Mon 10 May, 8:00 AM – Doesn’t fit your schedule",
                "Mon 17 May, 8:00 AM – Already requested",
                "Mon 24 May, 8:00 AM – Created"
            ),
            s.lines
        )
    }

    @Test fun theTitleSaysAllSomeOrNone() {
        assertEquals("All 4 ride requests created.", summaryTitle(4, 4))
        assertEquals("3 of 4 ride requests created.", summaryTitle(4, 3))
        assertEquals("None of the 4 requests could be created.", summaryTitle(4, 0))
        assertEquals("None of the 2 requests could be created.", summarise(result(refused(3, DUPLICATE_OPEN_REQUEST), refused(10, DUPLICATE_OPEN_REQUEST))).title)
    }

    @Test fun everyKnownReasonHasItsOwnPlainSentence() {
        val texts = listOf(DUPLICATE_OPEN_REQUEST, SCHEDULE_INFEASIBLE, CODE_TRAVEL_TIME_UNAVAILABLE, CODE_RIDE_FULL, CODE_RIDE_UNAVAILABLE, CODE_OWN_RIDE)
            .map { occurrenceStatusText(refused(3, it)) }
        assertEquals(texts.size, texts.toSet().size)
        assertEquals(listOf("Already requested", "Doesn’t fit your schedule", "Couldn’t check your schedule", "Ride is full", "Ride is no longer open", "Your own ride"), texts)
    }

    @Test fun noRawBackendCodeEverReachesTheUser() {
        val codes = listOf(DUPLICATE_OPEN_REQUEST, SCHEDULE_INFEASIBLE, CODE_TRAVEL_TIME_UNAVAILABLE, CODE_RIDE_FULL, CODE_RIDE_UNAVAILABLE, CODE_OWN_RIDE, "SOMETHING_NEW_FROM_A_NEWER_BACKEND", null)
        val s = summarise(result(*codes.mapIndexed { i, c -> refused(3 + i, c) }.toTypedArray()))
        s.lines.forEach { line ->
            assertFalse(line, Regex("[A-Z]{3,}_[A-Z_]+").containsMatchIn(line))
            assertFalse(line, line.contains("SOMETHING_NEW"))
        }
        assertEquals("Couldn’t be requested", occurrenceStatusText(refused(3, "SOMETHING_NEW_FROM_A_NEWER_BACKEND")))
        assertEquals("Couldn’t be requested", occurrenceStatusText(refused(3, null)))
    }

    @Test fun datesAreShownInTheViewersOwnZone() {
        val perth = summariseSubmission(result(made(3), made(10)), ZoneId.of("Australia/Perth"), Locale.ENGLISH)
        assertEquals("8:00 in Melbourne is 6:00 in Perth", "Mon 3 May, 6:00 AM – Created", perth.lines.first())
    }

    // ---------------------------------------------------------------- what the ViewModel does with it
    private class Api : RideApiService by FakeRideApiService() {
        var answer: RequestSubmissionResult? = null
        var mine: List<RideRequestSummary> = emptyList()      // what My Requests returns when refreshed
        val keys = mutableListOf<String?>()
        override fun createStandaloneRequest(draft: StandaloneRequestDraft, callback: RideApiService.Callback<RequestSubmissionResult>) {
            keys += draft.clientRequestId
            callback.onSuccess(answer!!)
        }
        override fun requestSeat(
            rideId: String, seats: Int, note: String?, applyToRecurringGroup: Boolean, clientRequestId: String?,
            journeyIntentId: String?, callback: RideApiService.Callback<RequestSubmissionResult>
        ) {
            keys += clientRequestId
            callback.onSuccess(answer!!)
        }
        override fun fetchMyRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) { callback.onSuccess(mine) }
        override fun signIn(email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
            callback.onSuccess(AuthResult("token-$email", UserAccountSummary(email, email, email, email, null, null, true, null, null)))
        }
    }

    private val api = Api()
    private val vm = RideshareViewModel(api)
    private val draft = StandaloneRequestDraft(
        originLat = -37.9112, originLng = 145.1029, destinationLat = -37.9105, destinationLng = 145.1362,
        isAsap = false, desiredDepartureTime = 1_900_000_000_000L, seatsRequested = 1, note = null,
        repeatDaysOfWeek = listOf(1), repeatWeeks = 4
    )

    @After fun clearSession() { AuthSession.clear() }

    private fun send(): Pair<Boolean, RequestSubmitError?> {
        var outcome: Pair<Boolean, RequestSubmitError?>? = null
        vm.createStandaloneRequest(draft) { ok, e -> outcome = ok to e }
        return outcome!!
    }

    @Test fun aPartialBatchIsASuccessThatCarriesASummary() {
        api.answer = result(made(3), refused(10, SCHEDULE_INFEASIBLE), made(17), made(24))
        val (ok, error) = send()
        assertTrue(ok); assertNull(error)
        assertEquals("3 of 4 ride requests created.", vm.submissionSummary!!.title)
        assertEquals(4, vm.submissionSummary!!.lines.size)
    }

    @Test fun aBatchWhereEverythingWasMadeStillSaysSo() {
        api.answer = result(made(3), made(10), made(17), made(24))
        assertTrue(send().first)
        assertEquals("All 4 ride requests created.", vm.submissionSummary!!.title)
    }

    @Test fun aBatchWhereNothingWasMadeKeepsThePassengerOnTheFormWithoutAnErrorBanner() {
        api.answer = result(refused(3, DUPLICATE_OPEN_REQUEST), refused(10, DUPLICATE_OPEN_REQUEST), refused(17, SCHEDULE_INFEASIBLE), refused(24, DUPLICATE_OPEN_REQUEST))
        val (ok, error) = send()
        assertFalse("nothing was created, so this is not a success", ok)
        assertNull("the summary explains it; a second banner would just repeat it", error)
        assertEquals("None of the 4 requests could be created.", vm.submissionSummary!!.title)
        assertFalse(vm.actionInFlight)
    }

    @Test fun aOneOffRequestIsAPlainSuccessWithNoSummary() {
        api.answer = result(made(3))
        val (ok, error) = send()
        assertTrue(ok); assertNull(error)
        assertNull(vm.submissionSummary)
    }

    @Test fun aOneOffRequestIsRememberedSoTheAppCanOpenIt() {
        api.answer = result(made(3))
        send()
        assertEquals("req-3", vm.lastCreatedRequestId)
    }

    @Test fun aBatchOrANothingMadeResultHasNoSingleRequestToOpen() {
        api.answer = result(made(3))
        send()
        api.answer = result(made(3), made(10))
        send()
        assertNull("a batch lands on the Requests list instead", vm.lastCreatedRequestId)
        assertNull(createdOneOffRequestId(result(refused(3, DUPLICATE_OPEN_REQUEST))))
    }

    @Test fun aProcessedBatchEndsTheSubmissionSoTheNextTapIsANewOne() {
        api.answer = result(refused(3, DUPLICATE_OPEN_REQUEST), refused(10, DUPLICATE_OPEN_REQUEST))
        send(); send()
        assertEquals("a processed batch (even one that made nothing) is finished: the next tap is a new submission", 2, api.keys.toSet().size)
        assertNotNull(api.keys.first())
    }

    @Test fun theSummaryGoesAwayWhenDismissedOrSignedOut() {
        vm.signIn("a@student.monash.edu", "password", keepSignedIn = false)
        api.answer = result(made(3), refused(10, SCHEDULE_INFEASIBLE))
        send()
        assertNotNull(vm.submissionSummary)
        vm.dismissSubmissionSummary()
        assertNull(vm.submissionSummary)
        send()
        vm.signOut()
        assertNull(vm.submissionSummary)
    }

    @Test fun theSummaryIsTheSubmissionsOwnAnswerAndALaterRefreshOfMyRequestsDoesNotChangeIt() {
        api.answer = result(made(3), refused(10, SCHEDULE_INFEASIBLE), made(17))
        send()
        val shown = vm.submissionSummary
        assertEquals("2 of 3 ride requests created.", shown!!.title)
        // Later the app refreshes My Requests (as after any submission or replay): that is a separate read of the latest state.
        api.mine = listOf(RideRequestSummary("req-3", null, "p", 1, "ACCEPTED", null, 1, 2, null, null, null, null, false, 1000, emptyList(), null, null, null, null))
        vm.loadMyRequests()
        assertEquals("ACCEPTED", vm.myRequests.data!!.single().status)
        assertEquals("the summary still describes what the submission came to", shown, vm.submissionSummary)
    }

    @Test fun aSeatSeriesGetsTheSameSummary() {
        api.answer = result(made(3), refused(4, CODE_RIDE_FULL), refused(5, CODE_RIDE_UNAVAILABLE))
        var ok: Boolean? = null
        vm.requestSeat("ride-1", 1, null, true) { success, _ -> ok = success }
        assertEquals(true, ok)
        assertEquals("1 of 3 ride requests created.", vm.submissionSummary!!.title)
        assertEquals(listOf("Ride is full", "Ride is no longer open"), vm.submissionSummary!!.lines.drop(1).map { it.substringAfter("– ") })
        assertNotEquals(0, api.keys.size)
    }

    // ---------------------------------------------------------------- what the backend sends
    @Test fun theBackendsEnvelopeParsesWithItsRejectedDatesAndTheirReasons() {
        val json = """{"requested":3,"created":1,"rejected":2,"requests":[],"occurrences":[
            {"departureTime":1800000000000,"outcome":"CREATED","requestId":"req-1"},
            {"departureTime":1800604800000,"outcome":"REJECTED","code":"DUPLICATE_OPEN_REQUEST","message":"You've already sent this request","existingRequestId":"req-0"},
            {"departureTime":1801209600000,"outcome":"REJECTED","code":"SCHEDULE_INFEASIBLE","message":"This doesn't fit your schedule",
             "conflict":{"type":"TOO_LATE_FOR_NEXT","conflictingId":"req-9","conflictingKind":"OPEN_REQUEST","conflictingDepartureTime":1,"conflictingArrivalTime":2,
             "transferBufferSeconds":600,"proposedDepartureTime":3,"proposedArrivalTime":4}}]}"""
        val dto = Gson().fromJson(json, CreateRequestsResultDto::class.java)
        assertEquals(listOf(3, 1, 2), listOf(dto.requested, dto.created, dto.rejected))
        assertEquals("req-0", dto.occurrences!![1].existingRequestId)
        assertEquals("req-9", dto.occurrences!![2].conflict!!.conflictingId)
        assertEquals("CREATED", dto.occurrences!![0].outcome)
    }

    @Test fun anEnvelopeWithNoOccurrenceListStillParses() {
        val dto = Gson().fromJson("""{"requested":1,"created":1,"rejected":0,"requests":[]}""", CreateRequestsResultDto::class.java)
        assertTrue(dto.occurrences.orEmpty().isEmpty())
    }
}
