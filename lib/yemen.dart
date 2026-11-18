// Plain Dart models for edge/out/yemen.json (dashboard v5 data only).
// No database, no code generation, no retained holders: the whole document
// is one immutable object, parsed once per refresh in an isolate
// (see parseYemenDoc) and handed to the UI as a single state object.
//
// Parsing is defensive on purpose: unknown / missing fields fall back to
// empty values instead of crashing the app. Nothing here invents data —
// every string comes from yemen.json.
import 'dart:convert';

String _s(dynamic v, [String fallback = '']) =>
    v == null ? fallback : v.toString();
int _i(dynamic v, [int fallback = 0]) =>
    v is num ? v.toInt() : int.tryParse(v.toString()) ?? fallback;
double _d(dynamic v, [double fallback = 0.0]) =>
    v is num ? v.toDouble() : double.tryParse(v.toString()) ?? fallback;
bool _b(dynamic v, [bool fallback = false]) =>
    v is bool ? v : fallback;
List<dynamic> _l(dynamic v) => v is List ? v : const [];
Map<String, dynamic> _m(dynamic v) =>
    v is Map ? Map<String, dynamic>.from(v) : const {};

class Place {
  final String nameFa;
  final String nameEn;
  final String governorateFa;
  final double lat;
  final double lon;
  final String faction;
  final String controlLabel;
  final String confidence;
  final String note;
  final String sourceNote;
  Place.fromJson(Map<String, dynamic> j)
      : nameFa = _s(j['name_fa']),
        nameEn = _s(j['name_en']),
        governorateFa = _s(j['governorate_fa']),
        lat = _d(j['lat']),
        lon = _d(j['lon']),
        faction = _s(j['faction'], 'unknown'),
        controlLabel = _s(j['control_label']),
        confidence = _s(j['confidence']),
        note = _s(j['note']),
        sourceNote = _s(j['source_note']);
}

class Waypoint {
  final double lat;
  final double lon;
  final String nameFa;
  Waypoint.fromJson(Map<String, dynamic> j)
      : lat = _d(j['lat']),
        lon = _d(j['lon']),
        nameFa = _s(j['name_fa']);
}

class Frontline {
  final String nameFa;
  final String nameEn;
  final String statusFa;
  final List<Waypoint> waypoints;
  final String sideAFa;
  final String sideBFa;
  final String confidenceFa;
  final String summaryFa;
  final String governoratesFa;
  final String movementFa;
  final String traceNoteFa;
  Frontline.fromJson(Map<String, dynamic> j)
      : nameFa = _s(j['name_fa']),
        nameEn = _s(j['name_en']),
        statusFa = _s(j['status_fa']),
        waypoints =
            _l(j['waypoints']).map((w) => Waypoint.fromJson(_m(w))).toList(),
        sideAFa = _s(j['side_a_fa']),
        sideBFa = _s(j['side_b_fa']),
        confidenceFa = _s(j['confidence_fa']),
        summaryFa = _s(j['summary_fa']),
        governoratesFa = _s(j['governorates_fa']),
        movementFa = _s(j['movement_fa']),
        traceNoteFa = _s(j['trace_note_fa']);
}

class AdvanceArrow {
  final String labelFa;
  final String side;
  final String kindFa;
  final double fromLat;
  final double fromLon;
  final double toLat;
  final double toLon;
  final String dateFa;
  final String confidenceFa;
  final String noteFa;
  final String sourceFa;
  AdvanceArrow.fromJson(Map<String, dynamic> j)
      : labelFa = _s(j['label_fa']),
        side = _s(j['side']),
        kindFa = _s(j['kind_fa']),
        fromLat = _d(j['from_lat']),
        fromLon = _d(j['from_lon']),
        toLat = _d(j['to_lat']),
        toLon = _d(j['to_lon']),
        dateFa = _s(j['date_fa']),
        confidenceFa = _s(j['confidence_fa']),
        noteFa = _s(j['note_fa']),
        sourceFa = _s(j['source_fa']);
}

