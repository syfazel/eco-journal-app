package com.ecojournal.app.data

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Yemen tab domain (v5.2, audit §4). Parsed from edge yemen.json
 * (schema_version 1) and from the bundled seed copy of the same
 * file. Every value comes from the dashboard v5 snapshot — nothing
 * is invented here, and human-stats values stay STRINGS, never
 * numbers. Nothing claim-only is ever labelled confirmed: the
 * three claim layers (confirmed / contested / houthi_only) travel
 * separately and the UI renders each under its own layer label.
 */

// ---- Identity (audit §4.2, byte-identical to edge/generate_yemen.py) ----

object YemenKeys {
    private fun sha20(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    /** Place: "y-" + first 20 hex of SHA-256(name_fa + "|" + governorate_fa). */
    fun placeAppKey(nameFa: String, governorateFa: String): String =
        "y-" + sha20(nameFa + "|" + governorateFa)

    /** Event: "y-event-" + first 20 hex of SHA-256(str(dashboard event id)). */
    fun eventAppKey(eventId: Int): String = "y-event-" + sha20(eventId.toString())

    /** Place content hash, for change detection: SHA-256 over the
     * control-bearing fields. A faction change is a control change. */
    fun placeContentHash(place: YemenPlace): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val canonical = listOf(
            place.faction, place.controlLabel, place.confidence,
            place.note, place.sourceNote,
            place.lat.toString(), place.lon.toString(),
        ).joinToString("\u0001")
        return digest.digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

// ---- Domain ----

data class YemenPlace(
    val externalId: Int,
    val appKey: String,
    val nameFa: String,
    val nameEn: String,
    val governorateFa: String,
    val lat: Double,
    val lon: Double,
    val faction: String,
    val controlLabel: String,
    val confidence: String,
    val note: String,
    /** Provenance travels from day one (P1-26 lesson). */
    val provenance: String,
    val sourceNote: String,
    val updatedAt: String?,
)

data class YemenEvent(
    val externalId: Int,
    val appKey: String,
    val eventDate: String,
    val titleFa: String,
    val detailFa: String,
    val origin: String,
    val corroboration: Int,
    val families: String?,
    val sourceName: String,
    val sourceUrl: String,
)

data class YemenWaypoint(
    val lat: Double,
    val lon: Double,
    val nameFa: String,
    val inLine: Boolean,
)

data class YemenFrontline(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val statusKind: String,
    val statusFa: String,
    val traceKind: String,
    val waypoints: List<YemenWaypoint>,
    val sideAFa: String,
    val sideBFa: String,
    val confidenceFa: String,
    val summaryFa: String,
    val governoratesFa: String,
    val movementFa: String,
    val traceNoteFa: String,
    val sources: List<LabelUrl>,
)

data class YemenArrow(
    val id: String,
    val labelFa: String,
    val side: String,
    val kindFa: String,
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val dateFa: String,
    val confidenceFa: String,
    val noteFa: String,
    val sourceFa: String,
)

data class YemenUnit(
    val nameFa: String,
    val side: String,
    val locationFa: String,
    val lat: Double,
    val lon: Double,
    val confidenceFa: String,
    val noteFa: String,
    val sourceFa: String,
    val uncertain: Boolean,
)

data class YemenForce(
    val name: String,
    val factionKey: String,
    val status: String,
    val body: String,
)

data class TitleBody(val title: String, val body: String)
data class LabelUrl(val label: String, val url: String)

data class YemenRingSegment(
    val slot: String,
    val statusKind: String,
    val directionFa: String,
    val statusFa: String,
    val bodyFa: String,
)

data class YemenAdenTimelineItem(
    val date: String,
    val titleFa: String,
    val bodyFa: String,
    val layer: String,
    val sourceFa: String,
)

data class YemenLayers(
    val confirmed: List<TitleBody>,
    val officialClaims: List<TitleBody>,
    val rumours: List<TitleBody>,
)

data class YemenAdenImage(
    val name: String,
    val originFa: String,
    val showsFa: String,
    val verdictFa: String,
    val url: String,
)

data class YemenAdenCrossCheck(
    val dateFa: String,
    val saysFa: String,
    val source: String,
)

data class YemenAdenChannelOfficial(
    val name: String,
    val handle: String,
    val kindFa: String,
    val bodyFa: String,
    val verdictFa: String,
)

data class YemenAdenSocialPost(
    val name: String,
    val kindFa: String,
    val dateFa: String,
    val bodyFa: String,
    val url: String,
)

data class YemenAdenAmplifier(
    val name: String,
    val dateFa: String,
    val bodyFa: String,
    val url: String,
)

data class YemenAden(
    val verdictTitleFa: String,
    val verdictBodyFa: String,
    val verdictConfidenceFa: String,
    val ringSegments: List<YemenRingSegment>,
    val timeline: List<YemenAdenTimelineItem>,
    val keyPoints: List<String>,
    val sources: List<LabelUrl>,
    val layers: YemenLayers,
    val contradictions: List<TitleBody>,
    val channelsMethodFa: String,
    val crossCheckSummaryFa: String,
    val seenNoteFa: String,
    val taizNoteFa: String,
    val openQuestions: List<String>,
    // v5.6 (R4): the Aden investigation sections the app used to
    // drop. All five travel verbatim from yemen.json (edge
    // generate_yemen.py copies them field-for-field from the
    // dashboard snapshot); empty lists on older bundles.
    val images: List<YemenAdenImage>,
    val crossCheck: List<YemenAdenCrossCheck>,
    val channelsOfficial: List<YemenAdenChannelOfficial>,
    val socialPosts: List<YemenAdenSocialPost>,
    val amplifiers: List<YemenAdenAmplifier>,
)

data class YemenMilitaryFeature(
    val titleFa: String,
    val kindFa: String,
    val bodyFa: String,
    val confidenceFa: String,
    val sourceFa: String,
)

/** Bab el-Mandeb key point (dashboard keyPoints): a place, its
 * status, and the basis for that status — kept as separate
 * fields, never merged into a sentence. */
data class YemenKeyPoint(
    val name: String,
    val status: String,
    val statusKind: String,
    val confidence: String,
    val basis: String,
)
data class HumanStat(val label: String, val value: String, val source: String)

data class YemenSnapshot(
    val id: Int,
    val label: String,
    val createdAt: String,
    val placesCount: Int,
    val eventsCount: Int,
    val headlineFa: String,
    val counts: List<Pair<String, Int>>,
)

data class YemenDoc(
    val schemaVersion: Int,
    val generatedAtUtc: String,
    val asOf: String,
    val headlineFa: String,
    val lastRefreshStatus: String,
    val lastRefreshStartedAt: String,
    val lastRefreshFinishedAt: String,
    val counts: Map<String, Int>,
    val factionCounts: Map<String, Int>,
    val places: List<YemenPlace>,
    val frontlines: List<YemenFrontline>,
    val advanceArrows: List<YemenArrow>,
    val unitPositions: List<YemenUnit>,
    val forces: List<YemenForce>,
    val events: List<YemenEvent>,
    val claimsConfirmed: List<TitleBody>,
    val claimsContested: List<TitleBody>,
    val claimsHouthiOnly: List<TitleBody>,
    val aden: YemenAden,
    val humanStats: List<HumanStat>,
    val sources: List<LabelUrl>,
    val mapReading: List<TitleBody>,
    val militaryFeatures: List<YemenMilitaryFeature>,
    val keyPoints: List<YemenKeyPoint>,
    val openQuestions: List<String>,
    val disagreements: List<TitleBody>,
    val snapshots: List<YemenSnapshot>,
    val frontlineWarningFa: String,
)

// ---- Parsing (org.json; never throws out — returns null) ----

object YemenParsers {

    fun parseDoc(text: String): YemenDoc? {
        return try {
            val root = JSONObject(text)
            if (root.optInt("schema_version", 0) != 1) return null
            val countsObj = root.optJSONObject("counts") ?: JSONObject()
            val factionObj = root.optJSONObject("faction_counts") ?: JSONObject()
            val last = root.optJSONObject("last_refresh") ?: JSONObject()
            val claims = root.optJSONObject("claims") ?: JSONObject()
            YemenDoc(
                schemaVersion = 1,
                generatedAtUtc = root.optString("generated_at_utc", ""),
                asOf = root.optString("as_of", ""),
                headlineFa = root.optString("headline_fa", ""),
                lastRefreshStatus = last.optString("status", ""),
                lastRefreshStartedAt = last.optString("started_at", ""),
                lastRefreshFinishedAt = last.optString("finished_at", ""),
                counts = countsObj.keys().asSequence().associateWith {
                    countsObj.optInt(it, 0)
                },
                factionCounts = factionObj.keys().asSequence().associateWith {
                    factionObj.optInt(it, 0)
                },
                places = parsePlaces(root.optJSONArray("places") ?: JSONArray()),
                frontlines = parseFrontlines(
                    root.optJSONArray("frontlines") ?: JSONArray(),
                ),
                advanceArrows = parseArrows(
                    root.optJSONArray("advance_arrows") ?: JSONArray(),
                ),
                unitPositions = parseUnits(
                    root.optJSONArray("unit_positions") ?: JSONArray(),
                ),
                forces = parseForces(root.optJSONArray("forces") ?: JSONArray()),
                events = parseEvents(root.optJSONArray("events") ?: JSONArray()),
                claimsConfirmed = titleBodyList(
                    claims.optJSONArray("confirmed") ?: JSONArray(),
                ),
                claimsContested = titleBodyList(
                    claims.optJSONArray("contested") ?: JSONArray(),
                ),
                claimsHouthiOnly = titleBodyList(
                    claims.optJSONArray("houthi_only") ?: JSONArray(),
                ),
                aden = parseAden(root.optJSONObject("aden") ?: JSONObject()),
                humanStats = parseHumanStats(
                    root.optJSONArray("human_stats") ?: JSONArray(),
                ),
                sources = labelUrlList(
                    root.optJSONArray("sources") ?: JSONArray(),
                ),
                mapReading = titleBodyList(
                    root.optJSONArray("map_reading") ?: JSONArray(),
                ),
                militaryFeatures = run {
                    val out = mutableListOf<YemenMilitaryFeature>()
                    val arr = root.optJSONArray("military_features") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        try {
                            val o = arr.getJSONObject(i)
                            out.add(
                                YemenMilitaryFeature(
                                    titleFa = o.optString("title_fa", ""),
                                    kindFa = o.optString("kind_fa", ""),
                                    bodyFa = o.optString("body_fa", ""),
                                    confidenceFa = o.optString("confidence_fa", ""),
                                    sourceFa = o.optString("source_fa", ""),
                                ),
                            )
                        } catch (_: Exception) {
                            continue
                        }
                    }
                    out
                },
                keyPoints = run {
                    val out = mutableListOf<YemenKeyPoint>()
                    val arr = root.optJSONArray("key_points") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        try {
                            val o = arr.getJSONObject(i)
                            out.add(
                                YemenKeyPoint(
                                    name = o.optString("name", ""),
                                    status = o.optString("status", ""),
                                    statusKind = o.optString("status_kind", ""),
                                    confidence = o.optString("confidence", ""),
                                    basis = o.optString("basis", ""),
                                ),
                            )
                        } catch (_: Exception) {
                            continue
                        }
                    }
                    out
                },
                openQuestions = stringList(
                    root.optJSONArray("open_questions") ?: JSONArray(),
                ),
                disagreements = titleBodyList(
                    root.optJSONArray("disagreements") ?: JSONArray(),
                ),
                snapshots = parseSnapshots(
                    root.optJSONArray("snapshots") ?: JSONArray(),
                ),
                frontlineWarningFa = root.optString("frontline_warning_fa", ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parsePlaces(arr: JSONArray): List<YemenPlace> {
        val out = mutableListOf<YemenPlace>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val nameFa = o.optString("name_fa", "")
                val gov = o.optString("governorate_fa", "")
                if (nameFa.isBlank()) continue
                out.add(
                    YemenPlace(
                        externalId = o.optInt("external_id", 0),
                        appKey = o.optString("app_key")
                            .takeIf { it.isNotBlank() }
                            ?: YemenKeys.placeAppKey(nameFa, gov),
                        nameFa = nameFa,
                        nameEn = o.optString("name_en", ""),
                        governorateFa = gov,
                        lat = o.optDouble("lat", 0.0),
                        lon = o.optDouble("lon", 0.0),
                        faction = o.optString("faction", "unknown"),
                        controlLabel = o.optString("control_label", ""),
                        confidence = o.optString("confidence", ""),
                        note = o.optString("note", ""),
                        provenance = o.optString("provenance", ""),
                        sourceNote = o.optString("source_note", ""),
                        updatedAt = o.optString("updated_at")
                            .takeIf { it.isNotBlank() },
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    fun parseEvents(arr: JSONArray): List<YemenEvent> {
        val out = mutableListOf<YemenEvent>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val extId = o.optInt("external_id", 0)
                out.add(
                    YemenEvent(
                        externalId = extId,
                        appKey = o.optString("app_key")
                            .takeIf { it.isNotBlank() }
                            ?: YemenKeys.eventAppKey(extId),
                        eventDate = o.optString("event_date", ""),
                        titleFa = o.optString("title_fa", ""),
                        detailFa = o.optString("detail_fa", ""),
                        origin = o.optString("origin", ""),
                        corroboration = o.optInt("corroboration", 0),
                        families = o.optString("families")
                            .takeIf { it.isNotBlank() },
                        sourceName = o.optString("source_name", ""),
                        sourceUrl = o.optString("source_url", ""),
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    fun parseFrontlines(arr: JSONArray): List<YemenFrontline> {
        val out = mutableListOf<YemenFrontline>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val wps = mutableListOf<YemenWaypoint>()
                val warr = o.optJSONArray("waypoints") ?: JSONArray()
                for (j in 0 until warr.length()) {
                    try {
                        val w = warr.getJSONObject(j)
                        wps.add(
                            YemenWaypoint(
                                lat = w.optDouble("lat", 0.0),
                                lon = w.optDouble("lon", 0.0),
                                nameFa = w.optString("name_fa", ""),
                                inLine = w.optBoolean("in_line", false),
                            ),
                        )
                    } catch (_: Exception) {
                        continue
                    }
                }
                out.add(
                    YemenFrontline(
                        id = o.optString("id", ""),
                        nameFa = o.optString("name_fa", ""),
                        nameEn = o.optString("name_en", ""),
                        statusKind = o.optString("status_kind", ""),
                        statusFa = o.optString("status_fa", ""),
                        traceKind = o.optString("trace_kind", ""),
                        waypoints = wps,
                        sideAFa = o.optString("side_a_fa", ""),
                        sideBFa = o.optString("side_b_fa", ""),
                        confidenceFa = o.optString("confidence_fa", ""),
                        summaryFa = o.optString("summary_fa", ""),
                        governoratesFa = o.optString("governorates_fa", ""),
                        movementFa = o.optString("movement_fa", ""),
                        traceNoteFa = o.optString("trace_note_fa", ""),
                        sources = labelUrlList(
                            o.optJSONArray("sources") ?: JSONArray(),
                        ),
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    fun parseArrows(arr: JSONArray): List<YemenArrow> {
        val out = mutableListOf<YemenArrow>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(
                    YemenArrow(
                        id = o.optString("id", ""),
                        labelFa = o.optString("label_fa", ""),
                        side = o.optString("side", ""),
                        kindFa = o.optString("kind_fa", ""),
                        fromLat = o.optDouble("from_lat", 0.0),
                        fromLon = o.optDouble("from_lon", 0.0),
                        toLat = o.optDouble("to_lat", 0.0),
                        toLon = o.optDouble("to_lon", 0.0),
                        dateFa = o.optString("date_fa", ""),
                        confidenceFa = o.optString("confidence_fa", ""),
                        noteFa = o.optString("note_fa", ""),
                        sourceFa = o.optString("source_fa", ""),
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    fun parseUnits(arr: JSONArray): List<YemenUnit> {
        val out = mutableListOf<YemenUnit>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(
                    YemenUnit(
                        nameFa = o.optString("name_fa", ""),
                        side = o.optString("side", ""),
                        locationFa = o.optString("location_fa", ""),
                        lat = o.optDouble("lat", 0.0),
                        lon = o.optDouble("lon", 0.0),
                        confidenceFa = o.optString("confidence_fa", ""),
                        noteFa = o.optString("note_fa", ""),
                        sourceFa = o.optString("source_fa", ""),
                        uncertain = o.optBoolean("uncertain", false),
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    fun parseForces(arr: JSONArray): List<YemenForce> {
        val out = mutableListOf<YemenForce>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(
                    YemenForce(
                        name = o.optString("name", ""),
                        factionKey = o.optString("faction_key", ""),
                        status = o.optString("status", ""),
                        body = o.optString("body", ""),
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    fun parseAden(obj: JSONObject): YemenAden {
        val verdict = obj.optJSONObject("verdict") ?: JSONObject()
        val layers = obj.optJSONObject("layers") ?: JSONObject()
        return YemenAden(
            verdictTitleFa = verdict.optString("title_fa", ""),
            verdictBodyFa = verdict.optString("body_fa", ""),
            verdictConfidenceFa = verdict.optString("confidence_fa", ""),
            ringSegments = run {
                val out = mutableListOf<YemenRingSegment>()
                val arr = obj.optJSONArray("ring_segments") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        out.add(
                            YemenRingSegment(
                                slot = o.optString("slot", ""),
                                statusKind = o.optString("status_kind", ""),
                                directionFa = o.optString("direction_fa", ""),
                                statusFa = o.optString("status_fa", ""),
                                bodyFa = o.optString("body_fa", ""),
                            ),
                        )
                    } catch (_: Exception) {
                        continue
                    }
                }
                out
            },
            timeline = run {
                val out = mutableListOf<YemenAdenTimelineItem>()
                val arr = obj.optJSONArray("timeline") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        out.add(
                            YemenAdenTimelineItem(
                                date = o.optString("date", ""),
                                titleFa = o.optString("title_fa", ""),
                                bodyFa = o.optString("body_fa", ""),
                                layer = o.optString("layer", ""),
                                sourceFa = o.optString("source_fa", ""),
                            ),
                        )
                    } catch (_: Exception) {
                        continue
                    }
                }
                out
            },
            keyPoints = stringList(obj.optJSONArray("key_points") ?: JSONArray()),
            sources = labelUrlList(obj.optJSONArray("sources") ?: JSONArray()),
            layers = YemenLayers(
                confirmed = titleBodyList(
                    layers.optJSONArray("confirmed") ?: JSONArray(),
                ),
                officialClaims = titleBodyList(
                    layers.optJSONArray("official_claims") ?: JSONArray(),
                ),
                rumours = titleBodyList(
                    layers.optJSONArray("rumours") ?: JSONArray(),
                ),
            ),
            contradictions = titleBodyList(
                obj.optJSONArray("contradictions") ?: JSONArray(),
            ),
            channelsMethodFa = obj.optString("channels_method_fa", ""),
            crossCheckSummaryFa = obj.optString("cross_check_summary_fa", ""),
            seenNoteFa = obj.optString("seen_note_fa", ""),
            taizNoteFa = obj.optString("taiz_note_fa", ""),
            openQuestions = stringList(
                obj.optJSONArray("open_questions") ?: JSONArray(),
            ),
            images = run {
                val out = mutableListOf<YemenAdenImage>()
                val arr = obj.optJSONArray("images") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        out.add(
                            YemenAdenImage(
                                name = o.optString("name", ""),
                                originFa = o.optString("origin_fa", ""),
                                showsFa = o.optString("shows_fa", ""),
                                verdictFa = o.optString("verdict_fa", ""),
                                url = o.optString("url", ""),
                            ),
                        )
                    } catch (_: Exception) {
                        continue
                    }
                }
                out
            },
            crossCheck = run {
                val out = mutableListOf<YemenAdenCrossCheck>()
                val arr = obj.optJSONArray("cross_check") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        out.add(
                            YemenAdenCrossCheck(
                                dateFa = o.optString("date_fa", ""),
                                saysFa = o.optString("says_fa", ""),
                                source = o.optString("source", ""),
                            ),
                        )
                    } catch (_: Exception) {
                        continue
                    }
                }
                out
            },
            channelsOfficial = run {
                val out = mutableListOf<YemenAdenChannelOfficial>()
                val arr = obj.optJSONArray("channels_official") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        out.add(
                            YemenAdenChannelOfficial(
                                name = o.optString("name", ""),
                                handle = o.optString("handle", ""),
                                kindFa = o.optString("kind_fa", ""),
                                bodyFa = o.optString("body_fa", ""),
                                verdictFa = o.optString("verdict_fa", ""),
                            ),
                        )
                    } catch (_: Exception) {
                        continue
                    }
                }
                out
            },
            socialPosts = run {
                val out = mutableListOf<YemenAdenSocialPost>()
                val arr = obj.optJSONArray("social_posts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        out.add(
                            YemenAdenSocialPost(
                                name = o.optString("name", ""),
                                kindFa = o.optString("kind_fa", ""),
                                dateFa = o.optString("date_fa", ""),
                                bodyFa = o.optString("body_fa", ""),
                                url = o.optString("url", ""),
                            ),
                        )
                    } catch (_: Exception) {
                        continue
                    }
                }
                out
            },
            amplifiers = run {
                val out = mutableListOf<YemenAdenAmplifier>()
                val arr = obj.optJSONArray("amplifiers") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    try {
                        val o = arr.getJSONObject(i)
                        out.add(
                            YemenAdenAmplifier(
                                name = o.optString("name", ""),
                                dateFa = o.optString("date_fa", ""),
                                bodyFa = o.optString("body_fa", ""),
                                url = o.optString("url", ""),
                            ),
                        )
                    } catch (_: Exception) {
                        continue
                    }
                }
                out
            },
        )
    }

    fun parseHumanStats(arr: JSONArray): List<HumanStat> {
        val out = mutableListOf<HumanStat>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                // Value stays a string, verbatim. A non-string value
                // is dropped, never coerced.
                if (o.isNull("value")) continue
                val v = o.optString("value", "")
                out.add(
                    HumanStat(
                        label = o.optString("label", ""),
                        value = v,
                        source = o.optString("source", ""),
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    fun parseSnapshots(arr: JSONArray): List<YemenSnapshot> {
        val out = mutableListOf<YemenSnapshot>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val counts = mutableListOf<Pair<String, Int>>()
                val carr = o.optJSONArray("counts") ?: JSONArray()
                for (j in 0 until carr.length()) {
                    try {
                        val c = carr.getJSONObject(j)
                        counts.add(c.optString("faction", "") to c.optInt("count", 0))
                    } catch (_: Exception) {
                        continue
                    }
                }
                out.add(
                    YemenSnapshot(
                        id = o.optInt("id", 0),
                        label = o.optString("label", ""),
                        createdAt = o.optString("created_at", ""),
                        placesCount = o.optInt("places_count", 0),
                        eventsCount = o.optInt("events_count", 0),
                        headlineFa = o.optString("headline_fa", ""),
                        counts = counts,
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    /** [{title, body}] — titles may be empty (plain-string items). */
    fun titleBodyList(arr: JSONArray): List<TitleBody> {
        val out = mutableListOf<TitleBody>()
        for (i in 0 until arr.length()) {
            try {
                when (val item = arr.get(i)) {
                    is JSONObject -> out.add(
                        TitleBody(
                            title = item.optString("title", ""),
                            body = item.optString("body", "")
                                .takeIf { it.isNotBlank() }
                                ?: item.optString("body_fa", ""),
                        ),
                    )
                    is String -> out.add(TitleBody(title = "", body = item))
                    else -> continue
                }
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    fun labelUrlList(arr: JSONArray): List<LabelUrl> {
        val out = mutableListOf<LabelUrl>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                out.add(
                    LabelUrl(
                        label = o.optString("label", ""),
                        url = o.optString("url", ""),
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    fun stringList(arr: JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            try {
                when (val item = arr.get(i)) {
                    is String -> out.add(item)
                    is JSONObject -> {
                        val b = item.optString("body", "")
                            .takeIf { it.isNotBlank() }
                            ?: item.optString("body_fa", "")
                        if (b.isNotBlank()) out.add(b)
                    }
                    else -> continue
                }
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    // ---- Serialisers for the Room verbatim-JSON columns ----

    fun titleBodyJson(list: List<TitleBody>): String {
        val arr = JSONArray()
        for (item in list) {
            arr.put(JSONObject().put("title", item.title).put("body", item.body))
        }
        return arr.toString()
    }

    fun labelUrlJson(list: List<LabelUrl>): String {
        val arr = JSONArray()
        for (item in list) {
            arr.put(JSONObject().put("label", item.label).put("url", item.url))
        }
        return arr.toString()
    }

    fun stringListJson(list: List<String>): String {
        val arr = JSONArray()
        for (item in list) arr.put(item)
        return arr.toString()
    }

    fun ringJson(list: List<YemenRingSegment>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("slot", s.slot)
                    .put("status_kind", s.statusKind)
                    .put("direction_fa", s.directionFa)
                    .put("status_fa", s.statusFa)
                    .put("body_fa", s.bodyFa),
            )
        }
        return arr.toString()
    }

    fun ringList(json: String): List<YemenRingSegment> {
        val out = mutableListOf<YemenRingSegment>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    YemenRingSegment(
                        slot = o.optString("slot", ""),
                        statusKind = o.optString("status_kind", ""),
                        directionFa = o.optString("direction_fa", ""),
                        statusFa = o.optString("status_fa", ""),
                        bodyFa = o.optString("body_fa", ""),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun timelineJson(list: List<YemenAdenTimelineItem>): String {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject()
                    .put("date", t.date)
                    .put("title_fa", t.titleFa)
                    .put("body_fa", t.bodyFa)
                    .put("layer", t.layer)
                    .put("source_fa", t.sourceFa),
            )
        }
        return arr.toString()
    }

    fun timelineList(json: String): List<YemenAdenTimelineItem> {
        val out = mutableListOf<YemenAdenTimelineItem>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    YemenAdenTimelineItem(
                        date = o.optString("date", ""),
                        titleFa = o.optString("title_fa", ""),
                        bodyFa = o.optString("body_fa", ""),
                        layer = o.optString("layer", ""),
                        sourceFa = o.optString("source_fa", ""),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun layersJson(layers: YemenLayers): String {
        return JSONObject()
            .put("confirmed", JSONArray(titleBodyJson(layers.confirmed)))
            .put("official_claims", JSONArray(titleBodyJson(layers.officialClaims)))
            .put("rumours", JSONArray(titleBodyJson(layers.rumours)))
            .toString()
    }

    // ---- v5.6 (R4): verbatim-JSON columns for the Aden
    // investigation sections (same pattern as ringJson above) ----

    fun adenImagesJson(list: List<YemenAdenImage>): String {
        val arr = JSONArray()
        for (im in list) {
            arr.put(
                JSONObject()
                    .put("name", im.name)
                    .put("origin_fa", im.originFa)
                    .put("shows_fa", im.showsFa)
                    .put("verdict_fa", im.verdictFa)
                    .put("url", im.url),
            )
        }
        return arr.toString()
    }

    fun adenImagesList(json: String): List<YemenAdenImage> {
        val out = mutableListOf<YemenAdenImage>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    YemenAdenImage(
                        name = o.optString("name", ""),
                        originFa = o.optString("origin_fa", ""),
                        showsFa = o.optString("shows_fa", ""),
                        verdictFa = o.optString("verdict_fa", ""),
                        url = o.optString("url", ""),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun adenCrossCheckJson(list: List<YemenAdenCrossCheck>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("date_fa", c.dateFa)
                    .put("says_fa", c.saysFa)
                    .put("source", c.source),
            )
        }
        return arr.toString()
    }

    fun adenCrossCheckList(json: String): List<YemenAdenCrossCheck> {
        val out = mutableListOf<YemenAdenCrossCheck>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    YemenAdenCrossCheck(
                        dateFa = o.optString("date_fa", ""),
                        saysFa = o.optString("says_fa", ""),
                        source = o.optString("source", ""),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun adenChannelsOfficialJson(list: List<YemenAdenChannelOfficial>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("handle", c.handle)
                    .put("kind_fa", c.kindFa)
                    .put("body_fa", c.bodyFa)
                    .put("verdict_fa", c.verdictFa),
            )
        }
        return arr.toString()
    }

    fun adenChannelsOfficialList(json: String): List<YemenAdenChannelOfficial> {
        val out = mutableListOf<YemenAdenChannelOfficial>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    YemenAdenChannelOfficial(
                        name = o.optString("name", ""),
                        handle = o.optString("handle", ""),
                        kindFa = o.optString("kind_fa", ""),
                        bodyFa = o.optString("body_fa", ""),
                        verdictFa = o.optString("verdict_fa", ""),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun adenSocialPostsJson(list: List<YemenAdenSocialPost>): String {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("kind_fa", p.kindFa)
                    .put("date_fa", p.dateFa)
                    .put("body_fa", p.bodyFa)
                    .put("url", p.url),
            )
        }
        return arr.toString()
    }

    fun adenSocialPostsList(json: String): List<YemenAdenSocialPost> {
        val out = mutableListOf<YemenAdenSocialPost>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    YemenAdenSocialPost(
                        name = o.optString("name", ""),
                        kindFa = o.optString("kind_fa", ""),
                        dateFa = o.optString("date_fa", ""),
                        bodyFa = o.optString("body_fa", ""),
                        url = o.optString("url", ""),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun adenAmplifiersJson(list: List<YemenAdenAmplifier>): String {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("name", a.name)
                    .put("date_fa", a.dateFa)
                    .put("body_fa", a.bodyFa)
                    .put("url", a.url),
            )
        }
        return arr.toString()
    }

    fun adenAmplifiersList(json: String): List<YemenAdenAmplifier> {
        val out = mutableListOf<YemenAdenAmplifier>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    YemenAdenAmplifier(
                        name = o.optString("name", ""),
                        dateFa = o.optString("date_fa", ""),
                        bodyFa = o.optString("body_fa", ""),
                        url = o.optString("url", ""),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun layersFromJson(json: String): YemenLayers {
        return try {
            val o = JSONObject(json)
            YemenLayers(
                confirmed = titleBodyList(o.optJSONArray("confirmed") ?: JSONArray()),
                officialClaims = titleBodyList(
                    o.optJSONArray("official_claims") ?: JSONArray(),
                ),
                rumours = titleBodyList(o.optJSONArray("rumours") ?: JSONArray()),
            )
        } catch (_: Exception) {
            YemenLayers(emptyList(), emptyList(), emptyList())
        }
    }

    fun waypointJson(list: List<YemenWaypoint>): String {
        val arr = JSONArray()
        for (w in list) {
            arr.put(
                JSONObject()
                    .put("lat", w.lat).put("lon", w.lon)
                    .put("name_fa", w.nameFa).put("in_line", w.inLine),
            )
        }
        return arr.toString()
    }

    fun waypointList(json: String): List<YemenWaypoint> {
        val out = mutableListOf<YemenWaypoint>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                out.add(
                    YemenWaypoint(
                        lat = w.optDouble("lat", 0.0),
                        lon = w.optDouble("lon", 0.0),
                        nameFa = w.optString("name_fa", ""),
                        inLine = w.optBoolean("in_line", false),
                    ),
                )
            }
        } catch (_: Exception) {
        }
        return out
    }
}

// ---- Room conversions ----

fun YemenPlace.toEntity(): YemenPlaceEntity =
    YemenPlaceEntity(
        externalId = externalId,
        appKey = appKey,
        nameFa = nameFa,
        nameEn = nameEn,
        governorateFa = governorateFa,
        lat = lat,
        lon = lon,
        faction = faction,
        controlLabel = controlLabel,
        confidence = confidence,
        note = note,
        provenance = provenance,
        sourceNote = sourceNote,
        updatedAt = updatedAt,
        contentHash = YemenKeys.placeContentHash(this),
    )

fun YemenEvent.toEntity(): YemenEventEntity =
    YemenEventEntity(
        externalId = externalId,
        appKey = appKey,
        eventDate = eventDate,
        titleFa = titleFa,
        detailFa = detailFa,
        origin = origin,
        corroboration = corroboration,
        families = families,
        sourceName = sourceName,
        sourceUrl = sourceUrl,
    )

fun YemenFrontline.toEntity(): YemenFrontlineEntity =
    YemenFrontlineEntity(
        id = id,
        nameFa = nameFa,
        nameEn = nameEn,
        statusKind = statusKind,
        statusFa = statusFa,
        traceKind = traceKind,
        waypointsJson = YemenParsers.waypointJson(waypoints),
        sideAFa = sideAFa,
        sideBFa = sideBFa,
        confidenceFa = confidenceFa,
        summaryFa = summaryFa,
        governoratesFa = governoratesFa,
        movementFa = movementFa,
        traceNoteFa = traceNoteFa,
        sourcesJson = YemenParsers.labelUrlJson(sources),
    )

fun YemenAden.toEntity(): YemenAdenEntity =
    YemenAdenEntity(
        id = "aden",
        verdictTitleFa = verdictTitleFa,
        verdictBodyFa = verdictBodyFa,
        verdictConfidenceFa = verdictConfidenceFa,
        ringJson = YemenParsers.ringJson(ringSegments),
        timelineJson = YemenParsers.timelineJson(timeline),
        keyPointsJson = YemenParsers.stringListJson(keyPoints),
        sourcesJson = YemenParsers.labelUrlJson(sources),
        layersJson = YemenParsers.layersJson(layers),
        contradictionsJson = YemenParsers.titleBodyJson(contradictions),
        channelsMethodFa = channelsMethodFa,
        crossCheckSummaryFa = crossCheckSummaryFa,
        seenNoteFa = seenNoteFa,
        taizNoteFa = taizNoteFa,
        openQuestionsJson = YemenParsers.stringListJson(openQuestions),
        imagesJson = YemenParsers.adenImagesJson(images),
        crossCheckJson = YemenParsers.adenCrossCheckJson(crossCheck),
        channelsOfficialJson = YemenParsers.adenChannelsOfficialJson(channelsOfficial),
        socialPostsJson = YemenParsers.adenSocialPostsJson(socialPosts),
        amplifiersJson = YemenParsers.adenAmplifiersJson(amplifiers),
    )

fun YemenFrontlineEntity.toDomain(): YemenFrontline =
    YemenFrontline(
        id = id,
        nameFa = nameFa,
        nameEn = nameEn,
        statusKind = statusKind,
        statusFa = statusFa,
        traceKind = traceKind,
        waypoints = YemenParsers.waypointList(waypointsJson),
        sideAFa = sideAFa,
        sideBFa = sideBFa,
        confidenceFa = confidenceFa,
        summaryFa = summaryFa,
        governoratesFa = governoratesFa,
        movementFa = movementFa,
        traceNoteFa = traceNoteFa,
        sources = YemenParsers.labelUrlList(
            try {
                JSONArray(sourcesJson)
            } catch (_: Exception) {
                JSONArray()
            },
        ),
    )

fun YemenAdenEntity.toDomain(): YemenAden =
    YemenAden(
        verdictTitleFa = verdictTitleFa,
        verdictBodyFa = verdictBodyFa,
        verdictConfidenceFa = verdictConfidenceFa,
        ringSegments = YemenParsers.ringList(ringJson),
        timeline = YemenParsers.timelineList(timelineJson),
        keyPoints = YemenParsers.stringList(
            try {
                JSONArray(keyPointsJson)
            } catch (_: Exception) {
                JSONArray()
            },
        ),
        sources = YemenParsers.labelUrlList(
            try {
                JSONArray(sourcesJson)
            } catch (_: Exception) {
                JSONArray()
            },
        ),
        layers = YemenParsers.layersFromJson(layersJson),
        contradictions = YemenParsers.titleBodyList(
            try {
                JSONArray(contradictionsJson)
            } catch (_: Exception) {
                JSONArray()
            },
        ),
        channelsMethodFa = channelsMethodFa,
        crossCheckSummaryFa = crossCheckSummaryFa,
        seenNoteFa = seenNoteFa,
        taizNoteFa = taizNoteFa,
        openQuestions = YemenParsers.stringList(
            try {
                JSONArray(openQuestionsJson)
            } catch (_: Exception) {
                JSONArray()
            },
        ),
        images = YemenParsers.adenImagesList(imagesJson),
        crossCheck = YemenParsers.adenCrossCheckList(crossCheckJson),
        channelsOfficial = YemenParsers.adenChannelsOfficialList(channelsOfficialJson),
        socialPosts = YemenParsers.adenSocialPostsList(socialPostsJson),
        amplifiers = YemenParsers.adenAmplifiersList(amplifiersJson),
    )

fun YemenPlaceEntity.toDomain(): YemenPlace =
    YemenPlace(
        externalId = externalId,
        appKey = appKey,
        nameFa = nameFa,
        nameEn = nameEn,
        governorateFa = governorateFa,
        lat = lat,
        lon = lon,
        faction = faction,
        controlLabel = controlLabel,
        confidence = confidence,
        note = note,
        provenance = provenance,
        sourceNote = sourceNote,
        updatedAt = updatedAt,
    )

fun YemenEventEntity.toDomain(): YemenEvent =
    YemenEvent(
        externalId = externalId,
        appKey = appKey,
        eventDate = eventDate,
        titleFa = titleFa,
        detailFa = detailFa,
        origin = origin,
        corroboration = corroboration,
        families = families,
        sourceName = sourceName,
        sourceUrl = sourceUrl,
    )
