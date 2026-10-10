package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Small, reusable form building blocks introduced with the Phase 7A Profile / Vehicle design.
// They only use MaterialTheme tokens, so they follow whichever scheme the host screen provides
// (the branded Activity scheme in light and dark). Request Ride / Offer Ride can adopt them later.

/** True when the user has raised Android's font scale enough that rows should stack. */
@Composable
fun rememberLargeText(): Boolean = LocalDensity.current.fontScale >= 1.5f

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() }.align(Alignment.CenterVertically)
                )
                trailing?.invoke()
            }
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** Inline message under a field or group. Errors carry an icon so they never rely on colour alone. */
@Composable
fun FieldMessage(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isError) Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp).padding(top = 1.dp), tint = colors.error)
        Text(
            text, style = MaterialTheme.typography.bodySmall,
            color = if (isError) colors.error else colors.onSurfaceVariant
        )
    }
}

@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    errorText: String? = null,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    supportingText: String? = null
) {
    val message = errorText ?: supportingText
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().semantics { if (errorText != null) error(errorText) },
        enabled = enabled, singleLine = singleLine, isError = errorText != null,
        label = { Text(label) },
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        supportingText = if (message != null) ({ FieldMessage(message, isError = errorText != null) }) else null
    )
}

/** Single-choice group of wrapping, >=48dp pills. Selection is shown by container, border, weight AND a check. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChipGroup(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    groupDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    errorText: String? = null
) {
    Column(modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = groupDescription },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                ChoiceChip(label(option), isSelected, enabled, errored = errorText != null && !isSelected) { onSelect(option) }
            }
        }
        errorText?.let {
            FieldMessage(it, isError = true, modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, enabled: Boolean, errored: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val borderColor = when {
        selected -> colors.primary
        errored -> colors.error
        else -> colors.outlineVariant
    }
    Row(
        Modifier
            .alpha(if (enabled) 1f else .5f)
            .heightIn(min = 48.dp).widthIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) colors.primaryContainer else colors.surface)
            .border(if (selected || errored) 1.5.dp else 1.dp, borderColor, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = colors.onPrimaryContainer)
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface
        )
    }
}

@Composable
fun FormInfoRow(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Rounded.Info, null, Modifier.size(18.dp).padding(top = 1.dp), tint = colors.onSecondaryContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSecondaryContainer)
    }
}

/** [-] value [+] with 48dp round buttons that disable at the limits, plus a derived supporting line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeatStepper(
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    supporting: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val buttonColors = IconButtonDefaults.iconButtonColors(
        containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
        disabledContainerColor = colors.surfaceVariant, disabledContentColor = colors.onSurface.copy(alpha = .38f)
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant.copy(alpha = .5f))
                .border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (value > min) onValueChange(value - 1) }, enabled = enabled && value > min,
                colors = buttonColors, modifier = Modifier.size(48.dp)
            ) { Icon(Icons.Rounded.Remove, contentDescription = "Decrease seats") }
            Column(
                Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = "$value seats"
                    liveRegion = LiveRegionMode.Polite
                },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(value.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("seats", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            IconButton(
                onClick = { if (value < max) onValueChange(value + 1) }, enabled = enabled && value < max,
                colors = buttonColors, modifier = Modifier.size(48.dp)
            ) { Icon(Icons.Rounded.Add, contentDescription = "Increase seats") }
        }
        // Decorative seat map: the first block is the driver, the rest are passengers.
        FlowRow(
            Modifier.clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(value) { index ->
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                        .background(if (index == 0) colors.primary else colors.primaryContainer)
                        .border(1.5.dp, colors.primary, RoundedCornerShape(7.dp))
                )
            }
        }
        FormInfoRow(supporting)
    }
}

// Warning is information about something that is still fine to act on (for example, a booking that may now be hard to make):
// amber, polite to screen readers, and visibly not an error.
enum class FormBannerKind { Error, Success, Warning }

private data class Tone(val container: Color, val content: Color)

// The branded scheme has no success role, so this follows whichever scheme is active (not the system flag).
@Composable
private fun successTone(): Tone =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Tone(Color(0xFF1E4632), Color(0xFFC7F0D5))
    else Tone(Color(0xFFCFEDD9), Color(0xFF0E2E1C))

@Composable
private fun warningTone(): Tone =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Tone(Color(0xFF4A3A12), Color(0xFFFFE9B0))
    else Tone(Color(0xFFFFF0C9), Color(0xFF3B2A00))

/** Compact status pill, e.g. "Ready to offer rides". */
@Composable
fun FormStatusPill(text: String, modifier: Modifier = Modifier, success: Boolean = false, icon: @Composable (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val tone = if (success) successTone() else Tone(colors.secondaryContainer, colors.onSecondaryContainer)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(tone.container).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        icon?.invoke()
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tone.content)
    }
}

@Composable
fun FormBanner(
    message: String,
    kind: FormBannerKind,
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val tone = when (kind) {
        FormBannerKind.Success -> successTone()
        FormBannerKind.Warning -> warningTone()
        FormBannerKind.Error -> Tone(colors.error.copy(alpha = .12f), colors.onSurface)
    }
    val iconTint = if (kind == FormBannerKind.Error) colors.error else tone.content
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(tone.container)
            .border(1.dp, iconTint.copy(alpha = .3f), shape).padding(14.dp)
            .semantics {
                liveRegion = if (kind == FormBannerKind.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top
    ) {
        Icon(
            when (kind) {
                FormBannerKind.Success -> Icons.Rounded.Check
                FormBannerKind.Warning -> Icons.Rounded.Warning
                FormBannerKind.Error -> Icons.Rounded.ErrorOutline
            }, null,
            Modifier.size(20.dp), tint = iconTint
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = tone.content) }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = tone.content)
        }
        trailing?.invoke()
    }
}

/** Busy spinner + verb. Spinner alone has no meaning for TalkBack, so the label is always paired with it. */
@Composable
fun BusyLabel(text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = color)
        Text(text)
    }
}

/**
 * Sticky bottom actions for a form: Cancel + primary in a row, stacking (primary first) at large
 * font scale. Sits above the keyboard and the navigation bar.
 */
@Composable
fun FormActionBar(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    busyLabel: String = primaryLabel
) {
    val colors = MaterialTheme.colorScheme
    val stacked = rememberLargeText()
    Surface(modifier.navigationBarsPadding().imePadding(), color = colors.surface) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            val primary: @Composable (Modifier) -> Unit = { m ->
                Button(
                    onClick = { if (!busy) onPrimary() },
                    modifier = m.heightIn(min = 52.dp).semantics {
                        if (busy) { disabled(); stateDescription = busyLabel }
                    }
                ) { if (busy) BusyLabel(busyLabel, colors.onPrimary) else Text(primaryLabel) }
            }
            val secondary: @Composable (Modifier) -> Unit = { m ->
                OutlinedButton(
                    onClick = onSecondary, enabled = !busy, modifier = m.heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
                    border = BorderStroke(1.dp, colors.outline)
                ) { Text(secondaryLabel) }
            }
            if (stacked) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    primary(Modifier.fillMaxWidth()); secondary(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    secondary(Modifier.weight(1f)); primary(Modifier.weight(1.7f))
                }
            }
        }
    }
}
