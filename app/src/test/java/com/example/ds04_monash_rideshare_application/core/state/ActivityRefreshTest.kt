package com.example.ds04_monash_rideshare_application.core.state

import com.example.ds04_monash_rideshare_application.Auth.AuthSession
import com.example.ds04_monash_rideshare_application.Repository.Remote.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ActivityRefreshTest {
    private class DeferredApi : RideApiService by FakeRideApiService() {
        val analytics = mutableListOf<RideApiService.Callback<AnalyticsSummary>>()
        val dashboards = mutableListOf<RideApiService.Callback<DashboardSummary>>()
        override fun fetchAnalytics(callback: RideApiService.Callback<AnalyticsSummary>) { analytics += callback }
        override fun fetchDashboard(callback: RideApiService.Callback<DashboardSummary>) { dashboards += callback }
        // Signs straight in as whichever account the email names.
        override fun signIn(email: String, password: String, callback: RideApiService.Callback<AuthResult>) {
            val user = UserAccountSummary(email, email, email, email, null, null, true, null, null)
            callback.onSuccess(AuthResult("token-$email", user))
        }
    }
    private val accounts = listOf("first@student.monash.edu", "second@student.monash.edu")
    private fun signInAs(index: Int) = vm.signIn(accounts[index], "password", keepSignedIn = false)
    private val api = DeferredApi()
    private val vm = RideshareViewModel(api)
    private val funnel = RequestFunnelCounts(0, 0, 0, 0, 0, expired = 2)
    private fun analytics(n: Int) = AnalyticsSummary(funnel, funnel, n.toDouble(), emptyList(), List(7) { 0 }, null, null)
    private fun dashboard(n: Int) = DashboardSummary(n, n.toDouble(), 0.0, emptyList())
    @After fun clearSession() { AuthSession.clear() }
    private fun start() {
        signInAs(0)
        vm.loadAnalytics(); vm.loadDashboard()
    }
    private fun reply(index: Int, failure: Boolean) {
        if (failure) {
            api.analytics[index].onFailure(Exception("old error"))
            api.dashboards[index].onFailure(Exception("old error"))
        } else {
            api.analytics[index].onSuccess(analytics(index))
            api.dashboards[index].onSuccess(dashboard(index))
        }
    }
    private fun switchAccount(failure: Boolean) {
        start()
        signInAs(1)
        vm.loadAnalytics(); vm.loadDashboard(); reply(1, false)
        reply(0, failure)
        assertEquals(analytics(1), vm.analytics.data)
        assertEquals(dashboard(1), vm.dashboard.data)
        assertNull(vm.analytics.errorMessage); assertNull(vm.dashboard.errorMessage)
    }
    @Test fun previousUserSuccessCannotReplaceCurrentUser() = switchAccount(false)
    @Test fun previousUserFailureCannotReplaceCurrentUser() = switchAccount(true)
    private fun logout(failure: Boolean) {
        start(); vm.signOut(); reply(0, failure)
        assertEquals(Resource<AnalyticsSummary>(), vm.analytics)
        assertEquals(Resource<DashboardSummary>(), vm.dashboard)
    }
    @Test fun logoutInvalidatesSuccess() = logout(false)
    @Test fun logoutInvalidatesFailure() = logout(true)
    private fun outOfOrder(failure: Boolean) {
        start(); vm.loadAnalytics(); vm.loadDashboard(); reply(1, false); reply(0, failure)
        assertEquals(Resource(data = analytics(1)), vm.analytics)
        assertEquals(Resource(data = dashboard(1)), vm.dashboard)
    }
    @Test fun olderRefreshSuccessCannotReplaceNewerResult() = outOfOrder(false)
    @Test fun olderRefreshFailureCannotReplaceNewerResult() = outOfOrder(true)
    @Test fun switchingAwayAndBackStillInvalidatesOriginalRequests() {
        start(); signInAs(1); signInAs(0)
        reply(0, false)
        assertEquals(Resource<AnalyticsSummary>(), vm.analytics)
        assertEquals(Resource<DashboardSummary>(), vm.dashboard)
    }
    @Test fun currentFailuresFinishLoadingAndRetryCanSucceed() {
        start(); reply(0, true)
        assertFalse(vm.analytics.isLoading); assertFalse(vm.dashboard.isLoading)
        assertEquals("old error", vm.analytics.errorMessage)
        assertEquals("old error", vm.dashboard.errorMessage)
        vm.loadAnalytics(); vm.loadDashboard(); reply(1, false)
        assertEquals(analytics(1), vm.analytics.data); assertEquals(dashboard(1), vm.dashboard.data)
    }
}
