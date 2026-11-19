// The sections. Pure widgets: they receive a YemenDoc and render it.
// No I/O, no timers, no state of their own beyond expansion tiles and
// the timeline's filter chips.
//
// Sourcing: Ansarullah sources ONLY (Al-Masirah, Yahya Saree, military
// media, Ansarullah channels). Government-force sources are never used
// and must never be added back — see edge/SOURCING-RULES.md.
import 'package:flutter/material.dart';

import 'fronts.dart';
import 'jalali.dart';
import 'map_painter.dart' show MapLegend;
import 'real_map.dart';
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
    ('فید انصارالله', doc.feeds.items.length),
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
  final Front front;
  const StatusTab(this.doc, {required this.front, super.key});

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
        // Real map (flutter_map + OSM tiles + bundled governorates).
        // Falls back to the schematic painter only if tiles are
        // unreachable — see real_map.dart.
        RealMapWidget(doc: doc, front: front),
        const SizedBox(height: 10),
        const MapLegend(),
        const SizedBox(height: 6),
        const Text(
          'مرز استان‌ها: OCHA HDX COD-AB، سازمان مرکزی آمار یمن (CC BY-IGO) — نمایش، نه مرز قانونی.',
          style: TextStyle(fontSize: 12, height: 1.6),
        ),
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
        SectionTitle('تصاویر بررسی‌شده (${toPersianDigitsInt(aden.images.length)})'),
        for (final img in aden.images)
          BodyCard(
            title: img.name,
            body: 'نشان می‌دهد: ${img.showsFa}\n\nحکم: ${img.verdictFa}',
            footer: 'منشأ: ${img.originFa}',
          ),
        SectionTitle('راستی‌آزمایی متقابل (${toPersianDigitsInt(aden.crossCheck.length)})'),
        Text(aden.crossCheckSummaryFa,
            style: const TextStyle(height: 1.7)),
        const SizedBox(height: 8),
        for (final c in aden.crossCheck)
          BodyCard(title: '${c.dateFa} — ${c.source}', body: c.saysFa),
        SectionTitle('کانال‌های رسمی (${toPersianDigitsInt(aden.channels.length)})'),
        Text(aden.channelsMethodFa, style: const TextStyle(height: 1.7)),
        const SizedBox(height: 8),
        for (final ch in aden.channels)
          BodyCard(
            title: '${ch.name} (${ch.handle})',
            body: '${ch.bodyFa}\n\nحکم: ${ch.verdictFa}',
            footer: ch.kindFa,
          ),
        SectionTitle('پست‌های شبکه‌های اجتماعی (${toPersianDigitsInt(aden.socialPosts.length)})'),
        for (final p in aden.socialPosts)
          BodyCard(
            title: '${p.name} — ${p.dateFa}',
            body: p.bodyFa,
            footer: '${p.kindFa}${p.url.isNotEmpty ? '\n${p.url}' : ''}',
          ),
        SectionTitle('تقویت‌کننده‌ها (${toPersianDigitsInt(aden.amplifiers.length)})'),
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
        SectionTitle('منابع عدن (${toPersianDigitsInt(aden.sources.length)})'),
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
// Tab 5 — timeline + sources, classified by the event taxonomy (v1).
// No flat ungrouped list: group headers per front, filter chips for
// event type and confidence tier, newest first. Every event card shows
// its type badge, confidence badge, front label and time.
// ---------------------------------------------------------------------------

class TimelineTab extends StatefulWidget {
  final YemenDoc doc;
  const TimelineTab(this.doc, {super.key});

  @override
  State<TimelineTab> createState() => _TimelineTabState();
}

class _TimelineTabState extends State<TimelineTab> {
  String? _selectedType; // null = همه
  String? _selectedConfidence; // null = همه

