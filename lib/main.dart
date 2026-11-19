// ECO JOURNAL — Yemen only, v6.2.0/code 15. Flutter/Dart rebuild.
//
// ONE feature: the Yemen dashboard. No messaging, no invite gate, no
// broadcast packets, no shared password, no other sections, no FCM,
// no channel posts, no cron. No archive (removed in v6.1.0).
//
// v6.2.0: IN-APP UPDATE CHECK (the v6.0/v6.1 bug — neither could ever
// notify the user of a newer version; the old Kotlin AppUpdateWorker
// was dropped in the rebuild). After the bundled seed renders, the app
// fetches version.json in the background, compares version_code with
// the running code (package_info_plus, never hard-coded) and shows an
// update dialog when a newer version exists; mandatory = no dismiss.
// «دانلود و نصب» downloads the APK (SHA-256 verified) and opens the
// SYSTEM installer via a FileProvider intent — see app_update.dart.
// The side sidebar footer shows the installed version and a manual
// «بررسی نسخهٔ تازه» control that runs the same check on demand.
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
//  * No database, no workers, no mutex: the 24 h refresh is a plain
//    Timer that only fires a background fetch — it can never block a
//    manual tap.
//  * The instant-update button is the only refresh trigger on the UI,
//    and it only flips state flags; the work happens in futures.
//
// v6.1.0: real map (flutter_map + OSM tiles + bundled governorates),
// circular side NavigationRail (RTL), generic front registry
// (Yemen active, Ukraine coming-soon scaffold), Ansarullah feeds tab,
// classified timeline. Sourcing: Ansarullah sources ONLY —
// government-force sources are never used (see edge/SOURCING-RULES.md).
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:package_info_plus/package_info_plus.dart';

import 'app_update.dart';
import 'fronts.dart';
import 'jalali.dart';
import 'repository.dart';
import 'sections.dart';
import 'yemen.dart';

void main() => runApp(const YemenApp());

/// The single state object. Immutable; replaced wholesale on every update.
class YemenState {
  final YemenDoc? doc;
  final String source; // 'seed' | 'cache' | 'live'
  final DateTime? fetchedAt;
  final bool loadingSeed;
  final bool refreshing;
  final String? errorFa;

  const YemenState({
    required this.doc,
    required this.source,
    required this.fetchedAt,
    required this.loadingSeed,
    required this.refreshing,
    required this.errorFa,
  });

  factory YemenState.initial() => const YemenState(
        doc: null,
        source: 'seed',
        fetchedAt: null,
        loadingSeed: true,
        refreshing: false,
        errorFa: null,
      );

