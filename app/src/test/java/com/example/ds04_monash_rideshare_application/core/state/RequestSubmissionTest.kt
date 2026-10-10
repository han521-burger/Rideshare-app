package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.AuthResult
import com.example.ds04_monash_rideshare_application.Repository.Remote.BackendException
import com.example.ds04_monash_rideshare_application.Repository.Remote.DUPLICATE_OPEN_REQUEST
import com.example.ds04_monash_rideshare_application.Repository.Remote.FakeRideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideApiService
import com.example.ds04_monash_rideshare_application.Repository.Remote.OccurrenceOutcome
import com.example.ds04_monash_rideshare_application.Repository.Remote.RequestSubmissionResult
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideRequestSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSearchCriteria
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.StandaloneRequestDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.UserAccountSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionKeysTest {
    private var counter = 0
    private val keys = SubmissionKeys<String> { "key-${++counter}" }

    @Test fun theSameSubmissionKeepsItsKeyAcrossRetries() {
        val first = keys.keyFor("ride to Clayton")
        assertEquals(first, keys.keyFor("ride to Clayton"))
        assertEquals(first, keys.keyFor("ride to Clayton"))
        assertEquals(1, counter)
    }

    @Test fun aChangedSubmissionGetsANewKey() {
        val first = keys.keyFor("ride to Clayton")
        assertNotEquals(first, keys.keyFor("ride to Caulfield"))
    }

    @Test fun afterResetTheSameSubmissionIsANewOne() {
        val first = keys.keyFor("ride to Clayton")
        keys.reset()
        assertNotEquals(first, keys.keyFor("ride to Clayton"))
    }

    @Test fun editingBackToTheEarlierSubmissionDoesNotResurrectItsOldKey() {
        val first = keys.keyFor("A")
        keys.keyFor("B")
        // A different submission was made in between, so A is a fresh one now.
        assertNotEquals(first, keys.keyFor("A"))
    }

    @Test fun generatedKeysSatisfyTheBackendFormat() {
        val key = SubmissionKeys<String>().keyFor("x")
        assertTrue(Regex("^[A-Za-z0-9_-]{8,64}$").matches(key))
    }
}

class RequestSubmitErrorMappingTest {
    @Test fun aDuplicateRefusalBecomesASpecificErrorNamingTheExistingRequest() {
        val error = BackendException("server text", DUPLICATE_OPEN_REQUEST, "req-9").toRequestSubmitError("fallback")
        assertTrue(error.isDuplicate)
        assertEquals("req-9", error.existingRequestId)
        // The app\'s own wording is used, not the backend\'s text.
        assertEquals(DUPLICATE_REQUEST_MESSAGE, error.message)
    }

    @Test fun aDuplicateWithoutAnIdIsStillADuplicate() {
        val error = BackendException("x", DUPLICATE_OPEN_REQUEST).toRequestSubmitError("fallback")
        assertTrue(error.isDuplicate)
        assertNull(error.existingRequestId)
    }

    @Test fun anyOtherFailureKeepsItsMessageAndIsNotADuplicate() {
        val error = BackendException("Not enough seats left", "SOMETHING_ELSE").toRequestSubmitError("fallback")
        assertFalse(error.isDuplicate)
        assertEquals("Not enough seats left", error.message)
        assertNull(error.existingRequestId)
    }

    @Test fun aFailureWithNoMessageUsesTheFallback() {
        assertEquals("fallback", Exception().toRequestSubmitError("fallback").message)
    }
}

// The ViewModel end of sending a request: one idempotency key per logical submission, and a
// duplicate answer handled as "you already have this", not as a failure.
class RequestSubmissionViewModelTest {
    private class RecordingApi : RideApiService by FakeRideApiService() {
        val drafts = mutableListOf<StandaloneRequestDraft>()
        val seatKeys = mutableListOf<String?>()
        val seatIntents = mutableListOf<String?>()
        var searchHits: List<String> = emptyList()   // ride ids the next search returns
        var myRequestFetches = 0
        var next: ((RideApiService.Callback<RequestSubmissionResult>) -> Unit)? = null
        var nextSeat: ((RideApiService.Callback<RequestSubmissionResult>) -> Unit)? = null

