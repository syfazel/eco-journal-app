package com.ecojournal.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.TickerTopics

/**
 * v4-H masthead: script wordmark + CNN-style ticker, laid out
 * TOGETHER (v4 plan V4-H order), never stacked independently.
 *
 * Wordmark: SMALL English script "ECO JOURNAL", like the blog's
 * logo but small. Font honesty: no Blacksword is shipped (its
 * licence is personal-use-only and was never cleared for app
 * use), and no bundled OFL script font file exists in this repo
 * yet — the wordmark renders in the platform cursive family as
 * the script lookalike. A bundled OFL script font can replace
 * [ScriptFontFamily] without touching any call site; until then
 * the report must not claim a named OFL font is shipped.
 *
 * Ticker: a red, moving bar directly under the app name carrying
 * live war-room topics (see TickerTopics for the inclusion rule).
 * It is a masthead addition only: it does NOT replace the V10
 * category bars on cards, and it never shows held, feed-only,
 * or retracted items. Empty state is an honest static line.
 * TalkBack hears the topics once as a single announcement, not
 * the moving text — animation is never the only carrier.
 */
val ScriptFontFamily: FontFamily = FontFamily.Cursive

@Composable
fun ScriptWordmark(
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.app_name),
        fontSize = 22.sp * fontScale,
        fontFamily = ScriptFontFamily,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WarRoomTicker(
    topics: List<String>,
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val text = TickerTopics.marqueeText(topics)
    val desc = if (topics.isEmpty()) {
        stringResource(R.string.ticker_empty)
    } else {
        stringResource(R.string.ticker_desc, text)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFC62828))
            .semantics { contentDescription = desc },
    ) {
        if (topics.isEmpty()) {
            Text(
                text = stringResource(R.string.ticker_empty),
                fontSize = 13.sp * fontScale,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else {
            Text(
                text = text,
                fontSize = 13.sp * fontScale,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        velocity = 40.dp,
                    ),
            )
        }
    }
}

/** Wordmark + ticker + double rule, in that order, as one unit. */
@Composable
fun Masthead(
    topics: List<String>,
    tickerEnabled: Boolean,
    fontScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ScriptWordmark(fontScale = fontScale)
        if (tickerEnabled) {
            WarRoomTicker(topics = topics, fontScale = fontScale)
        }
        NewspaperDoubleRule(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
