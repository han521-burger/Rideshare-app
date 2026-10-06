package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ds04_monash_rideshare_application.feature.activity.activityColorScheme

// Building blocks for the Request Ride / Offer Ride wizards (Phase 8A design). They extend the form
// language in FormKit.kt (cards, chips, banners, action bar) and only use MaterialTheme tokens.
// StepWizard.kt is deliberately left alone: Request Seat still uses it.

/** The branded scheme shared by Home / Requests / Activity / Profile / Vehicle. */
@Composable
fun RideFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = activityColorScheme(), typography = MaterialTheme.typography, content = content)
}

// ---------------------------------------------------------------------------------------------
// Shell: top bar + progress + scrolling content + pinned actions
// ---------------------------------------------------------------------------------------------

/**
 * Focused wizard shell for pushed flows (no bottom navigation). The caller owns the step list and
 * validation: [canGoNext] / [onNextStep] / [onBackStep] / [onExit] keep exactly the old meaning, and
 * [onNextStep] on the last step is the submit. [hint] explains why Next is disabled.
 */
@Composable
fun RideFlowScaffold(
    title: String,
    stepLabels: List<String>,
    currentStep: Int,
    canGoNext: Boolean,
    hint: String?,
    isSubmitting: Boolean,
    finalLabel: String,
    busyLabel: String,
    onBackStep: () -> Unit,
    onNextStep: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    RideFlowTheme {
        val colors = MaterialTheme.colorScheme
        val isLast = currentStep == stepLabels.lastIndex
        val scroll = rememberScrollState()
        // Each step starts at the top (Next/Back swap the content in place).
        LaunchedEffect(currentStep) { scroll.scrollTo(0) }
        Scaffold(
            modifier = modifier,
            containerColor = colors.background,
            // Insets are explicit: status bar in the header, navigation bar + IME in the action bar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(color = colors.surface) {
                    Column(Modifier.statusBarsPadding()) {
                        RideFlowTopBar(title, onExit)
                        WizardProgress(stepLabels, currentStep)
                        HorizontalDivider(color = colors.outlineVariant)
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
                RideFlowActionBar(
                    primaryLabel = if (isLast) finalLabel else "Next",
                    busyLabel = busyLabel,
                    busy = isSubmitting,
                    enabled = canGoNext,
                    hint = hint,
                    showBack = currentStep > 0,
                    onBack = onBackStep,
                    onPrimary = onNextStep
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RideFlowTopBar(title: String, onExit: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        TextButton(
            onClick = onExit,
            modifier = Modifier.heightIn(min = 48.dp).align(Alignment.CenterVertically).semantics { contentDescription = "Cancel and leave $title" }
        ) {
            Icon(Icons.Rounded.Close, null, Modifier.size(20.dp))
            Text("Cancel", Modifier.padding(start = 4.dp))
        }
        Text(
            title,
            Modifier.padding(horizontal = 8.dp).align(Alignment.CenterVertically).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
        )
    }
}

/** "Route · Step 1 of 4" with a segmented bar. One TalkBack node: "Step 1 of 4: Route". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WizardProgress(stepLabels: List<String>, currentStep: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val total = stepLabels.size
    val name = stepLabels.getOrElse(currentStep) { "" }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = "Step ${currentStep + 1} of $total: $name" },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Step ${currentStep + 1} of $total", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically))
        }
        Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(total) { index ->
                val current = index == currentStep
                Box(
                    Modifier.weight(1f).height(if (current) 8.dp else 6.dp).clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                current -> colors.primary
                                index < currentStep -> colors.primary.copy(alpha = .55f)
                                else -> colors.outlineVariant
                            }
                        )
                )
            }
        }
    }
}

/**
 * Pinned actions: an optional hint (why Next is off), then Back + primary - stacked, primary first,
 * at large font scale. Busy shows a spinner AND text; repeat taps on the primary are ignored while busy.
 * Back is never locked (same as the legacy wizard).
 */
@Composable
fun RideFlowActionBar(
    primaryLabel: String,
    busyLabel: String,
    busy: Boolean,
    enabled: Boolean,
    hint: String?,
    showBack: Boolean,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val stacked = rememberLargeText()
    Surface(modifier.navigationBarsPadding().imePadding(), color = colors.surface) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!enabled && !busy && !hint.isNullOrBlank()) {
                    Row(
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(16.dp).padding(top = 2.dp), tint = colors.onSurfaceVariant)
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                val primary: @Composable (Modifier) -> Unit = { m ->
                    Button(
                        onClick = { if (!busy) onPrimary() },
                        enabled = enabled,
                        modifier = m.heightIn(min = 52.dp).semantics {
                            if (busy) { disabled(); stateDescription = busyLabel }
                        }
                    ) { if (busy) BusyLabel(busyLabel, colors.onPrimary) else Text(primaryLabel) }
                }
                val back: @Composable (Modifier) -> Unit = { m ->
                    // Back stays available while submitting, exactly as the legacy wizard allowed; only the
                    // primary action is locked, which is what prevents a duplicate submission.
                    OutlinedButton(
                        onClick = onBack, modifier = m.heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline)
                    ) { Text("Back") }
                }
                when {
                    !showBack -> primary(Modifier.fillMaxWidth())
                    stacked -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        primary(Modifier.fillMaxWidth()); back(Modifier.fillMaxWidth())
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        back(Modifier.weight(1f)); primary(Modifier.weight(1.7f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Route step pieces
// ---------------------------------------------------------------------------------------------

/** From Monash / To Monash as one radio group. Stacks at large text. */
@Composable
fun DirectionSegment(fromMonash: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val stacked = rememberLargeText()
    val options: @Composable (Modifier) -> Unit = { m ->
        DirectionOption("From Monash", "Leaving the campus", fromMonash, { onChange(true) }, m)
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surfaceVariant).padding(4.dp)
            .selectableGroup().semantics { contentDescription = "Direction" },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (stacked) {
            options(Modifier.fillMaxWidth())
            DirectionOption("To Monash", "Arriving at the campus", !fromMonash, { onChange(false) }, Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                options(Modifier.weight(1f))
                DirectionOption("To Monash", "Arriving at the campus", !fromMonash, { onChange(false) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DirectionOption(label: String, sub: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.heightIn(min = 56.dp).clip(shape)
            .background(if (selected) colors.surface else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (selected) Modifier.border(1.5.dp, colors.primary, shape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = colors.primary)
            Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) colors.onSurface else colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A nearest-approved-point result. Radio row; selected = tint + border + check disc (not colour alone). */
@Composable
fun HubOptionRow(
    name: String,
    roleLine: String,
    distanceLabel: String,
    selected: Boolean,
    closest: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val stacked = rememberLargeText()
    val shape = RoundedCornerShape(16.dp)
    val onColor = if (selected) colors.onPrimaryContainer else colors.onSurface
    val subColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
    val distance: @Composable () -> Unit = {
        Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = if (stacked) Alignment.Start else Alignment.End) {
            Text(distanceLabel, fontWeight = FontWeight.Bold, color = onColor)
            Text("from search", style = MaterialTheme.typography.bodySmall, color = subColor)
        }
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .background(if (selected) colors.primaryContainer else colors.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioDisc(selected)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, fontWeight = FontWeight.Bold, color = onColor)
            Text(roleLine, style = MaterialTheme.typography.bodySmall, color = subColor)
            if (closest) FormStatusPill("Closest", Modifier.padding(top = 4.dp))
            if (stacked) Box(Modifier.padding(top = 4.dp)) { distance() }
        }
        if (!stacked) distance()
    }
}

@Composable
private fun RadioDisc(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(24.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(colors.primary) else Modifier.border(2.dp, colors.outline, CircleShape)),
        contentAlignment = Alignment.Center
    ) { if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = colors.onPrimary) }
}

/** FROM / (VIA ...) / TO with a marker line - the resolved route context. */
@Composable
fun RouteTimeline(origin: String, destination: String, modifier: Modifier = Modifier, via: List<String> = emptyList()) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxHeight().width(20.dp).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(14.dp).clip(CircleShape).border(3.dp, colors.primary, CircleShape))
            Box(Modifier.weight(1f).padding(vertical = 3.dp).width(2.dp).background(colors.outline))
            Icon(Icons.Rounded.Place, null, Modifier.size(20.dp), tint = colors.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TimelineStop("FROM", origin)
            if (via.isNotEmpty()) TimelineStop("VIA", via.joinToString(" → "), emphasised = false)
            TimelineStop("TO", destination)
        }
    }
}

@Composable
private fun TimelineStop(label: String, value: String, emphasised: Boolean = true) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal)
    }
}

/** "Monash Clayton → Melbourne Central · 21.4 km · 27 min" recap shown above the later steps. */
@Composable
fun RouteRecap(origin: String, destination: String, detail: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Rounded.DirectionsCar, null, Modifier.size(20.dp).padding(top = 1.dp), tint = colors.primary)
        Column {
            Text("$origin → $destination", fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// When step pieces
// ---------------------------------------------------------------------------------------------

/** One switch for the whole row (no second nested click target). The thumb carries a check when on. */
@Composable
fun AsapToggleRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape)
            .background(if (checked) colors.primaryContainer else colors.surface)
            .border(if (checked) 1.5.dp else 1.dp, if (checked) colors.primary else colors.outlineVariant, shape)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Rounded.Bolt, null, Modifier.size(24.dp), tint = colors.primary)
        Column(Modifier.weight(1f)) {
            Text("Leave as soon as possible", fontWeight = FontWeight.Bold,
                color = if (checked) colors.onPrimaryContainer else colors.onSurface)
            Text(if (checked) "On — no date or time needed" else "Off — choose a date and time",
                style = MaterialTheme.typography.bodySmall,
                color = if (checked) colors.onPrimaryContainer else colors.onSurfaceVariant)
        }
        Switch(
            checked = checked, onCheckedChange = null,
            thumbContent = if (checked) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null
        )
    }
}

/** Tappable date/time entry surface used as the trigger of the (unchanged) Material pickers. */
@Composable
fun PickerChoice(
    icon: ImageVector,
    label: String,
    value: String?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    errored: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape)
            .border(if (errored) 2.dp else 1.dp, if (errored) colors.error else if (value != null) colors.primary else colors.outline, shape)
            .clickable(role = Role.Button, onClickLabel = "Choose ${label.lowercase()}", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = colors.primary)
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = if (errored) colors.error else colors.onSurfaceVariant)
            Text(value ?: placeholder, fontWeight = if (value != null) FontWeight.Bold else FontWeight.Normal,
                color = if (value != null) colors.onSurface else colors.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Repeat / details pieces
// ---------------------------------------------------------------------------------------------

/** Multi-select pill (days of the week): same look as ChoiceChip but a checkbox. */
@Composable
fun ToggleChip(text: String, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(shape)
            .background(if (selected) colors.primaryContainer else colors.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = colors.onPrimaryContainer)
        Text(text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface)
    }
}