  @override
  Widget build(BuildContext context) {
    final doc = widget.doc;
    final events = List<YemenEvent>.from(doc.events)
      ..sort((a, b) => b.eventDate.compareTo(a.eventDate));

    final typesPresent =
        events.map((e) => e.eventType).toSet().toList()..sort();
    final confidencesPresent =
        events.map((e) => e.confidence).toSet().toList()..sort();

    final filtered = events.where((e) {
      if (_selectedType != null && e.eventType != _selectedType) {
        return false;
      }
      if (_selectedConfidence != null &&
          e.confidence != _selectedConfidence) {
        return false;
      }
      return true;
    }).toList();

    // Group the filtered events by front (all current events are Yemen;
    // the grouping stays so a future front slots in with no UI change).
    final byFront = <String, List<YemenEvent>>{};
    for (final e in filtered) {
      (byFront[e.front] ??= []).add(e);
    }
    final frontIds = byFront.keys.toList()..sort();

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const SectionTitle('گاه‌شمار رویدادها'),
        const Text(
          'دسته‌بندی رویدادها بر پایهٔ نوع و درجهٔ گواهی انصارالله است؛ «تأییدشده» یعنی بیانیهٔ رسمی انصارالله آن را گفته است.',
          style: TextStyle(height: 1.7),
        ),
        const SizedBox(height: 12),
        const Text('نوع رویداد:',
            style: TextStyle(fontWeight: FontWeight.bold)),
        const SizedBox(height: 6),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            _filterChip('همه', _selectedType == null, () {
              setState(() => _selectedType = null);
            }),
            for (final t in typesPresent)
              _filterChip(
                eventTypeLabelsFa[t] ?? t,
                _selectedType == t,
                () => setState(() => _selectedType = t),
              ),
          ],
        ),
        const SizedBox(height: 12),
        const Text('درجهٔ گواهی:',
            style: TextStyle(fontWeight: FontWeight.bold)),
        const SizedBox(height: 6),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            _filterChip('همه', _selectedConfidence == null, () {
              setState(() => _selectedConfidence = null);
            }),
            for (final c in confidencesPresent)
              _filterChip(
                eventConfidenceLabelsFa[c] ?? c,
                _selectedConfidence == c,
                () => setState(() => _selectedConfidence = c),
              ),
          ],
        ),
        const SizedBox(height: 16),
        if (filtered.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Text(
                'با این پالایه‌ها رویدادی نیست.',
                style: TextStyle(height: 1.7),
              ),
            ),
          ),
        for (final frontId in frontIds) ...[
          _frontGroupHeader(
            frontId,
            byFront[frontId]!.length,
          ),
          for (final e in byFront[frontId]!) _EventCard(event: e),
        ],
        const SectionTitle('گاه‌شمار عدن'),
        for (final t in doc.aden.timeline)
          BodyCard(
            title: '${t.date} — ${t.titleFa}',
            body: t.bodyFa,
            footer: 'لایه: ${t.layer} — منبع: ${t.sourceFa}',
          ),
        SectionTitle('منابع (${toPersianDigitsInt(doc.sources.length)})'),
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

  Widget _filterChip(String label, bool selected, VoidCallback onTap) {
    return FilterChip(
      label: Text(label),
      selected: selected,
      onSelected: (_) => onTap(),
    );
  }

  Widget _frontGroupHeader(String frontId, int count) {
    final label = frontLabelsFa[frontId] ?? frontId;
    return Container(
      margin: const EdgeInsets.only(top: 8, bottom: 8),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        'جبههٔ $label — ${toPersianDigitsInt(count)} رویداد',
        style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
      ),
    );
  }
}

class _EventCard extends StatelessWidget {
  final YemenEvent event;
  const _EventCard({required this.event});

