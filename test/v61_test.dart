// v6.1.0 tests: taxonomy, feeds, fronts registry, GeoJSON assets,
// and the no-archive assertion. All assertions run against the real
// bundled assets (seed yemen.json, edge/fronts.json, GeoJSON), not
// invented fixtures.
import 'dart:io';

import 'package:ecojournal_yemen/fronts.dart';
import 'package:ecojournal_yemen/real_map.dart';
import 'package:ecojournal_yemen/yemen.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:flutter_test/flutter_test.dart';

Future<YemenDoc> loadSeedDoc() async {
  TestWidgetsFlutterBinding.ensureInitialized();
  final text = await rootBundle.loadString('assets/seed/yemen.json');
  return parseYemenDoc(text);
}

void main() {
  test('taxonomy: every event has a valid type, confidence and front',
      () async {
    final doc = await loadSeedDoc();
    expect(doc.events.length, 20);
    expect(doc.taxonomyVersion, 1);
    for (final e in doc.events) {
      expect(eventTypeLabelsFa, contains(e.eventType),
          reason: 'event ${e.titleFa} has unknown type ${e.eventType}');
      expect(eventConfidenceLabelsFa, contains(e.confidence),
          reason: 'event ${e.titleFa} has unknown confidence ${e.confidence}');
      expect(e.front, 'yemen');
    }
    final byType = <String, int>{};
    for (final e in doc.events) {
      byType[e.eventType] = (byType[e.eventType] ?? 0) + 1;
    }
    expect(byType, {
      'advance': 9,
      'missile_drone': 7,
      'clash': 2,
      'claim': 1,
      'humanitarian': 1,
    });
    final byConf = <String, int>{};
    for (final e in doc.events) {
      byConf[e.confidence] = (byConf[e.confidence] ?? 0) + 1;
    }
    expect(byConf, {'ansarullah-confirmed': 2, 'unverified': 18});
    // Labels exist for every taxonomy value the UI can show.
    expect(eventTypeLabelsFa.length, 8);
    expect(eventConfidenceLabelsFa.length, 3);
  });

  test('feeds: 58 verbatim Ansarullah posts, grouped by channel',
      () async {
    final doc = await loadSeedDoc();
    final feeds = doc.feeds;
    expect(feeds.items.length, 58);
    expect(feeds.unavailable, isEmpty);
    expect(feeds.fetchedAt, isNotNull);
    final byChannel = feeds.byChannel;
    expect(byChannel.keys.toSet(),
        {'army21ye', 'MilitaryMediaY', 'Almasirah_En'});
    expect(byChannel['army21ye']!.length, 19);
    expect(byChannel['MilitaryMediaY']!.length, 19);
    expect(byChannel['Almasirah_En']!.length, 20);
    for (final item in feeds.items) {
      expect(item.tier, 'ansarullah-official');
      expect(item.url, startsWith('https://t.me/'));
      expect(item.textFa, isNotEmpty);
      expect(item.authorFa, isNotEmpty);
      expect(item.publishedAtDt, isNotNull);
    }
    // Newest first inside each channel group.
    for (final list in byChannel.values) {
      for (var i = 0; i + 1 < list.length; i++) {
        expect(
          list[i].publishedAtDt!.isAfter(list[i + 1].publishedAtDt!) ||
              list[i].publishedAtDt!.isAtSameMomentAs(list[i + 1].publishedAtDt!),
          isTrue,
        );
      }
    }
    // Channel labels exist for all three feeds.
    expect(feedChannelLabelsFa.keys.toSet(),
        {'army21ye', 'MilitaryMediaY', 'Almasirah_En'});
  });

  test('fronts registry: yemen active, ukraine coming-soon with no data',
      () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    final text = await rootBundle.loadString('edge/fronts.json');
    final registry = parseFrontRegistry(text);
    expect(registry.fronts.length, 2);
    final yemen = registry.byId('yemen')!;
    expect(yemen.isActive, isTrue);
    expect(yemen.nameFa, 'یمن');
    expect(yemen.dataFile, 'yemen.json');
    expect(yemen.mapCenter, [15.55, 47.5]);
    expect(yemen.mapZoom, 6);
    expect(yemen.geojsonAsset, 'assets/geo/yemen-adm1.geojson');
    expect(yemen.feedChannels,
        ['army21ye', 'MilitaryMediaY', 'Almasirah_En']);
    final ukraine = registry.byId('ukraine')!;
    expect(ukraine.isActive, isFalse);
    expect(ukraine.nameFa, 'اوکراین');
    expect(ukraine.dataFile, isNull);
    expect(ukraine.geojsonAsset, isNull);
    expect(ukraine.feedChannels, isEmpty);
    expect(ukraine.hasNoData, isTrue);
    expect(registry.activeFronts.length, 1);
    expect(registry.comingSoonFronts.length, 1);
    // The bundled registry matches the mirror inside yemen.json.
    final docText = await rootBundle.loadString('assets/seed/yemen.json');
    expect(docText, contains('"id": "ukraine"'));
  });

  test('GeoJSON assets load: 22 governorates + country outline',
      () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    final adm1Text =
        await rootBundle.loadString('assets/geo/yemen-adm1.geojson');
    final govs = parseAdm1Geojson(adm1Text);
    expect(govs.length, 22);
    for (final gov in govs) {
      expect(gov.nameAr, isNotEmpty);
      expect(gov.pcode, isNotEmpty);
      expect(gov.rings, isNotEmpty);
      for (final ring in gov.rings) {
        expect(ring.length, greaterThanOrEqualTo(3));
      }
    }
    final outlineText =
        await rootBundle.loadString('assets/geo/yemen-outline.geojson');
    final outline = parseOutlineGeojson(outlineText);
    expect(outline.length, greaterThanOrEqualTo(1));
    expect(outline.first.length, greaterThan(100));
  });

  test('governorate mapping: every place maps to a GeoJSON governorate',
      () async {
    final doc = await loadSeedDoc();
    TestWidgetsFlutterBinding.ensureInitialized();
    final adm1Text =
        await rootBundle.loadString('assets/geo/yemen-adm1.geojson');
    final govKeys = parseAdm1Geojson(adm1Text)
        .map((g) => normalizeGovernorateName(g.nameAr))
        .toSet();
    expect(govKeys.length, 22);
    final unmapped = <String>{};
    for (final place in doc.places) {
      final mapped = place.governorateFa
          .split('/')
          .map(normalizeGovernorateName)
          .any(govKeys.contains);
      if (!mapped) unmapped.add(place.governorateFa);
    }
    expect(unmapped, isEmpty, reason: 'unmapped governorates: $unmapped');
    // Spot checks of the Persian/Arabic normalisation.
    expect(normalizeGovernorateName('صنعا'),
        normalizeGovernorateName('صنعاء'));
    expect(normalizeGovernorateName('ابین'),
        normalizeGovernorateName('ابين'));
    expect(normalizeGovernorateName('تعز (تنگه)'), 'تعز');
    expect(normalizeGovernorateName('حدیده'),
        normalizeGovernorateName('الحديده'));
    // Dominant faction is a plain majority vote.
    expect(dominantFaction({'houthi': 5, 'govt': 2}), 'houthi');
    expect(dominantFaction({}), 'unknown');
  });

  test('no archive remains in lib/', () {
    // The archive (tab, snapshot UI, snapshot file handling, strings)
    // was removed in v6.1.0. AsyncSnapshot in real_map.dart is the
    // FutureBuilder state, not the feature — so the assertion targets
    // the feature's own identifiers, not the bare word "snapshot".
    const banned = [
      'ArchiveTab',
      'SnapshotMeta',
      'SnapshotPage',
      'createSnapshot',
      'loadSnapshot',
      'deleteSnapshot',
      'listSnapshots',
      'آرشیو',
      'اسنپ‌شات',
    ];
    final libDir = Directory('lib');
    final files = libDir
        .listSync(recursive: true)
        .whereType<File>()
        .where((f) => f.path.endsWith('.dart'))
        .toList();
    expect(files, isNotEmpty);
    for (final file in files) {
      final content = file.readAsStringSync();
      for (final needle in banned) {
        expect(content.contains(needle), isFalse,
            reason: '${file.path} still contains "$needle"');
      }
    }
  });

  test('version is 6.1.0+14, single source of truth', () {
    final pubspec = File('pubspec.yaml').readAsStringSync();
    expect(pubspec, contains('version: 6.1.0+14'));
    expect(pubspec, contains('flutter_map: ^8.3.2'));
    expect(pubspec, contains('flutter_map_cache: ^2.1.2'));
    expect(pubspec, contains('assets/geo/yemen-adm1.geojson'));
    expect(pubspec, contains('assets/geo/yemen-outline.geojson'));
    expect(pubspec, contains('edge/fronts.json'));
  });

  test('no government-force source string remains in lib/', () {
    // Banned strings from edge/SOURCING-RULES.md (Tier A/B/C sources
    // and their figures). The faction legend label 'دولت / نیروهای
    // جنوب' is data-driven control info, not a source, and is the
    // only 'دولت' string allowed.
    const banned = [
      'Shafaq',
      'شفق',
      'Yemen Monitor',
      'Al-Jumhuriya',
      'Al-Ain',
      'Arab News',
      'عرب‌نیوز',
      'The National',
      'نشنال',
      'House of Saud',
      'وضاح الدبیش',
      '۶۶۱',
      '۹۰۶',
      'South24',
      'Sheba',
      'شبا',
    ];
    final libDir = Directory('lib');
    final files = libDir
        .listSync(recursive: true)
        .whereType<File>()
        .where((f) => f.path.endsWith('.dart'))
        .toList();
    for (final file in files) {
      final content = file.readAsStringSync();
      for (final needle in banned) {
        expect(content.contains(needle), isFalse,
            reason: '${file.path} still contains "$needle"');
      }
    }
  });
}
