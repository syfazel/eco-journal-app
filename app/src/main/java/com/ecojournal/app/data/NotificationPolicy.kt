package com.ecojournal.app.data

/**
 * Phase 4 — Per-service notification preferences, quiet hours, and
 * the Law 15 precedence table (spec §4.7/§4.8). Pure Kotlin: no
 * Android dependency, so the precedence table is mirror-testable
 * in the lab fixture verifier.
 *
 * Check order (binding, spec §4.7): master → per-service toggle →
 * Law 15 eligibility (urgent only) → quiet hours → cooldown.
 * The recorded suppressedReason is the FIRST failing check in
 * that order, so a suppressed notification is diagnosable.
 *
 * Precedence table (spec §4.8, binding):
 * - Day (08:00–24:00), no quiet hours: everything notifies if its
 *   toggle is on.
 * - Day, inside quiet hours: urgent still notifies UNLESS
 *   quiet_mute_urgent is on; non-urgent is suppressed (bundle /
 *   nightly re-presented once as a deferred summary at quiet end).
 * - Night (00:00–08:00), no quiet hours: urgent notifies ONLY if
 *   Law-15-instant (score ≥85, FLASH, or WATCH at L3/L4 — that
 *   eligibility is computed by NightLaw at sync and carried on
 *   the item; this policy never re-grades it); night-held items
 *   (70–84) NEVER notify instantly; non-urgent NEVER notifies
 *   at night (deferred to the 08:00 Morning Bundle path).
 * - Night, inside quiet hours: same Law 15 rule; quiet hours do
 *   NOT upgrade or downgrade Law 15 eligibility — but if
 *   quiet_mute_urgent is on, even Law-15-eligible urgent is held.
 * - 08:00 Morning Bundle moment inside quiet hours (quiet ends
 *   later): the bundle notification defers to quiet-hours end.
 *
 * What quiet hours NEVER do: upgrade a night-held item to
 * instant; delay in-app content (feed, bundle, edition, banner
 * all appear on sync regardless); mute the urgent rail inside
 * the app; override a system-level channel denial.
 *
 * Local notifications only (Law 14): nothing here sends a DM.
 * No notification is ever generated from a payload body; content
 * always comes from the edge-synced Room row.
 */

enum class NotificationType(val key: String) {
    URGENT("urgent"),
    MARKET_ALERTS("market_alerts"),
    BUNDLES("bundles"),
    SITREP("sitrep"),
    NIGHTLY("nightly"),
    SPECIAL_ISSUES("special_issues"),
    RADAR("radar"),
}

enum class SuppressedReason(val key: String) {
    MASTER_OFF("master_off"),
    TOGGLE_OFF("toggle_off"),
    NIGHT_LAW("night_law"),
    QUIET_HOURS("quiet_hours"),
    COOLDOWN("cooldown"),
}

/** Per-service toggles + quiet hours, as stored in DataStore. */
data class NotificationPrefs(
    val masterEnabled: Boolean = true,
    val notifyUrgent: Boolean = true,
    val notifyMarketAlerts: Boolean = true,
    val notifyBundles: Boolean = true,
    val notifySitrep: Boolean = true,
    val notifyNightly: Boolean = true,
    val notifySpecialIssues: Boolean = false,
    val notifyRadar: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    /** Minutes after midnight Tehran time; may wrap midnight. */
    val quietStartMin: Int = 0,
    val quietEndMin: Int = 0,
    val quietMuteUrgent: Boolean = false,
) {
    fun toggleFor(type: NotificationType): Boolean = when (type) {
        NotificationType.URGENT -> notifyUrgent
        NotificationType.MARKET_ALERTS -> notifyMarketAlerts
        NotificationType.BUNDLES -> notifyBundles
        NotificationType.SITREP -> notifySitrep
        NotificationType.NIGHTLY -> notifyNightly
        NotificationType.SPECIAL_ISSUES -> notifySpecialIssues
        NotificationType.RADAR -> notifyRadar
    }
}

data class NotificationDecision(
    val fire: Boolean,
    val suppressedReason: SuppressedReason?,
    /** True when a suppressed bundle/nightly item should join the deferred summary. */
    val deferToSummary: Boolean = false,
)

object NotificationPolicy {
    /** Wrap-aware quiet-hours test (start == end with enabled = whole day). */
    fun isInsideQuietHours(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return true
        return if (startMin < endMin) {
            minuteOfDay in startMin until endMin
        } else {
            minuteOfDay >= startMin || minuteOfDay < endMin
        }
    }

    fun minuteOfDayTehran(epochMs: Long): Int {
        val f = Jalali.tehranFields(epochMs)
        return f.hour * 60 + f.minute
    }

    /**
     * Full decision for one notification candidate.
     *
     * [isLaw15Instant] applies ONLY to URGENT candidates and must
     * already be the NightLaw result carried on the item (instant
     * vs held vs feed-only); passing true for a non-urgent type is
     * ignored. [cooldownOk] applies ONLY to market alerts and is
     * checked last, after quiet hours, per the check order.
     */
    fun decide(
        prefs: NotificationPrefs,
        type: NotificationType,
        nowMs: Long,
        isLaw15Instant: Boolean = false,
        cooldownOk: Boolean = true,
    ): NotificationDecision {
        if (!prefs.masterEnabled) {
            return NotificationDecision(false, SuppressedReason.MASTER_OFF)
        }
        if (!prefs.toggleFor(type)) {
            return NotificationDecision(false, SuppressedReason.TOGGLE_OFF)
        }
        val isNight = Jalali.isNightTehran(nowMs)
        if (type == NotificationType.URGENT) {
            // Law 15: a non-instant urgent-channel candidate never
            // notifies instantly (night-held items go to the bundle).
            if (!isLaw15Instant) {
                return NotificationDecision(false, SuppressedReason.NIGHT_LAW)
            }
            if (prefs.quietHoursEnabled) {
                val minute = minuteOfDayTehran(nowMs)
                if (isInsideQuietHours(minute, prefs.quietStartMin, prefs.quietEndMin)) {
                    if (prefs.quietMuteUrgent) {
                        return NotificationDecision(false, SuppressedReason.QUIET_HOURS)
                    }
                    // Urgent bypasses quiet hours by default.
                }
            }
            return NotificationDecision(true, null)
        }
        // Non-urgent types: night law suppresses ALL non-urgent
        // interruption at night, not just news (spec §4.8).
        if (isNight) {
            return NotificationDecision(
                false, SuppressedReason.NIGHT_LAW, deferToSummary = false,
            )
        }
        if (prefs.quietHoursEnabled) {
            val minute = minuteOfDayTehran(nowMs)
            if (isInsideQuietHours(minute, prefs.quietStartMin, prefs.quietEndMin)) {
                // Bundle/nightly re-present once as a deferred
                // summary; market-alert suppressions are NOT
                // re-presented (the banner still shows the state).
                val defer = type == NotificationType.BUNDLES ||
                    type == NotificationType.NIGHTLY
                return NotificationDecision(
                    false, SuppressedReason.QUIET_HOURS, deferToSummary = defer,
                )
            }
        }
        if (type == NotificationType.MARKET_ALERTS && !cooldownOk) {
            return NotificationDecision(false, SuppressedReason.COOLDOWN)
        }
        return NotificationDecision(true, null)
    }

    /** Morning Bundle notification moment: never before 08:00 Tehran. */
    fun morningBundleDue(nowMs: Long): Boolean = !Jalali.isNightTehran(nowMs)
}
