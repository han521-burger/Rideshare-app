package com.example.ds04_monash_rideshare_application.feature.rides

import com.example.ds04_monash_rideshare_application.core.state.DUPLICATE_REQUEST_MESSAGE
import com.example.ds04_monash_rideshare_application.core.state.RequestSubmitError
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestSubmitErrorBannerTest {
    @Test fun aDuplicateIsTitledAsAlreadyRequestedNotAsAFailure() {
        val duplicate = RequestSubmitError(DUPLICATE_REQUEST_MESSAGE, isDuplicate = true, existingRequestId = "req-1")
        assertEquals("Already requested", requestSubmitErrorTitle(duplicate, failureTitle = "Request not sent"))
        assertEquals("Already requested", requestSubmitErrorTitle(duplicate, failureTitle = "Couldn\u2019t send your request"))
    }

    @Test fun anyOtherErrorKeepsTheScreensFailureTitle() {
        val other = RequestSubmitError("Not enough seats left")
        assertEquals("Request not sent", requestSubmitErrorTitle(other, failureTitle = "Request not sent"))
    }

    @Test fun theDuplicateWordingTellsThePassengerTheRequestIsStillLive() {
        assertEquals("You've already sent this request. A driver can still accept it.", DUPLICATE_REQUEST_MESSAGE)
    }
}
