package dev.tim.assistant.ui

import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.datetime.LocalDate

actual val isLaptop: Boolean = false

internal actual fun localeMediumDate(date: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(java.time.LocalDate.of(date.year, date.month.ordinal + 1, date.day))
