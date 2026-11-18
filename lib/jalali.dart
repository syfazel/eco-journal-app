// Jalali (Solar Hijri) calendar conversion.
//
// Port of the jalaali-js algorithm (Roozbeh Pournader / Behrang Noroozinia),
// verified against the `jdatetime` reference library on 10 dates
// (2026-03-21 -> 1405-01-01, 2026-11-19 -> 1405-08-28, ...).
// Pure arithmetic, no I/O, no dependencies. All divisions are truncating
// (Dart's `~/` on ints truncates toward zero, matching JS `~~(a / b)`).

/// Gregorian date -> Jalali (year, month, day).
List<int> gregorianToJalali(int gy, int gm, int gd) {
  final jdn = _gregorianToJdn(gy, gm, gd);
  return _jdnToJalali(jdn);
}

/// Format a [DateTime] as a Jalali date in Persian digits, e.g. «۲۸ آبان ۱۴۰۵».
/// [dt] is interpreted in Tehran time (+03:30 from UTC).
String formatJalali(DateTime dt, {bool withTime = false}) {
  final tehran = dt.toUtc().add(const Duration(hours: 3, minutes: 30));
  final j = gregorianToJalali(tehran.year, tehran.month, tehran.day);
  const months = [
    'فروردین',
    'اردیبهشت',
    'خرداد',
    'تیر',
    'مرداد',
    'شهریور',
    'مهر',
    'آبان',
    'آذر',
    'دی',
    'بهمن',
    'اسفند',
  ];
  var out =
      '${toPersianDigitsInt(j[2])} ${months[j[1] - 1]} ${toPersianDigitsInt(j[0])}';
  if (withTime) {
    final hh = tehran.hour.toString().padLeft(2, '0');
    final mm = tehran.minute.toString().padLeft(2, '0');
    out += ' — ساعت ${toPersianDigits(hh)}:${toPersianDigits(mm)}';
  }
  return out;
}

/// Latin digits (and the ':' separator is kept) -> Persian digits.
String toPersianDigits(String input) {
  const latin = '0123456789';
  const persian = '۰۱۲۳۴۵۶۷۸۹';
  final buf = StringBuffer();
  for (var i = 0; i < input.length; i++) {
    final idx = latin.indexOf(input[i]);
    buf.write(idx >= 0 ? persian[idx] : input[i]);
  }
  return buf.toString();
}

String toPersianDigitsInt(int n) => toPersianDigits(n.toString());

// ---------------------------------------------------------------------------
// jalaali-js internals (ported 1:1, variable names kept for auditability).
// ---------------------------------------------------------------------------

const List<int> _breaks = [
  -61,
  9,
  38,
  199,
  426,
  686,
  756,
  818,
  1111,
  1181,
  1210,
  1635,
  2060,
  2097,
  2192,
  2262,
  2324,
  2394,
  2456,
  3178,
];

int _div(int a, int b) => a ~/ b; // Dart ~/ truncates toward zero.
int _mod(int a, int b) => a - _div(a, b) * b;

/// Returns (leap, gregorianYear, marchDay): leap is 0 in a Jalali leap year.
List<int> _jalCal(int jy) {
  final bl = _breaks.length;
  final gy = jy + 621;
  var leapJ = -14;
  var jp = _breaks[0];
  var jump = 0;
  for (var i = 1; i < bl; i++) {
    final jm = _breaks[i];
    jump = jm - jp;
    if (jy < jm) break;
    leapJ += _div(jump, 33) * 8 + _div(_mod(jump, 33), 4);
    jp = jm;
  }
  var n = jy - jp;
  leapJ += _div(n, 33) * 8 + _div(_mod(n, 33) + 3, 4);
  if (_mod(jump, 33) == 4 && jump - n == 4) leapJ += 1;
  final leapG = _div(gy, 4) - _div((_div(gy, 100) + 1) * 3, 4) - 150;
  final march = 20 + leapJ - leapG;
  if (jump - n < 6) n = n - jump + _div(jump + 4, 33) * 33;
  var leap = _mod(_mod(n + 1, 33) - 1, 4);
  if (leap == -1) leap = 4;
  return [leap, gy, march];
}

int _gregorianToJdn(int gy, int gm, int gd) {
  var d = _div((gy + _div(gm - 8, 6) + 100100) * 1461, 4) +
      _div(153 * _mod(gm + 9, 12) + 2, 5) +
      gd -
      34840408;
  d = d - _div(_div(gy + 100100 + _div(gm - 8, 6), 100) * 3, 4) + 752;
  return d;
}

List<int> _jdnToGregorian(int jdn) {
  var j = 4 * jdn + 139361631;
  j = j + _div(_div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908;
  final i = _div(_mod(j, 1461), 4) * 5 + 308;
  final gd = _div(_mod(i, 153), 5) + 1;
  final gm = _mod(_div(i, 153), 12) + 1;
  final gy = _div(j, 1461) - 100100 + _div(8 - gm, 6);
  return [gy, gm, gd];
}

int _jalaliToJdn(int jy, int jm, int jd) {
  final cal = _jalCal(jy);
  return _gregorianToJdn(cal[1], 3, cal[2]) +
      (jm - 1) * 31 -
      _div(jm, 7) * (jm - 7) +
      jd -
      1;
}

List<int> _jdnToJalali(int jdn) {
  final g = _jdnToGregorian(jdn);
  final gy = g[0];
  var jy = gy - 621;
  var cal = _jalCal(jy);
  var jdn1f = _gregorianToJdn(gy, 3, cal[2]);
  var k = jdn - jdn1f;
  if (k >= 0) {
    if (k <= 185) {
      return [jy, 1 + _div(k, 31), _mod(k, 31) + 1];
    } else {
      k -= 186;
      return [jy, 7 + _div(k, 30), _mod(k, 30) + 1];
    }
  }
  jy -= 1;
  cal = _jalCal(jy);
  jdn1f = _gregorianToJdn(gy - 1, 3, cal[2]);
  k = jdn - jdn1f;
  if (k <= 185) {
    return [jy, 1 + _div(k, 31), _mod(k, 31) + 1];
  } else {
    k -= 186;
    return [jy, 7 + _div(k, 30), _mod(k, 30) + 1];
  }
}

/// Jalali -> JDN (used only by tests).
int jalaliToJdnForTest(int jy, int jm, int jd) => _jalaliToJdn(jy, jm, jd);