class UnitPosition {
  final String nameFa;
  final String side;
  final String locationFa;
  final double lat;
  final double lon;
  final String confidenceFa;
  final String noteFa;
  final String sourceFa;
  final bool uncertain;
  UnitPosition.fromJson(Map<String, dynamic> j)
      : nameFa = _s(j['name_fa']),
        side = _s(j['side']),
        locationFa = _s(j['location_fa']),
        lat = _d(j['lat']),
        lon = _d(j['lon']),
        confidenceFa = _s(j['confidence_fa']),
        noteFa = _s(j['note_fa']),
        sourceFa = _s(j['source_fa']),
        uncertain = _b(j['uncertain']);
}

class YemenEvent {
  final String eventDate;
  final String titleFa;
  final String detailFa;
  final String sourceName;
  final String sourceUrl;
  final int corroboration;
  YemenEvent.fromJson(Map<String, dynamic> j)
      : eventDate = _s(j['event_date']),
        titleFa = _s(j['title_fa']),
        detailFa = _s(j['detail_fa']),
        sourceName = _s(j['source_name']),
        sourceUrl = _s(j['source_url']),
        corroboration = _i(j['corroboration']);
}

class TitleBody {
  final String title;
  final String body;
  /// Confidence / source line, when the source item carries one.
  final String extra;
  TitleBody.fromJson(Map<String, dynamic> j)
      : title = _s(j['title']) == '' ? _s(j['title_fa']) : _s(j['title']),
        body = _s(j['body']) == '' ? _s(j['body_fa']) : _s(j['body']),
        extra = [
          _s(j['confidence_fa']),
          _s(j['source_fa']),
          _s(j['confidence']),
          _s(j['source']),
        ].where((e) => e.isNotEmpty).join(' — ');
  TitleBody.of(String t, String b)
      : title = t,
        body = b,
        extra = '';
}

class SourceLink {
  final String label;
  final String url;
  SourceLink.fromJson(Map<String, dynamic> j)
      : label = _s(j['label']),
        url = _s(j['url']);
}

class AdenImage {
  final String name;
  final String originFa;
  final String showsFa;
  final String verdictFa;
  final String? url;
  AdenImage.fromJson(Map<String, dynamic> j)
      : name = _s(j['name']),
        originFa = _s(j['origin_fa']),
        showsFa = _s(j['shows_fa']),
        verdictFa = _s(j['verdict_fa']),
        url = j['url'] == null ? null : _s(j['url']);
}

class CrossCheck {
  final String dateFa;
  final String saysFa;
  final String source;
  CrossCheck.fromJson(Map<String, dynamic> j)
      : dateFa = _s(j['date_fa']),
        saysFa = _s(j['says_fa']),
        source = _s(j['source']);
}

class Channel {
  final String name;
  final String handle;
  final String kindFa;
  final String bodyFa;
  final String verdictFa;
  Channel.fromJson(Map<String, dynamic> j)
      : name = _s(j['name']),
        handle = _s(j['handle']),
        kindFa = _s(j['kind_fa']),
        bodyFa = _s(j['body_fa']),
        verdictFa = _s(j['verdict_fa']);
}

class SocialPost {
  final String name;
  final String kindFa;
  final String dateFa;
  final String bodyFa;
  final String url;
  SocialPost.fromJson(Map<String, dynamic> j)
      : name = _s(j['name']),
        kindFa = _s(j['kind_fa']),
        dateFa = _s(j['date_fa']),
        bodyFa = _s(j['body_fa']),
        url = _s(j['url']);
}

class Amplifier {
  final String name;
  final String dateFa;
  final String bodyFa;
  final String url;
  Amplifier.fromJson(Map<String, dynamic> j)
      : name = _s(j['name']),
        dateFa = _s(j['date_fa']),
        bodyFa = _s(j['body_fa']),
        url = _s(j['url']);
}

class RingSegment {
  final String slot;
  final String statusKind;
  final String directionFa;
  final String statusFa;
  final String bodyFa;
  RingSegment.fromJson(Map<String, dynamic> j)
      : slot = _s(j['slot']),
        statusKind = _s(j['status_kind']),
        directionFa = _s(j['direction_fa']),
        statusFa = _s(j['status_fa']),
        bodyFa = _s(j['body_fa']);
}

class AdenTimelineItem {
  final String date;
  final String titleFa;
  final String bodyFa;
  final String layer;
  final String sourceFa;
  AdenTimelineItem.fromJson(Map<String, dynamic> j)
      : date = _s(j['date']),
        titleFa = _s(j['title_fa']),
        bodyFa = _s(j['body_fa']),
        layer = _s(j['layer']),
        sourceFa = _s(j['source_fa']);
}

