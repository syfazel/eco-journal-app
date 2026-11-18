// The six tabs. Pure widgets: they receive a YemenDoc (and, for the
// archive, snapshot callbacks) and render it. No I/O, no timers, no state
// of their own beyond expansion tiles.
import 'package:flutter/material.dart';

import 'jalali.dart';
import 'map_painter.dart';
import 'repository.dart';
import 'yemen.dart';

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

class SectionTitle extends StatelessWidget {
  final String text;
  const SectionTitle(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 16, bottom: 8),
      child: Text(
        text,
        style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold),
      ),
    );
  }
}

class BodyCard extends StatelessWidget {
  final String title;
  final String body;
  final String? footer;
  const BodyCard({required this.title, required this.body, this.footer, super.key});

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ExpansionTile(
        title: Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(body, style: const TextStyle(height: 1.7)),
                if (footer != null && footer!.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Text(
                    footer!,
                    style: TextStyle(
                      fontSize: 12,
                      color: Theme.of(context).colorScheme.secondary,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

Widget countChips(YemenDoc doc) {
  final items = [
    ('نقطه', doc.counts['places'] ?? doc.places.length),
    ('رویداد', doc.counts['events'] ?? doc.events.length),
    ('جبهه', doc.counts['frontlines'] ?? doc.frontlines.length),
    ('پیکان پیشروی', doc.counts['arrows'] ?? doc.arrows.length),
    ('موضع یگان', doc.counts['unit_positions'] ?? doc.units.length),
    ('منبع', doc.counts['sources'] ?? doc.sources.length),
  ];
  return Wrap(
    spacing: 8,
    runSpacing: 8,
    children: [
      for (final (label, n) in items)
        Chip(
          label: Text(
            '$label: ${toPersianDigitsInt(n)}',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
        ),
    ],
  );
}

// ---------------------------------------------------------------------------
// Tab 1 — status + map
// ---------------------------------------------------------------------------

class StatusTab extends StatelessWidget {
  final YemenDoc doc;
  const StatusTab(this.doc, {super.key});

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(
          doc.headlineFa,
          style: const TextStyle(fontSize: 16, height: 1.8),
        ),
        const SizedBox(height: 12),
        countChips(doc),
        const SectionTitle('نقشهٔ درگیری'),
        AspectRatio(
          aspectRatio: 13 / 8,
          child: Container(
            decoration: BoxDecoration(
              border: Border.all(color: Colors.black26),
              borderRadius: BorderRadius.circular(8),
            ),
            clipBehavior: Clip.antiAlias,
            child: CustomPaint(
              painter: YemenMapPainter(doc),
              child: const SizedBox.expand(),
            ),
          ),
        ),
        const SizedBox(height: 10),
        const MapLegend(),
        const SizedBox(height: 10),
        // The ±10 km warning is ALWAYS under the map (researcher's caveat).
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Colors.amber.shade100,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: Colors.amber.shade300),
          ),
          child: Text(
            doc.frontlineWarningFa,
            style: const TextStyle(fontSize: 13, height: 1.7),
          ),
        ),
        const SectionTitle('آمار انسانی'),
        for (final s in doc.humanStats)
          Card(
            margin: const EdgeInsets.symmetric(vertical: 4),
            child: ListTile(
              title: Text(s.label),
              subtitle: Text('منبع: ${s.source}'),
              trailing: Text(
                s.value,
                style:
                    const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
            ),
          ),
        const SectionTitle('خوانش نقشه'),
        for (final item in doc.mapReading)
          BodyCard(title: item.title, body: item.body),
        const SectionTitle('ویژگی‌های نظامی'),
        for (final item in doc.militaryFeatures)
          BodyCard(
            title: item.title,
            body: item.body,
            footer: item.extra.isNotEmpty ? item.extra : null,
          ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Tab 2 — frontlines
// ---------------------------------------------------------------------------

class FrontlinesTab extends StatelessWidget {
  final YemenDoc doc;
  const FrontlinesTab(this.doc, {super.key});

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const SectionTitle('خطوط جبهه'),
        for (final f in doc.frontlines)
          BodyCard(
            title: f.nameFa,
            body: f.summaryFa,
            footer:
                'وضعیت: ${f.statusFa}\nطرفین: ${f.sideAFa} در برابر ${f.sideBFa}\nحرکت: ${f.movementFa}\nاستان‌ها: ${f.governoratesFa}\nاطمینان: ${f.confidenceFa}\nنکتهٔ ترسیم: ${f.traceNoteFa}',
          ),
        const SectionTitle('پیکان‌های پیشروی'),
        for (final a in doc.arrows)
          BodyCard(
            title: a.labelFa,
            body: a.noteFa,
            footer:
                'نوع: ${a.kindFa} — تاریخ: ${a.dateFa} — اطمینان: ${a.confidenceFa}\nمنبع: ${a.sourceFa}',
          ),
        const SectionTitle('مواضع یگان‌ها'),
        for (final u in doc.units)
          BodyCard(
            title: '${u.nameFa} — ${u.locationFa}',
            body: u.noteFa,
            footer:
                'اطمینان: ${u.confidenceFa}${u.uncertain ? ' — موضع نامطمئن' : ''}\nمنبع: ${u.sourceFa}',
          ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Tab 3 — Aden (full investigation)
// ---------------------------------------------------------------------------

class AdenTab extends StatelessWidget {
  final YemenDoc doc;
  const AdenTab(this.doc, {super.key});

  @override
  Widget build(BuildContext context) {
    final aden = doc.aden;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Card(
          color: Theme.of(context).colorScheme.primaryContainer,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  aden.verdict.title,
                  style: const TextStyle(
                      fontSize: 17, fontWeight: FontWeight.bold, height: 1.6),
                ),
                const SizedBox(height: 8),
                Text(aden.verdict.body,
                    style: const TextStyle(height: 1.8)),
                const SizedBox(height: 8),
                Text(aden.verdictConfidenceFa,
                    style: const TextStyle(fontSize: 13, height: 1.7)),
              ],
            ),
          ),
        ),
        const SectionTitle('حلقهٔ پنج‌جهتهٔ عدن'),
        for (final seg in aden.ringSegments)
          BodyCard(
            title: '${seg.directionFa} — ${seg.statusFa}',
            body: seg.bodyFa,
          ),
        const SectionTitle('تصاویر بررسی‌شده (۷)'),
        for (final img in aden.images)
          BodyCard(
            title: img.name,
            body: 'نشان می‌دهد: ${img.showsFa}\n\nحکم: ${img.verdictFa}',
            footer: 'منشأ: ${img.originFa}',
          ),
        const SectionTitle('راستی‌آزمایی متقابل (۷)'),
        Text(aden.crossCheckSummaryFa,
            style: const TextStyle(height: 1.7)),
        const SizedBox(height: 8),
        for (final c in aden.crossCheck)
          BodyCard(title: '${c.dateFa} — ${c.source}', body: c.saysFa),
        const SectionTitle('کانال‌های رسمی (۵)'),
        Text(aden.channelsMethodFa, style: const TextStyle(height: 1.7)),
        const SizedBox(height: 8),
        for (final ch in aden.channels)
          BodyCard(
            title: '${ch.name} (${ch.handle})',
            body: '${ch.bodyFa}\n\nحکم: ${ch.verdictFa}',
            footer: ch.kindFa,
          ),
        const SectionTitle('پست‌های شبکه‌های اجتماعی (۹)'),
        for (final p in aden.socialPosts)
          BodyCard(
            title: '${p.name} — ${p.dateFa}',
            body: p.bodyFa,
            footer: '${p.kindFa}${p.url.isNotEmpty ? '\n${p.url}' : ''}',
          ),
        const SectionTitle('تقویت‌کننده‌ها (۴)'),
        for (final a in aden.amplifiers)
          BodyCard(
            title: '${a.name} — ${a.dateFa}',
            body: a.bodyFa,
            footer: a.url,
          ),
        const SectionTitle('یادداشت دیده‌ها و تعز'),
        BodyCard(title: 'دیده‌ها', body: aden.seenNoteFa),
        BodyCard(title: 'تعز', body: aden.taizNoteFa),
        const SectionTitle('لایه‌های شواهد'),
        _layerGroup('تأییدشده', aden.layersConfirmed),
        _layerGroup('ادعاهای رسمی', aden.layersOfficial),
        _layerGroup('شایعه‌ها', aden.layersRumours),
        const SectionTitle('تناقض‌ها'),
        for (final c in aden.contradictions)
          BodyCard(title: c.title, body: c.body),
        const SectionTitle('نکات کلیدی عدن'),
        for (final k in aden.keyPoints)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Text('• $k', style: const TextStyle(height: 1.7)),
          ),
        const SectionTitle('منابع عدن (۲۸)'),
        for (final s in aden.sources)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Text(
              '• ${s.label}${s.url.isNotEmpty ? '\n  ${s.url}' : ''}',
              style: const TextStyle(height: 1.6, fontSize: 13),
            ),
          ),
        const SectionTitle('سؤال‌های باز'),
        for (final q in aden.openQuestions)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Text('• $q', style: const TextStyle(height: 1.7)),
          ),
      ],
    );
  }

  Widget _layerGroup(String title, List<TitleBody> items) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 8, bottom: 4),
          child: Text(title,
              style:
                  const TextStyle(fontWeight: FontWeight.bold, fontSize: 15)),
        ),
        for (final item in items)
          BodyCard(title: item.title, body: item.body),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Tab 4 — dossiers (forces, claims, key points, disagreements)
