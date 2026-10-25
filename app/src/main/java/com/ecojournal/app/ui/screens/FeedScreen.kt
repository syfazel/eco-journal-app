package com.ecojournal.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.data.ArticleEntity
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.SyncResult
import com.ecojournal.app.ui.components.ArticleCard
import com.ecojournal.app.ui.components.NewspaperDoubleRule
import com.ecojournal.app.ui.theme.EcoFontFamily
import kotlinx.coroutines.launch

private data class Section(val key: String?, val label: String)

/**
 * Home front page, broadsheet style. Masthead first, then a
 * ScrollableTabRow of sections synced to a HorizontalPager.
 *
 * Sections are derived only from what is actually in the Room
 * cache: "همه" plus every distinct category present, in the
 * order it first appears in the cache (publishedAt DESC).
 * No section, label or article is invented here.
 *
 * Search still runs offline over the Room cache only (titles
 * and summaries). While a search query is present, the pager
 * is replaced by the flat search results list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(
    repo: EdgeRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<ArticleEntity>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ArticleEntity>?>(null) }
    var syncState by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    suspend fun loadAll() {
        loading = true
        all = repo.dao.all()
        loading = false
    }

    LaunchedEffect(Unit) { loadAll() }

    // Offline search: re-run against the cache whenever the query changes.
    LaunchedEffect(query) {
        searchResults = if (query.isBlank()) {
            null
        } else {
            repo.dao.search(query.trim())
        }
    }

    val sections = remember(all) {
        val seen = linkedSetOf<String>()
        val ordered = mutableListOf<Section>()
        for (a in all) {
            if (seen.add(a.category)) {
                ordered.add(Section(a.category, a.categoryFa))
            }
        }
        listOf(Section(null, "همه")) + ordered
    }
    val pagerState = rememberPagerState(pageCount = { sections.size })

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Masthead: ECO JOURNAL only. No other publication name appears.
        Text(
            text = "ECO JOURNAL",
            fontSize = 32.sp,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 10.dp),
        )
        NewspaperDoubleRule(
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("جست‌وجو در مقالات ذخیره‌شده", fontFamily = EcoFontFamily) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
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
                        loadAll()
                    }
                },
            ) {
                Text("همگام‌سازی", fontFamily = EcoFontFamily)
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (syncState.isNotBlank()) {
                Text(
                    text = syncState,
                    fontFamily = EcoFontFamily,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        val results = searchResults
        if (results != null) {
            // Search mode: flat newspaper rows, no pager.
            if (results.isEmpty()) {
                Text(
                    text = "در مقالات ذخیره‌شده چیزی برای این جست‌وجو یافت نشد.",
                    fontFamily = EcoFontFamily,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(results, key = { _, a -> a.id }) { _, a ->
                        ArticleCard(article = a, onClick = { onOpen(a.id) })
                    }
                }
            }
        } else if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        } else if (all.isEmpty()) {
            Text(
                text = "هنوز مقاله‌ای در حافظه نیست. دکمهٔ همگام‌سازی را بزنید " +
                    "تا پس از تنظیم لبهٔ انتشار، مقالات دریافت شوند.",
                fontFamily = EcoFontFamily,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            FrontPageTabs(
                sections = sections,
                pagerState = pagerState,
                all = all,
                onOpen = onOpen,
                onTabClick = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrontPageTabs(
    sections: List<Section>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    all: List<ArticleEntity>,
    onOpen: (String) -> Unit,
    onTabClick: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 16.dp,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            sections.forEachIndexed { index, section ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { onTabClick(index) },
                    text = {
                        Text(
                            text = section.label,
                            fontFamily = EcoFontFamily,
                            fontWeight = if (pagerState.currentPage == index) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                        )
                    },
                )
            }
        }
        // Pager keeps neighbouring pages composed so swiping stays smooth.
        HorizontalPager(
            state = pagerState,
            beyondBoundsPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val section = sections[page]
            val shown = if (section.key == null) {
                all
            } else {
                all.filter { it.category == section.key }
            }
            if (shown.isEmpty()) {
                Text(
                    text = "در این بخش هنوز مقاله‌ای در حافظه نیست.",
                    fontFamily = EcoFontFamily,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { _, a -> a.id }) { index, a ->
                        ArticleCard(
                            article = a,
                            onClick = { onOpen(a.id) },
                            isHero = index == 0,
                        )
                    }
                }
            }
        }
    }
}