        override fun createStandaloneRequest(draft: StandaloneRequestDraft, callback: RideApiService.Callback<RequestSubmissionResult>) {
            drafts += draft
            next?.invoke(callback)
        }
        override fun requestSeat(
            rideId: String, seats: Int, note: String?, applyToRecurringGroup: Boolean, clientRequestId: String?,
            journeyIntentId: String?, callback: RideApiService.Callback<RequestSubmissionResult>
        ) {
            seatKeys += clientRequestId
            seatIntents += journeyIntentId
            nextSeat?.invoke(callback)
        }
        override fun searchRides(criteria: RideSearchCriteria, callback: RideApiService.Callback<List<RideSummary>>) {
            callback.onSuccess(searchHits.map(::testRide))
        }
        override fun fetchMyRequests(callback: RideApiService.Callback<List<RideRequestSummary>>) {
            myRequestFetches++
            callback.onSuccess(emptyList())
        }
        override fun signIn(email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
            callback.onSuccess(AuthResult("token-$email", UserAccountSummary(email, email, email, email, null, null, true, null, null)))
        }
    }

    private val api = RecordingApi()
    private val vm = RideshareViewModel(api)
    private val draft = StandaloneRequestDraft(
        originLat = -37.9112, originLng = 145.1029, destinationLat = -37.9105, destinationLng = 145.1362,
        isAsap = false, desiredDepartureTime = 1_900_000_000_000L, seatsRequested = 1, note = null
    )
    private fun transientFailure(callback: RideApiService.Callback<RequestSubmissionResult>) =
        callback.onFailure(BackendException("Can't reach the server. Check your connection and try again."))
    private fun send(d: StandaloneRequestDraft = draft): Pair<Boolean, RequestSubmitError?> {
        var outcome: Pair<Boolean, RequestSubmitError?>? = null
        vm.createStandaloneRequest(d) { ok, error -> outcome = ok to error }
        return outcome!!
    }

    @After fun clearSession() { AuthSession.clear() }

    @Test fun aRetryAfterAFailureReusesTheSameIdempotencyKey() {
        api.next = ::transientFailure
        send(); send(); send()
        val keys = api.drafts.map { it.clientRequestId }
        assertNotNull(keys.first())
        assertEquals(1, keys.toSet().size)
    }

    @Test fun theKeyIsNotPartOfWhatMakesTwoSubmissionsTheSame() {
        api.next = ::transientFailure
        // The form hands over a draft with no key every time; the ViewModel is what adds it.
        assertNull(draft.clientRequestId)
        send(draft); send(draft.copy())
        assertEquals(1, api.drafts.map { it.clientRequestId }.toSet().size)
    }

    @Test fun anEditedSubmissionGetsANewKey() {
        api.next = ::transientFailure
        send(draft); send(draft.copy(seatsRequested = 2))
        assertEquals(2, api.drafts.map { it.clientRequestId }.toSet().size)
    }

    @Test fun afterASuccessTheNextRequestStartsWithAFreshKey() {
        api.next = { it.onSuccess(oneCreated()) }
        assertTrue(send().first)
        assertTrue(send().first)
        assertEquals(2, api.drafts.map { it.clientRequestId }.toSet().size)
    }

    @Test fun aDuplicateAnswerIsNotAGenericFailure() {
        api.next = { it.onFailure(BackendException("x", DUPLICATE_OPEN_REQUEST, "req-77")) }
        val (ok, error) = send()
        assertFalse(ok)
        assertTrue(error!!.isDuplicate)
        assertEquals("req-77", error.existingRequestId)
        assertEquals(DUPLICATE_REQUEST_MESSAGE, error.message)
        assertFalse(vm.actionInFlight)
    }

    @Test fun aDuplicateRefreshesMyRequestsSoTheExistingOneCanBeOpened() {
        api.next = { it.onFailure(BackendException("x", DUPLICATE_OPEN_REQUEST, "req-77")) }
        val before = api.myRequestFetches
        send()
        assertEquals(before + 1, api.myRequestFetches)
    }

    @Test fun anOrdinaryFailureDoesNotRefreshOrClaimDuplicate() {
        api.next = ::transientFailure
        val before = api.myRequestFetches
        val (_, error) = send()
        assertFalse(error!!.isDuplicate)
        assertEquals(before, api.myRequestFetches)
    }

