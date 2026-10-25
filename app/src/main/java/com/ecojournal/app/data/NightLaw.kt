package com.ecojournal.app.data

// Night law (Law 15) + urgent-rail eligibility, evaluated IN THE
// APP at first sync from shipped fields only (spec section 5).
// The app never re-derives scores, never trusts the publisher to
// have enforced the law, and never recomputes a stored decision:
// a decision is written once, at first delivery, and only
// retraction can remove the item afterwards.
//
// Decision table (spec 5.2, evaluation order matters):
//   retracted            -> REMOVED (handled by tombstones, not here)
//   FLASH                -> INSTANT (exempt, day or night, any score)
//   night + WATCH + L3/L4 -> INSTANT (exempt)
//   night + score >= 85  -> INSTANT
//   night + score 70-84  -> HELD to the 08:00 Morning Bundle
//   night + score < 70 or NULL -> FEED_ONLY
//   day + score >= 70    -> INSTANT
//   day + score < 70 or NULL   -> FEED_ONLY
// Night = 00:00-08:00 Asia/Tehran from published_at_epoch.
// Exactly 08:00:00 Tehran is DAY; exactly 00:00:00 is NIGHT.

enum class NightLawResult { INSTANT, HELD, FEED_ONLY }

object NightLaw {
    const val DAY_INSTANT_THRESHOLD = 70
    const val NIGHT_INSTANT_THRESHOLD = 85
    const val NIGHT_HELD_LOW = 70
    const val NIGHT_HELD_HIGH = 84
    const val PIN_MAX_AGE_MS = 6L * 60L * 60L * 1000L

    fun evaluate(
        formatType: String,
        urgencyScore: Int?,
        crisisLevel: String?,
        publishedAtEpoch: Long,
    ): NightLawResult {
        val night = Jalali.isNightTehran(publishedAtEpoch)
        if (formatType == "FLASH") return NightLawResult.INSTANT
        if (night && formatType == "WATCH" &&
            (crisisLevel == "L3" || crisisLevel == "L4")
        ) {
            return NightLawResult.INSTANT
        }
        if (night) {
            val score = urgencyScore ?: return NightLawResult.FEED_ONLY
            return when {
                score >= NIGHT_INSTANT_THRESHOLD -> NightLawResult.INSTANT
                score in NIGHT_HELD_LOW..NIGHT_HELD_HIGH -> NightLawResult.HELD
                else -> NightLawResult.FEED_ONLY
            }
        }
        val score = urgencyScore ?: return NightLawResult.FEED_ONLY
        return if (score >= DAY_INSTANT_THRESHOLD) {
            NightLawResult.INSTANT
        } else {
            NightLawResult.FEED_ONLY
        }
    }

    /** Held items release at 08:00 Tehran on their own Shamsi day. */
    fun heldUntilEpoch(publishedAtEpoch: Long): Long =
        Jalali.eightAmTehranEpoch(publishedAtEpoch)

    /** Pin age is wall-clock age from published_at_epoch (spec 5.3). */
    fun isPinExpired(publishedAtEpoch: Long, now: Long): Boolean =
        now - publishedAtEpoch > PIN_MAX_AGE_MS
}
