// Smoke test: the app renders from the bundled seed without any network.
// The seed load is a local asset + an isolate parse; the live fetch is
// fire-and-forget, so the test must not wait on it (bounded pumps only —
// never pumpAndSettle, which the 24 h timer would never let settle).
import 'package:ecojournal_yemen/main.dart';
import 'package:ecojournal_yemen/yemen.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('seed renders: title, refresh button, rail sections',
      (WidgetTester tester) async {
    await tester.pumpWidget(const YemenApp());
    // Let the seed asset load + isolate parse land (bounded pumps).
    for (var i = 0; i < 15; i++) {
      await tester.pump(const Duration(milliseconds: 300));
      if (find.text('به‌روزرسانی لحظه‌ای').evaluate().isNotEmpty) break;
    }
    expect(find.text('به‌روزرسانی لحظه‌ای'), findsOneWidget);
    expect(find.text('وضعیت و نقشه'), findsOneWidget);
    expect(find.text('جبهه‌ها'), findsOneWidget);
    expect(find.text('عدن'), findsOneWidget);
    expect(find.text('پرونده‌ها'), findsOneWidget);
    expect(find.text('گاه‌شمار و منابع'), findsOneWidget);
    expect(find.text('فیدهای انصارالله'), findsOneWidget);
    // v6.1.0: the archive is gone from the app entirely.
    expect(find.text('آرشیو'), findsNothing);
    // Front switcher: Yemen active, Ukraine coming-soon scaffold.
    expect(find.text('یمن'), findsWidgets);
    expect(find.text('اوکراین — به‌زودی'), findsOneWidget);
  });

  test('parseYemenDoc counts match the dashboard v5 contract', () async {
    // The bundled seed is loaded through the test bundle's asset system
    // in the widget test above; here we assert the contract on the model
    // shape directly with a minimal inline document.
    const minimal = '''
{"schema_version":1,"generated_at_utc":"2026-11-17T07:29:23.236Z",
"as_of":"2026-11-17T07:29:23.236Z","headline_fa":"تیتر",
"counts":{"places":1,"events":1,"frontlines":1,"arrows":1,
"unit_positions":1,"forces":1,"sources":1},
"faction_counts":{"houthi":1},
"places":[{"name_fa":"شهر عدن","name_en":"Aden","governorate_fa":"عدن",
"lat":12.8,"lon":45.03,"faction":"govt"}],
"frontlines":[],"advance_arrows":[],"unit_positions":[],"forces":[],
"events":[],"claims":{},"aden":{"verdict":{"title_fa":"حکم","body_fa":"متن",
"confidence_fa":"بالا"},"ring_segments":[],"timeline":[],"key_points":[],
"sources":[],"layers":{},"contradictions":[],"images":[],"cross_check":[],
"channels_official":[],"social_posts":[],"amplifiers":[],
"channels_method_fa":"","cross_check_summary_fa":"","seen_note_fa":"",
"taiz_note_fa":"","open_questions":[]},
"human_stats":[],"sources":[],"map_reading":[],"military_features":[],
"key_points":[],"open_questions":[],"disagreements":[],
"frontline_warning_fa":"هشدار"}''';
    final doc = parseYemenDoc(minimal);
    expect(doc.places.length, 1);
    expect(doc.places.first.nameFa, 'شهر عدن');
    expect(doc.aden.verdict.title, 'حکم');
    expect(doc.frontlineWarningFa, 'هشدار');
    // Missing optional blocks fall back to empty, never crash.
    expect(doc.feeds.items, isEmpty);
    expect(doc.taxonomyVersion, 1);
  });
}
