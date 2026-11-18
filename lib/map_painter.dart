// Drawn Yemen conflict map — CustomPainter.
//
// Data-driven: every point, line and arrow comes from yemen.json
// (places, frontlines, advance_arrows, unit_positions, Aden city).
// Coordinates are approximate (±10 km, the researcher's own caveat —
// the warning is shown under the map, always, by the status tab).
//
// The painter is pure drawing: no I/O, no network, no state. It draws
// once per document (shouldRepaint only when the doc identity changes),
// so a refresh can never make the map re-draw in a loop.
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

import 'yemen.dart';

// Yemen bounding box used for the linear projection.
const double _minLat = 12.0;
const double _maxLat = 17.5;
const double _minLon = 42.0;
const double _maxLon = 55.0;

Offset _project(double lat, double lon, Size size) {
  final x = (lon - _minLon) / (_maxLon - _minLon) * size.width;
  final y = (1 - (lat - _minLat) / (_maxLat - _minLat)) * size.height;
  return Offset(x, y);
}

class YemenMapPainter extends CustomPainter {
  final YemenDoc doc;
  const YemenMapPainter(this.doc);

  @override
  void paint(Canvas canvas, Size size) {
    // Sand background.
    canvas.drawRect(
        Offset.zero & size, Paint()..color = const Color(0xFFF4EFE6));

    // Faint latitude/longitude grid.
    final gridPaint = Paint()
      ..color = const Color(0xFFE0D8C8)
      ..strokeWidth = 1;
    for (var i = 1; i < 5; i++) {
      final x = size.width * i / 5;
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), gridPaint);
      final y = size.height * i / 5;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), gridPaint);
    }

    // Frontlines: dark-red polylines through their waypoints.
    for (final line in doc.frontlines) {
      if (line.waypoints.length < 2) continue;
      final path = Path();
      for (var i = 0; i < line.waypoints.length; i++) {
        final p = _project(
            line.waypoints[i].lat, line.waypoints[i].lon, size);
        if (i == 0) {
          path.moveTo(p.dx, p.dy);
        } else {
          path.lineTo(p.dx, p.dy);
        }
      }
      canvas.drawPath(
          path,
          Paint()
            ..color = const Color(0xFF8E2424)
            ..strokeWidth = 2.5
            ..style = PaintingStyle.stroke
            ..strokeJoin = StrokeJoin.round);
    }

    // Advance arrows with triangular heads, coloured by side.
    for (final arrow in doc.arrows) {
      final from = _project(arrow.fromLat, arrow.fromLon, size);
      final to = _project(arrow.toLat, arrow.toLon, size);
      final color = Color(factionMeta(arrow.side).color);
      final paint = Paint()
        ..color = color
        ..strokeWidth = 3
        ..style = PaintingStyle.stroke
        ..strokeCap = StrokeCap.round;
      canvas.drawLine(from, to, paint);
      final angle = math.atan2(to.dy - from.dy, to.dx - from.dx);
      final head = Path()
        ..moveTo(to.dx, to.dy)
        ..lineTo(to.dx - 14 * math.cos(angle - 0.4),
            to.dy - 14 * math.sin(angle - 0.4))
        ..lineTo(to.dx - 14 * math.cos(angle + 0.4),
            to.dy - 14 * math.sin(angle + 0.4))
        ..close();
      canvas.drawPath(head, Paint()..color = color);
    }

    // Unit positions: diamonds, coloured by side, white centre.
    for (final unit in doc.units) {
      final p = _project(unit.lat, unit.lon, size);
      final color = Color(factionMeta(unit.side).color);
      final diamond = Path()
        ..moveTo(p.dx, p.dy - 7)
        ..lineTo(p.dx + 7, p.dy)
        ..lineTo(p.dx, p.dy + 7)
        ..lineTo(p.dx - 7, p.dy)
        ..close();
      canvas.drawPath(diamond, Paint()..color = color);
      canvas.drawPath(
          diamond,
          Paint()
            ..color = Colors.white
            ..style = PaintingStyle.stroke
            ..strokeWidth = 1.2);
    }

    // Places: circles coloured by faction. Aden gets the dashed 5-slot ring.
    final aden = doc.places.where((p) => p.nameFa == 'شهر عدن').toList();
    for (final place in doc.places) {
      final p = _project(place.lat, place.lon, size);
      final color = Color(factionMeta(place.faction).color);
      canvas.drawCircle(p, 5, Paint()..color = color);
      canvas.drawCircle(
          p,
          5,
          Paint()
            ..color = Colors.black54
            ..style = PaintingStyle.stroke
            ..strokeWidth = 1);
    }
    if (aden.isNotEmpty) {
      final centre = _project(aden.first.lat, aden.first.lon, size);
      const segments = 5;
      for (var i = 0; i < segments; i++) {
        final start = (i * 2 * math.pi / segments) + 0.12;
        final sweep = (2 * math.pi / segments) - 0.24;
        canvas.drawArc(
          Rect.fromCircle(center: centre, radius: 30),
          start,
          sweep,
          false,
          Paint()
            ..color = const Color(0xFFB71C1C)
            ..strokeWidth = 2.5
            ..style = PaintingStyle.stroke,
        );
      }
    }

    // Labels for the major places only — the rest stay dots.
    const labelled = {
      'شهر عدن',
      'صنعا',
      'تعز',
      'الحدیده',
      'مخا',
      'مأرب',
      'صعده',
      'ذمار',
    };
    for (final place in doc.places) {
      if (!labelled.contains(place.nameFa)) continue;
      final p = _project(place.lat, place.lon, size);
      final span = TextSpan(
        text: place.nameFa,
        style: const TextStyle(
          color: Color(0xFF1A1A1A),
          fontSize: 12,
          fontWeight: FontWeight.bold,
          fontFamily: 'Vazirmatn',
        ),
      );
      final tp = TextPainter(
        text: span,
        textDirection: ui.TextDirection.rtl,
      )..layout();
      final labelPos = Offset(
        (p.dx + 9).clamp(0, math.max(0, size.width - tp.width)),
        (p.dy - tp.height / 2).clamp(0, math.max(0, size.height - tp.height)),
      );
      // White halo so the label stays readable over lines.
      final bg = Rect.fromLTWH(labelPos.dx - 2, labelPos.dy - 1,
          tp.width + 4, tp.height + 2);
      canvas.drawRect(bg, Paint()..color = Colors.white.withValues(alpha: 0.75));
      tp.paint(canvas, labelPos);
    }
  }

  @override
  bool shouldRepaint(covariant YemenMapPainter oldDelegate) =>
      oldDelegate.doc != doc;
}

/// Legend chips for the map — faction colours + symbol key.
class MapLegend extends StatelessWidget {
  const MapLegend({super.key});

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 6,
      children: [
        for (final f in factionMetas)
          Chip(
            avatar: CircleAvatar(
              backgroundColor: Color(f.color),
              radius: 7,
            ),
            label: Text(f.labelFa, style: const TextStyle(fontSize: 12)),
            visualDensity: VisualDensity.compact,
            materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
          ),
        const Chip(
          label: Text('◆ موضع یگان', style: TextStyle(fontSize: 12)),
          visualDensity: VisualDensity.compact,
          materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
        const Chip(
          label: Text('— خط جبهه', style: TextStyle(fontSize: 12)),
          visualDensity: VisualDensity.compact,
          materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
        const Chip(
          label: Text('◯ حلقهٔ عدن (۵ جهت)', style: TextStyle(fontSize: 12)),
          visualDensity: VisualDensity.compact,
          materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
      ],
    );
  }
}
