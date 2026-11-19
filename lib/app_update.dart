// In-app update check — v6.2.0/code 15.
//
// WHAT WAS MISSING: the Flutter rebuild (v6.0/v6.1) had NO update check
// at all — no version.json fetch, no version_code comparison, no dialog.
// The old Kotlin app had AppUpdateWorker/UpdateEngine; the rebuild
// dropped it entirely. So 6.0 never notified the user that 6.1 existed.
//
// HOW IT WORKS (keeps the v6 anti-freeze contract: nothing here is ever
// awaited on the build path; the check runs in the background AFTER the
// bundled seed has rendered, and a failure never blocks the Yemen tab):
//  1. [AppUpdateChecker.check] fetches the Pages version.json
//     (https://syfazel.github.io/eco-journal-app/version.json) with a
//     15 s timeout, parses it into [AppUpdateManifest], and compares its
//     version_code with the RUNNING app's code (package_info_plus —
//     never hard-coded). A newer code means an update exists.
//  2. The UI (main.dart) shows an update dialog. If the manifest says
//     mandatory, the dialog has NO dismiss option (barrier + back button
//     both blocked); otherwise «بعداً» dismisses it.
//  3. Tapping «دانلود و نصب» downloads the APK from apk_url into the
//     app's temp dir (streamed, off the UI thread), verifies its SHA-256
//     against the manifest, then hands the file to Android through the
//     MethodChannel `com.ecojournal.app/app_update` → `installApk`.
//     The native side (MainActivity.kt) fires ACTION_VIEW with a
//     FileProvider URI (application/vnd.android.package-archive), which
//     opens the SYSTEM installer — the only install path Android allows
//     for a sideloaded app; there is no silent self-install, and the
//     installer itself verifies the signing certificate matches the
//     installed app (same shared debug keystore), so an update over
//     6.0/6.1 succeeds instead of INSTALL_FAILED_UPDATE_INCOMPATIBLE.
//  4. Any failure (no network, bad manifest, bad hash, installer
//     refused) surfaces a Persian error with a retry affordance —
//     see [appUpdatePersianError].
//
// Testability: the fetcher, the current-code provider, the downloader
// and the install call are all injectable, so tests stub them (see
// test/app_update_test.dart) and never touch the network or the store.
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path_provider/path_provider.dart';

/// One version.json document, as served from GitHub Pages.
class AppUpdateManifest {
  final String versionName;
  final int versionCode;
  final String apkUrl;
  final String apkSha256;
  final String changelogFa;
  final bool mandatory;

  const AppUpdateManifest({
    required this.versionName,
    required this.versionCode,
    required this.apkUrl,
    required this.apkSha256,
    required this.changelogFa,
    required this.mandatory,
  });

  /// Parses and validates a version.json body. Throws [FormatException]
  /// on anything malformed (missing field, wrong type, non-HTTPS URL,
  /// bad hash) — a bad manifest must never be treated as "no update".
  factory AppUpdateManifest.fromJson(Map<String, dynamic> json) {
    final versionName = json['version_name'];
    final versionCode = json['version_code'];
    final apkUrl = json['apk_url'];
    final apkSha256 = json['apk_sha256'];
    final changelogFa = json['changelog_fa'];
    final mandatory = json['mandatory'];
    if (versionName is! String || versionName.isEmpty) {
      throw const FormatException('version.json: version_name missing');
    }
    if (versionCode is! int) {
      throw const FormatException('version.json: version_code missing');
    }
    if (apkUrl is! String || !apkUrl.startsWith('https://')) {
      throw const FormatException('version.json: apk_url must be HTTPS');
    }
    if (apkSha256 is! String ||
        !RegExp(r'^[0-9a-f]{64}$').hasMatch(apkSha256)) {
      throw const FormatException('version.json: apk_sha256 malformed');
    }
    if (changelogFa is! String) {
      throw const FormatException('version.json: changelog_fa missing');
    }
    if (mandatory is! bool) {
      throw const FormatException('version.json: mandatory missing');
    }
    return AppUpdateManifest(
      versionName: versionName,
      versionCode: versionCode,
      apkUrl: apkUrl,
      apkSha256: apkSha256,
      changelogFa: changelogFa,
      mandatory: mandatory,
    );
  }

  static AppUpdateManifest parse(String text) {
    final decoded = jsonDecode(text);
    if (decoded is! Map<String, dynamic>) {
      throw const FormatException('version.json: not a JSON object');
    }
    return AppUpdateManifest.fromJson(decoded);
  }

  /// A newer binary exists only when the manifest code is STRICTLY
  /// greater than the running code (equal = same version, less = stale
  /// manifest — neither is an update).
  bool isNewerThan(int currentVersionCode) =>
      versionCode > currentVersionCode;
}

/// Fetches the manifest and compares it with the running app.
/// Returns the manifest when a newer version exists, null otherwise.
/// Throws on network failure or a malformed manifest (callers map the
/// error to Persian via [appUpdatePersianError]).
class AppUpdateChecker {
  static const manifestUrl =
      'https://syfazel.github.io/eco-journal-app/version.json';

  final Future<String> Function(Uri url) fetchText;
  final Future<int> Function() currentVersionCode;

  AppUpdateChecker({
    Future<String> Function(Uri url)? fetchText,
    Future<int> Function()? currentVersionCode,
  })  : fetchText = fetchText ?? _defaultFetchText,
        currentVersionCode =
            currentVersionCode ?? _defaultCurrentVersionCode;

