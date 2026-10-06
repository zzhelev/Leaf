// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

package dev.app.leaf.domain.sorting

/**
 * Case-insensitive comparison where runs of digits compare as numbers, so `3.9.0` < `3.10.0` and
 * `CAPS-1034` < `CAPS-1249`. Strings that differ only in case or leading zeros still get a stable order.
 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0

    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]

        if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
            val endA = digitRunEnd(a, i)
            val endB = digitRunEnd(b, j)
            val result = compareDigitRuns(a, i, endA, b, j, endB)

            if (result != 0) return result

            i = endA
            j = endB
        } else {
            val result = ca.lowercaseChar().compareTo(cb.lowercaseChar())

            if (result != 0) return result

            i++
            j++
        }
    }

    val lengthResult = (a.length - i).compareTo(b.length - j)

    return if (lengthResult != 0) lengthResult else a.compareTo(b)
}

val NaturalOrder: Comparator<String> = Comparator(::naturalCompare)

private fun Char.isAsciiDigit() = this in '0'..'9'

private fun digitRunEnd(s: String, start: Int): Int {
    var end = start

    while (end < s.length && s[end].isAsciiDigit()) end++

    return end
}

/** Compares two digit runs by numeric value without parsing, so runs longer than a Long still work. */
private fun compareDigitRuns(a: String, startA: Int, endA: Int, b: String, startB: Int, endB: Int): Int {
    var i = startA
    var j = startB

    while (i < endA - 1 && a[i] == '0') i++
    while (j < endB - 1 && b[j] == '0') j++

    val lengthResult = (endA - i).compareTo(endB - j)

    if (lengthResult != 0) return lengthResult

    while (i < endA) {
        val result = a[i].compareTo(b[j])

        if (result != 0) return result

        i++
        j++
    }

    return 0
}
