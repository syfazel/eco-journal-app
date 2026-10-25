package com.ecojournal.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.data.ArticleEntity
import com.ecojournal.app.data.BookmarkEntity
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.ui.components.NewspaperByline
import com.ecojournal.app.ui.components.NewspaperDoubleRule
import com.ecojournal.app.ui.components.NewspaperKicker
import com.ecojournal.app.ui.theme.EcoFontFamily
import kotlinx.coroutines.launch

/**
 * Article reader, newspaper style. Headline, kicker, byline and
 * date block, a rule, then body paragraphs rendered verbatim
 * from the cached article; this screen never edits, summarises
 * or completes article text. Short cards show the "گزیده" label
 * in the kicker line.
 */
@Composable
fun ArticleScreen(
    repo: EdgeRepository,
    articleId: String,
    fontScale: Float,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var article by remember { mutableStateOf<ArticleEntity?>(null) }
    var bookmarked by remember { mutableStateOf(false) }

    LaunchedEffect(articleId) {
        article = repo.dao.byId(articleId)
        bookmarked = repo.dao.isBookmarked(articleId)
    }

    val a = article
    if (a == null) {
        Text(
            text = "مقاله یافت نشد. شاید هنوز همگام‌سازی نشده است.",
            fontFamily = EcoFontFamily,
            modifier = modifier.padding(16.dp),
        )
        return
    }
    val headlineSize = 28.sp * fontScale
    val baseBody = 18.sp * fontScale
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        NewspaperKicker(article = a)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = a.title,
            fontSize = headlineSize,
            lineHeight = headlineSize * 1.35f,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(10.dp))
        NewspaperByline(article = a)
        Spacer(modifier = Modifier.height(14.dp))
        NewspaperDoubleRule()
        Spacer(modifier = Modifier.height(16.dp))
        if (a.summary.isNotBlank()) {
            Text(
                text = a.summary,
                fontSize = baseBody,
                lineHeight = baseBody * 1.75f,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        for (block in a.bodyBlocks) {
            Text(
                text = block,
                fontSize = baseBody,
                lineHeight = baseBody * 1.75f,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    if (bookmarked) {
                        repo.dao.removeBookmark(a.id)
                        bookmarked = false
                    } else {
                        repo.dao.addBookmark(
                            BookmarkEntity(a.id, System.currentTimeMillis())
                        )
                        bookmarked = true
                    }
                }
            },
        ) {
            Text(
                text = if (bookmarked) "از ذخیره‌شده‌ها حذف شود" else "ذخیره شود",
                fontFamily = EcoFontFamily,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Original source link, opened in the user's own browser.
        // The URL comes verbatim from the source database; the app
        // never rewrites it. Hidden when the source record has no URL.
        a.originalUrl?.let { url ->
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                },
            ) {
                Text("مشاهدهٔ منبع اصلی", fontFamily = EcoFontFamily)
            }
        }
    }
}
