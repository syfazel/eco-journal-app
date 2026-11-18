// Jalali conversion tests — ground truth from the `jdatetime` reference
// library (checked 2026-11-19 in the lab before porting).
import 'package:ecojournal_yemen/jalali.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('gregorian -> jalali matches jdatetime ground truth', () {
    const cases = {
      (2026, 3, 21): (1405, 1, 1),
      (2026, 11, 19): (1405, 8, 28),
      (2026, 11, 18): (1405, 8, 27),
      (2025, 3, 21): (1404, 1, 1),
      (2024, 3, 20): (1403, 1, 1),
      (2026, 1, 1): (1404, 10, 11),
      (2026, 9, 23): (1405, 7, 1),
      (2026, 11, 17): (1405, 8, 26),
      (2027, 3, 21): (1406, 1, 1),
      (2000, 1, 1): (1378, 10, 11),
    };
    for (final entry in cases.entries) {
      final got = gregorianToJalali(
          entry.key.$1, entry.key.$2, entry.key.$3);
      expect(got, [entry.value.$1, entry.value.$2, entry.value.$3],
          reason: '${entry.key}');
    }
  });

  test('formatJalali renders Persian digits and month names', () {
    final text = formatJalali(DateTime.utc(2026, 11, 19, 12, 0));
    // 2026-11-19 12:00 UTC = 15:30 Tehran, 28 Aban 1405.
    expect(text, contains('۲۸ آبان ۱۴۰۵'));
  });

  test('toPersianDigits converts all digits', () {
    expect(toPersianDigits('0123456789'), '۰۱۲۳۴۵۶۷۸۹');
    expect(toPersianDigitsInt(90), '۹۰');
  });
}
