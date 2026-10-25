package com.ecojournal.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.AnalysisItemParsed
import com.ecojournal.app.data.AnalysisTypes
import com.ecojournal.app.data.SyncSections
import com.ecojournal.app.data.ConsensusRow
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.Phase6Bars
import com.ecojournal.app.data.Phase6Parsing
import com.ecojournal.app.data.TopStoriesRules
import com.ecojournal.app.ui.components.DomainBarStrip
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.toPersianDigits

// Phase 6 — Analysis section (spec §2, §5).
// Source: the nightly editions already synced (their analysis
// component JSON, stored verbatim on the edition row). The
// standalone section and the nightly edition link to the same
// story_id — two views of one item, never two copies (P6-X2).
// Every item passes the Phase6 parser gate first: 4 structure
// sections in order, length band, no monthly-ML type while the
// calibration gate is closed, no probability key, no blog URL,
// allow-listed verbatim only. Held items never render and are
// never padded; their count is shown only as the honest held
// line (no held titles — held items are generator-report
// content, not reader content).
//
// Top-stories card (§5): no real card renders here until the
// §5.4 decision is recorded (TopStoriesRules
// .NO_POLL_DECISION_RECORDED = false). Until then the section
// shows the honest "no card this week" line — never an
// editorial pick labelled as a poll result.
//
// Cross-phase bar rule (§0): each card carries the SAME bar as
// feed cards via DomainBarStrip — RED + format badge label for
// typed items, YELLOW + «انرژی» for energy articles, no bar
// otherwise. No separate service, no separate tab for
// war-room/red-alert: Analysis is the editorial section, and
// red-alert items stay inside it with bar + badge.
@Composable
fun AnalysisScreen(
    repo: EdgeRepository,
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val editions by repo.nightlyDao.latestEditionsFlow().collectAsState(initial = emptyList())
    // Phase 7: Analysis used to render stale items silently after
    // a failed nightly sync — no failed line, no retry. It now
    // surfaces the nightly section state (analysis travels in
    // the nightly section since Phase 5).
    var nightlyFailed by remember { mutableStateOf(false) }
    var nightlyErrorCode by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val st = repo.sectionStates().firstOrNull { it.section == SyncSections.NIGHTLY }
        nightlyFailed = st?.state == "failed"
        nightlyErrorCode = st?.lastErrorCode.orEmpty()
    }
    var selected by remember { mutableStateOf<AnalysisItemParsed?>(null) }
    val parsed = remember(editions) {
        val found = mutableListOf<AnalysisItemParsed>()
        var heldCount = 0
        val seen = mutableSetOf<String>()
        for (ed in editions) {
            val comp = Phase6Parsing.parseAnalysisComponent(ed.analysisJson)
            for (item in comp.items) {
                // One story = one card, even if it appears in
                // several editions (P6-X2).
                if (seen.add(item.storyId)) found.add(item)
            }
            heldCount += comp.held.size
        }
        found to heldCount
    }
    val (analysisItems, heldCount) = parsed
    if (selected != null) {
        AnalysisDetailScreen(item = selected!!, onBack = { selected = null }, fontScale = fontScale)
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text(
                text = stringResource(R.string.analysis_title),
                fontFamily = EcoFontFamily,
                fontSize = 20.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            if (nightlyFailed) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.section_error_line,
                        stringResource(R.string.diagnostics_section_name_nightly),
                        nightlyErrorCode.ifBlank { "network" },
                    ),
                    fontFamily = EcoFontFamily,
                    fontSize = 13.sp * fontScale,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (analysisItems.isEmpty()) {
            item {
                // Honest empty state (P6-A5): no placeholder
                // articles, no sample analysis, no backfilled
                // old posts presented as new analysis.
                Text(
                    text = stringResource(R.string.analysis_empty),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        } else {
            items(analysisItems) { item ->
                val bar = Phase6Bars.barFor(item.formatType, item.category)
                val label = Phase6Bars.labelFor(item.formatType, item.category)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Phase 7: bare clickable analysis row.
                        .clickable(role = Role.Button) { selected = item }
                        .padding(vertical = 12.dp),
                ) {
                    if (label != null) {
                        DomainBarStrip(bar = bar, label = label)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = item.title,
                        fontFamily = EcoFontFamily,
                        fontSize = 16.sp * fontScale,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = AnalysisTypes.badge(item.analysisType),
                        fontFamily = EcoFontFamily,
                        fontSize = 13.sp * fontScale,
                    )
                    if (item.thesisLine != null) {
                        Text(
                            text = item.thesisLine,
                            fontFamily = EcoFontFamily,
                            fontSize = 13.sp * fontScale,
                        )
                    }
                    Text(
                        text = stringResource(R.string.analysis_word_count, item.wordCount.toString().toPersianDigits()),
                        fontFamily = EcoFontFamily,
                        fontSize = 13.sp * fontScale,
                    )
                    if (item.correctionCount > 0) {
                        Text(
                            text = stringResource(R.string.analysis_correction_badge),
                            fontFamily = EcoFontFamily,
                            fontSize = 13.sp * fontScale,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
        if (heldCount > 0) {
            item {
                Text(
                    text = stringResource(R.string.analysis_held_note),
                    fontFamily = EcoFontFamily,
                    fontSize = 13.sp * fontScale,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        item {
            // Top-stories card slot (§5): gated on the §5.4
            // decision. Until it is recorded, the honest
            // no-card line — never a fabricated top-4.
            Text(
                text = stringResource(R.string.topstories_title),
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (!TopStoriesRules.NO_POLL_DECISION_RECORDED) {
                Text(
                    text = stringResource(R.string.topstories_no_card),
                    fontFamily = EcoFontFamily,
                    fontSize = 14.sp * fontScale,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.topstories_readonly_note),
                fontFamily = EcoFontFamily,
                fontSize = 13.sp * fontScale,
            )
        }
    }
}

@Composable
private fun AnalysisDetailScreen(
    item: AnalysisItemParsed,
    onBack: () -> Unit,
    fontScale: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Button(onClick = onBack) {
            Text(text = stringResource(R.string.analysis_back), fontFamily = EcoFontFamily, fontSize = 14.sp * fontScale)
        }
        Spacer(modifier = Modifier.height(12.dp))
        val bar = Phase6Bars.barFor(item.formatType, item.category)
        val label = Phase6Bars.labelFor(item.formatType, item.category)
        if (label != null) {
            DomainBarStrip(bar = bar, label = label)
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(
            text = item.title,
            fontFamily = EcoFontFamily,
            fontSize = 20.sp * fontScale,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = item.byline,
            fontFamily = EcoFontFamily,
            fontSize = 13.sp * fontScale,
        )
        if (item.correctionCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.analysis_correction_badge),
                fontFamily = EcoFontFamily,
                fontSize = 13.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.analysis_correction_note),
                fontFamily = EcoFontFamily,
                fontSize = 13.sp * fontScale,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Four structure sections, always in fixed order (P6-A1).
        for (section in item.sections) {
            Text(
                text = section.heading,
                fontFamily = EcoFontFamily,
                fontSize = 16.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = section.body,
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (item.consensus != null) {
            Text(
                text = stringResource(R.string.analysis_consensus_label),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.consensus.topic,
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
            )
            Text(
                text = item.consensus.asOfShamsi,
                fontFamily = EcoFontFamily,
                fontSize = 13.sp * fontScale,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ConsensusRows(title = "موافق", rows = item.consensus.agreement, fontScale = fontScale)
            ConsensusRows(title = "مخالف", rows = item.consensus.divergence, fontScale = fontScale)
        }
        if (item.sourceLinks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.analysis_source_links_title),
                fontFamily = EcoFontFamily,
                fontSize = 14.sp * fontScale,
                fontWeight = FontWeight.Bold,
            )
            // Source links render as text lines (never as blog
            // links — the parser already held any blog URL).
            // Phase 7: they used to be inert text — untappable
            // for sighted readers and unopenable for TalkBack.
            // They are now real links (destination unchanged:
            // the news source, never the blog).
            val uriHandler = LocalUriHandler.current
            for (url in item.sourceLinks) {
                Text(
                    text = url,
                    fontFamily = EcoFontFamily,
                    fontSize = 12.sp * fontScale,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.article_open),
                    ) { uriHandler.openUri(url) },
                )
            }
        }
    }
}

@Composable
private fun ConsensusRows(title: String, rows: List<ConsensusRow>, fontScale: Float) {
    Text(
        text = title,
        fontFamily = EcoFontFamily,
        fontSize = 14.sp * fontScale,
        fontWeight = FontWeight.Bold,
    )
    for (row in rows) {
        // Quote vs paraphrase labelling (spec §2.3): direct
        // quotes in quotation marks, everything else «به نقل از».
        val positionText = if (row.isQuote) "«${row.position}»" else "به نقل از ${row.outlet}: ${row.position}"
        Text(
            text = positionText,
            fontFamily = EcoFontFamily,
            fontSize = 13.sp * fontScale,
        )
        Text(
            text = "${row.outlet} — ${row.date}",
            fontFamily = EcoFontFamily,
            fontSize = 12.sp * fontScale,
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
    Spacer(modifier = Modifier.height(8.dp))
}
