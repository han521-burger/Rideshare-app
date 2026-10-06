package com.example.ds04_monash_rideshare_application.feature.rides

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class RideFlowPresentationTest {
    private val original = Locale.getDefault()

    @Before fun english() = Locale.setDefault(Locale.ENGLISH)
    @After fun restore() = Locale.setDefault(original)

    // --- step lists: the count the user sees must be the real one -------------------------------

    @Test fun asapRequestHasFourStepsAndNoRepeat() {
        assertEquals(listOf("Route", "When", "Details", "Review"), requestStepLabels(isAsap = true))
    }

    @Test fun scheduledRequestAddsRepeatAsTheThirdStep() {
        val labels = requestStepLabels(isAsap = false)
        assertEquals(listOf("Route", "When", "Repeat", "Details", "Review"), labels)
        assertEquals(5, labels.size)
    }

    @Test fun offerAlwaysHasFiveSteps() {
        assertEquals(listOf("Route", "When", "Repeat", "Details", "Review"), OFFER_STEP_LABELS)
    }

    @Test fun togglingAsapKeepsRouteAndWhenInPlace() {
        // The wizard stays on index 1 ("When") when ASAP is toggled, so both lists must agree there.
        assertEquals(requestStepLabels(true)[1], requestStepLabels(false)[1])
        assertEquals(requestStepLabels(true)[0], requestStepLabels(false)[0])
    }

    // --- direction wording ------------------------------------------------------------------------

    @Test fun fromMonashSearchesForADropOff() {
        assertEquals("drop-off", searchPointNoun(true))
        assertEquals("Drop-off point", searchCardTitle(true))
        assertEquals("Nearest drop-off points", nearestHeading(true))
    }

    @Test fun toMonashSearchesForAPickup() {
        assertEquals("pickup", searchPointNoun(false))
        assertEquals("Pickup point", searchCardTitle(false))
        assertEquals("Nearest pickup points", nearestHeading(false))
    }

    @Test fun directionSummaryNamesTheCampusSide() {
        assertEquals("Choose a campus to start.", directionSummary(null, true))
        assertEquals("You’ll travel from Monash Clayton to an approved drop-off point.", directionSummary("Monash Clayton", true))
        assertEquals("You’ll travel to Monash Clayton from an approved pickup point.", directionSummary("Monash Clayton", false))
    }

    // --- distances keep the legacy "~0.6 km" meaning (straight-line, one decimal) -----------------

    @Test fun hubDistanceUsesOneDecimalAndATilde() {
        assertEquals("~0.6 km", hubDistanceLabel(600.0, Locale.US))
        assertEquals("~1.6 km", hubDistanceLabel(1_550.0, Locale.US))
        assertEquals("~0.0 km", hubDistanceLabel(10.0, Locale.US))
    }

    @Test fun hubDistanceFollowsTheDeviceLocaleLikeTheLegacyLabel() {
        assertEquals("~0,6 km", hubDistanceLabel(600.0, Locale.GERMANY))
    }

    @Test fun recapDistanceIsScopedToTheDirectRouteWhenThereAreStops() {
        assertEquals("21.4 km · 27 min drive", routeRecapDetail(21_400.0, 1_650, 0, Locale.US))
        assertEquals("Direct route: 21.4 km · 27 min (stops not included)", routeRecapDetail(21_400.0, 1_650, 2, Locale.US))
    }

    @Test fun routeSummaryMatchesTheLegacyFormats() {
        assertEquals("21.4 km", routeKmLabel(21_400.0, Locale.US))
        assertEquals("27 min", routeMinutesLabel(1_650))
        // Whole minutes, rounded down - exactly like the legacy "%d min".
        assertEquals("0 min", routeMinutesLabel(59))
    }

    // --- repeat -----------------------------------------------------------------------------------

    @Test fun dayLabelsAreFixedEnglishShortNames() {
        assertEquals("Mon", dayShortLabel(DayOfWeek.MONDAY))
        assertEquals("Sun", dayShortLabel(DayOfWeek.SUNDAY))
        assertEquals(7, WEEK_DAYS.size)
    }

    @Test fun daysAreListedInWeekOrderWhateverTheSelectionOrder() {
        assertEquals("Mon, Wed, Sat", dayListLabel(setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)))
    }

    @Test fun repeatSummaryIsNullWhenNothingRepeats() {
        assertNull(repeatSummary(emptySet(), 4))
    }

    @Test fun repeatSummaryPluralisesWeeks() {
        assertEquals("Mon, Wed for 4 weeks", repeatSummary(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), 4))
        assertEquals("Thu for 1 week", repeatSummary(setOf(DayOfWeek.THURSDAY), 1))
    }

    @Test fun repeatLimitsAreTheLegacyOnes() {
        assertEquals(1, MIN_REPEAT_WEEKS)
        assertEquals(12, MAX_REPEAT_WEEKS)
    }

    // --- seats ------------------------------------------------------------------------------------

    @Test fun requestSeatLimitIsStillFour() = assertEquals(4, MAX_REQUEST_SEATS)

    @Test fun seatLabelsAgreeWithTheCount() {
        assertEquals("1 seat needed", seatsNeededLabel(1))
        assertEquals("2 seats needed", seatsNeededLabel(2))
        assertEquals("1 passenger seat offered", seatsOfferedLabel(1))
        assertEquals("3 passenger seats offered", seatsOfferedLabel(3))
    }

    @Test fun vehicleSupportExplainsTheDriverSeat() {
        assertEquals("Your registered vehicle seats 5 including you, so you can offer up to 4 passenger seats.", vehicleSeatsSupport(4))
        assertEquals("Your vehicle seats 2 including you, so you can offer 1 passenger seat.", vehicleSeatsSupport(1))
    }

    // --- when -------------------------------------------------------------------------------------

    @Test fun departureSummaryUsesThePickerFormats() {
        assertEquals("Thu 8 Oct 2026 · 08:30", departureSummary(LocalDate.of(2026, 10, 8), LocalTime.of(8, 30)))
    }

    @Test fun whenHintExplainsWhyNextIsOff() {
        assertEquals("Choose a date and time to continue.", whenHint(hasDate = false, hasTime = false, tooSoon = false))
        assertEquals("Choose a time to continue.", whenHint(hasDate = true, hasTime = false, tooSoon = false))
        assertEquals("Choose a date to continue.", whenHint(hasDate = false, hasTime = true, tooSoon = false))
        assertEquals("Choose a time at least 30 minutes from now to continue.", whenHint(hasDate = true, hasTime = true, tooSoon = true))
        assertEquals("", whenHint(hasDate = true, hasTime = true, tooSoon = false))
    }
}
