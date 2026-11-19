// Real Yemen map (v6.1.0) — flutter_map over OpenStreetMap tiles, with
// the bundled HDX COD-AB governorate boundaries drawn on top.
//
// Layers, bottom to top:
//  * TileLayer — OSM standard tiles, User-Agent = the real applicationId
//    (com.ecojournal.app, from android/app/build.gradle.kts), cached
//    in-session by flutter_map_cache (MemCacheStore, maxStale 7 days,
//    honouring cache headers). No bulk pre-seeding: OSM tile policy
//    forbids it, and the built-in cache is not a guaranteed offline mode.
//  * PolygonLayer — the 22 bundled governorates, each filled/tinted by
//    the faction controlling most of its places (from yemen.json), with
//    visible borders and a name label at the centroid.
//  * PolygonLayer — the country outline (border only).
//  * PolylineLayer — the 8 frontlines + the dashed Aden ring.
//  * PolylineLayer + head polygons — the 8 advance arrows.
//  * MarkerLayer — place dots (faction-coloured) and unit diamonds.
//  * RichAttributionWidget — © OpenStreetMap contributors + the OCHA
//    HDX boundary attribution (CC BY-IGO), always visible.
//
// Fallback: if tiles are unreachable (many tile errors), the widget
// switches to the old schematic Canvas map (map_painter.dart) with a
// notice and a retry button. The schematic is ONLY a fallback — the
// real map is the primary. The ±10 km approximation warning stays
// under the map, rendered by the status tab.
import 'dart:convert';
import 'dart:math' as math;

import 'package:dio_cache_interceptor/dio_cache_interceptor.dart';
import 'package:flutter/foundation.dart' show compute;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_map_cache/flutter_map_cache.dart';
import 'package:latlong2/latlong.dart';

import 'fronts.dart';
import 'map_painter.dart';
import 'yemen.dart';

/// The real applicationId (android/app/build.gradle.kts) — the tile
/// User-Agent. Never com.example.
const String kTileUserAgentPackageName = 'com.ecojournal.app';

/// OSM standard tile template (operations policy: a valid User-Agent
/// identifying the application is mandatory).
const String kOsmTileUrlTemplate =
    'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

/// One governorate from yemen-adm1.geojson. [rings] holds the outer ring
/// of every polygon of the feature (holes are not drawn — display only).
class GovFeature {
  final String nameEn;
  final String nameAr;
  final String pcode;
  final List<List<LatLng>> rings;
  const GovFeature({
    required this.nameEn,
    required this.nameAr,
    required this.pcode,
    required this.rings,
  });

  /// Rough centroid (mean of the outer-ring points) — label anchor only.
  LatLng get centroid {
    var lat = 0.0;
    var lon = 0.0;
    var n = 0;
    for (final ring in rings) {
      for (final p in ring) {
        lat += p.latitude;
        lon += p.longitude;
        n++;
      }
    }
    if (n == 0) return const LatLng(15.55, 47.5);
    return LatLng(lat / n, lon / n);
  }
}

/// Top-level GeoJSON parse — the only thing run on an isolate via
/// `compute`. Handles Polygon and MultiPolygon; keeps the outer ring of
/// each polygon. Throws [FormatException] on a malformed file.
List<GovFeature> parseAdm1Geojson(String jsonText) {
  final decoded = jsonDecode(jsonText);
  if (decoded is! Map<String, dynamic>) {
    throw const FormatException('yemen-adm1.geojson: not an object');
  }
  final features = decoded['features'];
  if (features is! List) {
    throw const FormatException('yemen-adm1.geojson: no features list');
  }
  final out = <GovFeature>[];
  for (final raw in features) {
    if (raw is! Map<String, dynamic>) continue;
    final props = raw['properties'] is Map
        ? Map<String, dynamic>.from(raw['properties'] as Map)
        : const <String, dynamic>{};
    final geom = raw['geometry'] is Map
        ? Map<String, dynamic>.from(raw['geometry'] as Map)
        : const <String, dynamic>{};
    final type = geom['type']?.toString() ?? '';
    final coords = geom['coordinates'];
    if (coords is! List) continue;
    final rings = <List<LatLng>>[];
    // Polygon: coords = [ring, hole?...]; MultiPolygon: [polygon, ...].
    final polygons = type == 'MultiPolygon'
        ? coords
        : (type == 'Polygon' ? [coords] : const []);
    for (final polygon in polygons) {
      if (polygon is! List || polygon.isEmpty) continue;
      final outer = polygon[0];
      if (outer is! List) continue;
      final ring = <LatLng>[];
      for (final pt in outer) {
        if (pt is List && pt.length >= 2 && pt[0] is num && pt[1] is num) {
          // GeoJSON is [lon, lat].
          ring.add(LatLng((pt[1] as num).toDouble(), (pt[0] as num).toDouble()));
        }
      }
      if (ring.length >= 3) rings.add(ring);
    }
    if (rings.isEmpty) continue;
    out.add(GovFeature(
      nameEn: props['adm1_name']?.toString() ?? '',
      nameAr: props['adm1_name1']?.toString() ?? '',
      pcode: props['adm1_pcode']?.toString() ?? '',
      rings: rings,
    ));
  }
  return out;
}

