package io.github.uirlisi.mostor.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The app's locale, read so that a language change recomposes (unlike `Locale.getDefault()`). */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * A date format with the fields of [skeleton] (e.g. "dMMM", "EEEd") in [locale]'s own order and
 * punctuation: "Nov 12" in the US, "12 Nov" in Ireland.
 */
fun localizedPattern(locale: Locale, skeleton: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
