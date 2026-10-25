package com.ecojournal.app.data

// Phase 6 — Editorial, Voices, Analysis, Special Issues (LAB ONLY).
//
// Spec: docs/big-update/phase6-editorial-voices-spec.md. The freeze
// stays ON. This file is pure domain logic (no Android dependency):
// analysis length bands + structure gate, consensus-matrix gate,
// top-stories card gate, voices gating, special-issues gating.
// NOTHING here publishes a voice or a special issue: the gates
// below are closed by constants (VOICES_FINALISED = false,
// SPECIAL_ISSUES_FROZEN = true) and can only be opened by Yasin's
// own acts (his-hand-steps.md §C for voices, §4.5+§4.6 of the
// spec for special issues) — never by flipping a flag in code
// during a lab pass.
//
// Cross-phase bar rule (new-requirements.md §0, binding on every
// surface Phase 6 adds or restyles): analysis cards carry the
// SAME bar as feed cards — RED for typed/war-room items
// (format_type present = red-alert category, outranking domain
// colour), YELLOW for energy-domain articles — with the distinct
// label, never colour alone. No separate service, no separate
// tab for war-room/red-alert. The bar itself is rendered by the
// existing DomainBarStrip; this file only supplies the rule
// (Phase6Bars) so a new card type cannot invent its own.

/** Method page: the 7 fixed sections, in fixed order (spec §1.2). */
object MethodSections {
    const val VERSION = 1
    // Bundled-copy date, Shamsi (2026-10-25 Tehran = 1405-08-03,
    // verified by the Phase 2 Jalali anchors). A Method text
    // change ships only in a new app build (spec §1.4).
    const val UPDATED_SHAMSI = "۱۴۰۵-۰۸-۰۳"
    val ORDER = listOf("M1", "M2", "M3", "M4", "M5", "M6", "M7")
}

/** Analysis types and their word-count bands (spec §2.1, §2.4). */
object AnalysisTypes {
    const val OP_ED = "op-ed"
    const val WEEKLY = "weekly"
    const val MONTHLY_ML = "monthly-ml"

    /** (min, max) inclusive, or null when the type is not a standing type. */
    fun band(type: String): Pair<Int, Int>? = when (type) {
        OP_ED -> 1000 to 3000
        WEEKLY -> 3000 to 5000
        // monthly-ml is a GATED subtype, not a standing type: until
        // the calibration gate passes (§2.4) it is held whatever
        // its length. Its band (2000-4000, S28) is recorded here
        // for the post-gate generator, never used to admit one now.
        MONTHLY_ML -> 2000 to 4000
        else -> null
    }

    fun badge(type: String): String = when (type) {
        OP_ED -> "یادداشت تحلیلی"
        WEEKLY -> "تحلیل هفتگی"
        MONTHLY_ML -> "گزارش ماهانهٔ مدل"
        else -> type
    }
}

/** The four mandatory analysis structure sections, in fixed order (spec §2.2). */
object AnalysisStructure {
    val ORDER = listOf("thesis", "evidence", "counter_argument", "scenarios")
    val HEADINGS = mapOf(
        "thesis" to "تز",
        "evidence" to "شواهد",
        "counter_argument" to "مخالف‌خوانی",
        "scenarios" to "سناریوها",
    )
}

/** Held reasons for analysis items (generator holds, app never pads). */
object AnalysisHeldReasons {
    const val MISSING_SECTION = "analysis_structure"
    const val OUT_OF_BAND = "analysis_band"
    const val MONTHLY_ML_GATED = "monthly_ml_gated"
    const val THIN = "floor_300"
    const val BAD_SOURCE_URL = "bad_source_url"
    const val VERBATIM_NOT_ALLOWED = "verbatim_not_allowed"
    const val PROBABILITY_PRESENT = "probability_present"
}

/** Consensus matrix display contract (spec §2.3, S41/S40). */
object ConsensusRules {
    const val LABEL = "سیگنال‌ها، نه پیش‌بینی‌ها"
    // A matrix with a single outlet is not consensus: held.
    const val MIN_OUTLETS = 2
}

/** Top-stories read-only card (spec §5, S36). Voting is deferred,
 * never faked: the card carries no vote field at all. */
object TopStoriesRules {
    const val BASIS_POLL = "poll"
    const val BASIS_EDITORIAL = "editorial"
    const val READ_ONLY_NOTE = "رأی‌گیری در اپ فعلاً فعال نیست"

    fun labelFor(basis: String): String? = when (basis) {
        BASIS_POLL -> "برگزیدهٔ نظرسنجی هفته"
        BASIS_EDITORIAL -> "گزیدهٔ تحریریهٔ هفته"
        else -> null
    }

    /**
     * §5.4 entry criterion for a REAL week's card: Yasin or the
     * desk must record, in writing, whether a no-poll week's card
     * is editorially selected or omitted that week. Until that
     * decision is on file, no real card ships — the code may be
     * built and lab-tested, but the decision flag stays false.
     * This is NOT a code toggle to flip in a lab pass.
     */
    const val NO_POLL_DECISION_RECORDED = false
}

/**
 * Voices gating (spec §3, D6). FINALISED is false until Yasin
 * completes §3.2 in writing (edited registry v2+, closed blank
 * slot, confirmed source per retained voice, scope line). The
 * general freeze lift does NOT finalise the list.
 */
