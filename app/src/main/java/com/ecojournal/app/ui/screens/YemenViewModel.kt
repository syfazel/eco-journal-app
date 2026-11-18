package com.ecojournal.app.ui.screens

import androidx.lifecycle.ViewModel
import com.ecojournal.app.data.YemenAden
import com.ecojournal.app.data.YemenDoc
import com.ecojournal.app.data.YemenEvent
import com.ecojournal.app.data.YemenFrontline
import com.ecojournal.app.data.YemenMetaEntity
import com.ecojournal.app.data.YemenPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v5.2 retained Yemen holder — the same shockproof pattern as
 * FeedViewModel (audit §1.3 / A1). The Room flows collected by
 * YemenScreen start at emptyList() on every (re)composition and
 * can emit an empty list transiently. This holder survives
 * recomposition AND Activity recreation, and a list is REPLACED
 * only by a non-empty emission, NEVER cleared by an empty one.
 * There is deliberately no clear() — nothing in the UI may clear
 * a held list; the holder dies with the process, so after a
 * process kill the tab restores from Room + seed. Emptiness
 * decisions read the DISPLAY lists, never the raw flows. The
 * supplementary document (arrows, units, forces, claims, human
 * stats, sources, snapshots) is retained under the same rule:
 * replaced only when its places list is non-empty.
 */
class YemenViewModel : ViewModel() {

    private val _heldPlaces = MutableStateFlow<List<YemenPlace>>(emptyList())
    val heldPlaces: StateFlow<List<YemenPlace>> = _heldPlaces.asStateFlow()

    private val _heldEvents = MutableStateFlow<List<YemenEvent>>(emptyList())
    val heldEvents: StateFlow<List<YemenEvent>> = _heldEvents.asStateFlow()

    private val _heldFrontlines = MutableStateFlow<List<YemenFrontline>>(emptyList())
    val heldFrontlines: StateFlow<List<YemenFrontline>> = _heldFrontlines.asStateFlow()

    private val _heldAden = MutableStateFlow<YemenAden?>(null)
    val heldAden: StateFlow<YemenAden?> = _heldAden.asStateFlow()

    private val _heldDoc = MutableStateFlow<YemenDoc?>(null)
    val heldDoc: StateFlow<YemenDoc?> = _heldDoc.asStateFlow()

    private val _heldMeta = MutableStateFlow<YemenMetaEntity?>(null)
    val heldMeta: StateFlow<YemenMetaEntity?> = _heldMeta.asStateFlow()

    fun retainPlaces(list: List<YemenPlace>) {
        if (list.isNotEmpty()) _heldPlaces.value = list
    }

    fun retainEvents(list: List<YemenEvent>) {
        if (list.isNotEmpty()) _heldEvents.value = list
    }

    fun retainFrontlines(list: List<YemenFrontline>) {
        if (list.isNotEmpty()) _heldFrontlines.value = list
    }

    fun retainAden(aden: YemenAden?) {
        if (aden != null) _heldAden.value = aden
    }

    fun retainDoc(doc: YemenDoc?) {
        if (doc != null && doc.places.isNotEmpty()) _heldDoc.value = doc
    }

    fun retainMeta(meta: YemenMetaEntity?) {
        if (meta != null) _heldMeta.value = meta
    }
}
