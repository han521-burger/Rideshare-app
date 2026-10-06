package com.example.ds04_monash_rideshare_application.feature.rides

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.Repository.Remote.CreateRideDraft
import com.example.ds04_monash_rideshare_application.Repository.Remote.RideSummary
import com.example.ds04_monash_rideshare_application.Repository.Remote.StandaloneRequestDraft
import com.example.ds04_monash_rideshare_application.core.state.Resource
import com.example.ds04_monash_rideshare_application.core.ui.AsapToggleRow
import com.example.ds04_monash_rideshare_application.core.ui.CountStepper
import com.example.ds04_monash_rideshare_application.core.ui.DirectionSegment
import com.example.ds04_monash_rideshare_application.core.ui.HubOptionRow
import com.example.ds04_monash_rideshare_application.core.ui.RideFlowActionBar
import com.example.ds04_monash_rideshare_application.core.ui.RideFlowScaffold
import com.example.ds04_monash_rideshare_application.core.ui.RoutePickerResult
import com.example.ds04_monash_rideshare_application.core.ui.StopPicker
import com.example.ds04_monash_rideshare_application.core.ui.ToggleChip
import com.example.ds04_monash_rideshare_application.feature.activity.activityColorScheme
import com.example.ds04_monash_rideshare_application.ui.NamedLocation
import com.yandex.mapkit.geometry.Point
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

// Presentation + semantics of the Phase 8A Request / Offer wizards. The route lookup (geocoder +
// driving route) is network-bound and covered by lower-level tests, so flow tests substitute a fake
// Route step through the screens' `routeStep` seam; everything else is the real screen.
class RideFlowComponentsTest {
    @get:Rule val compose = createComposeRule()

    private val originalLocale = Locale.getDefault()
    @Before fun english() = Locale.setDefault(Locale.ENGLISH)
    @After fun restore() = Locale.setDefault(originalLocale)

    private val clayton = NamedLocation("Monash Clayton", Point(-37.9105, 145.1362), isCampus = true)
    private val central = NamedLocation("Melbourne Central", Point(-37.8102, 144.9628))
    private val route = RoutePickerResult(clayton, central, 21_400.0, 1_650)