/// Top-level parse for the country outline (MultiPolygon, 1 feature) —
/// returns its outer rings.
List<List<LatLng>> parseOutlineGeojson(String jsonText) {
  final decoded = jsonDecode(jsonText);
  if (decoded is! Map<String, dynamic>) {
    throw const FormatException('yemen-outline.geojson: not an object');
  }
  final features = decoded['features'];
  if (features is! List || features.isEmpty) return const [];
  final first = features[0];
  if (first is! Map<String, dynamic>) return const [];
  final geom = first['geometry'] is Map
      ? Map<String, dynamic>.from(first['geometry'] as Map)
      : const <String, dynamic>{};
  final coords = geom['coordinates'];
  if (coords is! List) return const [];
  final rings = <List<LatLng>>[];
  for (final polygon in coords) {
    if (polygon is! List || polygon.isEmpty) continue;
    final outer = polygon[0];
    if (outer is! List) continue;
    final ring = <LatLng>[];
    for (final pt in outer) {
      if (pt is List && pt.length >= 2 && pt[0] is num && pt[1] is num) {
        ring.add(LatLng((pt[1] as num).toDouble(), (pt[0] as num).toDouble()));
      }
    }
    if (ring.length >= 3) rings.add(ring);
  }
  return rings;
}

/// Normalise a governorate name so Persian place data
/// (`governorate_fa`, e.g. «صنعا», «ابین») matches the Arabic GeoJSON
/// name (`adm1_name1`, e.g. «صنعاء», «ابين»): unifies ي/ى/ك/ة/ء forms,
/// strips a leading «ال», and takes the first segment before '(' or '/'
/// («تعز (تنگه)» -> «تعز», «تعز/لحج» -> «تعز»).
String normalizeGovernorateName(String raw) {
  var t = raw.trim();
  for (final sep in ['(', '/']) {
    final i = t.indexOf(sep);
    if (i > 0) t = t.substring(0, i).trim();
  }
  t = t
      .replaceAll('ي', 'ی')
      .replaceAll('ى', 'ا')
      .replaceAll('ك', 'ک')
      .replaceAll('ة', 'ه')
      .replaceAll('ء', '')
      .replaceAll('أ', 'ا')
      .replaceAll('إ', 'ا')
      .replaceAll('آ', 'ا');
  if (t.startsWith('ال')) t = t.substring(2);
  return t;
}

/// The faction controlling most places of a governorate (by place
/// count); 'unknown' when the governorate has no places.
String dominantFaction(Map<String, int> factionCounts) {
  if (factionCounts.isEmpty) return 'unknown';
  var best = 'unknown';
  var bestN = -1;
  for (final e in factionCounts.entries) {
    if (e.value > bestN) {
      best = e.key;
      bestN = e.value;
    }
  }
  return best;
}

class _LoadedGeo {
  final List<GovFeature> governorates;
  final List<List<LatLng>> outlineRings;
  const _LoadedGeo(this.governorates, this.outlineRings);
}

Future<_LoadedGeo> _loadGeo(Front front) async {
  final adm1Path = front.geojsonAsset ?? 'assets/geo/yemen-adm1.geojson';
  final adm1Text = await rootBundle.loadString(adm1Path);
  final outlineText =
      await rootBundle.loadString('assets/geo/yemen-outline.geojson');
  final govs = await compute(parseAdm1Geojson, adm1Text);
  final outline = await compute(parseOutlineGeojson, outlineText);
  return _LoadedGeo(govs, outline);
}

/// The real map. Falls back to the schematic painter only when tiles
/// are unreachable (see [_RealMapWidgetState]).
class RealMapWidget extends StatefulWidget {
  final YemenDoc doc;
  final Front front;
  const RealMapWidget({required this.doc, required this.front, super.key});

  @override
  State<RealMapWidget> createState() => _RealMapWidgetState();
}

class _RealMapWidgetState extends State<RealMapWidget> {
  late final Future<_LoadedGeo> _geoFuture;
  late final CachedTileProvider _tileProvider;
  int _tileErrors = 0;
  bool _tilesUnreachable = false;

  /// Switch to the schematic fallback after this many tile errors —
  /// a full screen at zoom 6 is ~10–20 tiles, so a single bad tile
  /// never flips the map.
  static const int _tileErrorThreshold = 8;