// ---------------------------------------------------------------------------

class DossiersTab extends StatelessWidget {
  final YemenDoc doc;
  const DossiersTab(this.doc, {super.key});

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const SectionTitle('نیروها'),
        for (final f in doc.forces)
          BodyCard(title: '${f.name} — ${f.status}', body: f.body),
        const SectionTitle('ادعاها — تأییدشده'),
        for (final c in doc.claims['confirmed'] ?? const [])
          BodyCard(title: c.title, body: c.body),
        const SectionTitle('ادعاها — مورد مناقشه'),
        for (final c in doc.claims['contested'] ?? const [])
          BodyCard(title: c.title, body: c.body),
        const SectionTitle('ادعاها — فقط از منابع حوثی'),
        for (final c in doc.claims['houthi_only'] ?? const [])
          BodyCard(title: c.title, body: c.body),
        const SectionTitle('نقاط کلیدی'),
        for (final k in doc.keyPoints)
          BodyCard(
            title: '${k.name} — ${k.status}',
            body: k.basis,
            footer: 'اطمینان: ${k.confidence}',
          ),
        const SectionTitle('اختلاف‌ها'),
        for (final d in doc.disagreements)
          BodyCard(title: d.title, body: d.body),
        const SectionTitle('سؤال‌های باز'),
        for (final q in doc.openQuestions)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Text('• $q', style: const TextStyle(height: 1.7)),
          ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Tab 5 — timeline + sources
// ---------------------------------------------------------------------------

class TimelineTab extends StatelessWidget {
  final YemenDoc doc;
  const TimelineTab(this.doc, {super.key});

