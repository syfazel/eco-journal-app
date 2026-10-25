package com.ecojournal.app.util

/**
 * Persian digits and LIKE escaping (M3).
 *
 * Numbers are rendered with Persian digits and the Persian thousands
 * separator (U+066C) so Latin digits never sit inside RTL text
 * (P2-07). Nothing here changes a value, only its display form.
 */
private val persianDigits = charArrayOf(
    '۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'
)

fun String.toPersianDigits(): String = buildString {
    for (c in this@toPersianDigits) {
        append(if (c in '0'..'9') persianDigits[c - '0'] else c)
    }
}

fun formatPersianInt(value: Long): String {
    // Reverse, group in threes, re-reverse: 1234567 -> "1٬234٬567".
    val grouped = value.toString()
        .reversed()
        .chunked(3)
        .joinToString("٬")
        .reversed()
    return grouped.toPersianDigits()
}

fun formatPersianInt(value: Int): String = formatPersianInt(value.toLong())

fun formatPersianDouble(value: Double, decimals: Int = 2): String {
    val raw = "%.${decimals}f".format(value)
    val parts = raw.split(".")
    val intPart = parts[0].toLongOrNull()?.let { formatPersianInt(it) }
        ?: parts[0].toPersianDigits()
    return if (parts.size > 1) {
        intPart + "." + parts[1].toPersianDigits()
    } else {
        intPart
    }
}

/**
 * Escape a user query for a LIKE pattern with ESCAPE '\'.
 * Without this, a typed '%' or '_' in a search acts as a wildcard
 * and can match (or hide) unrelated articles (P1-04).
 */
/** Normalises Persian/Arabic-Indic digits to Latin, for date-key comparison. */
fun String.toLatinDigits(): String = buildString {
    for (c in this@toLatinDigits) {
        append(
            when (c) {
                in '۰'..'۹' -> ('0' + (c - '۰'))
                in '٠'..'٩' -> ('0' + (c - '٠'))
                else -> c
            }
        )
    }
}

fun escapeLikeQuery(query: String): String = query
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")
