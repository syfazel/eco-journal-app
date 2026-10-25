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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.DomainBar
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.Formats
import com.ecojournal.app.data.NightlyComponentStates
import com.ecojournal.app.data.NightlyEditionEntity
import com.ecojournal.app.data.NightlyItemEntity
import com.ecojournal.app.data.SectionSyncState
import com.ecojournal.app.data.SyncSections
import com.ecojournal.app.ui.components.DomainBarStrip
import com.ecojournal.app.ui.theme.EcoFontFamily
import com.ecojournal.app.util.formatPersianDouble
import com.ecojournal.app.util.toPersianDigits
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// Phase 5 — Nightly section + edition page + dossier page.
//
// Spec §5.14: a top-level Nightly section (hero = tonight's
// edition, below it the archive list newest first), each edition
// openable as a SINGLE scrollable edition page in component
// order 1→7 (not a pile of feed rows), bookmarkable and
// searchable. While no edition has ever published, the honest
// empty state — never a fake sample edition, never a dry-run
// edition presented as published (dry-run editions are lab
// files, never synced: see Nightly.kt U4 note).
//
// Cross-phase bar rule (new-requirements.md §0, binding on
// every surface Phase 5 adds): edition item cards carry the
// SAME bar as feed cards — RED for typed/war-room items
// (format_type present = red-alert category, outranking domain
// colour), YELLOW for energy-domain articles — with the
// distinct label, never colour alone. No separate service, no
// separate tab for war-room/red-alert: the Nightly tab is the
// edition section, and red-alert items stay inside it with
// their bar + badge like everywhere else.

private fun jsonObj(text: String): JSONObject {
    return try {
        JSONObject(text)
    } catch (_: Exception) {
        JSONObject()
    }
}

private fun kindLabel(kind: String): Int = when (kind) {
    "full_article" -> R.string.nightly_kind_full
    "summary" -> R.string.nightly_kind_summary
    else -> R.string.nightly_kind_brief
}

/** The bar for an edition item, by the SAME rule as feed cards. */
@Composable
private fun EditionItemBar(item: NightlyItemEntity) {
    when {
        item.formatType != null -> DomainBarStrip(
            bar = DomainBar.RED_WAR,
            label = Formats.badgeText(item.formatType),
        )
        item.category == "energy" -> DomainBarStrip(
            bar = DomainBar.YELLOW_ENERGY,
            label = "انرژی",
        )
        else -> Unit
    }
}