    @Test fun aSuccessRefreshesMyRequests() {
        api.next = { it.onSuccess(oneCreated()) }
        val before = api.myRequestFetches
        val (ok, error) = send()
        assertTrue(ok); assertNull(error)
        assertEquals(before + 1, api.myRequestFetches)
    }

    @Test fun seatRequestsReuseTheirKeyOnRetryAndRenewItAfterSuccess() {
        api.nextSeat = { it.onFailure(BackendException("Can't reach the server.")) }
        vm.requestSeat("ride-1", 1, null, false) { _, _ -> }
        vm.requestSeat("ride-1", 1, null, false) { _, _ -> }
        assertEquals(1, api.seatKeys.toSet().size)

        api.nextSeat = { it.onSuccess(oneCreated()) }
        vm.requestSeat("ride-1", 1, null, false) { _, _ -> }   // the retry that finally works: same key
        vm.requestSeat("ride-1", 1, null, false) { _, _ -> }   // a new request after that: new key
        assertEquals(api.seatKeys[0], api.seatKeys[2])
        assertNotEquals(api.seatKeys[2], api.seatKeys[3])
    }

    @Test fun aDuplicateSeatRequestIsReportedAsADuplicate() {
        api.nextSeat = { it.onFailure(BackendException("x", DUPLICATE_OPEN_REQUEST, "req-5")) }
        var error: RequestSubmitError? = null
        vm.requestSeat("ride-1", 1, null, false) { _, e -> error = e }
        assertTrue(error!!.isDuplicate)
        assertEquals("req-5", error!!.existingRequestId)
    }

    @Test fun signingOutForgetsThePendingKey() {
        vm.signIn("a@student.monash.edu", "password", keepSignedIn = false)
        api.next = ::transientFailure
        send()
        vm.signOut()
        vm.signIn("b@student.monash.edu", "password", keepSignedIn = false)
        send()
        assertEquals(2, api.drafts.map { it.clientRequestId }.toSet().size)
    }

    // ---- journey intent: one id per ride search, shared by every request made from its results ----
    private val search = RideSearchCriteria(-37.9112, 145.1029, -37.9105, 145.1362, 1_900_000_000_000L, seatsNeeded = 1)
    private fun requestFromSearch(rideId: String) = vm.requestSeat(rideId, 1, null, false) { _, _ -> }

    @Test fun requestingSeveralResultsOfOneSearchSendsTheSameIntent() {
        api.searchHits = listOf("ride-1", "ride-2", "ride-3")
        vm.searchRides(search)
        api.nextSeat = { it.onSuccess(oneCreated()) }
        requestFromSearch("ride-1"); requestFromSearch("ride-2"); requestFromSearch("ride-3")
        assertNotNull(api.seatIntents.first())
        assertEquals(1, api.seatIntents.toSet().size)
        // ...while each is its own submission with its own idempotency key.
        assertEquals(3, api.seatKeys.toSet().size)
    }

    @Test fun searchingAgainForTheSameJourneyKeepsTheIntentEvenIfThePassengerCountChanged() {
        api.searchHits = listOf("ride-1")
        api.nextSeat = { it.onSuccess(oneCreated()) }
        vm.searchRides(search); requestFromSearch("ride-1")
        vm.searchRides(search.copy(seatsNeeded = 2)); requestFromSearch("ride-1")
        assertEquals(1, api.seatIntents.toSet().size)
    }

    @Test fun changingTheSearchCriteriaStartsANewIntent() {
        api.searchHits = listOf("ride-1")
        api.nextSeat = { it.onSuccess(oneCreated()) }
        vm.searchRides(search); requestFromSearch("ride-1")
        vm.searchRides(search.copy(departureTime = search.departureTime + 25 * 60_000L)); requestFromSearch("ride-1")
        vm.searchRides(search.copy(destinationLat = -37.8774, destinationLng = 145.0449)); requestFromSearch("ride-1")
        assertEquals(3, api.seatIntents.toSet().size)
        assertTrue(api.seatIntents.none { it == null })
    }

    @Test fun aRideThatIsNotInTheCurrentResultsDeclaresNoIntent() {
        api.searchHits = listOf("ride-1")
        api.nextSeat = { it.onSuccess(oneCreated()) }
        requestFromSearch("ride-1")                       // no search yet at all
        vm.searchRides(search)
        requestFromSearch("ride-9")                       // reached some other way, not one of this search's results
        assertEquals(listOf<String?>(null, null), api.seatIntents)
    }

