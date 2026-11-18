// ECO JOURNAL — Yemen only, v6.0.0/code 13. Flutter/Dart rebuild.
//
// ONE feature: the Yemen dashboard. No messaging, no invite gate, no
// broadcast packets, no shared password, no other sections, no FCM,
// no channel posts, no cron.
//
// ANTI-FREEZE DESIGN (the v5.6 freeze was blocking network I/O + a ~249 KB
// JSON parse on the UI thread, behind a shared mutex):
//  * ONE immutable state object ([YemenState]), ONE update path
//    ([_YemenHomeState._update]) — no retained holders, no mirrored flows.
//  * ALL network + JSON parsing happens off the UI thread: async
//    HttpClient (never awaited on the build path) + `compute` isolates
//    for parsing (see repository.dart). The build method never awaits.
//  * The bundled seed renders IMMEDIATELY on first launch, even with no
//    network; live refresh is a background replacement, never a gate.
//  * No database, no workers, no mutex: snapshots are plain JSON files,
//    and the 24 h refresh is a plain Timer that only fires a background
//    fetch — it can never block a manual tap.
//  * The instant-update button is the only refresh trigger on the UI,
//    and it only flips state flags; the work happens in futures.
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'jalali.dart';
import 'repository.dart';
import 'sections.dart';
import 'yemen.dart';

void main() => runApp(const YemenApp());

/// The single state object. Immutable; replaced wholesale on every update.
class YemenState {
  final YemenDoc? doc;
  final String? rawJson;
  final String source; // 'seed' | 'cache' | 'live' | 'snapshot'
  final DateTime? fetchedAt;
  final bool loadingSeed;
  final bool refreshing;
  final String? errorFa;
  final List<SnapshotMeta> snapshots;

  const YemenState({
    required this.doc,
    required this.rawJson,
    required this.source,
    required this.fetchedAt,
    required this.loadingSeed,
    required this.refreshing,
    required this.errorFa,
    required this.snapshots,
  });

  factory YemenState.initial() => const YemenState(
        doc: null,
        rawJson: null,
        source: 'seed',
        fetchedAt: null,
        loadingSeed: true,
        refreshing: false,
        errorFa: null,
        snapshots: [],
      );

  YemenState copyWith({
    YemenDoc? doc,
    String? rawJson,
    String? source,
    DateTime? fetchedAt,
    bool? loadingSeed,
    bool? refreshing,
    String? errorFa,
    bool clearError = false,
    List<SnapshotMeta>? snapshots,
  }) =>
      YemenState(
        doc: doc ?? this.doc,
        rawJson: rawJson ?? this.rawJson,
        source: source ?? this.source,
        fetchedAt: fetchedAt ?? this.fetchedAt,
        loadingSeed: loadingSeed ?? this.loadingSeed,
        refreshing: refreshing ?? this.refreshing,
        errorFa: clearError ? null : (errorFa ?? this.errorFa),
        snapshots: snapshots ?? this.snapshots,
      );

  /// Stale when the document itself is older than 24 h.
  bool get isStale {
    final gen = doc?.generatedAt;
    if (gen == null) return true;
    return DateTime.now().toUtc().difference(gen).inHours >= 24;
  }
}

class YemenApp extends StatelessWidget {
  const YemenApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ECO JOURNAL — یمن',
      debugShowCheckedModeBanner: false,
      locale: const Locale('fa'),
      supportedLocales: const [Locale('fa'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF00695C),
        fontFamily: 'Vazirmatn',
      ),
      home: const YemenHome(),
    );
  }
}

class YemenHome extends StatefulWidget {
  const YemenHome({super.key});

  @override
  State<YemenHome> createState() => _YemenHomeState();
}

class _YemenHomeState extends State<YemenHome> {
  YemenState _state = YemenState.initial();
  Timer? _dayTimer;

  // -- The ONE update path. Every state change in this screen goes here.
  void _update(YemenState next) {
    if (!mounted) return;
    setState(() => _state = next);
  }