class AdenData {
  final TitleBody verdict;
  final String verdictConfidenceFa;
  final List<RingSegment> ringSegments;
  final List<AdenTimelineItem> timeline;
  final List<String> keyPoints;
  final List<SourceLink> sources;
  final List<TitleBody> layersConfirmed;
  final List<TitleBody> layersOfficial;
  final List<TitleBody> layersRumours;
  final List<TitleBody> contradictions;
  final List<AdenImage> images;
  final List<CrossCheck> crossCheck;
  final List<Channel> channels;
  final List<SocialPost> socialPosts;
  final List<Amplifier> amplifiers;
  final String channelsMethodFa;
  final String crossCheckSummaryFa;
  final String seenNoteFa;
  final String taizNoteFa;
  final List<String> openQuestions;
  AdenData.fromJson(Map<String, dynamic> j)
      : verdict = TitleBody.fromJson(_m(j['verdict'])),
        verdictConfidenceFa = _s(_m(j['verdict'])['confidence_fa']),
        ringSegments = _l(j['ring_segments'])
            .map((e) => RingSegment.fromJson(_m(e)))
            .toList(),
        timeline = _l(j['timeline'])
            .map((e) => AdenTimelineItem.fromJson(_m(e)))
            .toList(),
        keyPoints = _l(j['key_points']).map((e) => e.toString()).toList(),
        sources =
            _l(j['sources']).map((e) => SourceLink.fromJson(_m(e))).toList(),
        layersConfirmed = _l(_m(j['layers'])['confirmed'])
            .map((e) => TitleBody.fromJson(_m(e)))
            .toList(),
        layersOfficial = _l(_m(j['layers'])['official_claims'])
            .map((e) => TitleBody.fromJson(_m(e)))
            .toList(),
        layersRumours = _l(_m(j['layers'])['rumours'])
            .map((e) => TitleBody.fromJson(_m(e)))
            .toList(),
        contradictions = _l(j['contradictions'])
            .map((e) => TitleBody.fromJson(_m(e)))
            .toList(),
        images =
            _l(j['images']).map((e) => AdenImage.fromJson(_m(e))).toList(),
        crossCheck = _l(j['cross_check'])
            .map((e) => CrossCheck.fromJson(_m(e)))
            .toList(),
        channels =
            _l(j['channels_official']).map((e) => Channel.fromJson(_m(e))).toList(),
        socialPosts = _l(j['social_posts'])
            .map((e) => SocialPost.fromJson(_m(e)))
            .toList(),
        amplifiers = _l(j['amplifiers'])
            .map((e) => Amplifier.fromJson(_m(e)))
            .toList(),
        channelsMethodFa = _s(j['channels_method_fa']),
        crossCheckSummaryFa = _s(j['cross_check_summary_fa']),
        seenNoteFa = _s(j['seen_note_fa']),
        taizNoteFa = _s(j['taiz_note_fa']),
        openQuestions =
            _l(j['open_questions']).map((e) => e.toString()).toList();
}

class HumanStat {
  final String label;
  final String value;
  final String source;
  HumanStat.fromJson(Map<String, dynamic> j)
      : label = _s(j['label']),
        value = _s(j['value']),
        source = _s(j['source']);
}

class KeyPoint {
  final String name;
  final String status;
  final String confidence;
  final String basis;
  KeyPoint.fromJson(Map<String, dynamic> j)
      : name = _s(j['name']),
        status = _s(j['status']),
        confidence = _s(j['confidence']),
        basis = _s(j['basis']);
}

class Force {
  final String name;
  final String factionKey;
  final String status;
  final String body;
  Force.fromJson(Map<String, dynamic> j)
      : name = _s(j['name']),
        factionKey = _s(j['faction_key']),
        status = _s(j['status']),
        body = _s(j['body']);
}

