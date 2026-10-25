package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ecojournal.app.R
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.ui.components.ArticleCard
import com.ecojournal.app.ui.theme.EcoFontFamily
import kotlinx.coroutines.launch

/** Category browser. Category labels come from the edge data itself. */
@Composable
fun CategoriesScreen(
    repo: EdgeRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reactive (M2): categories and their articles update when the
    // cache changes, instead of a single once-only read at open.
    val all by repo.dao.allFlow().collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<String?>(null) }
    val categories = all.map { it.category to it.categoryFa }.distinct().sortedBy { it.second }
    val shown = if (selected == null) all else all.filter { it.category == selected }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "دسته‌ها",
            fontFamily = EcoFontFamily,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                FilterChip(
                    selected = selected == null,
                    onClick = { selected = null },
                    label = { Text("همه", fontFamily = EcoFontFamily) },
                )
            }
            items(categories) { (key, label) ->
                FilterChip(
                    selected = selected == key,
                    onClick = { selected = key },
                    label = { Text(label, fontFamily = EcoFontFamily) },
                )
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(shown, key = { it.appKey }) { a ->
                ArticleCard(article = a, onClick = { onOpen(a.appKey) })
            }
        }
    }
}

/**
 * Saved articles (bookmarks), offline.
 *
 * M3: orphans are no longer silently dropped. A bookmark whose
 * article left the cache (retracted, or a stale row from an older
 * build) appears under a "no longer available" heading with a
 * prune action, and a retracted bookmark still shows its article
 * row flagged by status via the card (the bookmarked query does
 * not filter by status, by design).
 */
@Composable
fun BookmarksScreen(
    repo: EdgeRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    // Reactive (M2): bookmarks update when the cache changes, and
    // open by appKey (M1), matching how ArticleScreen stores them.
    val saved by repo.dao.bookmarkedFlow().collectAsState(initial = emptyList())
    val orphans by repo.dao.orphanBookmarksFlow().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    if (saved.isEmpty() && orphans.isEmpty()) {
        Text(
            text = stringResource(R.string.bookmarks_empty),
            fontFamily = EcoFontFamily,
            modifier = modifier.padding(16.dp),
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            items(saved, key = { it.appKey }) { a ->
                ArticleCard(
                    article = a,
                    onClick = { onOpen(a.appKey) },
                    fontScale = fontScale,
                )
            }
            if (orphans.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.bookmarks_unavailable_title),
                        fontFamily = EcoFontFamily,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                }
                items(orphans, key = { it.appKey }) { orphan ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.bookmark_unavailable_row),
                            fontFamily = EcoFontFamily,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    repo.dao.removeBookmark(orphan.appKey)
                                }
                            },
                        ) {
                            Text(
                                stringResource(R.string.bookmark_prune),
                                fontFamily = EcoFontFamily,
                            )
                        }
                    }
                }
            }
        }
    }
}