  /// The running app's versionCode, from the platform — never hard-coded.
  static Future<int> _defaultCurrentVersionCode() async {
    final info = await PackageInfo.fromPlatform();
    return int.tryParse(info.buildNumber) ?? 0;
  }

  static Future<String> _defaultFetchText(Uri url) async {
    final client = HttpClient();
    client.connectionTimeout = const Duration(seconds: 15);
    try {
      final request =
          await client.getUrl(url).timeout(const Duration(seconds: 15));
      final response =
          await request.close().timeout(const Duration(seconds: 15));
      if (response.statusCode != 200) {
        throw HttpException('version.json: HTTP ${response.statusCode}',
            uri: url);
      }
      return await response
          .transform(utf8.decoder)
          .join()
          .timeout(const Duration(seconds: 15));
    } on TimeoutException {
      throw TimeoutException('version.json fetch timed out');
    } finally {
      client.close();
    }
  }

  Future<AppUpdateManifest?> check() async {
    final current = await currentVersionCode();
    final text = await fetchText(Uri.parse(manifestUrl));
    final manifest = AppUpdateManifest.parse(text);
    return manifest.isNewerThan(current) ? manifest : null;
  }
}

/// Downloads the APK and hands it to the system installer.
/// The download and the install call are injectable for tests.
typedef ApkDownloader = Future<File> Function(
    Uri url, String expectedSha256);
typedef ApkInstallCall = Future<void> Function(File apk);

class AppUpdateInstaller {
  static const _channel =
      MethodChannel('com.ecojournal.app/app_update');

  final ApkDownloader downloadApk;
  final ApkInstallCall installFile;

  AppUpdateInstaller({
    ApkDownloader? downloadApk,
    ApkInstallCall? installFile,
  })  : downloadApk = downloadApk ?? _defaultDownloadApk,
        installFile = installFile ?? _defaultInstallFile;

  /// Download (with SHA-256 verification) then open the system
  /// installer. Returns only after the installer intent was fired.
  Future<void> downloadAndInstall(AppUpdateManifest manifest) async {
    final file = await downloadApk(
        Uri.parse(manifest.apkUrl), manifest.apkSha256);
    await installFile(file);
  }

  /// Streams the APK to the temp dir and verifies its SHA-256 against
  /// the manifest. A mismatch deletes the file and throws — a corrupt
  /// or tampered download must never reach the installer.
  static Future<File> _defaultDownloadApk(
      Uri url, String expectedSha256) async {
    final dir = await getTemporaryDirectory();
    final file = File('${dir.path}/eco-journal-update.apk');
    final client = HttpClient();
    client.connectionTimeout = const Duration(seconds: 15);
    try {
      final request =
          await client.getUrl(url).timeout(const Duration(seconds: 30));
      final response =
          await request.close().timeout(const Duration(seconds: 30));
      if (response.statusCode != 200) {
        throw HttpException('APK download: HTTP ${response.statusCode}',
            uri: url);
      }
      final sink = file.openWrite();
      await response.pipe(sink);
      await sink.close();
      final bytes = await file.readAsBytes();
      final actual = sha256.convert(bytes).toString();
      if (actual != expectedSha256) {
        try {
          await file.delete();
        } catch (_) {}
        throw StateError(
            'APK hash mismatch: downloaded file does not match version.json');
      }
      return file;
    } on TimeoutException {
      throw TimeoutException('APK download timed out');
    } finally {
      client.close();
    }
  }

  /// The real install path: native MainActivity fires ACTION_VIEW with
  /// a FileProvider URI for the APK, which opens the SYSTEM installer.
  /// This is not "open a browser and hope" — the intent targets the
  /// package installer directly, and Android verifies the signature.
  static Future<void> _defaultInstallFile(File apk) async {
    try {
      await _channel.invokeMethod<void>('installApk', {'path': apk.path});
    } on MissingPluginException {
      throw StateError(
          'installer channel unavailable (old build without the native install path)');
    }
  }
}

/// Persian, user-facing error text for any update-check/download/
/// install failure. Used for the dialog error state and the manual
/// check SnackBar; every branch carries a retry affordance in the UI.
String appUpdatePersianError(Object e) {
  final msg = e.toString();
  if (msg.contains('timed out') || msg.contains('TimeoutException')) {
    return 'بررسی نسخه ناموفق بود: اتصال کند است یا پاسخ نداد (مهلت ۱۵ ثانیه). دوباره تلاش کنید.';
  }
  if (msg.contains('SocketException') || msg.contains('Failed host')) {
    return 'بررسی نسخه ناموفق بود: به اینترنت دسترسی نیست. اتصال را بررسی کنید و دوباره تلاش کنید.';
  }
  if (msg.contains('HTTP')) {
    return 'بررسی نسخه ناموفق بود: سرور پاسخ نامناسبی داد. دوباره تلاش کنید.';
  }
  if (msg.contains('hash mismatch')) {
    return 'دانلود ناقص بود: فایل دریافتی با نسخهٔ اعلام‌شده هم‌خوانی ندارد. دوباره تلاش کنید.';
  }
  if (msg.contains('installer channel unavailable')) {
    return 'نصب از داخل برنامه در این نسخه پشتیبانی نمی‌شود؛ نسخهٔ تازه را از لینک مستقیم نصب کنید.';
  }
  if (msg.contains('FormatException') || msg.contains('version.json:')) {
    return 'بررسی نسخه ناموفق بود: اطلاعات نسخه خوانا نیست. دوباره تلاش کنید.';
  }
  return 'بررسی نسخه ناموفق بود. دوباره تلاش کنید.';
}