  @override
  Widget build(BuildContext context) {
    final typeLabel = eventTypeLabelsFa[event.eventType] ?? 'دسته‌بندی‌نشده';
    final confLabel =
        eventConfidenceLabelsFa[event.confidence] ?? 'تأییدنشده';
    final frontLabel = frontLabelsFa[event.front] ?? event.front;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              event.titleFa,
              style: const TextStyle(
                  fontWeight: FontWeight.bold, fontSize: 15, height: 1.6),
            ),
            const SizedBox(height: 6),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                Chip(
                  label: Text(typeLabel,
                      style: const TextStyle(fontSize: 12)),
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                Chip(
                  label: Text(confLabel,
                      style: const TextStyle(fontSize: 12)),
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                Chip(
                  label: Text('جبههٔ $frontLabel',
                      style: const TextStyle(fontSize: 12)),
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                Chip(
                  label: Text(event.eventDate,
                      style: const TextStyle(fontSize: 12)),
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(event.detailFa, style: const TextStyle(height: 1.7)),
            const SizedBox(height: 6),
            Text(
              'منبع: ${event.sourceName}${event.corroboration > 1 ? ' — تأیید از ${toPersianDigitsInt(event.corroboration)} منبع' : ''}${event.sourceUrl.isNotEmpty ? '\n${event.sourceUrl}' : ''}',
              style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.secondary,
                height: 1.6,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Tab 6 — Ansarullah feeds: verbatim posts from the three official
// Ansarullah channels, grouped by channel, newest first. Nothing is
// invented; a channel that could not be fetched is shown as
// unavailable with its reason.
// ---------------------------------------------------------------------------

class FeedsTab extends StatelessWidget {
  final YemenDoc doc;
  const FeedsTab(this.doc, {super.key});

  @override
  Widget build(BuildContext context) {
    final feeds = doc.feeds;
    final byChannel = feeds.byChannel;
    // Fixed channel order (registry order), then any extra channel.
    final orderedChannels = <String>[
      for (final ch in feedChannelLabelsFa.keys)
        if (byChannel.containsKey(ch)) ch,
      for (final ch in byChannel.keys)
        if (!feedChannelLabelsFa.containsKey(ch)) ch,
    ];
    final fetchedAt = feeds.fetchedAt;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const SectionTitle('فیدهای انصارالله'),
        Text(
          fetchedAt == null
              ? 'پست‌های رسمی انصارالله — متن‌ها بی‌کم‌وکاست و به زبان اصلی‌اند.'
              : 'پست‌های رسمی انصارالله — آخرین خوانش: ${formatJalali(fetchedAt, withTime: true)} — متن‌ها بی‌کم‌وکاست و به زبان اصلی‌اند.',
          style: const TextStyle(height: 1.7),
        ),
        const SizedBox(height: 8),
        if (feeds.items.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(16),
              child: Text(
                'هنوز فیدی خوانده نشده است.',
                style: TextStyle(height: 1.7),
              ),
            ),
          ),
        for (final u in feeds.unavailable)
          Card(
            color: Colors.orange.shade50,
            margin: const EdgeInsets.symmetric(vertical: 4),
            child: ListTile(
              leading: const Icon(Icons.cloud_off_outlined),
              title: Text(feedChannelLabelsFa[u.channel] ?? u.channel),
              subtitle: Text(
                'در دسترس نیست: ${u.error}',
                style: const TextStyle(height: 1.6),
              ),
            ),
          ),
        for (final channel in orderedChannels) ...[
          Container(
            margin: const EdgeInsets.only(top: 12, bottom: 8),
            padding:
                const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.primaryContainer,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              '${feedChannelLabelsFa[channel] ?? channel} — ${toPersianDigitsInt(byChannel[channel]!.length)} پست',
              style:
                  const TextStyle(fontWeight: FontWeight.bold, fontSize: 15),
            ),
          ),
          for (final item in byChannel[channel]!) _FeedCard(item: item),
        ],
      ],
    );
  }
}

class _FeedCard extends StatelessWidget {
  final FeedItem item;
  const _FeedCard({required this.item});

  @override
  Widget build(BuildContext context) {
    final published = item.publishedAtDt;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    item.authorFa.isNotEmpty
                        ? item.authorFa
                        : item.channelTitleFa,
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                ),
                if (published != null)
                  Text(
                    formatJalali(published, withTime: true),
                    style: TextStyle(
                      fontSize: 12,
                      color: Theme.of(context).colorScheme.secondary,
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              item.channelTitleFa,
              style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.secondary,
              ),
            ),
            const SizedBox(height: 8),
            SelectableText(
              item.textFa,
              style: const TextStyle(height: 1.8),
            ),
            if (item.url.isNotEmpty) ...[
              const SizedBox(height: 8),
              SelectableText(
                item.url,
                style: TextStyle(
                  fontSize: 12,
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
