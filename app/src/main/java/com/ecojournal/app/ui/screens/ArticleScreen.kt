package com.ecojournal.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ecojournal.app.R
import com.ecojournal.app.data.ArticleEntity
import com.ecojournal.app.data.BookmarkEntity
import com.ecojournal.app.data.ChannelItemEntity
import com.ecojournal.app.data.DomainBars
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.Settings
import com.ecojournal.app.data.SettingsStore
import com.ecojournal.app.data.StoryArc
import com.ecojournal.app.ui.components.DomainBarStrip
import com.ecojournal.app.ui.components.NewspaperByline
import com.ecojournal.app.ui.components.NewspaperDoubleRule
import com.ecojournal.app.ui.components.NewspaperKicker
import com.ecojournal.app.ui.components.ReadingRuler
import com.ecojournal.app.ui.components.StoryArcStrip
import com.ecojournal.app.ui.theme.EcoDesignTokens
import com.ecojournal.app.ui.theme.EcoFontFamily
import kotlinx.coroutines.launch

/**
 * Article reader, newspaper style. Headline, kicker, byline and
 * date block, a rule, then body paragraphs rendered verbatim
 * from the cached article; this screen never edits, summarises
 * or completes article text. Short cards show the "گزیده" label
 * in the kicker line.
 *
 * M3 (P1-05, P1-07, P1-08, P1-14): a real top bar with a back
 * affordance (the bottom bar is hidden on this route by
 * MainActivity), the bookmark toggle at the TOP of the article
 * instead of after the whole body, a not-found page with back
 * and retry actions (retry falls back to repo.fetchOne, which
 * was previously never called from here), key-points and
 * why-it-matters blocks when the cached article carries them,
 * no summary/body duplication for short cards, and a guarded
 * source button (scheme check + chooser + try/catch).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(
    repo: EdgeRepository,
    articleId: String,
    fontScale: Float,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    // v4-H H2: typed arc nodes open their channel reader.
    onOpenChannel: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var article by remember { mutableStateOf<ArticleEntity?>(null) }
    var bookmarked by remember { mutableStateOf(false) }
    var loadAttempted by remember { mutableStateOf(false) }
    var sourceError by remember { mutableStateOf(false) }
    // v4-H reader settings (ruler / calm) + the story's typed
    // items for the RED bar path and the arc strip.
    val readerSettings by SettingsStore(context).settings
        .collectAsState(initial = Settings())
    var storyTyped by remember { mutableStateOf<List<ChannelItemEntity>>(emptyList()) }

    // articleId is an appKey (M1). The verbatim source id is
    // accepted as a fallback for older deep links only. If the
    // article is not cached at all, fall back to a single edge
    // fetch (P1-07) instead of stopping at "not found": offline
    // this returns false immediately and the not-found page stays.
    LaunchedEffect(articleId) {
        var found = repo.dao.byAppKey(articleId) ?: repo.dao.byId(articleId)
        if (found == null) {
            repo.fetchOne(articleId)
            found = repo.dao.byAppKey(articleId) ?: repo.dao.byId(articleId)
        }
        article = found
        val key = found?.appKey ?: articleId
        bookmarked = repo.dao.isBookmarked(key)
        // v4-H: typed items sharing this article's story_id
        // (join_keys). The query already excludes retracted rows.
        val joinKey = found?.let { repo.dao.joinKeyByAppKey(it.appKey) }
        storyTyped = joinKey?.storyId?.let { repo.channelDao.channelByStory(it) }
            ?: emptyList()
        loadAttempted = true
    }

    val toggleBookmark = {
        scope.launch {
            val a = article ?: return@launch
            if (bookmarked) {
                repo.dao.removeBookmark(a.appKey)
                bookmarked = false
            } else {
                repo.dao.addBookmark(
                    BookmarkEntity(a.appKey, System.currentTimeMillis())
                )
                bookmarked = true
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = article?.categoryFa ?: stringResource(R.string.app_name),
                        fontFamily = EcoFontFamily,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.article_back),
                        )
                    }
                },
                actions = {
                    if (article != null) {
                        IconButton(onClick = { toggleBookmark() }) {
                            Icon(
                                imageVector = if (bookmarked) {
                                    Icons.Filled.Bookmark
                                } else {
                                    Icons.Filled.BookmarkBorder
                                },
                                contentDescription = if (bookmarked) {
                                    stringResource(R.string.article_bookmarked_state)
                                } else {
                                    stringResource(R.string.article_not_bookmarked_state)
                                },
                            )
                        }
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { inner ->
        val a = article
        if (a == null) {
            // Not-found page (P1-07): a dead end before M3. Back
            // returns to the feed; retry re-runs the edge fetch
            // in case a sync landed meanwhile. Until the first
            // load attempt finishes, nothing is shown yet, so a
            // slow cache read is never mistaken for "not found".
            if (loadAttempted) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner)
                        .padding(EcoDesignTokens.Spacing.PageMargin),
                ) {
                    Text(
                        text = stringResource(R.string.article_not_found),
                        fontFamily = EcoFontFamily,
                    )
                    Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.PageMargin))
                    Button(onClick = onBack) {
                        Text(
                            stringResource(R.string.article_back),
                            fontFamily = EcoFontFamily,
                        )
                    }
                    Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                loadAttempted = false
                                repo.fetchOne(articleId)
                                article = repo.dao.byAppKey(articleId)
                                    ?: repo.dao.byId(articleId)
                                loadAttempted = true
                            }
                        },
                    ) {
                        Text(
                            stringResource(R.string.article_not_found_retry),
                            fontFamily = EcoFontFamily,
                        )
                    }
                }
            }
            return@Scaffold
        }
        // v5 P3: reader scale is the P2 token scale, not a new
        // size — headline 28sp at 1.35x line, body 18sp at 1.75x
        // (calm 2.0x). Token line heights are 38/32/36sp, so the
        // pre-P3 products (37.8/31.5/36) round to the token by
        // at most 0.5sp; no resize is intended.
        val headlineSize = EcoDesignTokens.Type.ReaderHeadline * fontScale
        val baseBody = EcoDesignTokens.Type.ReaderBody * fontScale
        // v4-H H1 calm mode: taller line height, no overlays, no
        // arc navigation chrome during reading. Attribution is
        // never removed: the byline (verbatim source) always stays.
        val bodyLine = if (readerSettings.calmMode) {
            EcoDesignTokens.LineHeight.ReaderBodyCalm * fontScale
        } else {
            EcoDesignTokens.LineHeight.ReaderBody * fontScale
        }
        // v4-H: Box so the draggable reading ruler can overlay
        // the scrolling column without reflowing any text.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(EcoDesignTokens.Spacing.PageMargin),
        ) {
            // Cross-phase category bar on the reader header.
            // v4-H RED path: an article whose story has a typed
            // item carries RED with the format's own label; energy
            // without a typed item carries YELLOW + «انرژی».
            val readerTypedFormat = storyTyped
                .maxByOrNull { it.publishedAtEpoch }?.formatType
            val readerBar = DomainBars.forArticleWithTyped(a, readerTypedFormat)
            val readerBarLabel = DomainBars.labelFor(readerBar, readerTypedFormat)
            if (readerBar != com.ecojournal.app.data.DomainBar.NONE &&
                readerBarLabel != null
            ) {
                DomainBarStrip(bar = readerBar, label = readerBarLabel)
                Spacer(modifier = Modifier.height(6.dp))
            }
            NewspaperKicker(article = a)
            Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            Text(
                text = a.title,
                fontSize = headlineSize,
                lineHeight = EcoDesignTokens.LineHeight.ReaderHeadline * fontScale,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(10.dp))
            NewspaperByline(article = a)
            Spacer(modifier = Modifier.height(14.dp))
            NewspaperDoubleRule()
            Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.PageMargin))
            // Bookmark toggle at the top (P1-08): before M3 the
            // only toggle sat after the entire body.
            val bookmarkStateText = if (bookmarked) {
                stringResource(R.string.article_bookmarked_state)
            } else {
                stringResource(R.string.article_not_bookmarked_state)
            }
            OutlinedButton(
                onClick = { toggleBookmark() },
                modifier = Modifier.semantics {
                    contentDescription = bookmarkStateText
                },
            ) {
                Text(
                    text = if (bookmarked) {
                        stringResource(R.string.article_unbookmark)
                    } else {
                        stringResource(R.string.article_bookmark)
                    },
                    fontFamily = EcoFontFamily,
                )
            }
            Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.PageMargin))
            // v4-H H2 arc strip: versions of this story, oldest
            // first. Hidden in calm mode; absent entirely for a
            // single-version story (StoryArc returns empty).
            if (!readerSettings.calmMode) {
                val arcNodes = remember(a, storyTyped) {
                    StoryArc.nodes(a, storyTyped)
                }
                if (arcNodes.size >= 2) {
                    StoryArcStrip(
                        nodes = arcNodes,
                        onOpenChannel = onOpenChannel,
                        fontScale = fontScale,
                    )
                    Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.PageMargin))
                }
            }
            // Short cards (P1-06): the stored summary is a
            // truncation of the same full text held in bodyBlocks,
            // so showing both repeats the first ~280 characters.
            // A short shows its body only; a full article shows
            // its summary as the standfirst, as before.
            if (a.kind != "short" && a.summary.isNotBlank()) {
                Text(
                    text = a.summary,
                    fontSize = baseBody,
                    lineHeight = bodyLine,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.PageMargin))
            }
            // Key points (P1-05): present in most full articles,
            // parsed and stored, but never shown before M3.
            val points = repo.entityToArticle(a).points.filter { it.isNotBlank() }
            if (points.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.key_points),
                    fontSize = baseBody,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
                for (point in points) {
                    Text(
                        text = "• $point",
                        fontSize = baseBody,
                        lineHeight = bodyLine,
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
                }
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
            }
            for (block in a.bodyBlocks) {
                Text(
                    text = block,
                    fontSize = baseBody,
                    lineHeight = bodyLine,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(14.dp))
            }
            // Why it matters (P1-05): shown when present, after
            // the body, as its own labelled block.
            if (!a.whyMatters.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.why_matters),
                    fontSize = baseBody,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
                Text(
                    text = a.whyMatters,
                    fontSize = baseBody,
                    lineHeight = bodyLine,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(14.dp))
            }
            // v5 P3 body-empty state (plan §1 R1): a cached
            // article with no body used to render as a
            // near-blank page (or, with a summary, as a page
            // that silently ends after the standfirst). The
            // test is the body itself only: a summary, key
            // points, or why-it-matters block does not hide
            // a missing body. The state names what happened
            // and offers exactly one next step — re-fetch
            // from the edge (the not-found retry path). The
            // top-bar back button is the way out, not a
            // second step on this line.
            val bodyEmpty = a.bodyBlocks.none { it.isNotBlank() }
            if (bodyEmpty) {
                Text(
                    text = stringResource(R.string.v5_reader_body_empty),
                    fontSize = baseBody,
                    lineHeight = bodyLine,
                    fontFamily = EcoFontFamily,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            loadAttempted = false
                            repo.fetchOne(articleId)
                            article = repo.dao.byAppKey(articleId)
                                ?: repo.dao.byId(articleId)
                            loadAttempted = true
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.article_not_found_retry),
                        fontFamily = EcoFontFamily,
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
            }
            // Original source link, opened in the user's own browser.
            // The URL comes verbatim from the source database; the app
            // never rewrites it. Hidden when the source record has no URL.
            // Guarded (P1-14): http(s) only, via a chooser, inside a
            // try/catch — an unhandled scheme or a missing browser
            // used to crash the app from a tap.
            a.originalUrl?.let { url ->
                Button(
                    onClick = {
                        sourceError = false
                        val uri = try {
                            Uri.parse(url)
                        } catch (_: Exception) {
                            null
                        }
                        if (uri == null || (uri.scheme != "http" && uri.scheme != "https")) {
                            sourceError = true
                        } else {
                            try {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    uri,
                                )
                                context.startActivity(
                                    Intent.createChooser(intent, null)
                                )
                            } catch (_: ActivityNotFoundException) {
                                sourceError = true
                            } catch (_: Exception) {
                                sourceError = true
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.open_source), fontFamily = EcoFontFamily)
                }
                if (sourceError) {
                    Spacer(modifier = Modifier.height(EcoDesignTokens.Spacing.ElementGap))
                    Text(
                        text = stringResource(R.string.open_source_failed),
                        fontFamily = EcoFontFamily,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
            // v4-H H1 ruler: a fixed guide band over the scrolling
            // body, draggable vertically. Hidden in calm mode
            // (calm = body only, no overlays).
            if (readerSettings.readingRuler && !readerSettings.calmMode) {
                ReadingRuler(fontScale = fontScale)
            }
        }
    }
}
