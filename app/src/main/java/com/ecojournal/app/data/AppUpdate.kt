package com.ecojournal.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * v4-I remote in-app update system — PURE DOMAIN (no Android UI
 * dependency beyond PackageManager for the installed version).
 *
 * HONEST ANDROID LIMIT, stated here and in docs/v4i-report.md
 * because it shapes the whole feature: a sideloaded app CANNOT
 * silently self-install without the user tapping install. Only
 * Play in-app updates do that. So v4-I means: automatic check
 * (on launch + periodic) + background download with progress +
 * hash verification + ONE TAP on the system installer. The win
 * is that the reader never has to come back to the Muse chat
 * to download an APK by hand — not that the tap disappears.
 *
 * SEPARATION FROM CONTENT UPDATES: content (articles, market,
 * war room, nightly editions) already updates with NO APK at
 * all, through the Phase 1 manifest-v3 delta sync. This file
 * is ONLY about updating the app binary itself (new code,
 * screens, capabilities). A content-only change must never
 * trigger an app-update banner, and an app update must never
 * be presented as content.
 *
 * Manifest shape (edge file `version.json`, served at the
 * Pages root next to manifest.json — see edge/version_manifest.py):
 *   version_name: "4.0.0" (string, required)
 *   version_code: 4 (integer, required, must exceed the installed
 *     code to be offered)
 *   apk_url: https://… (required, HTTPS only)
 *   apk_sha256: 64 lowercase hex (required, never a placeholder)
 *   changelog_fa: Persian changelog text (required, may be "")
 *   mandatory: true/false (required; mandatory = no dismiss)
 *   generated_at_utc: ISO-8601 string (optional, informational)
 * Anything else (missing field, wrong type, non-HTTPS URL,
 * non-hex hash, extra fields are IGNORED, never fatal) yields
 * null from [parseManifest]: a corrupt manifest offers
 * NOTHING, it never crashes and never offers a partial update.
 *
 * Fixed error codes (same convention as sync codes): the UI
 * maps each code to a fixed Persian string; raw exception
 * text and HTTP internals are never shown or stored.
 * Codes: "network", "http_404" (manifest absent = NO update
 * info, NOT an error banner — see AppUpdateRepository),
 * "http_403", "http_other", "bad_manifest", "hash_mismatch"
 * (downloaded file discarded), "no_install_permission"
 * (user must allow "install unknown apps" for ECO JOURNAL).
 */

/** A validated remote version. Never constructed from raw JSON. */
data class VersionManifest(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val apkSha256: String,
    val changelogFa: String,
    val mandatory: Boolean,
)

private val HEX64 = Regex("^[0-9a-f]{64}$")

/** Strict parser: returns null on ANY structural problem. */
fun parseManifest(body: String): VersionManifest? {
    val obj = try {
        JSONObject(body)
    } catch (_: Exception) {
        return null
    }
    val name = obj.optString("version_name").takeIf { it.isNotBlank() } ?: return null
    if (!obj.has("version_code")) return null
    val code = try {
        obj.getLong("version_code")
    } catch (_: Exception) {
        return null
    }
    if (code <= 0) return null
    val url = obj.optString("apk_url").takeIf { it.startsWith("https://") } ?: return null
    val sha = obj.optString("apk_sha256").takeIf { HEX64.matches(it) } ?: return null
    if (!obj.has("changelog_fa") || !obj.has("mandatory")) return null
    val changelog = try {
        obj.getString("changelog_fa")
    } catch (_: Exception) {
        return null
    }
    val mandatory = try {
        obj.getBoolean("mandatory")
    } catch (_: Exception) {
        return null
    }
    return VersionManifest(name, code, url, sha, changelog, mandatory)
}

/** Installed app version, from PackageManager (never hardcoded). */
data class InstalledVersion(val versionName: String, val versionCode: Long)

fun installedVersion(context: Context): InstalledVersion {
    val info = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    } catch (_: Exception) {
        return InstalledVersion("", 0L)
    }
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    return InstalledVersion(info.versionName ?: "", code)
}

/**
 * Offer decision. A manifest is offered ONLY when its code
 * strictly exceeds the installed code. A dismissed code is
 * honoured ONLY for optional updates: a mandatory update can
 * never be dismissed, and a NEWER code than the dismissed one
 * is offered again (dismissal is per-version, not forever).
 */
fun shouldOffer(
    manifest: VersionManifest,
    installed: InstalledVersion,
    dismissedCode: Long,
): Boolean {
    if (manifest.versionCode <= installed.versionCode) return false
    if (manifest.mandatory) return true
    return manifest.versionCode != dismissedCode
}

/** SHA-256 hex of a file, or null if it cannot be read. */
fun sha256Of(file: File): String? {
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }
}

/** The pending update record persisted in SettingsStore. */
data class PendingUpdate(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val apkSha256: String,
    val changelogFa: String,
    val mandatory: Boolean,
)

fun PendingUpdate.toManifest(): VersionManifest = VersionManifest(
    versionName = versionName,
    versionCode = versionCode,
    apkUrl = apkUrl,
    apkSha256 = apkSha256,
    changelogFa = changelogFa,
    mandatory = mandatory,
)

fun VersionManifest.toPending(): PendingUpdate = PendingUpdate(
    versionName = versionName,
    versionCode = versionCode,
    apkUrl = apkUrl,
    apkSha256 = apkSha256,
    changelogFa = changelogFa,
    mandatory = mandatory,
)
