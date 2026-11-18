// Data layer — plain JSON files only. No database, no workers, no mutex.
//
// Anti-freeze contract (the whole point of the v6 rebuild):
//  * network is `dart:io` HttpClient — async, never blocks the UI thread;
//  * JSON parsing always runs in an isolate via `compute(parseYemenDoc, …)`;
//  * file reads/writes are async (`readAsString` / `writeAsString`);
//  * NOTHING in this file is ever awaited on the UI build path — callers
//    fire it from event handlers / initState and update state when it lands.
//  * there is no shared lock between UI refresh and background refresh:
//    a background pass can never make a manual tap wait.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:path_provider/path_provider.dart';

import 'yemen.dart';

class YemenRepository {
  static const liveUrl = 'https://syfazel.github.io/eco-journal-app/yemen.json';
  static const _cacheFileName = 'yemen_cache.json';
  static const _snapshotsDirName = 'snapshots';
  static const _snapshotsIndexName = 'snapshots_index.json';

  /// Bundled seed — renders immediately on first launch, even offline.
  /// Asset load is async; the ~249 KB parse runs on an isolate.
  static Future<LoadedDoc> loadSeed() async {
    final text = await rootBundle.loadString('assets/seed/yemen.json');
    return LoadedDoc(await compute(parseYemenDoc, text), text);
  }

  /// Live fetch from GitHub Pages. 15 s timeout, one attempt only —
  /// no retry loop on the UI path (a retry is the user's tap on «تلاش دوباره»).
  /// On success the body is also saved as the plain-JSON cache file.
  static Future<LoadedDoc> fetchLive() async {
    final client = HttpClient();
    client.connectionTimeout = const Duration(seconds: 15);
    try {
      final request = await client
          .getUrl(Uri.parse(liveUrl))
          .timeout(const Duration(seconds: 15));
      final response = await request.close().timeout(const Duration(seconds: 15));
      if (response.statusCode != 200) {
        throw HttpException(
            'live fetch: HTTP ${response.statusCode}', uri: Uri.parse(liveUrl));
      }
      final text = await response
          .transform(utf8.decoder)
          .join()
          .timeout(const Duration(seconds: 15));
      final doc = await compute(parseYemenDoc, text);
      // Cache best-effort: a cache-write failure must never fail the refresh.
      try {
        final dir = await getApplicationDocumentsDirectory();
        await File('${dir.path}/$_cacheFileName')
            .writeAsString(text, flush: true);
      } catch (_) {
        // Cache is a convenience; the fresh doc is already in hand.
      }
      return LoadedDoc(doc, text);
    } on TimeoutException {
      throw TimeoutException('live fetch timed out');
    } finally {
      client.close();
    }
  }

