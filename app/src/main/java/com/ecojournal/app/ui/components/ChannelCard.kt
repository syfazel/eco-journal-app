package com.ecojournal.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ecojournal.app.R
import com.ecojournal.app.data.Attribution
import com.ecojournal.app.data.AttributionRules
import com.ecojournal.app.data.ChannelItemEntity
import com.ecojournal.app.data.CrisisLevels
import com.ecojournal.app.data.DomainBar
import com.ecojournal.app.data.DomainBars
import com.ecojournal.app.data.Formats
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.data.Verdicts
import com.ecojournal.app.ui.theme.EcoDesignTokens
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.formatPersianDouble
import com.ecojournal.app.util.toPersianDigits
import org.json.JSONArray
import org.json.JSONObject

// Phase 2 typed cards (spec section 3) + the cross-phase
// category bar (Yasin, 2026-10-25): a red or yellow strip on
// feed cards and reader headers for the war-room/red-alert and
// energy categories, inside the same feed/tabs system — never a
// separate service or tab. Bar colours are [SPEC DECISION] in
// Phase2Domain (badge colours are fixed by the spec itself).
// Badge meaning never relies on colour alone: badge text is
// always rendered and TalkBack reads "format: {badge text}".

/** Category bar strip: coloured bar + distinct category label. */
@Composable
fun DomainBarStrip(
    bar: DomainBar,
    label: String,
    modifier: Modifier = Modifier,
) {
    if (bar == DomainBar.NONE) return
    val argb = when (bar) {
        DomainBar.RED_WAR -> DomainBars.RED_ARGB
        DomainBar.YELLOW_ENERGY -> DomainBars.YELLOW_ARGB
        DomainBar.NONE -> return
    }
    // Phase 7 contrast fix: the label used to be drawn IN the bar
    // colour on the theme background — yellow on light/sepia was
    // 1.78–1.97:1, a WCAG failure. The label now sits on a filled
    // chip in the bar colour with contrasting text (black on
    // yellow, white on red), the same pattern CrisisChip already
    // used. Colour is still never the only cue: the label text
    // names the category.
    val textColor = if (bar == DomainBar.YELLOW_ENERGY) Color.Black else Color.White
    Row(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(22.dp)
                .background(Color(argb))
        )
        Spacer(modifier = Modifier.width(EcoDesignTokens.Spacing.ElementGap))
        Text(
            text = label,
            fontSize = EcoDesignTokens.Type.Meta,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier
                .background(Color(argb))
                .padding(horizontal = EcoDesignTokens.Spacing.ElementGap, vertical = 3.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.domain_bar_category_suffix),
            fontSize = EcoDesignTokens.Type.Meta,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun FormatBadge(item: ChannelItemEntity, modifier: Modifier = Modifier) {
    val info = Formats.info(item.formatType)
    val badgeText = info?.badgeText ?: item.formatType
    val bg = Color(info?.badgeArgb ?: 0xFF616161L)
    // Phase 7 contrast fix: white on WATCH orange (#E65100) was
    // 3.79:1 (fails AA normal text); black on the same orange is
    // 5.54:1. Other badge colours keep white (all pass AA).
    val textColor = if (info?.badgeArgb == 0xFFE65100L) Color.Black else Color.White
    // Precomputed outside the semantics block: stringResource
    // is composable-only and cannot run inside semantics {}.
    val badgeDesc = stringResource(R.string.format_badge_desc, badgeText)
    Text(
        text = badgeText,
        fontSize = EcoDesignTokens.Type.Meta,
        fontFamily = EcoFontFamily,
        fontWeight = FontWeight.Bold,
        color = textColor,
        modifier = modifier
            .background(bg)
            .padding(horizontal = EcoDesignTokens.Spacing.ElementGap, vertical = 3.dp)
            .semantics { contentDescription = badgeDesc },
    )
}

@Composable
fun CrisisChip(level: String?, modifier: Modifier = Modifier) {
    if (level == null) return
    val bg = Color(CrisisLevels.COLORS[level] ?: 0xFF616161L)
    // Phase 7 contrast fix: L3 shares WATCH orange, so it gets
    // black text like L2 on yellow; L4 red keeps white (5.62:1).
    val textColor = if (level == "L2" || level == "L3") Color.Black else Color.White
    val levelDesc = stringResource(R.string.crisis_level_desc, level)
    Text(
        text = level,
        fontSize = EcoDesignTokens.Type.Meta,
        fontFamily = EcoFontFamily,
        fontWeight = FontWeight.Bold,
        color = textColor,
        modifier = modifier
            .background(bg)
            .padding(horizontal = EcoDesignTokens.Spacing.ElementGap, vertical = 3.dp)
            .semantics { contentDescription = levelDesc },
    )
}

private fun pricesLineText(pricesJson: String?): String? {
    if (pricesJson == null) return null
    return try {
        val obj = JSONObject(pricesJson)
        val parts = mutableListOf<String>()
        val asset = obj.optString("asset").takeIf { it.isNotBlank() }
        if (asset != null) parts.add(asset)
        if (!obj.isNull("price")) {
            parts.add(formatPersianDouble(obj.optDouble("price"), 2))
        }
        if (!obj.isNull("change")) {
            parts.add("تغییر: ${formatPersianDouble(obj.optDouble("change"), 2)}")
        }
        if (!obj.isNull("bubble_pct")) {
            parts.add("حباب: ${formatPersianDouble(obj.optDouble("bubble_pct"), 2)}٪")
        }
        if (!obj.isNull("spread_pct")) {
            parts.add("اسپرد: ${formatPersianDouble(obj.optDouble("spread_pct"), 2)}٪")
        }
        parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
    } catch (_: Exception) {
        null
    }
}

private data class SitrepSection(val heading: String, val body: String)
private data class WatchNext(val title: String, val linkedStoryId: String?)

private fun parseSitrepSections(raw: String?): List<SitrepSection> {
    if (raw == null) return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                SitrepSection(o.optString("heading"), o.optString("body"))
            } catch (_: Exception) {
                null
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseWatchNext(raw: String?): List<WatchNext> {
    if (raw == null) return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                val title = o.optString("title")
                if (title.isBlank()) null else WatchNext(
                    title,
                    o.optString("linked_story_id").takeIf { it.isNotBlank() },
                )
            } catch (_: Exception) {
                null
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Typed channel card. [preview] renders the 3-line feed preview
 * with "ادامه" affordance (spec 3.4); full bodies render in the
 * reader and in the War Room SITREP list. [positionTag] is the
 * «{position} از {total}» tag, shown ONLY inside bundle pages.
 * [bundleTag] is the «بسته» tag linking to the item's bundle.
 * [heldTag] marks Morning Bundle items released from the night
 * hold («از شب گذشته»). Source buttons never point at the blog
 * (BLOG_LIVE=False) and never exist on WIRE cards.
 */
@Composable
fun TypedCard(
    item: ChannelItemEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preview: Boolean = true,
    positionTag: String? = null,
    bundleTag: (() -> Unit)? = null,
    heldTag: Boolean = false,
    // Phase 7: an expired urgent pin (>6h) stays visible at the
    // end of the rail by spec decision, but it used to render
    // unlabelled — indistinguishable from a live pin.
    expiredTag: Boolean = false,
    hasTimeline: Boolean = false,
    onOpenStory: ((String) -> Unit)? = null,
    onOpenMarket: (() -> Unit)? = null,
    fontScale: Float = 1f,
) {
    val context = LocalContext.current
    val attribution = when (item.attribution) {
        "VERBATIM" -> Attribution.VERBATIM
        "TRANSLATED" -> Attribution.TRANSLATED
        "WIRE" -> Attribution.WIRE
        else -> Attribution.NORMAL
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .semantics { contentDescription = "خبر: ${item.headline}" }
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.article_open), onClick = onClick)
            .padding(horizontal = EcoDesignTokens.Spacing.PageMargin, vertical = 12.dp),
    ) {
        // Cross-phase category bar: typed items are the red-alert
        // category, so every typed card carries the RED bar with
        // its format badge text as the distinct category label.
        DomainBarStrip(
            bar = DomainBars.forChannelItem(),
            label = Formats.badgeText(item.formatType),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row {
            FormatBadge(item = item)
            if (item.formatType == "SITREP") {
                Spacer(modifier = Modifier.width(EcoDesignTokens.Spacing.ElementGap))
                if (item.crisisLevel != null) {
                    CrisisChip(level = item.crisisLevel)
                } else {
                    Text(
                        text = stringResource(R.string.sitrep_level_missing),
                        fontSize = EcoDesignTokens.Type.Meta,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (item.crisisLevel != null) {
                Spacer(modifier = Modifier.width(EcoDesignTokens.Spacing.ElementGap))
                CrisisChip(level = item.crisisLevel)
            }
            if (item.urgencyScore != null && item.urgencyScore in 0..100) {
                Spacer(modifier = Modifier.width(EcoDesignTokens.Spacing.ElementGap))
                Text(
                    text = stringResource(
                        R.string.urgent_score_tag,
                        item.urgencyScore.toString().toPersianDigits(),
                    ),
                    fontSize = EcoDesignTokens.Type.Meta,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (positionTag != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = positionTag,
                fontSize = EcoDesignTokens.Type.Meta,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (heldTag) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.morning_bundle_held_tag),
                fontSize = EcoDesignTokens.Type.Meta,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expiredTag) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.expired_pin_label),
                fontSize = EcoDesignTokens.Type.Meta,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.headline,
            fontSize = EcoDesignTokens.Type.RowHeadline * fontScale,
            lineHeight = EcoDesignTokens.LineHeight.RowHeadline * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = if (preview) 3 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        // Format-specific blocks (spec section 3), in order.
        if (item.formatType == "VERIFY") {
            val chip = Verdicts.chipText(item.verdictStatus)
            if (item.verdictText != null) {
                Text(
                    text = if (chip != null) "$chip: ${item.verdictText}" else item.verdictText,
                    fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                    lineHeight = EcoDesignTokens.LineHeight.RowSummary * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            } else {
                Text(
                    text = stringResource(R.string.format_incomplete_line),
                    fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            }
        }
        if (item.formatType == "MARKET") {
            val prices = pricesLineText(item.pricesJson)
            if (prices != null) {
                Text(
                    text = prices,
                    fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                    lineHeight = EcoDesignTokens.LineHeight.RowSummary * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = if (onOpenMarket != null) {
                        Modifier.clickable { onOpenMarket() }
                    } else {
                        Modifier
                    },
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            } else {
                Text(
                    text = stringResource(R.string.format_incomplete_line),
                    fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            }
        }
        if (item.body.isNotBlank()) {
            Text(
                text = item.body,
                fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                lineHeight = EcoDesignTokens.LineHeight.RowSummary * fontScale,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (preview) 3 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
        }
        if (item.formatType == "SITREP") {
            val sections = parseSitrepSections(item.sitrepSectionsJson)
            if (sections.isEmpty()) {
                Text(
                    text = stringResource(R.string.format_incomplete_line),
                    fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            } else {
                for (section in sections) {
                    Text(
                        text = section.heading,
                        fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                        fontFamily = EcoFontFamily,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = section.body,
                        fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                        lineHeight = EcoDesignTokens.LineHeight.RowSummary * fontScale,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
                if (sections.size != 5) {
                    Text(
                        text = stringResource(R.string.format_incomplete_line),
                        fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
                }
            }
            val watch = parseWatchNext(item.watchNextJson)
            Text(
                text = stringResource(R.string.sitrep_watch_next_title),
                fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            for (row in watch) {
                val storyId = row.linkedStoryId
                Text(
                    text = "• ${row.title}",
                    fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                    lineHeight = EcoDesignTokens.LineHeight.RowSummary * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = if (storyId != null && onOpenStory != null) {
                        Modifier.clickable { onOpenStory(storyId) }
                    } else {
                        Modifier
                    },
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (watch.size != 2) {
                Text(
                    text = stringResource(R.string.format_incomplete_line),
                    fontSize = EcoDesignTokens.Type.RowSummary * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            }
        }
        // Byline: Shamsi date + Tehran time, Persian digits, and
        // the attribution line (spec 2.6). WIRE shows the source
        // name ONLY, with no link button, ever.
        val byline = "${AttributionRules.sourceLine(attribution, item.sourceName)} • " +
            Jalali.formatShamsiDateTime(item.publishedAtEpoch)
        Text(
            text = byline,
            fontSize = EcoDesignTokens.Type.Meta,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (bundleTag != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.bundle_tag),
                fontSize = EcoDesignTokens.Type.Meta,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { bundleTag() },
            )
        }
        if (hasTimeline) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.story_timeline_hint),
                fontSize = EcoDesignTokens.Type.Meta,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (attribution != Attribution.WIRE && item.sourceLink != null) {
            Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            var sourceError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            Button(onClick = {
                sourceError = false
                val uri = try {
                    Uri.parse(item.sourceLink)
                } catch (_: Exception) {
                    null
                }
                if (uri == null || (uri.scheme != "http" && uri.scheme != "https")) {
                    sourceError = true
                } else {
                    try {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), null))
                    } catch (_: ActivityNotFoundException) {
                        sourceError = true
                    } catch (_: Exception) {
                        sourceError = true
                    }
                }
            }) {
                Text(stringResource(R.string.open_source), fontFamily = EcoFontFamily)
            }
            if (sourceError) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.open_source_failed),
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        NewspaperRule()
    }
}