/** [-] value unit [+] with 48dp buttons that disable at the limits; the value is announced politely. */
@Composable
fun CountStepper(
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    unit: String,
    subject: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val buttonColors = IconButtonDefaults.iconButtonColors(
        containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
        disabledContainerColor = colors.surfaceVariant, disabledContentColor = colors.onSurface.copy(alpha = .38f)
    )
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant.copy(alpha = .5f))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { if (value > min) onValueChange(value - 1) }, enabled = value > min,
            colors = buttonColors, modifier = Modifier.size(48.dp)
        ) { Icon(Icons.Rounded.Remove, contentDescription = "Decrease $subject") }
        Column(
            Modifier.weight(1f).clearAndSetSemantics {
                contentDescription = "$value $unit"
                liveRegion = LiveRegionMode.Polite
            },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        IconButton(
            onClick = { if (value < max) onValueChange(value + 1) }, enabled = value < max,
            colors = buttonColors, modifier = Modifier.size(48.dp)
        ) { Icon(Icons.Rounded.Add, contentDescription = "Increase $subject") }
    }
}

/**
 * Decorative seat blocks under a stepper. Request: [count] passenger blocks. Offer ([capacity] set):
 * the driver block, [count] offered seats and the unused rest. Hidden from TalkBack (the stepper speaks).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeatBlocks(count: Int, capacity: Int? = null, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (capacity != null) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(colors.primary).border(1.5.dp, colors.primary, RoundedCornerShape(7.dp)))
            }
            repeat(count) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(colors.primaryContainer).border(1.5.dp, colors.primary, RoundedCornerShape(7.dp)))
            }
            if (capacity != null) repeat((capacity - count).coerceAtLeast(0)) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).border(1.5.dp, colors.outline, RoundedCornerShape(7.dp)).alpha(.8f))
            }
        }
        if (capacity != null) {
            Text("Dark = you (driver) · tinted = offered · outlined = not offered", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Multiline optional note. [label] is the field label; [subject] names it for TalkBack. */
@Composable
fun OptionalNoteField(value: String, onValueChange: (String) -> Unit, label: String, subject: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "$subject, optional" },
        label = { Text(label) },
        minLines = 3, maxLines = 6,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    )
}

// ---------------------------------------------------------------------------------------------
// Review pieces
// ---------------------------------------------------------------------------------------------

/** Small-caps label + content, e.g. ROUTE / WHEN / SEATS / NOTE. The label is a TalkBack heading. */
@Composable
fun ReviewSection(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
fun ReviewDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
