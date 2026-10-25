package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.ui.components.ArticleCard
import com.ecojournal.app.ui.theme.EcoFontFamily

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

/** Saved articles (bookmarks), offline. */
@Composable
fun BookmarksScreen(
    repo: EdgeRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reactive (M2): bookmarks update when the cache changes, and
    // open by appKey (M1), matching how ArticleScreen stores them.
    val saved by repo.dao.bookmarkedFlow().collectAsState(initial = emptyList())
    if (saved.isEmpty()) {
        Text(
            text = "هنوز چیزی ذخیره نکرده‌اید.",
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
                ArticleCard(article = a, onClick = { onOpen(a.appKey) })
            }
        }
    }
}