/// The whole Yemen document — one immutable object, one update path.
class YemenDoc {
  final String generatedAtUtc;
  final String asOf;
  final String headlineFa;
  final String frontlineWarningFa;
  final Map<String, int> counts;
  final Map<String, int> factionCounts;
  final List<Place> places;
  final List<Frontline> frontlines;
  final List<AdvanceArrow> arrows;
  final List<UnitPosition> units;
  final List<Force> forces;
  final List<YemenEvent> events;
  final Map<String, List<TitleBody>> claims;
  final AdenData aden;
  final List<HumanStat> humanStats;
  final List<SourceLink> sources;
  final List<TitleBody> mapReading;
  final List<TitleBody> militaryFeatures;
  final List<KeyPoint> keyPoints;
  final List<String> openQuestions;
  final List<TitleBody> disagreements;

  YemenDoc.fromJson(Map<String, dynamic> j)
      : generatedAtUtc = _s(j['generated_at_utc']),
        asOf = _s(j['as_of']),
        headlineFa = _s(j['headline_fa']),
        frontlineWarningFa = _s(j['frontline_warning_fa']),
        counts = _m(j['counts']).map((k, v) => MapEntry(k, _i(v))),
        factionCounts =
            _m(j['faction_counts']).map((k, v) => MapEntry(k, _i(v))),
        places =
            _l(j['places']).map((e) => Place.fromJson(_m(e))).toList(),
        frontlines = _l(j['frontlines'])
            .map((e) => Frontline.fromJson(_m(e)))
            .toList(),
        arrows = _l(j['advance_arrows'])
            .map((e) => AdvanceArrow.fromJson(_m(e)))
            .toList(),
        units = _l(j['unit_positions'])
            .map((e) => UnitPosition.fromJson(_m(e)))
            .toList(),
        forces =
            _l(j['forces']).map((e) => Force.fromJson(_m(e))).toList(),
        events = _l(j['events'])
            .map((e) => YemenEvent.fromJson(_m(e)))
            .toList(),
        claims = _m(j['claims']).map((k, v) => MapEntry(
            k, _l(v).map((e) => TitleBody.fromJson(_m(e))).toList())),
        aden = AdenData.fromJson(_m(j['aden'])),
        humanStats = _l(j['human_stats'])
            .map((e) => HumanStat.fromJson(_m(e)))
            .toList(),
        sources =
            _l(j['sources']).map((e) => SourceLink.fromJson(_m(e))).toList(),
        mapReading = _l(j['map_reading'])
            .map((e) => TitleBody.fromJson(_m(e)))
            .toList(),
        militaryFeatures = _l(j['military_features'])
            .map((e) => TitleBody.fromJson(_m(e)))
            .toList(),
        keyPoints = _l(j['key_points'])
            .map((e) => KeyPoint.fromJson(_m(e)))
            .toList(),
        openQuestions =
            _l(j['open_questions']).map((e) => e.toString()).toList(),
        disagreements = _l(j['disagreements'])
            .map((e) => TitleBody.fromJson(_m(e)))
            .toList();

  DateTime? get generatedAt => DateTime.tryParse(generatedAtUtc);
}

/// Top-level parse function — the ONLY JSON parsing entry point, and the
/// only thing ever run on an isolate via `compute`. It must stay top-level
/// (compute cannot call a closure or a method).
YemenDoc parseYemenDoc(String jsonText) {
  final decoded = jsonDecode(jsonText);
  if (decoded is! Map<String, dynamic>) {
    throw const FormatException('yemen.json: top level is not an object');
  }
  return YemenDoc.fromJson(decoded);
}

/// Faction display metadata — single definition used by the map and lists.
class FactionMeta {
  final String key;
  final String labelFa;
  final int color; // ARGB
  const FactionMeta(this.key, this.labelFa, this.color);
}

const List<FactionMeta> factionMetas = [
  FactionMeta('houthi', 'حوثی / انصارالله', 0xFF2E7D32),
  FactionMeta('govt', 'دولت / نیروهای جنوب', 0xFF1565C0),
  FactionMeta('contested', 'مورد مناقشه', 0xFFF9A825),
  FactionMeta('stc', 'شورای انتقالی جنوب', 0xFF6A1B9A),
  FactionMeta('aqap', 'القاعده', 0xFFB71C1C),
  FactionMeta('unknown', 'نامشخص', 0xFF757575),
];

FactionMeta factionMeta(String key) => factionMetas.firstWhere(
      (f) => f.key == key,
      orElse: () => factionMetas.last,
    );