  @override
  void initState() {
    super.initState();
    _loadInitial();
    // 24 h background refresh: a plain timer that only starts a
    // background fetch. It shares nothing with the UI refresh path.
    _dayTimer = Timer.periodic(const Duration(hours: 24), (_) {
      if (_state.doc != null && _state.isStale && !_state.refreshing) {
        _refresh(manual: false);
      }
    });
  }

  @override
  void dispose() {
    _dayTimer?.cancel();
    super.dispose();
  }

  /// Seed first (local asset — fast, offline-safe), then cache, then live.
  /// Each step replaces the state once; the UI never waits on the network.
  Future<void> _loadInitial() async {
    final snapshots = await YemenRepository.listSnapshots();
    _update(_state.copyWith(snapshots: snapshots));
    try {
      final seed = await YemenRepository.loadSeed();
      _update(_state.copyWith(
        doc: seed.doc,
        rawJson: seed.rawJson,
        source: 'seed',
        fetchedAt: DateTime.now().toUtc(),
        loadingSeed: false,
        clearError: true,
      ));
    } catch (e) {
      _update(_state.copyWith(
        loadingSeed: false,
        errorFa: 'بذر داخلی خوانده نشد: $e',
      ));
      return;
    }
    // A cached live copy is newer than the seed — swap it in silently.
    final cache = await YemenRepository.loadCache();
    if (cache != null &&
        (cache.doc.generatedAt ?? DateTime.fromMillisecondsSinceEpoch(0))
                .isAfter(_state.doc?.generatedAt ??
                    DateTime.fromMillisecondsSinceEpoch(0))) {
      _update(_state.copyWith(
        doc: cache.doc,
        rawJson: cache.rawJson,
        source: 'cache',
        fetchedAt: DateTime.now().toUtc(),
      ));
    }
    // Background live fetch — fire and forget; never blocks the UI.
    _refresh(manual: false);
  }

  /// The only refresh entry point (manual button + timer + launch).
  /// Never throws; a failure keeps the current document and sets a
  /// Persian error with a retry affordance.
  Future<void> _refresh({required bool manual}) async {
    if (_state.refreshing) return;
    if (_state.doc == null && !manual) return;
    _update(_state.copyWith(refreshing: true, clearError: true));
    try {
      final live = await YemenRepository.fetchLive();
      _update(_state.copyWith(
        doc: live.doc,
        rawJson: live.rawJson,
        source: 'live',
        fetchedAt: DateTime.now().toUtc(),
        refreshing: false,
        clearError: true,
      ));
    } catch (e) {
      _update(_state.copyWith(
        refreshing: false,
        errorFa: _persianError(e),
      ));
    }
  }

  static String _persianError(Object e) {
    final msg = e.toString();
    if (msg.contains('TimeoutException') || msg.contains('timed out')) {
      return 'به‌روزرسانی ناموفق بود: اتصال کند است یا پاسخ نداد (مهلت ۱۵ ثانیه). دوباره تلاش کنید.';
    }
    if (msg.contains('SocketException') || msg.contains('Failed host')) {
      return 'به‌روزرسانی ناموفق بود: به اینترنت دسترسی نیست. اتصال را بررسی کنید و دوباره تلاش کنید.';
    }
    if (msg.contains('HTTP')) {
      return 'به‌روزرسانی ناموفق بود: سرور پاسخ نامناسبی داد. دوباره تلاش کنید.';
    }
    return 'به‌روزرسانی ناموفق بود. دوباره تلاش کنید.';
  }