  @override
  void initState() {
    super.initState();
    _geoFuture = _loadGeo(widget.front);
    // In-session tile cache, honouring cache headers with a 7-day
    // max-stale window. flutter_map_cache 2.1.2 ships only an in-memory
    // store (the persistent DB store was split into a separate package
    // in dio_cache_interceptor v4), so tiles re-download after a
    // restart — no bulk pre-seeding, which OSM policy forbids anyway.
    _tileProvider = CachedTileProvider(
      store: MemCacheStore(),
      maxStale: const Duration(days: 7),
    );
  }

  void _onTileError(dynamic tile, Object error, StackTrace? stackTrace) {
    _tileErrors++;
    if (_tileErrors >= _tileErrorThreshold && !_tilesUnreachable) {
      setState(() => _tilesUnreachable = true);
    }
  }

  void _retryRealMap() {
    setState(() {
      _tileErrors = 0;
      _tilesUnreachable = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<_LoadedGeo>(
      future: _geoFuture,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          // GeoJSON itself failed to load — schematic fallback.
          return _fallbackBox(
            'مرزهای استان‌ها خوانده نشد؛ نقشهٔ شماتیک نمایش داده می‌شود.',
          );
        }
        if (!snapshot.hasData) {
          return const SizedBox(
            height: 400,
            child: Center(child: CircularProgressIndicator()),
          );
        }
        if (_tilesUnreachable) {
          return _fallbackBox(
            'کاشی‌های نقشه بارگذاری نشد (دسترسی به اینترنت یا سرور نقشه نیست)؛ نقشهٔ شماتیک نمایش داده می‌شود.',
            onRetry: _retryRealMap,
          );
        }
        return _buildRealMap(snapshot.data!);
      },
    );
  }