  YemenState copyWith({
    YemenDoc? doc,
    String? source,
    DateTime? fetchedAt,
    bool? loadingSeed,
    bool? refreshing,
    String? errorFa,
    bool clearError = false,
  }) =>
      YemenState(
        doc: doc ?? this.doc,
        source: source ?? this.source,
        fetchedAt: fetchedAt ?? this.fetchedAt,
        loadingSeed: loadingSeed ?? this.loadingSeed,
        refreshing: refreshing ?? this.refreshing,
        errorFa: clearError ? null : (errorFa ?? this.errorFa),
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
  int _selectedIndex = 0;
  FrontRegistry? _registry;

  // -- In-app update check (v6.2.0). The installed version label shown
  // in the sidebar footer; the launch check runs once, AFTER the seed
  // has rendered, and never blocks the Yemen tab.
  String _appVersionLabel = 'نسخهٔ —';
  bool _checkingUpdate = false;
  bool _updateDialogShown = false;

  // -- The ONE update path. Every state change in this screen goes here.
  void _update(YemenState next) {
    if (!mounted) return;
    setState(() => _state = next);
  }

  @override
  void initState() {
    super.initState();
    _loadRegistry();
    _loadAppVersion();
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

  Future<void> _loadRegistry() async {
    final registry = await loadFrontRegistry();
    if (!mounted) return;
    setState(() => _registry = registry);
  }

  /// Installed version for the sidebar footer (e.g. «نسخهٔ ۶٫۲٫۰ —
  /// کد ۱۵»), from the platform — never hard-coded. A failure leaves
  /// the placeholder; the version is informational, never a gate.
  Future<void> _loadAppVersion() async {
    try {
      final info = await PackageInfo.fromPlatform();
      if (!mounted) return;
      setState(() =>
          _appVersionLabel = 'نسخهٔ ${info.version} — کد ${info.buildNumber}');
    } catch (_) {
      // Placeholder stays; nothing depends on this label.
    }
  }

  /// Runs the update check. Launch (`manual: false`) is fire-and-forget
  /// after the seed renders and stays SILENT on failure (a launch
  /// failure must never nag); the manual sidebar control reports
  /// "no update" and failures in Persian, with a retry action.
  Future<void> _checkForUpdate({required bool manual}) async {
    if (_checkingUpdate) return;
    if (!mounted) return;
    setState(() => _checkingUpdate = true);
    try {
      final manifest = await AppUpdateChecker().check();
      if (!mounted) return;
      if (manifest == null) {
        if (manual) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('نسخهٔ تازه‌ای نیست — شما جدیدترین نسخه را دارید.'),
            ),
          );
        }
        return;
      }
      _showUpdateDialog(manifest);
    } catch (e) {
      if (!mounted) return;
      if (manual) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(appUpdatePersianError(e)),
            action: SnackBarAction(
              label: 'تلاش دوباره',
              onPressed: () => _checkForUpdate(manual: true),
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _checkingUpdate = false);
    }
  }

  /// Update dialog. Mandatory = no dismiss (barrier + back button both
  /// blocked, no «بعداً» button). «دانلود و نصب» downloads the APK
  /// (SHA-256 verified) and opens the SYSTEM installer; a failure shows
  /// a Persian error inside the dialog with a retry button.
  void _showUpdateDialog(AppUpdateManifest manifest) {
    if (_updateDialogShown) return;
    _updateDialogShown = true;
    var downloading = false;
    String? errorFa;
    showDialog<void>(
      context: context,
      barrierDismissible: !manifest.mandatory,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, setDialogState) => PopScope(
          canPop: !manifest.mandatory,
          child: AlertDialog(
            title: Text('نسخهٔ تازه: ${manifest.versionName}'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (manifest.changelogFa.isNotEmpty)
                  Text(manifest.changelogFa,
                      style: const TextStyle(height: 1.8)),
                if (manifest.mandatory) ...[
                  const SizedBox(height: 12),
                  const Text(
                    'این نسخه اجباری است؛ برای ادامه باید نصب شود.',
                    style: TextStyle(fontWeight: FontWeight.bold, height: 1.8),
                  ),
                ],
                if (downloading) ...[
                  const SizedBox(height: 16),
                  const LinearProgressIndicator(),
                  const SizedBox(height: 8),
                  const Text('در حال دانلود نسخهٔ تازه…',
                      style: TextStyle(height: 1.8)),
                ],
                if (errorFa != null) ...[
                  const SizedBox(height: 12),
                  Text(errorFa!,
                      style: TextStyle(
                        height: 1.8,
                        color: Theme.of(context).colorScheme.error,
                      )),
                ],
              ],
            ),
            actions: [
              if (!manifest.mandatory)
                TextButton(
                  onPressed: () => Navigator.of(dialogContext).pop(),
                  child: const Text('بعداً'),
                ),
              FilledButton(
                onPressed: downloading
                    ? null
                    : () async {
                        setDialogState(() {
                          downloading = true;
                          errorFa = null;
                        });
                        try {
                          await AppUpdateInstaller().downloadAndInstall(manifest);
                          if (dialogContext.mounted) {
                            Navigator.of(dialogContext).pop();
                          }
                        } catch (e) {
                          setDialogState(() {
                            downloading = false;
                            errorFa = appUpdatePersianError(e);
                          });
                        }
                      },
                child: Text(errorFa != null ? 'تلاش دوباره' : 'دانلود و نصب'),
              ),
            ],
          ),
        ),
      ),
    ).whenComplete(() => _updateDialogShown = false);
  }

  /// The active front — Yemen, the only active front in the registry.
  Front? get _activeFront => _registry?.byId('yemen');

  /// Seed first (local asset — fast, offline-safe), then cache, then live.
  /// Each step replaces the state once; the UI never waits on the network.
  Future<void> _loadInitial() async {
    try {
      final seed = await YemenRepository.loadSeed();
      _update(_state.copyWith(
        doc: seed.doc,
        source: 'seed',
        fetchedAt: DateTime.now().toUtc(),
        loadingSeed: false,
        clearError: true,
      ));
      // Update check AFTER the seed has rendered — background only,
      // never a gate on the Yemen tab (v6.2.0).
      _checkForUpdate(manual: false);
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

  void _onComingSoonFront(Front front) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          '${front.nameFa} — به‌زودی؛ ${front.noteFa ?? 'داده‌ای هنوز گردآوری نشده است.'}',
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = _state;
    final front = _activeFront;
    return Scaffold(
      appBar: AppBar(
        title: const Text('ECO JOURNAL — یمن'),
      ),
      // RTL: the Row's first child (the rail) lands on the RIGHT side,
      // which is the correct side for a Persian RTL layout.
      body: Row(
        children: [
          _SideRail(
            selectedIndex: _selectedIndex,
            registry: _registry,
            onSelect: (i) => setState(() => _selectedIndex = i),
            onComingSoonFront: _onComingSoonFront,
            appVersionLabel: _appVersionLabel,
            checkingUpdate: _checkingUpdate,
            onCheckUpdate: () => _checkForUpdate(manual: true),
          ),
          const VerticalDivider(width: 1),
          Expanded(
            child: Column(
              children: [
                _RefreshBar(
                    state: state, onRefresh: () => _refresh(manual: true)),
                Expanded(
                  child: state.loadingSeed || front == null
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
                          : _SectionBody(
                              index: _selectedIndex,
                              doc: state.doc!,
                              front: front,
                            ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SectionBody extends StatelessWidget {
  final int index;
  final YemenDoc doc;
  final Front front;
  const _SectionBody({
    required this.index,
    required this.doc,
    required this.front,
  });

  @override
  Widget build(BuildContext context) {
    switch (index) {
      case 0:
        return StatusTab(doc, front: front);
      case 1:
        return FrontlinesTab(doc);
      case 2:
        return AdenTab(doc);
      case 3:
        return DossiersTab(doc);
      case 4:
        return TimelineTab(doc);
      case 5:
        return FeedsTab(doc);
      default:
        return StatusTab(doc, front: front);
    }
  }
}

/// Side NavigationRail: each section is a circular button (icon inside
/// a circle, Persian label under it). Selected = filled circle + accent
/// ring; unselected = outlined circle. The front switcher sits above
/// the sections as the rail's leading widget.
class _SideRail extends StatelessWidget {
  final int selectedIndex;
  final FrontRegistry? registry;
  final ValueChanged<int> onSelect;
  final ValueChanged<Front> onComingSoonFront;
  final String appVersionLabel;
  final bool checkingUpdate;
  final VoidCallback onCheckUpdate;

  const _SideRail({
    required this.selectedIndex,
    required this.registry,
    required this.onSelect,
    required this.onComingSoonFront,
    required this.appVersionLabel,
    required this.checkingUpdate,
    required this.onCheckUpdate,
  });

  static const _sections = [
    ('وضعیت و نقشه', Icons.map_outlined, Icons.map),
    ('جبهه‌ها', Icons.flag_outlined, Icons.flag),
    ('عدن', Icons.location_city_outlined, Icons.location_city),
    ('پرونده‌ها', Icons.folder_open_outlined, Icons.folder_open),
    ('گاه‌شمار و منابع', Icons.history_outlined, Icons.history),
    ('فیدهای انصارالله', Icons.rss_feed_outlined, Icons.rss_feed),
  ];

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return NavigationRail(
      minWidth: 100,
      scrollable: true, // 6 sections + front switcher exceed short screens
      labelType: NavigationRailLabelType.all,
      selectedIndex: selectedIndex,
      onDestinationSelected: onSelect,
      selectedLabelTextStyle: TextStyle(
        fontSize: 12,
        fontWeight: FontWeight.bold,
        color: scheme.primary,
      ),
      unselectedLabelTextStyle: TextStyle(
        fontSize: 12,
        color: scheme.onSurfaceVariant,
      ),
      leading: _FrontSwitcher(
        registry: registry,
        onComingSoonFront: onComingSoonFront,
      ),
      // v6.2.0 footer: installed version (so the user can confirm it
      // without Android Settings) + manual update check.
      trailing: SizedBox(
        width: 96,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(6, 8, 6, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Divider(),
              Text(
                appVersionLabel,
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 11,
                  height: 1.6,
                  color: scheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: 4),
              TextButton.icon(
                onPressed: checkingUpdate ? null : onCheckUpdate,
                icon: checkingUpdate
                    ? const SizedBox(
                        width: 14,
                        height: 14,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.system_update, size: 18),
                label: const Text(
                  'بررسی نسخهٔ تازه',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 11, height: 1.6),
                ),
              ),
            ],
          ),
        ),
      ),
      destinations: [
        for (var i = 0; i < _sections.length; i++)
          NavigationRailDestination(
            icon: _CircleIcon(
              icon: _sections[i].$2,
              selected: false,
            ),
            selectedIcon: _CircleIcon(
              icon: _sections[i].$3,
              selected: true,
            ),
            label: Text(_sections[i].$1, textAlign: TextAlign.center),
          ),
      ],
    );
  }
}

/// Circular section button: icon inside a circle. Selected state is a
/// filled circle with an accent ring; unselected is an outlined circle.
class _CircleIcon extends StatelessWidget {
  final IconData icon;
  final bool selected;
  const _CircleIcon({required this.icon, required this.selected});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: 48,
      height: 48,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: selected
            ? scheme.primary
            : scheme.surfaceContainerHighest,
        border: Border.all(
          color: selected ? scheme.primary : scheme.outlineVariant,
          width: selected ? 2.5 : 1.5,
        ),
        boxShadow: selected
            ? [
                BoxShadow(
                  color: scheme.primary.withValues(alpha: 0.35),
                  blurRadius: 8,
                  spreadRadius: 1,
                ),
              ]
            : null,
      ),
      child: Icon(
        icon,
        size: 24,
        color: selected ? scheme.onPrimary : scheme.onSurfaceVariant,
      ),
    );
  }
}

/// Front switcher (rail leading): the active front (Yemen) + the
/// prepared Ukraine scaffold — disabled, «به‌زودی», no data.
class _FrontSwitcher extends StatelessWidget {
  final FrontRegistry? registry;
  final ValueChanged<Front> onComingSoonFront;
  const _FrontSwitcher({
    required this.registry,
    required this.onComingSoonFront,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fronts = registry?.fronts ?? const <Front>[];
    // NavigationRail lays out `leading` with unbounded width, so the
    // switcher needs its own fixed width (the rail is 100 wide).
    return SizedBox(
      width: 96,
      child: Padding(
      padding: const EdgeInsets.fromLTRB(6, 12, 6, 8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text(
            'جبهه',
            style: TextStyle(fontSize: 12, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 6),
          for (final front in fronts)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: front.isActive
                  ? _FrontChip(
                      label: front.nameFa,
                      enabled: true,
                      selected: true,
                      onTap: null,
                    )
                  : _FrontChip(
                      label: '${front.nameFa} — به‌زودی',
                      enabled: false,
                      selected: false,
                      onTap: () => onComingSoonFront(front),
                    ),
            ),
          if (fronts.any((f) => !f.isActive))
            Text(
              'داده‌ای هنوز گردآوری نشده است.',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 10,
                height: 1.5,
                color: scheme.onSurfaceVariant,
              ),
            ),
        ],
      ),
      ),
    );
  }
}

class _FrontChip extends StatelessWidget {
  final String label;
  final bool enabled;
  final bool selected;
  final VoidCallback? onTap;
  const _FrontChip({
    required this.label,
    required this.enabled,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final chip = Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
      decoration: BoxDecoration(
        color: selected
            ? scheme.primaryContainer
            : scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(
          color: selected ? scheme.primary : scheme.outlineVariant,
          width: selected ? 2 : 1,
        ),
      ),
      child: Text(
        label,
        textAlign: TextAlign.center,
        style: TextStyle(
          fontSize: 12,
          fontWeight: selected ? FontWeight.bold : FontWeight.normal,
          color: enabled ? scheme.onSurface : scheme.onSurfaceVariant,
        ),
      ),
    );
    if (!enabled || onTap == null) {
      return Opacity(
        opacity: enabled ? 1 : 0.75,
        child: onTap == null
            ? chip
            : InkWell(
                onTap: onTap,
                borderRadius: BorderRadius.circular(20),
                child: chip,
              ),
      );
    }
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(20),
      child: chip,
    );
  }
}

/// Full-width instant-update button + last-refresh time (Jalali) +
/// staleness label + Persian error with retry. Shown on every section.
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
