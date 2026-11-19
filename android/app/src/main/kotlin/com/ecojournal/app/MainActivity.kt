package com.ecojournal.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

// v6.2.0: in-app update install path. The Dart side downloads the APK
// (SHA-256 verified against version.json) and calls `installApk` here;
// this fires ACTION_VIEW with a FileProvider URI, which opens the
// SYSTEM package installer. Android itself verifies the APK signature
// against the installed app (same shared debug keystore), so an update
// over 6.0/6.1 succeeds. There is no silent self-install on Android.
class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.ecojournal.app/app_update",
        ).setMethodCallHandler { call, result ->
            if (call.method == "installApk") {
                val path = call.argument<String>("path")
                if (path.isNullOrEmpty()) {
                    result.error("NO_PATH", "APK path missing", null)
                    return@setMethodCallHandler
                }
                try {
                    val apk = File(path)
                    val uri: Uri = FileProvider.getUriForFile(
                        this,
                        "$packageName.fileprovider",
                        apk,
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            uri,
                            "application/vnd.android.package-archive",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                    result.success(null)
                } catch (e: Exception) {
                    result.error("INSTALL_FAILED", e.message, null)
                }
            } else {
                result.notImplemented()
            }
        }
    }
}