    @Test fun aRetryKeepsBothTheIntentAndTheIdempotencyKey() {
        api.searchHits = listOf("ride-1")
        vm.searchRides(search)
        api.nextSeat = { it.onFailure(BackendException("Can't reach the server.")) }
        requestFromSearch("ride-1"); requestFromSearch("ride-1")
        api.nextSeat = { it.onSuccess(oneCreated()) }
        requestFromSearch("ride-1")                       // the retry that finally works
        assertEquals(1, api.seatKeys.toSet().size)
        assertEquals(1, api.seatIntents.toSet().size)
        assertNotNull(api.seatIntents.first())
    }

    @Test fun theSameRideAskedForUnderANewSearchIsANewSubmission() {
        api.searchHits = listOf("ride-1")
        vm.searchRides(search)
        api.nextSeat = { it.onFailure(BackendException("Can't reach the server.")) }
        requestFromSearch("ride-1")
        vm.searchRides(search.copy(departureTime = search.departureTime + 60_000L))
        requestFromSearch("ride-1")
        assertEquals(2, api.seatKeys.toSet().size)
        assertEquals(2, api.seatIntents.toSet().size)
    }

    @Test fun signingOutForgetsTheSearchIntent() {
        vm.signIn("a@student.monash.edu", "password", keepSignedIn = false)
        api.searchHits = listOf("ride-1")
        api.nextSeat = { it.onSuccess(oneCreated()) }
        vm.searchRides(search); requestFromSearch("ride-1")
        vm.signOut()
        vm.signIn("b@student.monash.edu", "password", keepSignedIn = false)
        vm.searchRides(search); requestFromSearch("ride-1")
        assertEquals(2, api.seatIntents.toSet().size)
    }

    // A one-off request that went through: one date, one made.
    private fun oneCreated() = RequestSubmissionResult(
        1, 1, 0, listOf(summary()), listOf(OccurrenceOutcome(1000L, true, null, "req-1", null, null, null, null))
    )

    private fun summary() = RideRequestSummary(
        "req-1", null, "p", 1, "PENDING", null, 0L, null, null, null, null, null, false, null, emptyList(), null, null, null, null
    )
}

private fun testRide(id: String) = RideSummary(
    id, "driver", null, -37.9112, 145.1029, -37.9105, 145.1362, 4000.0, 600L, 1_900_000_000_000L,
    3, 3, null, "OPEN", 0L, false, null, emptyList(), null, null, null
)

// The id of the intended journey itself.
class JourneyIntentsTest {
    private var counter = 0
    private val intents = JourneyIntents { "intent-${++counter}" }
    private val search = RideSearchCriteria(-37.9112, 145.1029, -37.9105, 145.1362, 1_900_000_000_000L, seatsNeeded = 1)

    @Test fun noSearchMeansNoIntent() = assertNull(intents.current())

    @Test fun aSearchEstablishesAnIntentAndTheSameJourneyKeepsIt() {
        val first = intents.forSearch(search)
        assertEquals(first, intents.current())
        assertEquals(first, intents.forSearch(search.copy()))
        assertEquals(first, intents.forSearch(search.copy(seatsNeeded = 3)))
        assertEquals(1, counter)
    }

    @Test fun eachPartOfTheJourneyChangesIt() {
        val base = intents.forSearch(search)
        val results = setOf(
            intents.forSearch(search.copy(originLat = -37.9)),
            intents.forSearch(search.copy(originLng = 145.2)),
            intents.forSearch(search.copy(destinationLat = -37.8)),
            intents.forSearch(search.copy(destinationLng = 145.0)),
            intents.forSearch(search.copy(departureTime = search.departureTime + 60_000L))
        )
        assertEquals(5, results.size)
        assertFalse(base in results)
    }

    @Test fun goingBackToAnEarlierJourneyIsANewSearchNotARevival() {
        val first = intents.forSearch(search)
        intents.forSearch(search.copy(departureTime = search.departureTime + 60_000L))
        assertNotEquals(first, intents.forSearch(search))
    }

    @Test fun clearForgetsIt() {
        val first = intents.forSearch(search)
        intents.clear()
        assertNull(intents.current())
        assertNotEquals(first, intents.forSearch(search))
    }
}