@Composable
fun NightlyScreen(
    repo: EdgeRepository,
    onOpenEdition: (String) -> Unit,
    onOpenDossier: (String) -> Unit,
    fontScale: Float = 1f,
) {
    val editions by repo.nightlyDao.latestEditionsFlow().collectAsState(initial = emptyList())
    val dossiers by repo.nightlyDao.dossiersFlow().collectAsState(initial = emptyList())
    var sectionStates by remember { mutableStateOf(emptyList<SectionSyncState>()) }
    var query by remember { mutableStateOf("") }
    var scopedEdition by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<NightlyItemEntity>?>(null) }
    var fresh by remember { mutableStateOf<List<NightlyItemEntity>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        sectionStates = repo.sectionStates()
        fresh = repo.nightlyDao.searchItems("", null).take(20)
    }
    LaunchedEffect(query, scopedEdition) {
        results = if (query.length < 2) {
            null
        } else {
            repo.nightlyDao.searchItems(query, scopedEdition)
        }
    }
    val nightlyState = sectionStates.firstOrNull { it.section == SyncSections.NIGHTLY }
    val hero = editions.firstOrNull()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.nightly_title),
                    fontSize = 26.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (nightlyState?.state == "failed" && hero != null) {
                    Text(
                        text = stringResource(R.string.nightly_failed_line),
                        fontSize = 13.sp * fontScale,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (hero == null) {
                    // Honest empty state (spec §5.14). A failed
                    // sync with no cached edition shows the same
                    // line: there is no yesterday's edition to
                    // fall back to, and no fake edition is shown.
                    Text(
                        text = stringResource(R.string.nightly_empty),
                        fontSize = 15.sp * fontScale,
                        fontFamily = EcoFontFamily,
                    )
                } else {
                    EditionRow(
                        edition = hero,
                        onOpen = { onOpenEdition(hero.editionId) },
                        fontScale = fontScale,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.nightly_search_title),
                    fontSize = 18.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.nightly_search_hint), fontFamily = EcoFontFamily) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scopedEdition = if (scopedEdition == null) hero?.editionId else null
                        scope.launch { results = null }
                    }
                ) {
                    Text(
                        text = if (scopedEdition == null) {
                            stringResource(R.string.nightly_search_all)
                        } else {
                            stringResource(R.string.nightly_search_scoped, scopedEdition ?: "")
                        },
                        fontFamily = EcoFontFamily,
                    )
                }
                if (results != null) {
                    if (results!!.isEmpty()) {
                        Text(
                            text = stringResource(R.string.nightly_no_results),
                            fontSize = 14.sp * fontScale,
                            fontFamily = EcoFontFamily,
                        )
                    } else {
                        for (item in results!!) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // Phase 7: bare clickable row
                                    // (~35dp, no role/label).
                                    .clickable(
                                        role = Role.Button,
                                        onClickLabel = stringResource(R.string.open_edition_desc, item.editionId),
                                        onClick = { onOpenEdition(item.editionId) },
                                    )
                                    .padding(vertical = 12.dp)
                            ) {
                                EditionItemBar(item)
                                Text(
                                    text = item.title,
                                    fontSize = 15.sp * fontScale,
                                    fontFamily = EcoFontFamily,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = item.editionId,
                                    fontSize = 12.sp * fontScale,
                                    fontFamily = EcoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.nightly_fresh_title),
                    fontSize = 18.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.nightly_archive_entry),
                    fontSize = 13.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(fresh) { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.open_edition_desc, item.editionId),
                        onClick = { onOpenEdition(item.editionId) },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                EditionItemBar(item)
                Text(
                    text = item.title,
                    fontSize = 15.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = item.editionId,
                    fontSize = 12.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.nightly_dossiers_title),
                    fontSize = 18.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                for (dossier in dossiers) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.open_dossier_desc, dossier.topicTitleFa),
                                onClick = { onOpenDossier(dossier.dossierId) },
                            )
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = dossier.topicTitleFa,
                            fontSize = 15.sp * fontScale,
                            fontFamily = EcoFontFamily,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = dossier.dossierId,
                            fontSize = 12.sp * fontScale,
                            fontFamily = EcoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.nightly_archive_title),
                    fontSize = 18.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        items(editions.drop(1)) { edition ->
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                EditionRow(
                    edition = edition,
                    onOpen = { onOpenEdition(edition.editionId) },
                    fontScale = fontScale,
                )
            }
        }
    }
}

