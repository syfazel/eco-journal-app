package com.ecojournal.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.data.ArticleEntity
import com.ecojournal.app.ui.theme.EcoFontFamily

/** Thin light-grey rule between newspaper rows. */
@Composable
fun NewspaperRule(modifier: Modifier = Modifier) {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = modifier,
    )
}

/** Double rule used under the masthead and under a reader header. */
@Composable
fun NewspaperDoubleRule(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        HorizontalDivider(
            thickness = 2.dp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(2.dp))
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Kicker line: category in bold navy, with the "گزیده" label for short cards. */
@Composable
fun NewspaperKicker(article: ArticleEntity, modifier: Modifier = Modifier) {
    val text = if (article.kind == "short") {
        "گزیده • ${article.categoryFa}"
    } else {
        article.categoryFa
    }
    Text(
        text = text,
        fontSize = 12.sp,
        fontFamily = EcoFontFamily,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** Byline: verbatim source from the cache plus the cached date, if present. */
@Composable
fun NewspaperByline(article: ArticleEntity, modifier: Modifier = Modifier) {
    val date = article.publishedAt
    val text = if (date.isNullOrBlank()) article.source else "${article.source} • $date"
    Text(
        text = text,
        fontSize = 12.sp,
        fontFamily = EcoFontFamily,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * Newspaper article row. No card, no shadow, no elevation: flat
 * paper background, bold ink headline, summary and byline.
 * [isHero] renders the first article on a section page larger,
 * with a heavier rule underneath.
 */
@Composable
fun ArticleCard(
    article: ArticleEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHero: Boolean = false,
) {
    if (isHero) {
        NewspaperHeroArticle(article = article, onClick = onClick, modifier = modifier)
    } else {
        NewspaperArticleRow(article = article, onClick = onClick, modifier = modifier)
    }
}

@Composable
fun NewspaperHeroArticle(
    article: ArticleEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        NewspaperKicker(article = article)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = article.title,
            fontSize = 26.sp,
            lineHeight = 34.sp,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (article.summary.isNotBlank()) {
            Text(
                text = article.summary,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        NewspaperByline(article = article)
        Spacer(modifier = Modifier.height(14.dp))
        NewspaperDoubleRule()
    }
}

@Composable
fun NewspaperArticleRow(
    article: ArticleEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        NewspaperKicker(article = article)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = article.title,
            fontSize = 18.sp,
            lineHeight = 26.sp,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (article.summary.isNotBlank()) {
            Text(
                text = article.summary,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        NewspaperByline(article = article)
        Spacer(modifier = Modifier.height(12.dp))
        NewspaperRule()
    }
}