  /// Schematic fallback box (map_painter) + notice + optional retry.
  Widget _fallbackBox(String notice, {VoidCallback? onRetry}) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: Colors.orange.shade50,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: Colors.orange.shade200),
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(notice,
                    style: const TextStyle(fontSize: 13, height: 1.7)),
              ),
              if (onRetry != null)
                TextButton(
                  onPressed: onRetry,
                  child: const Text('تلاش دوباره برای نقشهٔ واقعی'),
                ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        AspectRatio(
          aspectRatio: 13 / 8,
          child: Container(
            decoration: BoxDecoration(
              border: Border.all(color: Colors.black26),
              borderRadius: BorderRadius.circular(8),
            ),
            clipBehavior: Clip.antiAlias,
            child: CustomPaint(
              painter: YemenMapPainter(widget.doc),
              child: const SizedBox.expand(),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildRealMap(_LoadedGeo geo) {
    final doc = widget.doc;
    final front = widget.front;

    // Governorate tint: dominant faction of its places.
    final govFactionCounts = <String, Map<String, int>>{};
    for (final place in doc.places) {
      for (final govPart in place.governorateFa.split('/')) {
        final key = normalizeGovernorateName(govPart);
        if (key.isEmpty) continue;
        final counts = govFactionCounts.putIfAbsent(key, () => {});
        counts[place.faction] = (counts[place.faction] ?? 0) + 1;
      }
    }

    final govPolygons = <Polygon>[];
    for (final gov in geo.governorates) {
      final key = normalizeGovernorateName(gov.nameAr);
      final faction =
          dominantFaction(govFactionCounts[key] ?? const {});
      final base = Color(factionMeta(faction).color);
      for (final ring in gov.rings) {
        govPolygons.add(Polygon(
          points: ring,
          color: base.withValues(alpha: 0.28),
          borderColor: base.withValues(alpha: 0.85),
          borderStrokeWidth: 1.2,
        ));
      }
    }

    final outlinePolygons = <Polygon>[
      for (final ring in geo.outlineRings)
        Polygon(
          points: ring,
          color: Colors.transparent,
          borderColor: const Color(0xFF37474F),
          borderStrokeWidth: 2.0,
        ),
    ];

    final frontlinePolylines = <Polyline>[
      for (final line in doc.frontlines)
        if (line.waypoints.length >= 2)
          Polyline(
            points: [
              for (final w in line.waypoints) LatLng(w.lat, w.lon),
            ],
            color: const Color(0xFF8E2424),
            strokeWidth: 2.5,
          ),
    ];

    // Advance arrows: line + triangular head at the tip.
    final arrowPolylines = <Polyline>[];
    final arrowHeads = <Polygon>[];
    for (final arrow in doc.arrows) {
      final from = LatLng(arrow.fromLat, arrow.fromLon);
      final to = LatLng(arrow.toLat, arrow.toLon);
      final color = Color(factionMeta(arrow.side).color);
      arrowPolylines.add(Polyline(
        points: [from, to],
        color: color,
        strokeWidth: 3.0,
      ));
      // Head: tip + two base points, in degrees (lon scaled by cos lat).
      final dLat = to.latitude - from.latitude;
      final dLon = (to.longitude - from.longitude) *
          math.cos(to.latitude * math.pi / 180);
      final len = math.sqrt(dLat * dLat + dLon * dLon);
      if (len > 0) {
        const headLen = 0.14; // degrees of latitude
        const headHalfWidth = 0.055;
        final uLat = dLat / len;
        final uLon = dLon / len;
        final pLat = -uLon;
        final pLon = uLat;
        final baseLat = to.latitude - uLat * headLen;
        final baseLon = to.longitude -
            (uLon * headLen) / math.cos(to.latitude * math.pi / 180);
        final cosLat = math.cos(to.latitude * math.pi / 180);
        arrowHeads.add(Polygon(
          points: [
            to,
            LatLng(baseLat + pLat * headHalfWidth,
                baseLon + (pLon * headHalfWidth) / cosLat),
            LatLng(baseLat - pLat * headHalfWidth,
                baseLon - (pLon * headHalfWidth) / cosLat),
          ],
          color: color,
          borderColor: color,
          borderStrokeWidth: 0,
        ));
      }
    }

    // Dashed Aden ring (~30 km around Aden city).
    final aden = doc.places.where((p) => p.nameFa == 'شهر عدن').toList();
    final adenRing = <Polyline>[];
    if (aden.isNotEmpty) {
      const radiusDeg = 0.28;
      final centre = LatLng(aden.first.lat, aden.first.lon);
      final pts = <LatLng>[];
      for (var i = 0; i <= 48; i++) {
        final a = i * 2 * math.pi / 48;
        pts.add(LatLng(
          centre.latitude + radiusDeg * math.sin(a),
          centre.longitude +
              radiusDeg * math.cos(a) / math.cos(centre.latitude * math.pi / 180),
        ));
      }
      adenRing.add(Polyline(
        points: pts,
        color: const Color(0xFFB71C1C),
        strokeWidth: 2.5,
        pattern: StrokePattern.dashed(segments: const [10, 8]),
      ));
    }

    final markers = <Marker>[
      for (final place in doc.places)
        Marker(
          point: LatLng(place.lat, place.lon),
          width: 12,
          height: 12,
          child: Container(
            decoration: BoxDecoration(
              color: Color(factionMeta(place.faction).color),
              shape: BoxShape.circle,
              border: Border.all(color: Colors.white, width: 1.5),
            ),
          ),
        ),
      for (final unit in doc.units)
        Marker(
          point: LatLng(unit.lat, unit.lon),
          width: 14,
          height: 14,
          child: Transform.rotate(
            angle: math.pi / 4,
            child: Container(
              decoration: BoxDecoration(
                color: Color(factionMeta(unit.side).color),
                border: Border.all(color: Colors.white, width: 1.5),
              ),
            ),
          ),
        ),
      for (final gov in geo.governorates)
        Marker(
          point: gov.centroid,
          width: 100,
          height: 20,
          child: Text(
            gov.nameAr,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.bold,
              color: Color(0xFF1A1A1A),
              shadows: [
                Shadow(color: Colors.white, blurRadius: 3),
                Shadow(color: Colors.white, blurRadius: 3),
              ],
            ),
          ),
        ),
    ];

    return SizedBox(
      height: 400,
      child: Container(
        decoration: BoxDecoration(
          border: Border.all(color: Colors.black26),
          borderRadius: BorderRadius.circular(8),
        ),
        clipBehavior: Clip.antiAlias,
        child: FlutterMap(
          options: MapOptions(
            initialCenter:
                LatLng(front.mapCenter[0], front.mapCenter[1]),
            initialZoom: front.mapZoom,
            minZoom: front.minZoom,
            maxZoom: front.maxZoom,
            backgroundColor: const Color(0xFFF4EFE6),
          ),
          children: [
            TileLayer(
              urlTemplate: kOsmTileUrlTemplate,
              userAgentPackageName: kTileUserAgentPackageName,
              tileProvider: _tileProvider,
              errorTileCallback: _onTileError,
            ),
            PolygonLayer(polygons: govPolygons),
            PolygonLayer(polygons: outlinePolygons),
            PolylineLayer(polylines: frontlinePolylines),
            PolylineLayer(polylines: arrowPolylines),
            PolygonLayer(polygons: arrowHeads),
            PolylineLayer(polylines: adenRing),
            MarkerLayer(markers: markers),
            const RichAttributionWidget(
              attributions: [
                TextSourceAttribution('OpenStreetMap contributors'),
                TextSourceAttribution(
                  'Yemen boundaries: OCHA HDX COD-AB, CSO Yemen (CC BY-IGO)',
                  prependCopyright: false,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