    private fun role(r: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, r)
    private fun enabledIsFalse() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled)

    private fun body(dark: Boolean = false, block: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        MaterialTheme(colorScheme = activityColorScheme(dark)) {
            Column(Modifier.verticalScroll(rememberScrollState())) { block() }
        }
    }

    private fun fakeRouteStep(): @androidx.compose.runtime.Composable (onResult: (RoutePickerResult?) -> Unit) -> Unit =
        { onResult ->
            LaunchedEffect(Unit) { onResult(route) }
            Text("Route picker (fake)")
        }

    // ============================== shell ==============================

    @Test fun progressIsOneNodeNamingStepAndTotal() {
        compose.setContent {
            RideFlowScaffold("Request a ride", requestStepLabels(true), 1, true, null, false, "Confirm request", "Sending request…", {}, {}, {}) {}
        }
        compose.onNodeWithContentDescription("Step 2 of 4: When").assertExists()
    }

    @Test fun scheduledProgressReportsFiveSteps() {
        compose.setContent {
            RideFlowScaffold("Request a ride", requestStepLabels(false), 2, true, null, false, "Confirm request", "Sending…", {}, {}, {}) {}
        }
        compose.onNodeWithContentDescription("Step 3 of 5: Repeat").assertExists()
    }

    @Test fun firstStepHasNoBackAndExplainsWhyNextIsOff() {
        compose.setContent {
            RideFlowScaffold("Request a ride", requestStepLabels(true), 0, false, "Choose a campus and an approved point to continue.",
                false, "Confirm request", "Sending…", {}, {}, {}) {}
        }
        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithText("Choose a campus and an approved point to continue.").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test fun laterStepsShowBackAndNextWiredToTheCallbacks() {
        var back = 0; var next = 0
        compose.setContent {
            RideFlowScaffold("Offer a ride", OFFER_STEP_LABELS, 2, true, null, false, "Publish ride", "Publishing ride…",
                { back++ }, { next++ }, {}) {}
        }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Next").performClick()
        assertEquals(1, back); assertEquals(1, next)
    }

    @Test fun lastStepShowsTheFinalLabelAndSubmitsOnce() {
        var next = 0
        compose.setContent {
            RideFlowScaffold("Offer a ride", OFFER_STEP_LABELS, 4, true, null, false, "Publish ride", "Publishing ride…", {}, { next++ }, {}) {}
        }
        compose.onNodeWithText("Next").assertDoesNotExist()
        compose.onNodeWithText("Publish ride").performClick()
        assertEquals(1, next)
    }

    @Test fun busyShowsTextAndIgnoresTapsButBackStaysAvailable() {
        var next = 0; var back = 0
        compose.setContent {
            RideFlowScaffold("Request a ride", requestStepLabels(true), 3, true, null, true, "Confirm request", "Sending request…", { back++ }, { next++ }, {}) {}
        }
        compose.onNodeWithText("Sending request…").assertExists()
        compose.onNodeWithText("Sending request…").performClick()
        assertEquals(0, next)
        // Source left Back enabled while submitting; only the primary action is locked.
        compose.onNodeWithText("Back").assertIsEnabled().performClick()
        assertEquals(1, back)
    }

    @Test fun cancelLeavesTheFlow() {
        var exited = 0
        compose.setContent {
            RideFlowScaffold("Request a ride", requestStepLabels(true), 0, false, null, false, "Confirm request", "Sending…", {}, {}, { exited++ }) {}
        }
        compose.onNodeWithText("Cancel").assert(role(Role.Button)).performClick()
        assertEquals(1, exited)
    }

    @Test fun titleIsAHeading() {
        compose.setContent {
            RideFlowScaffold("Offer a ride", OFFER_STEP_LABELS, 0, false, null, false, "Publish ride", "Publishing…", {}, {}, {}) {}
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("Offer a ride")).assertExists()
    }

    @Test fun actionButtonsStackWithThePrimaryFirstAtLargeText() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                RideFlowActionBar("Confirm request", "Sending…", false, true, null, true, {}, {})
            }
        }
        val primary = compose.onNodeWithText("Confirm request").getUnclippedBoundsInRoot()
        val back = compose.onNodeWithText("Back").getUnclippedBoundsInRoot()
        assertTrue("Back should sit below the primary action", back.top >= primary.bottom)
    }

    @Test fun actionButtonsShareARowAtNormalText() {
        compose.setContent { RideFlowActionBar("Confirm request", "Sending…", false, true, null, true, {}, {}) }
        val primary = compose.onNodeWithText("Confirm request").getUnclippedBoundsInRoot()
        val back = compose.onNodeWithText("Back").getUnclippedBoundsInRoot()
        assertTrue("Back and primary share a row", back.top < primary.bottom && back.right <= primary.left)
    }

    // ============================== route pieces ==============================

    @Test fun directionIsOneRadioGroupWithExposedSelection() {
        var from by mutableStateOf(true)
        body { DirectionSegment(from, { from = it }) }
        compose.onNodeWithText("From Monash").assert(role(Role.RadioButton)).assertIsSelected()
        compose.onNodeWithText("To Monash").assert(role(Role.RadioButton)).assertIsNotSelected().performClick()
        compose.onNodeWithText("To Monash").assertIsSelected()
        compose.onNodeWithText("From Monash").assertIsNotSelected()
        assertEquals(false, from)
    }

    @Test fun hubRowExposesSelectionNameAndDistance() {
        var picked by mutableStateOf(false)
        body {
            HubOptionRow("Waverley Gardens Shopping Centre", "Approved pickup point", "~4.6 km", picked, false, { picked = true })
        }
        compose.onNodeWithText("Waverley Gardens Shopping Centre").assert(role(Role.RadioButton)).assertIsNotSelected().performClick()
        compose.onNodeWithText("Waverley Gardens Shopping Centre").assertIsSelected()
        compose.onNodeWithText("~4.6 km").assertExists()
    }

    @Test fun hubDistanceMovesBelowTheNameAtLargeText() {
        compose.setContent {
            val d = LocalDensity.current
            MaterialTheme(colorScheme = activityColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                    HubOptionRow("Monash Melbourne City – 750 Collins St", "Monash campus", "~1.6 km", false, false, {})
                }
            }
        }
        val name = compose.onNodeWithText("Monash Melbourne City – 750 Collins St", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val distance = compose.onNodeWithText("~1.6 km", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("distance sits under the name", distance.top >= name.bottom)
    }

    @Test fun darkSchemeKeepsSelectionNonColourOnly() {
        body(dark = true) {
            HubOptionRow("Melbourne Central", "Approved drop-off point", "~0.6 km", true, true, {})
            DirectionSegment(true, {})
        }
        compose.onNodeWithText("Melbourne Central").assertIsSelected()
        compose.onNodeWithText("From Monash").assertIsSelected()
        compose.onNodeWithText("Closest").assertExists()
    }

    // ============================== when / repeat ==============================

    @Test fun asapIsOneSwitchWithOnOffState() {
        var on by mutableStateOf(true)
        body { AsapToggleRow(on, { on = it }) }
        compose.onNodeWithText("Leave as soon as possible").assert(role(Role.Switch)).assertIsOn()
        compose.onNodeWithText("Leave as soon as possible").performClick()
        compose.onNodeWithText("Off — choose a date and time").assertExists()
        assertEquals(false, on)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertCountEquals(1)
    }

    @Test fun requestWhenDisclosesDateAndTimeOnlyWhenAsapIsOff() {
        var asap by mutableStateOf(true)
        body { RequestWhenStep(asap, null, null, { asap = it }, {}, {}) }
        compose.onNodeWithText("ASAP requests don’t need a date or time", substring = true).assertExists()
        compose.onNodeWithText("Choose a date").assertDoesNotExist()
        compose.onNodeWithText("Leave as soon as possible").performClick()
        compose.onNodeWithText("Choose a date").assert(role(Role.Button)).assertExists()
        compose.onNodeWithText("Choose a time").assert(role(Role.Button)).assertExists()
        compose.onNodeWithText("Scheduled requests have 5 steps", substring = true).assertExists()
    }

    @Test fun tooSoonMessageIsAttachedToTheTimeField() {
        body { RequestWhenStep(false, LocalDate.now(), LocalTime.now(), {}, {}, {}) }
        compose.onNode(hasText("Pick a time at least 30 minutes from now", substring = true)).assertExists()
    }

    @Test fun offerWhenHasNoAsapOption() {
        body { OfferWhenStep(null, null, {}, {}) }
        compose.onNodeWithText("Leave as soon as possible").assertDoesNotExist()
        compose.onNodeWithText("Choose a date").assertExists()
        compose.onNodeWithText("Choose a time").assertExists()
    }

    @Test fun repeatRevealsDaysThenWeeksAndPluralises() {
        var weekly by mutableStateOf(false)
        var days by mutableStateOf<Set<DayOfWeek>>(emptySet())
        var weeks by mutableStateOf(4)
        body {
            RideRepeatStep(weekly, days, weeks, { weekly = it; if (!it) days = emptySet() }, { days = it }, { weeks = it }, "Also repeat on", "Pick at least one day.")
        }
        compose.onNodeWithText("One-time only").assert(role(Role.RadioButton)).assertIsSelected()
        compose.onNodeWithText("Mon").assertDoesNotExist()

        compose.onNodeWithText("Repeat weekly").performClick()
        compose.onNodeWithText("Repeat weekly").assertIsSelected()
        compose.onNodeWithText("For how many weeks?").assertDoesNotExist()

        compose.onNodeWithText("Mon").assert(role(Role.Checkbox)).performClick()
        compose.onNodeWithText("Mon").assertIsOn()
        compose.onNodeWithText("For how many weeks?").assertExists()
        compose.onNodeWithText("Mon for 4 weeks").assertExists()

        compose.onNodeWithContentDescription("Increase weeks").performClick()
        compose.onNodeWithContentDescription("5 weeks").assertExists()

        compose.onNodeWithText("Mon").performClick()
        compose.onNodeWithText("For how many weeks?").assertDoesNotExist()
    }

    @Test fun weeksStepperStopsAtTheLegacyLimits() {
        body { RideRepeatStep(true, setOf(DayOfWeek.MONDAY), 12, {}, {}, {}, "Repeat on", "Pick at least one day.") }
        compose.onNodeWithContentDescription("Increase weeks").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Decrease weeks").assertIsEnabled()
    }

    @Test fun toggleChipIsACheckbox() {
        var on by mutableStateOf(false)
        body { ToggleChip("Tue", on, { on = !on }) }
        compose.onNodeWithText("Tue").assert(role(Role.Checkbox)).performClick()
        compose.onNodeWithText("Tue").assertIsOn()
    }

    // ============================== details ==============================

    @Test fun stepperHasNamedButtonsBoundsAndAPoliteValue() {
        var v by mutableStateOf(1)
        body { CountStepper(v, 1, 4, { v = it }, "seats", "seats needed") }
        compose.onNodeWithContentDescription("Decrease seats needed").assert(role(Role.Button)).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Increase seats needed").assert(role(Role.Button)).performClick()
        compose.onNodeWithContentDescription("2 seats")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        repeat(2) { compose.onNodeWithContentDescription("Increase seats needed").performClick() }
        assertEquals(4, v)
        compose.onNodeWithContentDescription("Increase seats needed").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Decrease seats needed").assertIsEnabled()
    }

    @Test fun requestDetailsCapsSeatsAtFourAndKeepsTheNoteOptional() {
        var seats by mutableStateOf(3)
        var note by mutableStateOf("")
        body { RequestDetailsStep(seats, note, { seats = it }, { note = it }) }
        compose.onNodeWithText("You can request 1 to 4 seats.").assertExists()
        compose.onNodeWithContentDescription("Increase seats needed").performClick()
        compose.onNodeWithContentDescription("Increase seats needed").assertIsNotEnabled()
        compose.onNodeWithText("Optional").assertExists()
        compose.onNodeWithContentDescription("Note to driver, optional").performTextInput("One small suitcase")
        assertEquals("One small suitcase", note)
    }

    @Test fun offerSeatsFollowTheVehicleCapacity() {
        var seats by mutableStateOf(3)
        body { OfferSeatsStep(seats, 4, "", { seats = it }, {}) }
        compose.onNodeWithText("Passenger seats available").assertExists()
        compose.onNodeWithText("Your registered vehicle seats 5 including you, so you can offer up to 4 passenger seats.").assertExists()
        compose.onNodeWithContentDescription("Increase passenger seats available").performClick()
        compose.onNodeWithContentDescription("Increase passenger seats available").assertIsNotEnabled()
        assertEquals(4, seats)
    }

    @Test fun aTwoSeaterVehicleOffersExactlyOneSeat() {
        body { OfferSeatsStep(1, 1, "", {}, {}) }
        compose.onNodeWithContentDescription("Decrease passenger seats available").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Increase passenger seats available").assertIsNotEnabled()
        compose.onNodeWithText("Your vehicle seats 2 including you, so you can offer 1 passenger seat.").assertExists()
    }

    // ============================== review ==============================

    @Test fun asapRequestReviewIsStructured() {
        body { RequestReviewStep(route, true, null, null, emptySet(), 4, 1, "") }
        compose.onNodeWithText("ROUTE").assertExists()
        compose.onNodeWithText("Monash Clayton").assertExists()
        compose.onNodeWithText("Melbourne Central").assertExists()
        compose.onNodeWithText("21.4 km").assertExists()
        compose.onNodeWithText("about 27 min").assertExists()
        compose.onNodeWithText("Leaving ASAP").assertExists()
        compose.onNodeWithText("1 seat needed").assertExists()
        compose.onNodeWithText("NOTE").assertDoesNotExist()
        compose.onNodeWithText("No fare is shown", substring = true).assertExists()
    }

    @Test fun scheduledRequestReviewShowsDateRepeatAndNote() {
        body {
            RequestReviewStep(route, false, LocalDate.of(2026, 10, 8), LocalTime.of(8, 30),
                setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), 4, 2, "  Meet at the north entrance  ")
        }
        compose.onNodeWithText("Thu 8 Oct 2026 · 08:30").assertExists()
        compose.onNodeWithText("Also wants: Mon, Wed for 4 weeks").assertExists()
        compose.onNodeWithText("2 seats needed").assertExists()
        compose.onNodeWithText("NOTE").assertExists()
        compose.onNodeWithText("“Meet at the north entrance”").assertExists()
    }

    @Test fun oneTimeScheduledRequestSaysSo() {
        body { RequestReviewStep(route, false, LocalDate.of(2026, 10, 8), LocalTime.of(8, 30), emptySet(), 4, 1, "") }
        compose.onNodeWithText("One-time trip").assertExists()
    }

    @Test fun offerReviewShowsStopsSeatsOfferedAndRepeat() {
        val stop = NamedLocation("Frankston Station", Point(-38.1428, 145.1258))
        body {
            OfferReviewStep(route, LocalDate.of(2026, 10, 8), LocalTime.of(8, 30), listOf(stop),
                setOf(DayOfWeek.SATURDAY, DayOfWeek.THURSDAY), 4, 3, "")
        }
        compose.onNodeWithText("Frankston Station").assertExists()
        compose.onNodeWithText("Repeats on Thu, Sat for 4 weeks").assertExists()
        compose.onNodeWithText("SEATS OFFERED").assertExists()
        compose.onNodeWithText("3 passenger seats offered").assertExists()
        compose.onNodeWithText("NOTE").assertDoesNotExist()
    }

    // ============================== stops ==============================

    @Test fun stopsCanBeAddedNumberedAndRemoved() {
        val places = listOf("Frankston Station", "Footscray Station", "Glen Waverley Station")
            .map { NamedLocation(it, Point(-37.8, 145.0)) }
        var picked by mutableStateOf<List<NamedLocation>>(emptyList())
        body { StopPicker(places, picked, { picked = if (it in picked) picked - it else picked + it }) }
        compose.onNodeWithText("Add a stop").performTextInput("stat")
        compose.onNodeWithText("Frankston Station").assert(role(Role.Checkbox)).performClick()
        assertEquals(listOf("Frankston Station"), picked.map { it.name })
        compose.onNodeWithContentDescription("Remove stop Frankston Station").assert(role(Role.Button)).performClick()
        assertTrue(picked.isEmpty())
    }

    @Test fun noMatchingStopSaysSo() {
        body { StopPicker(listOf(NamedLocation("Frankston Station", Point(-38.1, 145.1))), emptyList(), {}) }
        compose.onNodeWithText("Add a stop").performTextInput("zzz")
        compose.onNodeWithText("No approved place matches \"zzz\"").assertExists()
    }

    // ============================== screens (real route picker) ==============================

    @Test fun requestRouteStepStartsEmptyWithTheRealPicker() {
        compose.setContent { RequestRideFlowScreen(false, { _, _ -> }, {}) }
        compose.onNodeWithContentDescription("Step 1 of 4: Route").assertExists()
        for (campus in listOf("Monash Clayton", "Monash Caulfield", "Monash Parkville", "Monash Peninsula", "Monash Melbourne City – 750 Collins St")) {
            compose.onNodeWithText(campus).assert(role(Role.RadioButton)).assertIsNotSelected()
        }
        compose.onNodeWithText("From Monash").assertIsSelected()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Choose a campus and an approved point to continue.").assertExists()
    }

    @Test fun campusAndDirectionChangeThePresentationLabels() {
        compose.setContent { RequestRideFlowScreen(false, { _, _ -> }, {}) }
        compose.onNodeWithText("Drop-off point").assertExists()
        compose.onNodeWithText("Monash Clayton").performClick()
        compose.onNodeWithText("Monash Clayton").assertIsSelected()
        compose.onNodeWithText("You’ll travel from Monash Clayton to an approved drop-off point.").assertExists()
        compose.onNodeWithText("To Monash").performClick()
        compose.onNodeWithText("Pickup point").performScrollTo().assertExists()
        compose.onNodeWithText("You’ll travel to Monash Clayton from an approved pickup point.").assertExists()
    }

    @Test fun blankSearchShowsAnErrorOnTheFieldThatClearsWhenTyping() {
        compose.setContent { RequestRideFlowScreen(false, { _, _ -> }, {}) }
        compose.onNodeWithTag("route-find").performScrollTo().performClick()
        compose.onNodeWithText("Enter a postcode or suburb.").assertExists()
        compose.onNodeWithTag("route-query").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag("route-query").performTextInput("3000")
        compose.onNodeWithText("Enter a postcode or suburb.").assertDoesNotExist()
    }

    @Test fun searchFieldIsATextKeyboardWithASearchAction() {
        compose.setContent { RequestRideFlowScreen(false, { _, _ -> }, {}) }
        compose.onNodeWithTag("route-query").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))
    }

    @Test fun offerRouteStepIsFiveSteps() {
        compose.setContent { OfferRideFlowScreen(false, 3, { _, _, _ -> }, {}, {}) }
        compose.onNodeWithContentDescription("Step 1 of 5: Route").assertExists()
        compose.onNodeWithText("Offer a ride").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
    }

    // ============================== full Request flow with a fake route ==============================

    @Test fun asapRequestWalksFourStepsAndSubmitsTheSourceDraft() {
        var draft: StandaloneRequestDraft? = null
        compose.setContent {
            RequestRideFlowScreen(false, { d, done -> draft = d; done(null) }, {}, routeStep = fakeRouteStep())
        }
        compose.onNodeWithContentDescription("Step 1 of 4: Route").assertExists()
        compose.onNodeWithText("Next").assertIsEnabled().performClick()

        compose.onNodeWithContentDescription("Step 2 of 4: When").assertExists()
        compose.onNodeWithText("Monash Clayton → Melbourne Central").assertExists()
        compose.onNodeWithText("Leave as soon as possible").assertIsOn()
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithContentDescription("Step 3 of 4: Details").assertExists()
        compose.onNodeWithContentDescription("Increase seats needed").performClick()
        compose.onNodeWithContentDescription("Note to driver, optional").performTextInput("  Bike helmet  ")
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithContentDescription("Step 4 of 4: Review").assertExists()
        compose.onNodeWithText("Leaving ASAP").assertExists()
        compose.onNodeWithText("2 seats needed").assertExists()
        compose.onNodeWithText("Confirm request").performClick()

        val sent = draft
        assertNotNull(sent)
        assertEquals(true, sent!!.isAsap)
        assertNull(sent.desiredDepartureTime)
        assertEquals(2, sent.seatsRequested)
        assertEquals("Bike helmet", sent.note)
        assertTrue(sent.repeatDaysOfWeek.isEmpty())
        assertNull(sent.repeatWeeks)
        assertEquals(-37.9105, sent.originLat, 0.0)
        assertEquals(144.9628, sent.destinationLng, 0.0)
    }

    @Test fun turningAsapOffMakesTheFlowFiveStepsAndBlocksNextUntilATimeIsPicked() {
        compose.setContent { RequestRideFlowScreen(false, { _, _ -> }, {}, routeStep = fakeRouteStep()) }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithContentDescription("Step 2 of 4: When").assertExists()
        compose.onNodeWithText("Leave as soon as possible").performClick()
        compose.onNodeWithContentDescription("Step 2 of 5: When").assertExists()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Choose a date and time to continue.").assertExists()
        compose.onNodeWithText("Leave as soon as possible").performClick()
        compose.onNodeWithContentDescription("Step 2 of 4: When").assertExists()
        compose.onNodeWithText("Next").assertIsEnabled()
    }

    @Test fun submitFailureKeepsTheReviewAndShowsTheServerMessage() {
        compose.setContent {
            RequestRideFlowScreen(false, { _, done -> done("Couldn’t send that request. Please try again.") }, {}, routeStep = fakeRouteStep())
        }
        repeat(3) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithContentDescription("Step 4 of 4: Review").assertExists()
        compose.onNodeWithText("Confirm request").performClick()
        compose.onNodeWithTag("ride-error").assertExists()
        compose.onNodeWithText("Couldn’t send that request. Please try again.").assertExists()
        compose.onNodeWithTag("ride-error")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        compose.onNodeWithText("Confirm request").assertIsEnabled()
    }

    @Test fun reviewShowsBusyTextAndCannotBeResubmitted() {
        var submitting by mutableStateOf(false)
        var sends = 0
        compose.setContent {
            RequestRideFlowScreen(submitting, { _, _ -> sends++ }, {}, routeStep = fakeRouteStep())
        }
        repeat(3) { compose.onNodeWithText("Next").performClick() }
        compose.runOnIdle { submitting = true }
        compose.onNodeWithText("Sending request…").assertExists()
        compose.onNodeWithText("Sending request…").performClick()
        assertEquals(0, sends)
        // Back is not locked (source behaviour); using it just returns to the previous step.
        compose.onNodeWithText("Back").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Step 3 of 4: Details").assertExists()
        assertEquals(0, sends)
    }

    @Test fun backReturnsToThePreviousStep() {
        compose.setContent { RequestRideFlowScreen(false, { _, _ -> }, {}, routeStep = fakeRouteStep()) }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithContentDescription("Step 3 of 4: Details").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithContentDescription("Step 2 of 4: When").assertExists()
    }

    @Test fun cancelOnTheScreenCallsExit() {
        var exited = 0
        compose.setContent { RequestRideFlowScreen(false, { _, _ -> }, { exited++ }) }
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, exited)
    }

    // ============================== Offer flow with a fake route ==============================

    @Test fun offerRouteShowsOptionalStopsOnceARouteExists() {
        compose.setContent { OfferRideFlowScreen(false, 4, { _, _, _ -> }, {}, {}, routeStep = fakeRouteStep()) }
        compose.onNodeWithText("Optional stops").assertExists()
        compose.onNodeWithText("Next").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Step 2 of 5: When").assertExists()
        compose.onNodeWithText("Leave as soon as possible").assertDoesNotExist()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Choose a date and time to continue.").assertExists()
    }

    @Test fun offerWithoutARouteHidesTheStopsCard() {
        compose.setContent { OfferRideFlowScreen(false, 4, { _, _, _ -> }, {}, {}) }
        compose.onNodeWithText("Optional stops").assertDoesNotExist()
        compose.onAllNodesWithText("Optional stops").assertCountEquals(0)
    }

    // ============================== Phase 8C additions ==============================

    private fun pickDateAndTime() {
        // Two days ahead: always beyond the 30-minute rule, whatever the time of day, so the time dialog's
        // initial 08:00 is valid. Uses the real Material dialogs.
        val today = LocalDate.now()
        val target = today.plusDays(2)
        compose.onNodeWithText("Choose a date").performClick()
        if (target.month != today.month) {
            compose.onNode(
                hasContentDescription("next month", substring = true, ignoreCase = true)
            ).performClick()
        }
        // Day cells are labelled with the full date, e.g. "Thursday, October 1, 2026".
        val label = target.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH))
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Choose a time").performClick()
        compose.onNodeWithText("OK").performClick()
    }

    private val summary = RideSummary(
        "r1", "d1", null, -37.9, 145.1, -37.8, 144.9, 21_400.0, 1_650, 0L, 3, 3, null, "OPEN", 0L,
        false, null, emptyList(), null, null, null
    )

    @Test fun imeSearchRunsTheSameFindHandlerAndValidation() {
        compose.setContent { RequestRideFlowScreen(false, { _, _ -> }, {}) }
        compose.onNodeWithTag("route-query").performScrollTo().performImeAction()
        compose.onNodeWithText("Enter a postcode or suburb.").assertExists()
        compose.onNodeWithTag("route-query").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        // Typing clears it, exactly like after Find.
        compose.onNodeWithTag("route-query").performTextInput("3000")
        compose.onNodeWithText("Enter a postcode or suburb.").assertDoesNotExist()
    }

    @Test fun scheduledRequestWalksFiveStepsAndSubmitsDateAndRepeat() {
        var draft: StandaloneRequestDraft? = null
        compose.setContent {
            RequestRideFlowScreen(false, { d, done -> draft = d; done(null) }, {}, routeStep = fakeRouteStep())
        }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Leave as soon as possible").performClick()
        compose.onNodeWithContentDescription("Step 2 of 5: When").assertExists()
        pickDateAndTime()
        compose.onNodeWithText("Next").assertIsEnabled().performClick()

        compose.onNodeWithContentDescription("Step 3 of 5: Repeat").assertExists()
        compose.onNodeWithText("Repeat weekly").performClick()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Pick at least one day to continue.").assertExists()
        compose.onNodeWithText("Fri").performClick()
        compose.onNodeWithText("Next").assertIsEnabled().performClick()

        compose.onNodeWithContentDescription("Step 4 of 5: Details").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithContentDescription("Step 5 of 5: Review").assertExists()
        compose.onNodeWithText("Also wants: Fri for 4 weeks").assertExists()
        compose.onNodeWithText("Confirm request").performClick()

        val sent = draft
        assertNotNull(sent)
        assertEquals(false, sent!!.isAsap)
        assertTrue(sent.desiredDepartureTime!! > System.currentTimeMillis() + 30 * 60_000L)
        assertEquals(listOf(5), sent.repeatDaysOfWeek)
        assertEquals(4, sent.repeatWeeks)
        assertEquals(1, sent.seatsRequested)
        assertNull(sent.note)
    }

    @Test fun switchingBackToAsapAfterPickingATimeDropsTheScheduleFromReviewAndDraft() {
        var draft: StandaloneRequestDraft? = null
        compose.setContent {
            RequestRideFlowScreen(false, { d, done -> draft = d; done(null) }, {}, routeStep = fakeRouteStep())
        }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Leave as soon as possible").performClick()
        pickDateAndTime()
        compose.onNodeWithText("Leave as soon as possible").performClick()
        compose.onNodeWithContentDescription("Step 2 of 4: When").assertExists()
        repeat(2) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithContentDescription("Step 4 of 4: Review").assertExists()
        compose.onNodeWithText("Leaving ASAP").assertExists()
        compose.onNodeWithText("Confirm request").performClick()
        assertEquals(true, draft!!.isAsap)
        assertNull(draft!!.desiredDepartureTime)
        assertTrue(draft!!.repeatDaysOfWeek.isEmpty())
    }

    @Test fun backThenSchedulingNeverStrandsTheStepIndex() {
        compose.setContent { RequestRideFlowScreen(false, { _, _ -> }, {}, routeStep = fakeRouteStep()) }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithContentDescription("Step 3 of 4: Details").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithContentDescription("Step 2 of 4: When").assertExists()
        compose.onNodeWithText("Leave as soon as possible").performClick()
        compose.onNodeWithContentDescription("Step 2 of 5: When").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithContentDescription("Step 1 of 5: Route").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithContentDescription("Step 2 of 5: When").assertExists()
    }

    @Test fun requestSubmitsExactlyOnceEvenIfTappedTwice() {
        var sends = 0
        var busy by mutableStateOf(false)
        compose.setContent {
            RequestRideFlowScreen(busy, { _, _ -> sends++; busy = true }, {}, routeStep = fakeRouteStep())
        }
        repeat(3) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("Confirm request").performClick()
        compose.onNodeWithText("Sending request…").performClick()
        assertEquals(1, sends)
    }

    @Test fun offerWalksFiveStepsPublishesTheSourceDraftAndFollowsTheSuccessCallback() {
        var draft: CreateRideDraft? = null
        var published: RideSummary? = null
        compose.setContent {
            OfferRideFlowScreen(false, 4, { d, ok, _ -> draft = d; ok(summary) }, {}, { published = it }, routeStep = fakeRouteStep())
        }
        compose.onNodeWithContentDescription("Step 1 of 5: Route").assertExists()
        // One stop on the Route step.
        compose.onNodeWithText("Add a stop").performScrollTo().performTextInput("Frankston")
        compose.onNodeWithText("Frankston Station").performClick()
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithContentDescription("Step 2 of 5: When").assertExists()
        // With a stop, the recap must not present distance/time as a total.
        compose.onNodeWithText("Direct route: 21.4 km · 27 min (stops not included)").assertExists()
        pickDateAndTime()
        compose.onNodeWithText("Next").assertIsEnabled().performClick()

        compose.onNodeWithContentDescription("Step 3 of 5: Repeat").assertExists()
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithContentDescription("Step 4 of 5: Details").assertExists()
        compose.onNodeWithContentDescription("Decrease passenger seats available").performClick()
        compose.onNodeWithContentDescription("Note for passengers, optional").performTextInput("Meet at the entrance")
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithContentDescription("Step 5 of 5: Review").assertExists()
        compose.onNodeWithText("2 passenger seats offered").assertExists()
        compose.onNodeWithText("Frankston Station").assertExists()
        compose.onNodeWithText("Distance and time are for the direct route and don’t include your stops.").assertExists()
        compose.onNodeWithText("One-time trip").assertExists()
        compose.onNodeWithText("Publish ride").performClick()

        val sent = draft
        assertNotNull(sent)
        assertEquals(2, sent!!.seatsTotal)
        assertEquals("Meet at the entrance", sent.notes)
        assertEquals(listOf("Frankston Station"), sent.stops.map { it.label })
        assertTrue(sent.repeatDaysOfWeek.isEmpty())
        assertEquals(4, sent.repeatWeeks)
        assertEquals(21_400.0, sent.distanceMeters, 0.0)
        assertEquals(1_650L, sent.estimatedDurationSeconds)
        assertTrue(sent.departureTime > System.currentTimeMillis() + 30 * 60_000L)
        assertEquals(summary, published)
    }

    @Test fun offerPublishFailureStaysOnReviewWithTheServerMessage() {
        compose.setContent {
            OfferRideFlowScreen(
                false, 4, { _, _, err -> err("Offer between 1 and 4 passenger seats; the driver needs a seat too") }, {}, {},
                routeStep = fakeRouteStep()
            )
        }
        compose.onNodeWithText("Next").performClick()
        pickDateAndTime()
        repeat(3) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithContentDescription("Step 5 of 5: Review").assertExists()
        compose.onNodeWithText("Publish ride").performClick()
        compose.onNodeWithTag("ride-error").assertExists()
        compose.onNodeWithText("Ride not published").assertExists()
        compose.onNodeWithText("Offer between 1 and 4 passenger seats; the driver needs a seat too").assertExists()
        compose.onNodeWithContentDescription("Step 5 of 5: Review").assertExists()
    }

    @Test fun offerPublishesExactlyOnceEvenIfTappedTwice() {
        var publishes = 0
        var busy by mutableStateOf(false)
        compose.setContent {
            OfferRideFlowScreen(busy, 4, { _, _, _ -> publishes++; busy = true }, {}, {}, routeStep = fakeRouteStep())
        }
        compose.onNodeWithText("Next").performClick()
        pickDateAndTime()
        repeat(3) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("Publish ride").performClick()
        compose.onNodeWithText("Publishing ride…").performClick()
        assertEquals(1, publishes)
        compose.onNodeWithText("Back").assertIsEnabled()
    }

    @Test fun offerReviewWithoutStopsHasNoDirectRouteCaveat() {
        body { OfferReviewStep(route, LocalDate.of(2026, 10, 8), LocalTime.of(8, 30), emptyList(), emptySet(), 4, 3, "") }
        compose.onNodeWithText("Distance and time are for the direct route and don’t include your stops.").assertDoesNotExist()
        compose.onNodeWithText("21.4 km").assertExists()
    }

    @Test fun legacyRequestSeatKeepsItsOwnWizardShell() {
        compose.setContent {
            MaterialTheme { RequestSeatFlowScreen(Resource(data = summary), false, { _, _, _, _ -> }, {}) }
        }
        compose.onNodeWithText("Step 1 of 4: Ride").assertExists()
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithText("Next").assertIsEnabled()
        // None of the new shell's semantics leak into the legacy flow.
        compose.onNodeWithContentDescription("Step 1 of 4: Ride").assertDoesNotExist()
        repeat(3) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("Step 4 of 4: Confirm").assertExists()
        compose.onNodeWithText("Confirm").assertExists()
    }

    @Test fun darkSchemeKeepsLaterStepsReadable() {
        body(dark = true) {
            RideRepeatStep(true, setOf(DayOfWeek.MONDAY), 4, {}, {}, {}, "Also repeat on", "Pick at least one day.")
            OfferSeatsStep(3, 4, "", {}, {})
        }
        compose.onNodeWithText("Mon").assertIsOn()
        compose.onNodeWithText("Repeat weekly").assertIsSelected()
        compose.onNodeWithContentDescription("3 seats").assertExists()
    }
}
