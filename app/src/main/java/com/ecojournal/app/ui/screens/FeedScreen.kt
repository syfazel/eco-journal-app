package com.ecojournal.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ecojournal.app.data.ArticleEntity
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.SyncResult
import com.ecojournal.app.ui.components.ArticleCard
import com.ecojournal.app.ui.theme.EcoFontFamily
import kotlinx.coroutines.launch

/**
 * Feed + offline search. Search runs against the Room cache only
 * (titles and summaries) — it never invents results and never needs
 * the network when articles are already cached.
 */
@Composable
fun FeedScreen(
    repo: EdgeRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var articles by remember { mutableStateOf<List<ArticleEntity>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var syncState by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    fun load() {
        scope.launch {
            loading = true
            articles = if (query.isBlank()) {
                repo.dao.all()
            } else {
                repo.dao.search(query.trim())
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("جست‌وجو در مقالات ذخیره‌شده", fontFamily = EcoFontFamily) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        )
        Button(
            onClick = {
                scope.launch {
                    syncState = "در حال همگام‌سازی…"
                    syncState = when (val r = repo.sync()) {
                        is SyncResult.Ok ->
                            if (r.newArticleIds.isEmpty()) {
                                "به‌روز است (${r.totalCached} مقاله در حافظه)."
                            } else {
                                "${r.newArticleIds.size} مقالهٔ تازه دریافت شد."
                            }
                        is SyncResult.NotConfigured ->
                            "لبهٔ انتشار هنوز تنظیم نشده است (TODO در EdgeConfig). " +
                                "فقط مقالات کش‌شده نمایش داده می‌شوند."
                        is SyncResult.Failed -> "همگام‌سازی ناموفق بود: ${r.reason}"
                    }
                    load()
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text("همگام‌سازی", fontFamily = EcoFontFamily)
        }
        if (syncState.isNotBlank()) {
            Text(
                text = syncState,
                fontFamily = EcoFontFamily,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        } else if (articles.isEmpty()) {
            Text(
                text = "هنوز مقاله‌ای در حافظه نیست. دکمهٔ همگام‌سازی را بزنید " +
                    "تا پس از تنظیم لبهٔ انتشار، مقالات دریافت شوند.",
                fontFamily = EcoFontFamily,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(articles, key = { it.id }) { a ->
                    ArticleCard(article = a, onClick = { onOpen(a.id) })
                }
            }
        }
    }
}
