package com.example.ds04_monash_rideshare_application.Repository.Remote

import com.google.gson.Gson
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class AnalyticsMappingTest {
    @Suppress("UNCHECKED_CAST")
    private fun mapJson(body: String): AnalyticsSummary {
        val dto = Gson().fromJson(body, AnalyticsResponseDto::class.java)
        val call = Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { proxy, method, args ->
            when (method.name) {
                "enqueue" -> {
                    (args!![0] as Callback<AnalyticsResponseDto>).onResponse(proxy as Call<AnalyticsResponseDto>, Response.success(dto))
                    null
                }
                else -> error("Unexpected call: ${method.name}")
            }
        } as Call<AnalyticsResponseDto>
        val api = Proxy.newProxyInstance(BackendApi::class.java.classLoader, arrayOf(BackendApi::class.java)) { _, method, _ ->
            if (method.name == "getAnalytics") call else error("Unexpected API call: ${method.name}")
        } as BackendApi
        var mappedResult: AnalyticsSummary? = null

        RideApiServiceImpl(api).fetchAnalytics(object : RideApiService.Callback<AnalyticsSummary> {
            override fun onSuccess(result: AnalyticsSummary) {
                mappedResult = result
            }

            override fun onFailure(error: Exception) {
                throw AssertionError(error)
            }
        })

        return requireNotNull(mappedResult)
    }
    @Test fun expiredWireCountsReachBothDomainFunnelsAndTotals() {
        val result = mapJson("""{
          "driverFunnel":{"pending":1,"accepted":2,"rejected":3,"cancelled":4,"completed":5,"expired":6},
          "passengerFunnel":{"pending":0,"accepted":0,"rejected":0,"cancelled":0,"completed":0,"expired":7},
          "hotzones":[],"tripsByDayOfWeek":[0,0,0,0,0,0,0]
        }""")
        assertEquals(6, result.driverFunnel.expired)
        assertEquals(21, result.driverFunnel.total)
        assertEquals(7, result.passengerFunnel.expired)
        assertEquals(7, result.passengerFunnel.total)
    }
    @Test fun olderServerWithoutExpiredRemainsCompatible() {
        val result = mapJson("""{
          "driverFunnel":{"pending":2},"passengerFunnel":{"completed":3},
          "hotzones":[],"tripsByDayOfWeek":[0,0,0,0,0,0,0]
        }""")
        assertEquals(0, result.driverFunnel.expired)
        assertEquals(2, result.driverFunnel.total)
        assertEquals(3, result.passengerFunnel.total)
    }
}
