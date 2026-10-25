package com.ecojournal.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.TickerTopics
import com.ecojournal.app.ui.theme.EcoDesignTokens
import com.ecojournal.app.ui.theme.EcoFontFamily

/**
 * v5 P2 masthead (docs/v5/v5-program-plan.md §2, delegated
 * decision 2026-10-26): 32sp bold navy newspaper masthead in
 * Vazirmatn (OFL), centred, with the double rule beneath.
 *
 * This SUPERSEDES the v4-H 22sp platform-cursive wordmark as the
 * masthead for v5: the audit found the 22sp script weak, and no
 * bundled OFL script font asset exists (Blacksword is
 * personal-use only and is never shipped). The CNN-style red
 * ticker stays (Yasin's post-phase requirement) but now has a
 * pause control and a reduced-motion path (see WarRoomTicker).
 *
 * [ScriptWordmark] is retained ONLY as a legacy secondary mark
 * and is no longer used by [Masthead]; nothing in v5 should
 * re-promote it to the masthead without a bundled OFL script
 * font file first. No other publication's branding, name, logo,
 * or assets appear here; the wordmark is ECO JOURNAL throughout.
 */
val ScriptFontFamily: FontFamily = FontFamily.Cursive

/** Legacy v4-H secondary mark — NOT the v5 masthead. */
@Composable
fun ScriptWordmark(
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.app_name),
        fontSize = 22.sp * fontScale,
        fontFamily = ScriptFontFamily,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = EcoDesignTokens.Spacing.MastheadTop,
                bottom = EcoDesignTokens.Spacing.MastheadBottom,
            )
            .semantics { heading() },
    )
}

/** v5 masthead wordmark: 32sp bold navy Vazirmatn, centred. */
@Composable
fun MastheadWordmark(
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.app_name),
        fontSize = EcoDesignTokens.Type.Masthead * fontScale,
        fontWeight = EcoDesignTokens.Type.MastheadWeight,
        fontFamily = EcoFontFamily,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = EcoDesignTokens.Spacing.MastheadTop,
                bottom = EcoDesignTokens.Spacing.MastheadBottom,
            )
            .semantics { heading() },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WarRoomTicker(
    topics: List<String>,
    fontScale: Float = 1f,
    /** v5 P2: when true, the ticker never animates — static text only. */
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val text = TickerTopics.marqueeText(topics)
    val desc = if (topics.isEmpty()) {
        stringResource(R.string.ticker_empty)
    } else {
        stringResource(R.string.ticker_desc, text)
    }
    // Pause is session-local: leaving the feed resets to playing,
    // matching the session-only filter behaviour elsewhere. The
    // reduced-motion setting (Settings) is the persistent control.
    var paused by remember { mutableStateOf(false) }
    val animating = topics.isNotEmpty() && !reducedMotion && !paused
    val stripColor = if (topics.isEmpty()) {
        // Distinct darker red: an empty ticker must not look like
        // a frozen/paused ticker (plan §1 M2).
        EcoDesignTokens.Palette.TickerEmptyRed
    } else {
        EcoDesignTokens.Palette.TickerRed
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(stripColor)
            .semantics { contentDescription = desc },
    ) {
        if (topics.isEmpty()) {
            Text(
                text = stringResource(R.string.ticker_empty),
                fontSize = EcoDesignTokens.Type.Ticker * fontScale,
                fontFamily = EcoFontFamily,
                color = Color.White,
                modifier = Modifier.padding(
                    horizontal = EcoDesignTokens.Spacing.TickerHorizontal,
                    vertical = EcoDesignTokens.Spacing.TickerVertical,
                ),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = text,
                    fontSize = EcoDesignTokens.Type.Ticker * fontScale,
                    fontFamily = EcoFontFamily,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = EcoDesignTokens.Spacing.TickerVertical)
                        .then(
                            if (animating) {
                                Modifier.basicMarquee(
                                    iterations = Int.MAX_VALUE,
                                    velocity = EcoDesignTokens.Motion.TickerVelocity,
                                )
                            } else {
                                Modifier
                            },
                        ),
                )
                // Pause/resume control: no infinite motion without
                // a user control (plan §2 motion rules). Under
                // reduced motion there is nothing to pause, so no
                // button is shown — the static text IS the state.
                if (!reducedMotion) {
                    IconButton(onClick = { paused = !paused }) {
                        Icon(
                            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = stringResource(
                                if (paused) R.string.ticker_resume else R.string.ticker_pause,
                            ),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** Wordmark + ticker + double rule, in that order, as one unit. */
@Composable
fun Masthead(
    topics: List<String>,
    tickerEnabled: Boolean,
    fontScale: Float = 1f,
    /** v5 P2: persistent reduced-motion setting from Settings. */
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MastheadWordmark(fontScale = fontScale)
        if (tickerEnabled) {
            WarRoomTicker(
                topics = topics,
                fontScale = fontScale,
                reducedMotion = reducedMotion,
            )
        }
        NewspaperDoubleRule(
            modifier = Modifier.padding(
                horizontal = EcoDesignTokens.Spacing.PageMargin,
                vertical = EcoDesignTokens.Spacing.ElementGap,
            ),
        )
    }
}
