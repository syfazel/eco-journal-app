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
}

/// A parsed document together with the exact raw JSON it came from.
class LoadedDoc {
  final YemenDoc doc;
  final String rawJson;
  const LoadedDoc(this.doc, this.rawJson);
}
