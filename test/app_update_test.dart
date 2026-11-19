// v6.2.0 tests: the in-app update check (the v6.0/v6.1 bug — no
// version.json fetch, no comparison, no dialog existed at all).
// All assertions use stubbed fetchers / installers — no network, no
// store — except the pure manifest parser.
import 'dart:async';
import 'dart:io';

import 'package:ecojournal_yemen/app_update.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _manifestJson = '''
{"version_name": "6.2.0", "version_code": 15,
 "apk_url": "https://syfazel.github.io/eco-journal-app/app-debug-v6.2.apk",
 "apk_sha256": "f15dba4662fe1859f2396f65b5c25a4c6b98c53b7a16c0405eb9932879270342",
 "changelog_fa": "نسخهٔ ۶٫۲: بررسی نسخهٔ تازه در داخل برنامه.",
 "mandatory": true}''';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('manifest parses: all fields, strict types', () {
    final m = AppUpdateManifest.parse(_manifestJson);
    expect(m.versionName, '6.2.0');
    expect(m.versionCode, 15);
    expect(m.apkUrl,
        'https://syfazel.github.io/eco-journal-app/app-debug-v6.2.apk');
    expect(m.apkSha256, hasLength(64));
    expect(m.changelogFa, contains('۶٫۲'));
    expect(m.mandatory, isTrue);
  });

  test('isNewerThan: strictly greater code only', () {
    final m = AppUpdateManifest.parse(_manifestJson);
    expect(m.isNewerThan(14), isTrue, reason: '6.1 (code 14) must update');
    expect(m.isNewerThan(15), isFalse, reason: 'same version is no update');
    expect(m.isNewerThan(16), isFalse, reason: 'stale manifest is no update');
  });

  test('manifest rejects malformed input instead of "no update"', () {
    expect(() => AppUpdateManifest.parse('{}'), throwsFormatException);
    expect(
        () => AppUpdateManifest.parse(_manifestJson.replaceAll(
            '"version_code": 15', '"version_code": "15"')),
        throwsFormatException);
    expect(
        () => AppUpdateManifest.parse(_manifestJson.replaceAll(
            'https://', 'http://')),
        throwsFormatException,
        reason: 'APK URL must be HTTPS');
    expect(
        () => AppUpdateManifest.parse(_manifestJson.replaceAll(
            'f15dba46', 'ZZZZZZZZ')),
        throwsFormatException,
        reason: 'bad SHA-256 must not pass');
    expect(() => AppUpdateManifest.parse('not json'), throwsA(anything));
  });

  test('checker detects a newer version.json (stubbed code 15 > running 14)',
      () async {
    final checker = AppUpdateChecker(
      fetchText: (_) async => _manifestJson,
      currentVersionCode: () async => 14,
    );
    final found = await checker.check();
    expect(found, isNotNull);
    expect(found!.versionCode, 15);
    expect(found.mandatory, isTrue);
  });

  test('checker returns null when running version is current', () async {
    final checker = AppUpdateChecker(
      fetchText: (_) async => _manifestJson,
      currentVersionCode: () async => 15,
    );
    expect(await checker.check(), isNull);
  });

  test('checker propagates fetch failure (UI maps it to Persian + retry)',
      () async {
    final checker = AppUpdateChecker(
      fetchText: (_) async => throw const SocketException('no network'),
      currentVersionCode: () async => 14,
    );
    expect(checker.check(), throwsA(isA<SocketException>()));
    expect(
        appUpdatePersianError(const SocketException('no network')),
        contains('اینترنت'));
  });

  test('Persian errors cover timeout / HTTP / hash / installer paths', () {
    expect(appUpdatePersianError(TimeoutException('x')),
        contains('دوباره تلاش کنید'));
    expect(appUpdatePersianError(HttpException('HTTP 404')),
        contains('دوباره تلاش کنید'));
    expect(appUpdatePersianError(StateError('APK hash mismatch')),
        contains('دوباره تلاش کنید'));
    expect(
        appUpdatePersianError(
            StateError('installer channel unavailable (old build)')),
        contains('لینک مستقیم'));
  });

  test('installer wires download + system-install channel call', () async {
    // Mock the native install channel: record the method + path.
    String? calledMethod;
    Map<dynamic, dynamic>? calledArgs;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.ecojournal.app/app_update'),
      (MethodCall call) async {
        calledMethod = call.method;
        calledArgs = call.arguments as Map<dynamic, dynamic>?;
        return null;
      },
    );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
              const MethodChannel('com.ecojournal.app/app_update'), null);
    });

    final fakeApk = File('${Directory.systemTemp.path}/eco-test-update.apk');
    await fakeApk.writeAsBytes([1, 2, 3]);
    final installer = AppUpdateInstaller(
      downloadApk: (_, __) async => fakeApk,
      // Use the REAL default install call so the channel path is exercised.
    );
    final manifest = AppUpdateManifest.parse(_manifestJson);
    await installer.downloadAndInstall(manifest);
    expect(calledMethod, 'installApk');
    expect(calledArgs!['path'], fakeApk.path);
    await fakeApk.delete();
  });

  test('update wiring present in main.dart + sidebar footer', () {
    final main = File('lib/main.dart').readAsStringSync();
    expect(main, contains('AppUpdateChecker'));
    expect(main, contains('بررسی نسخهٔ تازه'));
    expect(main, contains('نسخهٔ \${info.version}'));
    expect(main, contains('mandatory'));
    expect(main, contains('_checkForUpdate(manual: false)'));
    final update = File('lib/app_update.dart').readAsStringSync();
    expect(update, contains('version.json'));
    expect(update, contains('package_info_plus'));
    expect(update, contains('installApk'));
    final manifest =
        File('android/app/src/main/AndroidManifest.xml').readAsStringSync();
    expect(manifest, contains('REQUEST_INSTALL_PACKAGES'));
    expect(manifest, contains('FileProvider'));
    expect(
        File('android/app/src/main/res/xml/file_paths.xml').existsSync(),
        isTrue);
    final activity = File(
            'android/app/src/main/kotlin/com/ecojournal/app/MainActivity.kt')
        .readAsStringSync();
    expect(activity, contains('ACTION_VIEW'));
    expect(activity, contains('application/vnd.android.package-archive'));
  });
}
