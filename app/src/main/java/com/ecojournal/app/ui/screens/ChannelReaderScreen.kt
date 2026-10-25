package com.ecojournal.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecojournal.app.R
import com.ecojournal.app.data.BookmarkEntity
import com.ecojournal.app.data.ChannelItemEntity
import com.ecojournal.app.data.ChannelLists
import com.ecojournal.app.data.EdgeRepository
import com.ecojournal.app.data.Jalali
import com.ecojournal.app.ui.components.TypedCard
import com.ecojournal.app.ui.theme.EcoFontFamily
import kotlinx.coroutines.launch

// Typed item reader (spec 2.5, 3). The card itself carries the
// cross-phase RED category bar in its header (see TypedCard),
// so the reader header bar rule is satisfied by the card header,
// not by a second strip. Below the card, the story timeline
// renders all versions of the story chronological (oldest first),
// with the latest version pinned at the top as "latest update".
// A notification tap for a retracted item lands here with no
// item, and shows the honest line — never a stale cached body.

@Composable
fun ChannelReaderScreen(
    repo: EdgeRepository,
    channelId: String,
    onOpenChannel: (String) -> Unit,
    onOpenMarket: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
) {
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<ChannelItemEntity?>(null) }
    var timeline by remember { mutableStateOf<List<ChannelItemEntity>>(emptyList()) }
    var bookmarked by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(channelId) {
        val found = repo.channelDao.channelByAppKey(channelId)
        item = found?.takeIf { it.status != "retracted" }
        bookmarked = repo.dao.isBookmarked(channelId)
        val storyId = found?.storyId
        timeline = if (storyId != null) {
            ChannelLists.storyTimeline(
                repo.channelDao.channelByStory(storyId)
                    .filter { it.status != "retracted" }
            )
        } else {
            emptyList()
        }
        loaded = true
    }

    fun openStory(storyId: String) {
        scope.launch {
            val versions = repo.channelDao.channelByStory(storyId)
            val latest = versions.filter { it.status != "retracted" }
                .maxByOrNull { it.publishedAtEpoch }
            if (latest != null) onOpenChannel(latest.appKey)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (!loaded) {
            return@Column
        }
        val current = item
        if (current == null) {
            Text(
                text = stringResource(R.string.channel_not_found),
                fontFamily = EcoFontFamily,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack) {
                Text(text = stringResource(R.string.article_back), fontFamily = EcoFontFamily)
            }
            return@Column
        }
        OutlinedButton(onClick = {
            scope.launch {
                if (bookmarked) {
                    repo.dao.removeBookmark(current.appKey)
                    bookmarked = false
                } else {
                    repo.dao.addBookmark(
                        BookmarkEntity(current.appKey, System.currentTimeMillis())
                    )
                    bookmarked = true
                }
            }
        }) {
            Text(
                text = stringResource(
                    if (bookmarked) R.string.article_unbookmark else R.string.article_bookmark
                ),
                fontFamily = EcoFontFamily,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TypedCard(
            item = current,
            onClick = {},
            preview = false,
            hasTimeline = timeline.size > 1,
            onOpenStory = { storyId -> openStory(storyId) },
            onOpenMarket = onOpenMarket,
            fontScale = fontScale,
        )
        if (timeline.size > 1) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.channel_timeline_title),
                fontSize = 20.sp * fontScale,
                fontFamily = EcoFontFamily,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val latest = timeline.maxByOrNull { it.publishedAtEpoch }
            if (latest != null && latest.appKey != current.appKey) {
                Text(
                    text = stringResource(R.string.channel_latest_update),
                    fontSize = 13.sp,
                    fontFamily = EcoFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TimelineRow(item = latest, onOpen = { onOpenChannel(latest.appKey) }, fontScale = fontScale)
            }
            for (version in timeline) {
                TimelineRow(item = version, onOpen = { onOpenChannel(version.appKey) }, fontScale = fontScale)
            }
        }
    }
}

@Composable
private fun TimelineRow(
    item: ChannelItemEntity,
    onOpen: () -> Unit,
    fontScale: Float,
) {
    Row(
        modifier = Modifier
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.open_version_desc, item.headline),
                onClick = { onOpen() },
            )
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = Jalali.formatShamsiDateTime(item.publishedAtEpoch),
            fontSize = 13.sp,
            fontFamily = EcoFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            text = item.headline,
            fontSize = 15.sp * fontScale,
            fontFamily = EcoFontFamily,
        )
    }
}
