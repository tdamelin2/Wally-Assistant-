package dev.tim.assistant.ui

import kotlinx.datetime.LocalDate

/** True on the laptop (typed MM-dd-yyyy dates), false on the phone (picker with the locale's date format). */
expect val isLaptop: Boolean

/** The date in the device locale's medium format, for example "Nov 10, 2026". */
internal expect fun localeMediumDate(date: LocalDate): String
