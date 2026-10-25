package com.ecojournal.app.data

import com.ecojournal.app.util.toPersianDigits
import java.util.Calendar
import java.util.TimeZone

// Jalali (Shamsi) calendar + Asia/Tehran clock, pure Kotlin, no
// dependency. Phase 2 needs Shamsi dates in bylines, Shamsi-day
// grouping, the night-law window (00:00-08:00 Tehran) and the
// 08:00 release instant, all computed in Tehran time regardless of
// the device timezone (spec section 5.1).
//
// Conversion is the standard Jalali algorithm (Borkowski's
// break-point table, as used by jalaali-js): Gregorian <-> Julian
// Day Number <-> Jalali. All arithmetic uses floor division so
// negative intermediates behave, and every result is anchored on
// a Tehran-timezone Calendar, never on the device timezone.

data class JalaliDate(val year: Int, val month: Int, val day: Int)

data class TehranFields(
    val jalali: JalaliDate,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val gregorianYear: Int,
    val gregorianMonth: Int,
    val gregorianDay: Int,
)

object Jalali {
    private val TEHRAN: TimeZone = TimeZone.getTimeZone("Asia/Tehran")

    // Jalali leap-cycle break points (years). Do not edit: these
    // are the algorithm's constants, not app data.
    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181,
        1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
    )

    // Truncating division/remainder, matching the reference
    // algorithm's `~~(a / b)` semantics. Floor division is WRONG
    // here: it shifts Jan/Feb dates by a full year-cycle (found
    // by mirror-testing against known JDN values).
    private fun div(a: Int, b: Int): Int = a / b
    private fun mod(a: Int, b: Int): Int = a % b

    private data class JalCal(val leap: Int, val gy: Int, val march: Int)

    private fun jalCal(jy: Int): JalCal {
        val bl = BREAKS.size
        val gy = jy + 621
        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 0
        var i = 1
        while (i < bl) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += div(jump, 33) * 8 + div(mod(jump, 33), 4)
            jp = jm
            i++
        }
        var n = jy - jp
        leapJ += div(n, 33) * 8 + div(mod(n, 33) + 3, 4)
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ++
        val leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150
        val march = 20 + leapJ - leapG
        if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33
        var leap = mod(mod(n + 1, 33) - 1, 4)
        if (leap == -1) leap = 4
        return JalCal(leap, gy, march)
    }

    private fun g2d(gy: Int, gm: Int, gd: Int): Int {
        var d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) +
            div(153 * mod(gm + 9, 12) + 2, 5) +
            gd - 34840408
        d = d - div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752
        return d
    }

    private fun d2g(jdn: Int): Triple<Int, Int, Int> {
        var j = 4 * jdn + 139361631
        j += div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908
        val i = div(mod(j, 1461), 4) * 5 + 308
        val gd = div(mod(i, 153), 5) + 1
        val gm = mod(div(i, 153), 12) + 1
        val gy = div(j, 1461) - 100100 + div(8 - gm, 6)
        return Triple(gy, gm, gd)
    }

    private fun j2d(jy: Int, jm: Int, jd: Int): Int {
        val r = jalCal(jy)
        return g2d(r.gy, 3, r.march) + (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1
    }

    private fun d2j(jdn: Int): JalaliDate {
        val gy = d2g(jdn).first
        var jy = gy - 621
        val r = jalCal(jy)
        val jdn1f = g2d(gy, 3, r.march)
        var k = jdn - jdn1f
        var jm: Int
        var jd: Int
        if (k >= 0) {
            if (k <= 185) {
                jm = 1 + div(k, 31)
                jd = mod(k, 31) + 1
                return JalaliDate(jy, jm, jd)
            } else {
                k -= 186
            }
        } else {
            jy -= 1
            k += 179
            if (r.leap == 1) k++
        }
        jm = 7 + div(k, 30)
        jd = mod(k, 30) + 1
        return JalaliDate(jy, jm, jd)
    }

    private fun tehranCalendar(epochMs: Long): Calendar {
        val cal = Calendar.getInstance(TEHRAN)
        cal.timeInMillis = epochMs
        return cal
    }

    /** Gregorian + Jalali + clock fields for an epoch, in Tehran time. */
    fun tehranFields(epochMs: Long): TehranFields {
        val cal = tehranCalendar(epochMs)
        val gy = cal.get(Calendar.YEAR)
        val gm = cal.get(Calendar.MONTH) + 1
        val gd = cal.get(Calendar.DAY_OF_MONTH)
        val jdn = g2d(gy, gm, gd)
        return TehranFields(
            jalali = d2j(jdn),
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            second = cal.get(Calendar.SECOND),
            gregorianYear = gy,
            gregorianMonth = gm,
            gregorianDay = gd,
        )
    }

    /** "1405-08-03" style day key (Latin digits, for grouping only). */
    fun shamsiDayKey(epochMs: Long): String {
        val j = tehranFields(epochMs).jalali
        return "%04d-%02d-%02d".format(j.year, j.month, j.day)
    }

    /** Shamsi date + Tehran time for bylines, Persian digits. */
    fun formatShamsiDateTime(epochMs: Long): String {
        val f = tehranFields(epochMs)
        val j = f.jalali
        val date = "%04d/%02d/%02d".format(j.year, j.month, j.day)
        val time = "%02d:%02d".format(f.hour, f.minute)
        return "$date $time".toPersianDigits()
    }

    /** Shamsi date only, Persian digits. */
    fun formatShamsiDate(epochMs: Long): String {
        val j = tehranFields(epochMs).jalali
        return "%04d/%02d/%02d".format(j.year, j.month, j.day).toPersianDigits()
    }

    /** Night = 00:00-08:00 Tehran. Exactly 08:00:00 is DAY (spec 5.1). */
    fun isNightTehran(epochMs: Long): Boolean {
        val f = tehranFields(epochMs)
        return f.hour < 8
    }

    /** Epoch of 08:00 Tehran on the same Tehran day as [epochMs]. */
    fun eightAmTehranEpoch(epochMs: Long): Long {
        val cal = tehranCalendar(epochMs)
        cal.set(Calendar.HOUR_OF_DAY, 8)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Epoch of 00:00 Tehran on the same Tehran day as [epochMs]. */
    fun startOfTehranDayEpoch(epochMs: Long): Long {
        val cal = tehranCalendar(epochMs)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Start/end (exclusive) epochs of the Tehran day containing [now]. */
    fun tehranDayRangeEpoch(now: Long): Pair<Long, Long> {
        val cal = tehranCalendar(startOfTehranDayEpoch(now))
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return startOfTehranDayEpoch(now) to cal.timeInMillis
    }
}
