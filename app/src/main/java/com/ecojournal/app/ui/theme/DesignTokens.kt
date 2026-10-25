package com.ecojournal.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v5 P2 — ECO JOURNAL design system, single coherent layer.
 *
 * Source: docs/v5/v5-program-plan.md §2 (Phase 0). Patterns are
 * newspaper-front-page patterns only — never another publication's
 * branding, name, logo, or assets; the app name stays ECO JOURNAL.
 *
 * How to adopt (Phase 3 owns feed/reader adoption; Phase 4 owns
 * section screens; Phase 5 measures on device):
 * - Type/spacing: use [Type] / [Spacing] values, multiplied by the
 *   caller's fontScale exactly as existing screens already do
 *   (e.g. `Type.Masthead * fontScale`). Never hard-code a new size.
 * - Colour: [Palette] constants are the SAME hex values already in
 *   Theme.kt's Light/Dark/Sepia schemes — this file does not invent
 *   a second palette. Prefer MaterialTheme.colorScheme at the call
 *   site; use [Palette] only where a scheme value is unavailable
 *   (e.g. the ticker strip, which is red in every theme).
 * - Motion: [Motion] — no infinite motion without a user control
 *   (ticker pause button + reduced-motion setting, both wired in
 *   Masthead.kt / SettingsScreen.kt in this phase).
 * - Language: [StateLanguage] — every empty/error/offline state
 *   names what happened + the code + exactly ONE next step.
 *   Never a spinner without a bound, never a blank page, never a
 *   fake "up to date". The fixed Persian strings live in
 *   strings.xml (`v5_state_*`); P3 wires them into feed/reader.
 *
 * Gate honesty: this layer changes the masthead, the ticker
 * controls, and the bottom bar ONLY. It does not touch feed
 * branching, sync, or any feed query, and it does not fix or
 * claim to fix defect A1 (feed-flash-then-vanish). Gate G1 is
 * still OPEN (see docs/v5/v5-p1-report.md §1 and §4).
 */
object EcoDesignTokens {

    /** 8dp grid (plan §2). All spacing is a multiple of [Grid]. */
    object Spacing {
        val Grid = 8.dp
        val PageMargin = 16.dp
        val BlockGap = 24.dp
        val ElementGap = 8.dp
        val RuleGap = 2.dp
        val MastheadTop = 12.dp
        val MastheadBottom = 6.dp
        val TickerVertical = 6.dp
        val TickerHorizontal = 12.dp
    }

    /**
     * Type scale in sp (plan §2). Call sites multiply by fontScale.
     *
     * BottomBarLabel is 12sp, NOT the 14sp tab-label value: the
     * plan's navigation rule ("labels must fit on one line at
     * 12sp on a 360dp-wide phone") governs the bottom bar, because
     * 14sp is what made the eight-item bar wrap and split labels
     * (plan §1 M3). Section/tab labels elsewhere stay 14sp.
     */
    object Type {
        val Masthead = 32.sp
        val MastheadWeight = FontWeight.Bold
        val HeroHeadline = 26.sp
        val RowHeadline = 18.sp
        val HeroSummary = 16.sp
        val RowSummary = 15.sp
        val ReaderHeadline = 28.sp
        val ReaderBody = 18.sp
        val Meta = 12.sp
        val TabLabel = 14.sp
        val BottomBarLabel = 12.sp
        val SyncRow = 12.sp
        val SectionHeader = 15.sp
        val SectionHeaderWeight = FontWeight.Bold
        val Ticker = 13.sp
    }

    /** Line heights in sp (plan §2 ratios, rounded). */
    object LineHeight {
        val HeroHeadline = 34.sp
        val RowHeadline = 26.sp
        val HeroSummary = 24.sp
        val RowSummary = 22.sp
        val ReaderHeadline = 38.sp // 28sp at 1.35x
        val ReaderBody = 32.sp // 18sp at 1.75x
        val ReaderBodyCalm = 36.sp // 18sp at 2.0x (calm mode)
    }

    /**
     * Kept palette, copied verbatim from Theme.kt (light / dark /
     * sepia). No new colour is introduced in P2.
     */
    object Palette {
        val PaperLight = Color(0xFFFFFFFF)
        val InkLight = Color(0xFF121212)
        val NavyLight = Color(0xFF0F2A44)
        val GreyLight = Color(0xFF5A5A5A)
        val RuleLight = Color(0xFFE8E8E8)
        val RuleStrongLight = Color(0xFFD8D8D8)

        val PaperDark = Color(0xFF121212)
        val SurfaceDark = Color(0xFF1E1E1E)
        val InkDark = Color(0xFFF2F2F2)
        val NavyDark = Color(0xFF9DB8D6)
        val GreyDark = Color(0xFFB0B0B0)

        val PaperSepia = Color(0xFFFBF3DF)
        val InkSepia = Color(0xFF2B2118)
        val NavySepia = Color(0xFF5C4321)
        val GreySepia = Color(0xFF6B5D4A)

        /** Ticker strip red, identical in every theme. */
        val TickerRed = Color(0xFFC62828)

        /**
         * Ticker empty state: a darker red than [TickerRed], so an
         * empty ticker is visually distinct from a frozen/paused
         * ticker (plan §1 M2) instead of looking like a broken one.
         */
        val TickerEmptyRed = Color(0xFF8D1B1B)
    }

    /** Rules between blocks/stories (plan §2). */
    object Rules {
        val Story = 1.dp
        val DoubleThick = 2.dp
        val DoubleGap = 2.dp
        val DoubleThin = 1.dp
    }

    /** Motion rules (plan §2): pager 300ms; ticker pauses on user
     * control and stops entirely under reduced motion; list
     * changes animate only on insert/remove, never on sync
     * refresh (a refresh must not make the feed visibly jump). */
    object Motion {
        const val PagerDurationMs = 300
        val TickerVelocity = 40.dp
    }

    /**
     * Fixed empty/error/offline language rule (plan §2). The
     * strings themselves are `v5_state_*` in strings.xml; this
     * object states the rule so P3/P4 screens cannot drift from
     * it: what happened (plain Persian) + the code + exactly ONE
     * next step (retry and/or Diagnostics). A gated section says
     * "این بخش فعلاً متوقف است" — never "coming soon", never a
     * name list for gated voices, never an error for a gated
     * section (special issues).
     */
    object StateLanguage {
        const val GatedLineFa = "این بخش فعلاً متوقف است"
        const val MaxNextSteps = 1
    }
}

/**
 * WCAG relative-luminance contrast ratio between two colours
 * (1.0–21.0). Pure function over the token palette, so Phase 5
 * can measure the same pairs on device screenshots and compare
 * against the lab values recorded in docs/v5/v5-p2-report.md.
 * Targets (plan §2): body text >= 4.5:1, large text >= 3:1.
 */
fun contrastRatio(foreground: Color, background: Color): Double {
    fun linear(c: Float): Double {
        val s = c.toDouble()
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    val lumFg = 0.2126 * linear(foreground.red) +
        0.7152 * linear(foreground.green) + 0.0722 * linear(foreground.blue)
    val lumBg = 0.2126 * linear(background.red) +
        0.7152 * linear(background.green) + 0.0722 * linear(background.blue)
    val hi = maxOf(lumFg, lumBg)
    val lo = minOf(lumFg, lumBg)
    return (hi + 0.05) / (lo + 0.05)
}

fun meetsBodyContrast(foreground: Color, background: Color): Boolean =
    contrastRatio(foreground, background) >= 4.5

fun meetsLargeContrast(foreground: Color, background: Color): Boolean =
    contrastRatio(foreground, background) >= 3.0