object VoicesGate {
    const val FINALISED = false
    // Spec §3.6 placeholder text, verbatim. No names, no counts,
    // no tier labels, no sample card — the placeholder never
    // teases the draft.
    const val PLACEHOLDER =
        "صداهای مهم — فهرست در انتظار نهایی‌شدن توسط یاسین است. تا آن زمان هیچ صفحهٔ صدایی منتشر نمی‌شود."
    // Draft status, from the registry file's own table (spec
    // §3.3): 28 slots, 9 source-confirmed. These numbers live
    // ONLY in lab docs/reports, never in any reader surface.
    const val DRAFT_SLOTS = 28
    const val DRAFT_SOURCE_CONFIRMED = 9
    // 100-item test (spec §3.7): coverage has NO pass threshold
    // (a threshold would incentivise forced tagging); zero
    // attribution errors is the hard fail.
    const val TEST_SAMPLE_SIZE = 100
}

/**
 * `key_voices` field design (spec §3.5). This is a DESIGN ONLY:
 * no row is created from the draft, and no row exists anywhere
 * in the app until §3.2 finalisation + §3.7 test + §3.8 sequence.
 * The class exists so the post-finalisation builder has a fixed
 * shape to fill from the final registry version only.
 */
data class KeyVoiceDesign(
    val personId: String,
    val nameFa: String,
    val nameEn: String,
    val roleAtTimeOfWriting: String,
    val outlet: String,
    val tier: String,
    val primarySourceUrl: String,
    val sourceStatus: String,
    val format: String,
    val criteriaVersion: String,
    val finalisedDateShamsi: String,
    val notes: String?,
)

/**
 * Special Issues gating (spec §4). FROZEN is true under its own
 * 2026-10-25 ~01:56 decision, separate from the general freeze.
 * Opening needs BOTH §4.5 (C1–C7, all evidenced) AND §4.6 (its
 * own explicit order naming posts by inventory id).
 */
object SpecialIssuesGate {
    const val FROZEN = true
    // Task wording for the frozen section line (spec §4.4 wording
    // is near-identical; the single line below is what renders).
    const val PLACEHOLDER = "ویژه‌نامه‌ها — متوقف است"
    // Preconditions C1–C7 (spec §4.5). All NOT_PASSED in this
    // pass — see docs/phase6-report.md §5 for the evidence.
    // C1: re-verify 2 partially-verified posts from source pages.
    // C2: fully verify the other 2 queued posts, same check.
    // C3: resolve queue-location discrepancy (one inventory).
    // C4: recover missing full texts (articles/ empty).
    // C5: quality gates per post (numbers check, opposing edit, PDF v2).
    // C6: ML-firewall audit (zero ML-DB rows/labels/inputs).
    // C7: lab dry-run of issue page + PDF viewer/download.
    val PRECONDITIONS = listOf("C1", "C2", "C3", "C4", "C5", "C6", "C7")
    // The 4 queued posts are NOT a publishable set (spec §8 risk 1).
    const val QUEUED_POSTS = 4
}

/** Bar rule for Phase 6 cards (same rule as Nightly EditionItemBar). */
object Phase6Bars {
    fun barFor(formatType: String?, category: String?): DomainBar = when {
        formatType != null -> DomainBar.RED_WAR
        category == "energy" -> DomainBar.YELLOW_ENERGY
        else -> DomainBar.NONE
    }

    fun labelFor(formatType: String?, category: String?): String? = when {
        formatType != null -> Formats.badgeText(formatType)
        category == "energy" -> "انرژی"
        else -> null
    }
}

/** Pure validation shared by the Kotlin parser and the fixture verifier. */
object Phase6Validation {
    fun isHttpSourceUrl(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://")) &&
            !url.lowercase().contains("blog")

    fun isVerbatimAllowed(badge: String?): Boolean =
        badge == null || badge.contains("دنیای اقتصاد") || badge.contains("تابناک")

    /** Analysis gate: returns a held reason, or null when publishable. */
    fun analysisHeldReason(
        type: String,
        wordCount: Int,
        sectionsPresent: Set<String>,
        hasProbabilityKey: Boolean,
    ): String? {
        if (hasProbabilityKey) return AnalysisHeldReasons.PROBABILITY_PRESENT
        if (type == AnalysisTypes.MONTHLY_ML) return AnalysisHeldReasons.MONTHLY_ML_GATED
        val band = AnalysisTypes.band(type) ?: return AnalysisHeldReasons.OUT_OF_BAND
        if (wordCount < 300) return AnalysisHeldReasons.THIN
        if (wordCount < band.first || wordCount > band.second) {
            return AnalysisHeldReasons.OUT_OF_BAND
        }
        if (!sectionsPresent.containsAll(AnalysisStructure.ORDER)) {
            return AnalysisHeldReasons.MISSING_SECTION
        }
        return null
    }

    /** Consensus gate: single-outlet matrices are held, not rendered. */
    fun consensusHeldReason(distinctOutlets: Int): String? =
        if (distinctOutlets < ConsensusRules.MIN_OUTLETS) "single_outlet" else null

    /** Top-stories gate: week range + basis are mandatory; label must match basis. */
    fun topStoriesHeldReason(
        weekRange: String?,
        basis: String?,
        storyCount: Int,
        hasVoteField: Boolean,
    ): String? {
        if (hasVoteField) return "vote_field_present"
        if (weekRange.isNullOrBlank()) return "missing_week_range"
        if (TopStoriesRules.labelFor(basis ?: "") == null) return "bad_selection_basis"
        if (storyCount < 1 || storyCount > 4) return "bad_story_count"
        return null
    }
}