  Future<void> _createSnapshot() async {
    final doc = _state.doc;
    final raw = _state.rawJson;
    if (doc == null || raw == null) return;
    final updated = await YemenRepository.createSnapshot(doc, raw);
    _update(_state.copyWith(snapshots: updated));
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('اسنپ‌شات ساخته شد.')),
      );
    }
  }

  Future<void> _viewSnapshot(SnapshotMeta meta) async {
    final loaded = await YemenRepository.loadSnapshot(meta.fileName);
    if (loaded == null) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
              content: Text('خواندن اسنپ‌شات ناموفق بود.')),
        );
      }
      return;
    }
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => SnapshotPage(doc: loaded.doc, meta: meta),
      ),
    );
  }

  Future<void> _deleteSnapshot(SnapshotMeta meta) async {
    final updated = await YemenRepository.deleteSnapshot(meta.fileName);
    _update(_state.copyWith(snapshots: updated));
  }

  @override
  Widget build(BuildContext context) {
    final state = _state;
    return DefaultTabController(
      length: 6,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('ECO JOURNAL — یمن'),
          bottom: const TabBar(
            isScrollable: true,
            tabs: [
              Tab(text: 'وضعیت و نقشه'),
              Tab(text: 'جبهه‌ها'),
              Tab(text: 'عدن'),
              Tab(text: 'پرونده‌ها'),
              Tab(text: 'گاه‌شمار و منابع'),
              Tab(text: 'آرشیو'),
            ],
          ),
        ),
        body: Column(
          children: [
            _RefreshBar(state: state, onRefresh: () => _refresh(manual: true)),
            Expanded(
              child: state.loadingSeed
                  ? const Center(
                      child: Text(
                        'ECO JOURNAL — یمن\nدر حال آماده‌سازی…',
                        textAlign: TextAlign.center,
                        style: TextStyle(height: 1.8),
                      ),
                    )
                  : state.doc == null
                      ? _SeedErrorView(
                          errorFa: state.errorFa,
                          onRetry: () => _loadInitial(),
                        )
                      : TabBarView(
                          children: [
                            StatusTab(state.doc!),
                            FrontlinesTab(state.doc!),
                            AdenTab(state.doc!),
                            DossiersTab(state.doc!),
                            TimelineTab(state.doc!),
                            ArchiveTab(
                              snapshots: state.snapshots,
                              onCreateSnapshot: _createSnapshot,
                              onViewSnapshot: _viewSnapshot,
                              onDeleteSnapshot: _deleteSnapshot,
                            ),
                          ],
                        ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Full-width instant-update button + last-refresh time (Jalali) +
/// staleness label + Persian error with retry. Shown on every tab.
class _RefreshBar extends StatelessWidget {
  final YemenState state;
  final VoidCallback onRefresh;
  const _RefreshBar({required this.state, required this.onRefresh});

  @override
  Widget build(BuildContext context) {
    final doc = state.doc;
    final gen = doc?.generatedAt;
    return Container(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        border: Border(
          bottom: BorderSide(color: Theme.of(context).dividerColor),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: state.refreshing ? null : onRefresh,
              icon: state.refreshing
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.refresh),
              label: Text(
                state.refreshing
                    ? 'در حال به‌روزرسانی…'
                    : 'به‌روزرسانی لحظه‌ای',
                style: const TextStyle(fontSize: 16),
              ),
            ),
          ),
          const SizedBox(height: 6),
          Text(
            gen == null
                ? 'زمان آخرین به‌روزرسانی مشخص نیست.'
                : 'آخرین به‌روزرسانی داده: ${formatJalali(gen, withTime: true)}'
                    ' — منبع: ${_sourceLabel(state.source)}'
                    '${state.isStale ? ' — داده کهنه است (بیش از ۲۴ ساعت)' : ' — به‌روز'}',
            style: const TextStyle(fontSize: 12, height: 1.6),
            textAlign: TextAlign.center,
          ),
          if (state.errorFa != null) ...[
            const SizedBox(height: 6),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Colors.red.shade50,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.red.shade200),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      state.errorFa!,
                      style: const TextStyle(fontSize: 13, height: 1.7),
                    ),
                  ),
                  TextButton(
                    onPressed: onRefresh,
                    child: const Text('تلاش دوباره'),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  static String _sourceLabel(String source) {
    switch (source) {
      case 'live':
        return 'زنده';
      case 'cache':
        return 'حافظهٔ محلی';
      case 'snapshot':
        return 'اسنپ‌شات';
      default:
        return 'بذر داخلی (آفلاین)';
    }
  }
}

class _SeedErrorView extends StatelessWidget {
  final String? errorFa;
  final VoidCallback onRetry;
  const _SeedErrorView({required this.errorFa, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              errorFa ?? 'دادهٔ داخلی خوانده نشد.',
              textAlign: TextAlign.center,
              style: const TextStyle(height: 1.8),
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: onRetry,
              child: const Text('تلاش دوباره'),
            ),
          ],
        ),
      ),
    );
  }
}