  @override
  Widget build(BuildContext context) {
    final events = List<YemenEvent>.from(doc.events)
      ..sort((a, b) => b.eventDate.compareTo(a.eventDate));
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const SectionTitle('گاه‌شمار رویدادها'),
        for (final e in events)
          BodyCard(
            title: '${e.eventDate} — ${e.titleFa}',
            body: e.detailFa,
            footer:
                'منبع: ${e.sourceName}${e.corroboration > 1 ? ' — تأیید از ${toPersianDigitsInt(e.corroboration)} منبع' : ''}${e.sourceUrl.isNotEmpty ? '\n${e.sourceUrl}' : ''}',
          ),
        const SectionTitle('گاه‌شمار عدن'),
        for (final t in doc.aden.timeline)
          BodyCard(
            title: '${t.date} — ${t.titleFa}',
            body: t.bodyFa,
            footer: 'لایه: ${t.layer} — منبع: ${t.sourceFa}',
          ),
        const SectionTitle('منابع (۲۵)'),
        for (final s in doc.sources)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Text(
              '• ${s.label}${s.url.isNotEmpty ? '\n  ${s.url}' : ''}',
              style: const TextStyle(height: 1.6, fontSize: 13),
            ),
          ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Tab 6 — archive: local snapshots + dashboard version history v1–v5
// ---------------------------------------------------------------------------

class ArchiveTab extends StatelessWidget {
  final List<SnapshotMeta> snapshots;
  final VoidCallback onCreateSnapshot;
  final void Function(SnapshotMeta) onViewSnapshot;
  final void Function(SnapshotMeta) onDeleteSnapshot;
  const ArchiveTab({
    required this.snapshots,
    required this.onCreateSnapshot,
    required this.onViewSnapshot,
    required this.onDeleteSnapshot,
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const SectionTitle('اسنپ‌شات‌های محلی'),
        const Text(
          'هر اسنپ‌شات، وضعیت فعلی را همان‌طور که هست منجمد می‌کند و به‌صورت یک فایل JSON ساده روی گوشی ذخیره می‌شود؛ بدون پایگاه داده.',
          style: TextStyle(height: 1.7),
        ),
        const SizedBox(height: 10),
        SizedBox(
          width: double.infinity,
          child: FilledButton.icon(
            onPressed: onCreateSnapshot,
            icon: const Icon(Icons.camera_alt_outlined),
            label: const Text('ساختن اسنپ‌شات از وضعیت فعلی'),
          ),
        ),
        const SizedBox(height: 8),
        if (snapshots.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Text('هنوز اسنپ‌شاتی ساخته نشده است.',
                  style: TextStyle(height: 1.7)),
            ),
          ),
        for (final s in snapshots)
          Card(
            margin: const EdgeInsets.symmetric(vertical: 4),
            child: ListTile(
              title: Text(s.labelFa),
              subtitle: Text(
                formatJalali(
                    DateTime.tryParse(s.createdAtUtc) ?? DateTime.now(),
                    withTime: true),
                style: const TextStyle(height: 1.6),
              ),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    icon: const Icon(Icons.visibility_outlined),
                    tooltip: 'مشاهدهٔ نقاط منجمدشده',
                    onPressed: () => onViewSnapshot(s),
                  ),
                  IconButton(
                    icon: const Icon(Icons.delete_outline),
                    tooltip: 'حذف',
                    onPressed: () => onDeleteSnapshot(s),
                  ),
                ],
              ),
            ),
          ),
        const SectionTitle('تاریخچهٔ نسخه‌های داشبورد'),
        for (final v in _dashboardVersions)
          Card(
            margin: const EdgeInsets.symmetric(vertical: 4),
            child: ListTile(
              leading: CircleAvatar(child: Text(toPersianDigitsInt(v.$1))),
              title: Text(v.$2),
              subtitle: Text(v.$3, style: const TextStyle(height: 1.6)),
            ),
          ),
      ],
    );
  }
}