@Composable
private fun EditionRow(
    edition: NightlyEditionEntity,
    onOpen: () -> Unit,
    fontScale: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Phase 7: bare clickable edition row. No item bar is
            // added here by ruling: an edition aggregates many
            // categories, so a single RED/YELLOW bar would mislabel
            // it; bars stay on item cards and item readers.
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.open_edition_desc, edition.shamsiDate),
                onClick = { onOpen() },
            )
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = edition.shamsiDate,
            fontSize = 20.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        if (edition.version >= 2) {
            Text(
                text = stringResource(
                    R.string.nightly_version_corrected,
                    edition.version.toString().toPersianDigits(),
                ),
                fontSize = 13.sp * fontScale,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.nightly_last_updated, edition.generatedAtTehran),
            fontSize = 12.sp * fontScale,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EditionPageScreen(
    repo: EdgeRepository,
    editionId: String,
    onOpenDossier: (String) -> Unit,
    onOpenEdition: (String) -> Unit,
    onBack: () -> Unit,
    fontScale: Float = 1f,
) {
    var edition by remember { mutableStateOf<NightlyEditionEntity?>(null) }
    var items by remember { mutableStateOf<List<NightlyItemEntity>>(emptyList()) }
    var dossiers by remember { mutableStateOf(emptyList<com.ecojournal.app.data.DossierEntity>()) }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(editionId) {
        edition = repo.nightlyDao.latestEditionById(editionId)
        items = repo.nightlyDao.itemsForEdition(editionId)
        dossiers = repo.nightlyDao.dossiersFlow().first()
    }
    val ed = edition
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Button(onClick = onBack) {
            Text(text = "بازگشت", fontFamily = EcoFontFamily)
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (ed == null) {
            // Deep link to a pruned, non-bookmarked old edition:
            // honest line, never a crash, never a fake empty
            // edition (spec §5.14). On-demand fetch is a sync
            // action the reader triggers from the Nightly tab.
            Text(
                text = stringResource(R.string.nightly_not_kept_locally),
                fontSize = 15.sp * fontScale,
                fontFamily = EcoFontFamily,
            )
            return@Column
        }
        Text(
            text = ed.shamsiDate,
            fontSize = 26.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        if (ed.version >= 2) {
            Text(
                text = stringResource(
                    R.string.nightly_version_corrected,
                    ed.version.toString().toPersianDigits(),
                ),
                fontSize = 14.sp * fontScale,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.nightly_last_updated, ed.generatedAtTehran),
            fontSize = 13.sp * fontScale,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        val bookmarked = ed.bookmarked
        OutlinedButton(
            onClick = {
                scope.launch {
                    repo.nightlyDao.setEditionBookmarked(ed.editionId, !bookmarked)
                    edition = repo.nightlyDao.latestEditionById(editionId)
                }
            }
        ) {
            Text(
                text = stringResource(
                    if (bookmarked) R.string.nightly_unbookmark else R.string.nightly_bookmark
                ),
                fontFamily = EcoFontFamily,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        // Corrections map, for inline «اصلاح شد» notes on their
        // originals (spec §5.12) as well as the component-6 list.
        val correctionsObj = jsonObj(ed.correctionsJson)
        val correctionsArr = correctionsObj.optJSONArray("items") ?: JSONArray()
        val correctionsByStory = mutableMapOf<String, JSONObject>()
        for (i in 0 until correctionsArr.length()) {
            val c = try {
                correctionsArr.getJSONObject(i)
            } catch (_: Exception) {
                continue
            }
            val sid = c.optString("story_id")
            if (sid.isNotBlank()) correctionsByStory[sid] = c
        }
        // Component 1 — articles, in edition order. Held state
        // shows NO held titles: held items are generator-report
        // content (lab-side), not published reader content.
        Text(
            text = stringResource(R.string.nightly_articles_title),
            fontSize = 20.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        if (ed.articlesState == NightlyComponentStates.NONE_TONIGHT) {
            Text(
                text = stringResource(R.string.nightly_none_tonight),
                fontSize = 14.sp * fontScale,
                fontFamily = EcoFontFamily,
            )
        } else if (ed.articlesState == NightlyComponentStates.HELD_WITH_REASONS) {
            Text(
                text = stringResource(R.string.nightly_held_line),
                fontSize = 14.sp * fontScale,
                fontFamily = EcoFontFamily,
            )
        }
        for (item in items) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) {
                        expandedKey = if (expandedKey == item.editionKey) null else item.editionKey
                    }
                    .padding(vertical = 12.dp)
            ) {
                EditionItemBar(item)
                Text(
                    text = item.title,
                    fontSize = 17.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(kindLabel(item.kind)) + " • " +
                        stringResource(
                            R.string.nightly_word_count,
                            item.wordCount.toString().toPersianDigits(),
                        ) + " • " + item.sourceName,
                    fontSize = 12.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.verbatimBadge != null) {
                    Text(
                        text = item.verbatimBadge,
                        fontSize = 12.sp * fontScale,
                        fontFamily = EcoFontFamily,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val corr = correctionsByStory[item.storyId]
                if (corr != null) {
                    Text(
                        text = stringResource(
                            R.string.nightly_correction_line,
                            corr.optString("note_fa"),
                            corr.optInt("version_after", ed.version).toString().toPersianDigits(),
                            corr.optInt("version_before", 1).toString().toPersianDigits(),
                        ),
                        fontSize = 13.sp * fontScale,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (expandedKey == item.editionKey) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.body,
                        fontSize = 15.sp * fontScale,
                        fontFamily = EcoFontFamily,
                    )
                }
            }
        }
        // Component 2 — market close (verbatim from the edition;
        // alert history is the shipped Phase 4 rows, never
        // re-derived from snapshots in the app).
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.nightly_market_close_title),
            fontSize = 20.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        val closeObj = jsonObj(ed.marketCloseJson)
        if (ed.marketCloseState == NightlyComponentStates.NONE_TONIGHT) {
            Text(
                text = stringResource(R.string.nightly_none_tonight),
                fontSize = 14.sp * fontScale,
                fontFamily = EcoFontFamily,
            )
        } else {
            val snap = closeObj.optJSONObject("snapshot")
            if (snap == null) {
                Text(
                    text = stringResource(R.string.nightly_no_snapshot),
                    fontSize = 14.sp * fontScale,
                    fontFamily = EcoFontFamily,
                )
            } else {
                Text(
                    text = snap.optString("close_ts_tehran"),
                    fontSize = 13.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (closeObj.optBoolean("stale_close", false)) {
                    Text(
                        text = stringResource(
                            R.string.nightly_stale_close,
                            snap.optString("close_ts_tehran"),
                        ),
                        fontSize = 13.sp * fontScale,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val prices = snap.optJSONObject("prices")
                if (prices != null) {
                    val keys = prices.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = if (prices.isNull(k)) "—" else formatPersianDouble(prices.optDouble(k), 2)
                        Text(
                            text = "$k: $v",
                            fontSize = 14.sp * fontScale,
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
                val formulas = snap.optJSONObject("formulas")
                if (formulas != null) {
                    val keys = formulas.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = if (formulas.isNull(k)) "—" else formatPersianDouble(formulas.optDouble(k), 2)
                        Text(
                            text = "$k: $v",
                            fontSize = 14.sp * fontScale,
                            fontFamily = EcoFontFamily,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.nightly_alert_history_title),
                fontSize = 16.sp * fontScale,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
            )
            val alerts = closeObj.optJSONArray("alert_history") ?: JSONArray()
            if (alerts.length() == 0) {
                // Empty alert history is NOT "thresholds off":
                // thresholds live on the close itself. An empty
                // day with thresholds set means no alert fired.
                val thresholds = closeObj.optJSONObject("thresholds")
                val anyThreshold = thresholds != null &&
                    (!thresholds.isNull("bubble_threshold_pct") ||
                        !thresholds.isNull("spread_threshold_pct"))
                Text(
                    text = stringResource(
                        if (anyThreshold) R.string.nightly_no_alerts_today
                        else R.string.nightly_alert_no_threshold
                    ),
                    fontSize = 13.sp * fontScale,
                    fontFamily = EcoFontFamily,
                )
            } else {
                for (i in 0 until alerts.length()) {
                    val a = try {
                        alerts.getJSONObject(i)
                    } catch (_: Exception) {
                        continue
                    }
                    Text(
                        text = stringResource(
                            R.string.nightly_alert_row,
                            a.optString("metric"),
                            formatPersianDouble(a.optDouble("value_pct", 0.0), 1),
                            if (a.isNull("threshold_pct")) "—"
                            else formatPersianDouble(a.optDouble("threshold_pct"), 1),
                            stringResource(
                                if (a.optInt("notified", 0) == 1) {
                                    R.string.nightly_alert_notified
                                } else {
                                    R.string.nightly_alert_not_notified
                                }
                            ),
                        ),
                        fontSize = 13.sp * fontScale,
                        fontFamily = EcoFontFamily,
                    )
                }
            }
        }
        // Component 3 — digests, consolidated.
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.nightly_digests_title),
            fontSize = 20.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        val digestsObj = jsonObj(ed.digestsJson)
        if (ed.digestsState == NightlyComponentStates.NONE_TONIGHT) {
            Text(
                text = stringResource(R.string.nightly_none_tonight),
                fontSize = 14.sp * fontScale,
                fontFamily = EcoFontFamily,
            )
        } else {
            val bundles = digestsObj.optJSONArray("bundles") ?: JSONArray()
            for (i in 0 until bundles.length()) {
                val b = try {
                    bundles.getJSONObject(i)
                } catch (_: Exception) {
                    continue
                }
                Text(
                    text = b.optString("title"),
                    fontSize = 15.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                val members = b.optJSONArray("members") ?: JSONArray()
                for (j in 0 until members.length()) {
                    val m = try {
                        members.getJSONObject(j)
                    } catch (_: Exception) {
                        continue
                    }
                    Text(
                        text = "• " + m.optString("title"),
                        fontSize = 14.sp * fontScale,
                        fontFamily = EcoFontFamily,
                    )
                }
            }
            val nightDesk = digestsObj.optJSONObject("night_desk")
            if (nightDesk != null) {
                Text(
                    text = stringResource(R.string.nightly_night_desk_title),
                    fontSize = 16.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                val ndItems = nightDesk.optJSONArray("items") ?: JSONArray()
                if (ndItems.length() == 0) {
                    Text(
                        text = nightDesk.optString("state_note", ""),
                        fontSize = 13.sp * fontScale,
                        fontFamily = EcoFontFamily,
                    )
                } else {
                    for (i in 0 until ndItems.length()) {
                        val m = try {
                            ndItems.getJSONObject(i)
                        } catch (_: Exception) {
                            continue
                        }
                        Text(
                            text = "• " + m.optString("title"),
                            fontSize = 14.sp * fontScale,
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
            }
            val arc = digestsObj.optJSONArray("sitrep_arc") ?: JSONArray()
            if (arc.length() > 0) {
                Text(
                    text = stringResource(R.string.nightly_sitrep_arc_title),
                    fontSize = 16.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                val path = digestsObj.optJSONArray("crisis_path") ?: JSONArray()
                if (path.length() > 0) {
                    val parts = (0 until path.length()).map { path.optString(it) }
                    Text(
                        text = parts.joinToString(" ← "),
                        fontSize = 14.sp * fontScale,
                        fontFamily = EcoFontFamily,
                    )
                }
                for (i in 0 until arc.length()) {
                    val s = try {
                        arc.getJSONObject(i)
                    } catch (_: Exception) {
                        continue
                    }
                    Text(
                        text = "• " + s.optString("title") +
                            (s.optString("crisis_level").takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                        fontSize = 14.sp * fontScale,
                        fontFamily = EcoFontFamily,
                    )
                }
            }
        }
        // Component 4 — analysis + consensus matrix.
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.nightly_analysis_title),
            fontSize = 20.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        val analysisObj = jsonObj(ed.analysisJson)
        if (ed.analysisState == NightlyComponentStates.NONE_TONIGHT) {
            Text(
                text = stringResource(R.string.nightly_none_tonight),
                fontSize = 14.sp * fontScale,
                fontFamily = EcoFontFamily,
            )
        } else {
            val aItems = analysisObj.optJSONArray("items") ?: JSONArray()
            for (i in 0 until aItems.length()) {
                val a = try {
                    aItems.getJSONObject(i)
                } catch (_: Exception) {
                    continue
                }
                Text(
                    text = a.optString("title"),
                    fontSize = 15.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = a.optString("body"),
                    fontSize = 14.sp * fontScale,
                    fontFamily = EcoFontFamily,
                )
            }
            val matrix = analysisObj.optJSONObject("consensus_matrix")
            if (matrix != null) {
                for (key in listOf("agreed", "divergent", "verified")) {
                    val rows = matrix.optJSONArray(key) ?: JSONArray()
                    for (i in 0 until rows.length()) {
                        Text(
                            text = "$key: " + rows.optString(i),
                            fontSize = 13.sp * fontScale,
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
            }
        }
        // Component 5 — special issues: PAUSED, no content, no
        // count, ever, while frozen (spec §5.10). This line is
        // the whole component-5 surface.
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.nightly_special_paused),
            fontSize = 15.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        // Component 6 — corrections list.
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.nightly_corrections_title),
            fontSize = 20.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        if (ed.correctionsState == NightlyComponentStates.NONE_TONIGHT || correctionsArr.length() == 0) {
            Text(
                text = stringResource(R.string.nightly_no_corrections),
                fontSize = 14.sp * fontScale,
                fontFamily = EcoFontFamily,
            )
        } else {
            for (i in 0 until correctionsArr.length()) {
                val c = try {
                    correctionsArr.getJSONObject(i)
                } catch (_: Exception) {
                    continue
                }
                Text(
                    text = stringResource(
                        R.string.nightly_correction_line,
                        c.optString("note_fa"),
                        c.optInt("version_after", ed.version).toString().toPersianDigits(),
                        c.optInt("version_before", 1).toString().toPersianDigits(),
                    ),
                    fontSize = 14.sp * fontScale,
                    fontFamily = EcoFontFamily,
                )
            }
        }
        // Component 7 — archive entry + dossier links.
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.nightly_archive_entry),
            fontSize = 13.sp * fontScale,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val links = try {
            JSONArray(ed.dossierLinksJson)
        } catch (_: Exception) {
            JSONArray()
        }
        for (i in 0 until links.length()) {
            val link = links.optJSONObject(i) ?: continue
            val dossierId = link.optString("dossier_id")
            if (dossierId.isBlank()) continue
            val dossier = dossiers.firstOrNull { it.dossierId == dossierId }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenDossier(dossierId) }
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = dossier?.topicTitleFa ?: dossierId,
                    fontSize = 15.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                if (dossier != null && dossier.lastUpdatedTehran > ed.cutTehran) {
                    Text(
                        text = stringResource(R.string.nightly_dossier_updated_since),
                        fontSize = 12.sp * fontScale,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun DossierPageScreen(
    repo: EdgeRepository,
    dossierId: String,
    onOpenEdition: (String) -> Unit,
    onBack: () -> Unit,
    fontScale: Float = 1f,
) {
    var dossier by remember { mutableStateOf<com.ecojournal.app.data.DossierEntity?>(null) }
    LaunchedEffect(dossierId) {
        dossier = repo.nightlyDao.dossierById(dossierId)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Button(onClick = onBack) {
            Text(text = "بازگشت", fontFamily = EcoFontFamily)
        }
        Spacer(modifier = Modifier.height(12.dp))
        val d = dossier
        if (d == null) {
            Text(
                text = stringResource(R.string.nightly_not_kept_locally),
                fontSize = 15.sp * fontScale,
                fontFamily = EcoFontFamily,
            )
            return@Column
        }
        Text(
            text = d.topicTitleFa,
            fontSize = 24.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = d.dossierId,
            fontSize = 12.sp * fontScale,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = d.summary,
            fontSize = 15.sp * fontScale,
            fontFamily = EcoFontFamily,
        )
        Spacer(modifier = Modifier.height(12.dp))
        val editionsArr = try {
            JSONArray(d.editionIdsJson)
        } catch (_: Exception) {
            JSONArray()
        }
        for (i in 0 until editionsArr.length()) {
            val eid = editionsArr.optString(i)
            if (eid.isBlank()) continue
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.open_edition_desc, eid),
                        onClick = { onOpenEdition(eid) },
                    )
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = eid,
                    fontSize = 14.sp * fontScale,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
