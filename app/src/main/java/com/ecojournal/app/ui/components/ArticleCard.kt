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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.ArticleEntity
import com.ecojournal.app.data.DomainBars
import com.ecojournal.app.ui.theme.EcoDesignTokens
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

/**
 * Double rule used under the masthead and under a reader header.
 * v5 P2: thicknesses/gap now come from EcoDesignTokens.Rules
 * (2dp + 2dp gap + 1dp, plan §2) instead of literals.
 */
@Composable
fun NewspaperDoubleRule(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        HorizontalDivider(
            thickness = EcoDesignTokens.Rules.DoubleThick,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(EcoDesignTokens.Rules.DoubleGap))
        HorizontalDivider(
            thickness = EcoDesignTokens.Rules.DoubleThin,
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
 * with a heavier rule underneath. [isDuplicateTitle] flags a
 * repeated headline (P1-02) instead of showing two identical rows
 * with no distinction: the kind kicker already separates a short
 * from a full article, and this note separates same-kind repeats.
 * [fontScale] (M3) scales row and hero type with the Settings
 * slider, as the reader already did.
 */
// Cross-phase category bar (Yasin, 2026-10-25): energy-domain
// articles carry the YELLOW bar with the «انرژی» label on feed
// cards (and on the reader header, see ArticleScreen). War-room /
// red-alert items carry the RED bar via TypedCard instead — the
// two categories stay inside the same feed/tabs system.
//
// v4-H fix (V19): [typedFormatType] is the newest non-retracted
// typed format for this article's story (resolved by the caller
// from join_keys + typed items). When present, the article IS a
// red-alert item and carries RED with the format's own label —
// before this fix the card hard-coded the «انرژی» label for ANY
// bar, so a RED bar here would have been mislabelled. RED wins
// over YELLOW, exactly as in Phase 6's barFor.
@Composable
fun ArticleCard(
    article: ArticleEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHero: Boolean = false,
    isDuplicateTitle: Boolean = false,
    fontScale: Float = 1f,
    typedFormatType: String? = null,
) {
    val bar = DomainBars.forArticleWithTyped(article, typedFormatType)
    val barLabel = DomainBars.labelFor(bar, typedFormatType)
    if (isHero) {
        NewspaperHeroArticle(
            article = article,
            onClick = onClick,
            modifier = modifier,
            isDuplicateTitle = isDuplicateTitle,
            fontScale = fontScale,
            domainBar = bar,
            barLabel = barLabel,
        )
    } else {
        NewspaperArticleRow(
            article = article,
            onClick = onClick,
            modifier = modifier,
            isDuplicateTitle = isDuplicateTitle,
            fontScale = fontScale,
            domainBar = bar,
            barLabel = barLabel,
        )
    }
}

@Composable
private fun rowClick(
    article: ArticleEntity,
    onClick: () -> Unit,
): Modifier = Modifier
    .semantics { contentDescription = "مقاله: ${article.title}" }
    .clickable(
        role = Role.Button,
        onClickLabel = stringResource(R.string.article_open),
        onClick = onClick,
    )

@Composable
fun NewspaperHeroArticle(
    article: ArticleEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDuplicateTitle: Boolean = false,
    fontScale: Float = 1f,
    domainBar: com.ecojournal.app.data.DomainBar =
        com.ecojournal.app.data.DomainBar.NONE,
    barLabel: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .then(rowClick(article, onClick))
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        if (domainBar != com.ecojournal.app.data.DomainBar.NONE && barLabel != null) {
            DomainBarStrip(bar = domainBar, label = barLabel)
            Spacer(modifier = Modifier.height(6.dp))
        }
        NewspaperKicker(article = article)
        if (isDuplicateTitle) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.duplicate_title),
                fontSize = 12.sp,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = article.title,
            fontSize = 26.sp * fontScale,
            lineHeight = 34.sp * fontScale,
            fontFamily = EcoFontFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (article.summary.isNotBlank()) {
            // Hero summaries were untruncated (P2-09): a long hero
            // could dominate a whole page. Six lines keeps the
            // broadsheet hierarchy without hiding the section.
            Text(
                text = article.summary,
                fontSize = 16.sp * fontScale,
                lineHeight = 24.sp * fontScale,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
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
    isDuplicateTitle: Boolean = false,
    fontScale: Float = 1f,
    domainBar: com.ecojournal.app.data.DomainBar =
        com.ecojournal.app.data.DomainBar.NONE,
    barLabel: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .then(rowClick(article, onClick))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (domainBar != com.ecojournal.app.data.DomainBar.NONE && barLabel != null) {
            DomainBarStrip(bar = domainBar, label = barLabel)
            Spacer(modifier = Modifier.height(6.dp))
        }
        NewspaperKicker(article = article)
        if (isDuplicateTitle) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.duplicate_title),
                fontSize = 12.sp,
                fontFamily = EcoFontFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = article.title,
            fontSize = 18.sp * fontScale,
            lineHeight = 26.sp * fontScale,
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
                fontSize = 15.sp * fontScale,
                lineHeight = 22.sp * fontScale,
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