/// Dashboard version history — editorial record from v5, nothing invented.
/// (Only v5 carries a date in the source data; the earlier versions are
/// listed without dates rather than guessing them.)
const List<(int, String, String)> _dashboardVersions = [
  (1, 'نسخهٔ ۱ — ساخت داشبورد', 'اولین نسخهٔ داشبورد یمن.'),
  (2, 'نسخهٔ ۲ — ادغام سه فضا', 'ادغام سه فضای جداگانه در یک داشبورد.'),
  (3, 'نسخهٔ ۳ — مرزهای درگیری', 'افزودن مرزها و خطوط درگیری به نقشه.'),
  (4, 'نسخهٔ ۴ — تب عدن', 'افزودن تب بررسی عدن.'),
  (
    5,
    'نسخهٔ ۵ — ارتقای رابط کاربری و آرشیو نسخه‌ها',
    '۱۷ نوامبر ۲۰۲۶ — ارتقای رابط کاربری و افزودن آرشیو نسخه‌ها.'
  ),
];

// ---------------------------------------------------------------------------
// Snapshot viewer — a frozen document, read-only.
// ---------------------------------------------------------------------------

class SnapshotPage extends StatelessWidget {
  final YemenDoc doc;
  final SnapshotMeta meta;
  const SnapshotPage({required this.doc, required this.meta, super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(meta.labelFa)),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            padding: const EdgeInsets.all(12),
            color: Colors.amber.shade100,
            child: Text(
              'اسنپ‌شات منجمدشده — ${formatJalali(DateTime.tryParse(meta.createdAtUtc) ?? DateTime.now(), withTime: true)} — این نما فقط خواندنی است و با به‌روزرسانی زنده تغییر نمی‌کند.',
              style: const TextStyle(height: 1.7),
            ),
          ),
          Expanded(child: StatusTab(doc)),
        ],
      ),
    );
  }
}