  /// Last successfully fetched live document, if any.
  static Future<LoadedDoc?> loadCache() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final file = File('${dir.path}/$_cacheFileName');
      if (!await file.exists()) return null;
      final text = await file.readAsString();
      return LoadedDoc(await compute(parseYemenDoc, text), text);
    } catch (_) {
      return null;
    }
  }

  // ------------------------------------------------------------------
  // Local snapshots: plain JSON files, one per snapshot.
  // A snapshot file IS a yemen.json document (same schema), so viewing
  // one is just parsing it with the same isolate entry point. The index
  // file carries only labels/dates/counts for the list view.
  // ------------------------------------------------------------------

  static Future<Directory> _snapshotsDir() async {
    final dir = await getApplicationDocumentsDirectory();
    final snap = Directory('${dir.path}/$_snapshotsDirName');
    if (!await snap.exists()) await snap.create(recursive: true);
    return snap;
  }

  static Future<List<SnapshotMeta>> listSnapshots() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final indexFile = File('${dir.path}/$_snapshotsIndexName');
      if (!await indexFile.exists()) return const [];
      final raw = jsonDecode(await indexFile.readAsString());
      if (raw is! List) return const [];
      return raw
          .map((e) => SnapshotMeta.fromJson(
              e is Map ? Map<String, dynamic>.from(e) : const {}))
          .toList();
    } catch (_) {
      return const [];
    }
  }

  /// Create a snapshot from the exact raw JSON text that was loaded
  /// (seed / cache / live) — byte-faithful, never re-serialised.
  static Future<List<SnapshotMeta>> createSnapshot(
      YemenDoc doc, String rawJson) async {
    final snapDir = await _snapshotsDir();
    final now = DateTime.now().toUtc();
    final stamp = now.toIso8601String().replaceAll(RegExp(r'[^0-9]'), '');
    final fileName = 'snapshot_$stamp.json';
    await File('${snapDir.path}/$fileName').writeAsString(rawJson, flush: true);
    final meta = SnapshotMeta(
      fileName: fileName,
      labelFa: 'اسنپ‌شات ${doc.places.length} نقطه / ${doc.events.length} رویداد',
      createdAtUtc: now.toIso8601String(),
      placesCount: doc.places.length,
      eventsCount: doc.events.length,
      headlineFa: doc.headlineFa,
    );
    final existing = await listSnapshots();
    final updated = [meta, ...existing];
    final dir = await getApplicationDocumentsDirectory();
    await File('${dir.path}/$_snapshotsIndexName').writeAsString(
        jsonEncode(updated.map((m) => m.toJson()).toList()),
        flush: true);
    return updated;
  }

  static Future<LoadedDoc?> loadSnapshot(String fileName) async {
    try {
      final snapDir = await _snapshotsDir();
      final text = await File('${snapDir.path}/$fileName').readAsString();
      return LoadedDoc(await compute(parseYemenDoc, text), text);
    } catch (_) {
      return null;
    }
  }

  static Future<List<SnapshotMeta>> deleteSnapshot(String fileName) async {
    final snapDir = await _snapshotsDir();
    try {
      await File('${snapDir.path}/$fileName').delete();
    } catch (_) {}
    final remaining =
        (await listSnapshots()).where((m) => m.fileName != fileName).toList();
    final dir = await getApplicationDocumentsDirectory();
    await File('${dir.path}/$_snapshotsIndexName').writeAsString(
        jsonEncode(remaining.map((m) => m.toJson()).toList()),
        flush: true);
    return remaining;
  }
}

/// A parsed document together with the exact raw JSON it came from.
/// The raw text is what snapshots store, so a snapshot is byte-faithful
/// to the fetched / seeded file — never re-serialised from the model.
class LoadedDoc {
  final YemenDoc doc;
  final String rawJson;
  const LoadedDoc(this.doc, this.rawJson);
}

class SnapshotMeta {
  final String fileName;
  final String labelFa;
  final String createdAtUtc;
  final int placesCount;
  final int eventsCount;
  final String headlineFa;
  const SnapshotMeta({
    required this.fileName,
    required this.labelFa,
    required this.createdAtUtc,
    required this.placesCount,
    required this.eventsCount,
    required this.headlineFa,
  });

  factory SnapshotMeta.fromJson(Map<String, dynamic> j) => SnapshotMeta(
        fileName: j['file_name']?.toString() ?? '',
        labelFa: j['label_fa']?.toString() ?? '',
        createdAtUtc: j['created_at_utc']?.toString() ?? '',
        placesCount: j['places_count'] is num
            ? (j['places_count'] as num).toInt()
            : 0,
        eventsCount: j['events_count'] is num
            ? (j['events_count'] as num).toInt()
            : 0,
        headlineFa: j['headline_fa']?.toString() ?? '',
      );

  Map<String, dynamic> toJson() => {
        'file_name': fileName,
        'label_fa': labelFa,
        'created_at_utc': createdAtUtc,
        'places_count': placesCount,
        'events_count': eventsCount,
        'headline_fa': headlineFa,
      };
}
