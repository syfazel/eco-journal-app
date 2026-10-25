package com.ecojournal.app.ui.screens

import androidx.lifecycle.ViewModel
import com.ecojournal.app.data.ArticleEntity
import com.ecojournal.app.data.ChannelItemEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v5.1 shockproof — retained feed holder (single source of truth
 * for what the feed shows).
 *
 * The Room flows collected by FeedScreen start at emptyList() on
 * every (re)composition and can emit an empty list transiently
 * (a sync pass, a re-created Activity, a transient read). A plain
 * `remember` list would die with the composition and a raw Flow
 * value would flip a populated feed to empty on any such
 * emission. This holder survives recomposition AND Activity
 * recreation (it is a ViewModel, scoped to the feed destination),
 * and it is append-only in one direction: a list is REPLACED
 * only by a non-empty emission, NEVER cleared by an empty one.
 * There is deliberately no clear() — nothing in the UI may clear
 * a held list; the holder dies with the process, so after a
 * process kill / restart the feed restores from Room + seed
 * (the holder starts empty on a cold start and the first Room
 * emission repopulates it). A populated feed is therefore
 * impossible to flip back to empty inside one process lifetime,
 * while a genuinely empty database still shows the empty state
 * (held empty AND Room empty).
 *
 * Holds articles, visible typed items and instant (rail) items
 * under the same rule, so the rail and the format filter cannot
 * flip independently of the article list either. No sync, no
 * invite logic, no Room access lives here — the composable
 * passes in the flows' emissions; this class only retains them.
 */
class FeedViewModel : ViewModel() {

    private val _heldArticles = MutableStateFlow<List<ArticleEntity>>(emptyList())
    val heldArticles: StateFlow<List<ArticleEntity>> = _heldArticles.asStateFlow()

    private val _heldTyped = MutableStateFlow<List<ChannelItemEntity>>(emptyList())
    val heldTyped: StateFlow<List<ChannelItemEntity>> = _heldTyped.asStateFlow()

    private val _heldInstant = MutableStateFlow<List<ChannelItemEntity>>(emptyList())
    val heldInstant: StateFlow<List<ChannelItemEntity>> = _heldInstant.asStateFlow()

    /** Replace the held articles only when [list] is non-empty. */
    fun retainArticles(list: List<ArticleEntity>) {
        if (list.isNotEmpty()) _heldArticles.value = list
    }

    /** Replace the held typed items only when [list] is non-empty. */
    fun retainTyped(list: List<ChannelItemEntity>) {
        if (list.isNotEmpty()) _heldTyped.value = list
    }

    /** Replace the held instant items only when [list] is non-empty. */
    fun retainInstant(list: List<ChannelItemEntity>) {
        if (list.isNotEmpty()) _heldInstant.value = list
    }
}
