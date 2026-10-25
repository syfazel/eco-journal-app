package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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

/**
 * Saved articles (bookmarks), offline.
 *
 * M6: moved here verbatim from the deleted CategoriesScreen.kt so
 * deleting the orphaned category browser could not lose it. It
 * remains reachable from the bookmarks bottom-nav tab (MainActivity
 * "bookmarks" route) exactly as before.
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
