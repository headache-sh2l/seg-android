package com.example.segandroid.segmenter

import java.util.Locale

/** Formats a millisecond value with one decimal place, e.g. "41.8". */
internal fun formatMs(value: Double): String = String.format(Locale.US, "%.1f", value)

/** Formats a byte count as kilobytes with one decimal place, e.g. "19278.7". */
internal fun formatKb(bytes: Double): String = String.format(Locale.US, "%.1f", bytes / 1024.0)
