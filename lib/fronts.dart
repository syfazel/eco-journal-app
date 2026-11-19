// Front registry — the generic front architecture (v6.1.0).
//
// The app renders its fronts from config, not from hard-coded UI:
// `edge/fronts.json` is bundled as an asset (and mirrored inside
// yemen.json under the `fronts` key). Yemen is front #1 and the only
// active front; Ukraine is a prepared scaffold — coming_soon, no data
// file, no GeoJSON, no feed channels, NO fake places or events.
// Adding a future front is data/config only: one registry entry + its
// data file + its GeoJSON, no code change.
import 'dart:convert';

import 'package:flutter/services.dart' show rootBundle;

String _s(dynamic v, [String fallback = '']) =>
    v == null ? fallback : v.toString();
List<dynamic> _l(dynamic v) => v is List ? v : const [];
Map<String, dynamic> _m(dynamic v) =>
    v is Map ? Map<String, dynamic>.from(v) : const {};
double _d(dynamic v, [double fallback = 0.0]) =>
    v is num ? v.toDouble() : double.tryParse(v.toString()) ?? fallback;

enum FrontStatus { active, comingSoon }

class Front {
  final String id;
  final String nameFa;
  final String nameEn;
  final FrontStatus status;
  final String? dataFile;
  final List<double> mapCenter; // [lat, lon]
  final double mapZoom;
  final double minZoom;
  final double maxZoom;
  final String? geojsonAsset;
  final List<String> feedChannels;
  final String? noteFa;
  final int? taxonomyVersion;

  const Front({
    required this.id,
    required this.nameFa,
    required this.nameEn,
    required this.status,
    required this.dataFile,
    required this.mapCenter,
    required this.mapZoom,
    required this.minZoom,
    required this.maxZoom,
    required this.geojsonAsset,
    required this.feedChannels,
    required this.noteFa,
    required this.taxonomyVersion,
  });

  factory Front.fromJson(Map<String, dynamic> j) {
    final map = _m(j['map']);
    final center = _l(map['center']);
    return Front(
      id: _s(j['id']),
      nameFa: _s(j['name_fa']),
      nameEn: _s(j['name_en']),
      status: _s(j['status']) == 'active'
          ? FrontStatus.active
          : FrontStatus.comingSoon,
      dataFile: j['data_file'] == null ? null : _s(j['data_file']),
      mapCenter: center.length >= 2
          ? [_d(center[0], 15.55), _d(center[1], 47.5)]
          : const [15.55, 47.5],
      mapZoom: _d(map['zoom'], 6),
      minZoom: _d(map['min_zoom'], 5),
      maxZoom: _d(map['max_zoom'], 12),
      geojsonAsset: j['geojson'] == null ? null : _s(j['geojson']),
      feedChannels:
          _l(j['feed_channels']).map((e) => e.toString()).toList(),
      noteFa: j['note_fa'] == null ? null : _s(j['note_fa']),
      taxonomyVersion: j['taxonomy_version'] is num
          ? (j['taxonomy_version'] as num).toInt()
          : null,
    );
  }

  bool get isActive => status == FrontStatus.active;

  /// A coming-soon front must carry no data at all — enforce it here
  /// so a bad registry entry can never render fake content.
  bool get hasNoData =>
      dataFile == null && geojsonAsset == null && feedChannels.isEmpty;
}

class FrontRegistry {
  final int version;
  final List<Front> fronts;
  const FrontRegistry({required this.version, required this.fronts});

  factory FrontRegistry.fromJson(Map<String, dynamic> j) => FrontRegistry(
        version: j['version'] is num ? (j['version'] as num).toInt() : 1,
        fronts:
            _l(j['fronts']).map((e) => Front.fromJson(_m(e))).toList(),
      );

  Front? byId(String id) {
    for (final f in fronts) {
      if (f.id == id) return f;
    }
    return null;
  }

  List<Front> get activeFronts =>
      fronts.where((f) => f.isActive).toList();

  List<Front> get comingSoonFronts =>
      fronts.where((f) => !f.isActive).toList();
}

/// Top-level parse function — the only JSON parsing entry point for the
/// registry, testable without any asset loading.
FrontRegistry parseFrontRegistry(String jsonText) {
  final decoded = jsonDecode(jsonText);
  if (decoded is! Map<String, dynamic>) {
    throw const FormatException('fronts.json: top level is not an object');
  }
  return FrontRegistry.fromJson(decoded);
}

/// Bundled registry asset. Falls back to the Yemen-only built-in
/// registry if the asset is missing, so the app never renders empty.
Future<FrontRegistry> loadFrontRegistry() async {
  try {
    final text = await rootBundle.loadString('edge/fronts.json');
    return parseFrontRegistry(text);
  } catch (_) {
    return const FrontRegistry(version: 1, fronts: [_yemenFallback]);
  }
}

const Front _yemenFallback = Front(
  id: 'yemen',
  nameFa: 'یمن',
  nameEn: 'Yemen',
  status: FrontStatus.active,
  dataFile: 'yemen.json',
  mapCenter: [15.55, 47.5],
  mapZoom: 6,
  minZoom: 5,
  maxZoom: 12,
  geojsonAsset: 'assets/geo/yemen-adm1.geojson',
  feedChannels: ['army21ye', 'MilitaryMediaY', 'Almasirah_En'],
  noteFa: null,
  taxonomyVersion: 1,
);
