package com.example.ds04_monash_rideshare_application.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// A tappable field that opens Material3's DatePickerDialog. Note: DatePickerState always returns
// UTC-midnight millis for the picked calendar day regardless of device timezone - read/write it
// via ZoneOffset.UTC only, never treat the millis as a real wall-clock instant.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { showDialog = true }, modifier = modifier.fillMaxWidth()) {
        Text(selectedDate?.let { formatDate(it) } ?: label)
    }

    if (showDialog) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onDateSelected(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerField(
    label: String,
    selectedTime: LocalTime?,
    onTimeSelected: (LocalTime) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    OutlinedButton(onClick = { showDialog = true }, modifier = modifier.fillMaxWidth()) {
        Text(selectedTime?.let { formatTime(it) } ?: label)
    }

    if (showDialog) {
        val state = rememberTimePickerState(
            initialHour = selectedTime?.hour ?: 8,
            initialMinute = selectedTime?.minute ?: 0,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeSelected(LocalTime.of(state.hour, state.minute))
                    showDialog = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = state) }
        )
    }
}

// Combines a calendar day (device-local) with a wall-clock time-of-day (device-local) into an
// epoch-millis instant - the format every ride departureTime field actually needs.
fun combineDateAndTime(date: LocalDate?, time: LocalTime?): Long? {
    if (date == null || time == null) return null
    return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault()))

private fun formatTime(time: LocalTime): String =
    time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
