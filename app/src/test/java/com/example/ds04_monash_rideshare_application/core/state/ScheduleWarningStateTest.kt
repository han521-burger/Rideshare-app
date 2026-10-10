package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// How the signed-in user's schedule warnings reach the screens: loaded alongside their requests and rides, kept when a refresh
// fails, never carried over to the next account, and never able to be overtaken by an older answer.
class ScheduleWarningStateTest {
    private class DeferredApi : RideApiService by FakeRideApiService() {
        val warnings = mutableListOf<RideApiService.Callback<List<ScheduleWarning>>>()
        override fun fetchScheduleWarnings(callback: RideApiService.Callback<List<ScheduleWarning>>) { warnings += callback }
        override fun signIn(email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
            val user = UserAccountSummary(email, email, email, email, null, null, true, null, null)
            callback.onSuccess(AuthResult("token-$email", user))
        }
    }

    private val api = DeferredApi()
    private val vm = RideshareViewModel(api)
    @After fun clearSession() { AuthSession.clear() }

    private fun warning(id: String, kind: String = WarningSubject.REQUEST, subject: String = "req-1", at: Long = 1_000L) = ScheduleWarning(
        warningId = id, type = "PREVIOUS_RIDE_FINISHED_LATE", subjectKind = kind, subjectId = subject, createdAt = at,
        previousRideId = "ride-a", previousCompletedAt = 1_800_000_000_000L, previousPlannedArrivalTime = 1_799_998_000_000L,
        fromPointName = "Monash Caulfield", toPointName = "Monash Caulfield", repositioningSeconds = 0, transferBufferSeconds = 600,
        earliestFeasibleDeparture = 1_800_000_600_000L, nextDepartureTime = 1_800_000_300_000L
    )

    @Test fun requestsAndDriverRidesBothRefreshTheWarnings() {
        vm.loadMyRequests(); vm.loadMyDriverRides()
        assertEquals(2, api.warnings.size)
    }

    @Test fun aLoadedWarningIsFoundForItsRequestOrRideOnly() {
        vm.loadMyRequests()
        api.warnings.last().onSuccess(listOf(warning("w1"), warning("w2", WarningSubject.RIDE, "ride-7")))
        assertNotNull(vm.warningMessageFor(WarningSubject.REQUEST, "req-1"))
        assertNotNull(vm.warningMessageFor(WarningSubject.RIDE, "ride-7"))
        assertNull(vm.warningMessageFor(WarningSubject.REQUEST, "ride-7"))
        assertNull(vm.warningMessageFor(WarningSubject.REQUEST, "req-2"))
    }

    @Test fun aFailedRefreshKeepsTheWarningsAlreadyShown() {
        vm.loadMyRequests(); api.warnings[0].onSuccess(listOf(warning("w1")))
        vm.loadMyRequests(); api.warnings[1].onFailure(Exception("offline"))
        assertNotNull(vm.warningMessageFor(WarningSubject.REQUEST, "req-1"))
        assertEquals("offline", vm.scheduleWarnings.errorMessage)
    }

    @Test fun anOlderAnswerCannotReplaceANewerOne() {
        vm.loadMyRequests(); vm.loadMyRequests()
        api.warnings[1].onSuccess(listOf(warning("new", subject = "req-new")))
        api.warnings[0].onSuccess(listOf(warning("old", subject = "req-old")))
        assertNotNull(vm.warningMessageFor(WarningSubject.REQUEST, "req-new"))
        assertNull(vm.warningMessageFor(WarningSubject.REQUEST, "req-old"))
    }

    @Test fun aRefreshThatNoLongerHasTheWarningRemovesIt() {
        vm.loadMyRequests(); api.warnings[0].onSuccess(listOf(warning("w1")))
        vm.loadMyRequests(); api.warnings[1].onSuccess(emptyList())
        assertNull(vm.warningMessageFor(WarningSubject.REQUEST, "req-1"))
    }

    @Test fun signingOutOrSwitchingAccountDropsThemAndIgnoresLateAnswers() {
        vm.signIn("first@student.monash.edu", "password", keepSignedIn = false)
        vm.loadMyRequests(); api.warnings.last().onSuccess(listOf(warning("w1")))
        assertNotNull(vm.warningMessageFor(WarningSubject.REQUEST, "req-1"))

        vm.loadMyRequests()
        val inFlight = api.warnings.last()
        vm.signOut()
        assertEquals(Resource<List<ScheduleWarning>>(), vm.scheduleWarnings)
        inFlight.onSuccess(listOf(warning("late")))
        assertFalse(vm.scheduleWarnings.data?.isNotEmpty() == true)
        assertNull(vm.warningMessageFor(WarningSubject.REQUEST, "req-1"))
    }
}
