package com.ecojournal.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * v4-I remote update repository + session state.
 *
 * CHECK (on launch + periodic, see AppUpdateWorker):
 * fetches `<edge>/version.json` with the same HttpURLConnection
 * discipline as the content sync (15s connect / 30s read,
 * fixed error codes, never raw exception text). A 404 means
 * "no update info published yet" — it is reported as
 * [CheckResult.NoInfo], never as an error banner, because
 * version.json legitimately does not exist until the v4
 * publish step writes it with the real v4 APK hash.
 *
 * DOWNLOAD: streams the APK to cacheDir/updates/app-update.apk
 * with progress (0..1) emitted to [state]; the file's SHA-256
 * is verified BEFORE anything is offered to install. A hash
 * mismatch deletes the file and yields Failed("hash_mismatch"):
 * a corrupt or substituted APK is never installed, never kept.
 *
 * INSTALL (one tap, system installer — see the honest limit in
 * AppUpdate.kt): a FileProvider content URI with MIME
 * application/vnd.android.package-archive. On Android 8+ the
 * "install unknown apps" permission is checked first; when it
 * is missing, the state becomes NeedsInstallPermission and the
 * UI opens the system settings page for THIS package only —
 * the app never asks for a blanket unknown-sources grant.
 *
 * SESSION STATE: a single app-wide StateFlow so the feed
 * banner and the Settings section can never disagree. The
 * pending record itself lives in SettingsStore (survives
 * process death); the download progress lives only in memory.
 * Content sync is untouched: this repository never calls
 * EdgeRepository.sync and never touches Room content tables.
 */
sealed class CheckResult {
    /** version.json absent (404) or edge unconfigured: no banner. */
    data object NoInfo : CheckResult()
    data object UpToDate : CheckResult()
    data class Available(val manifest: VersionManifest) : CheckResult()
    data class Failed(val code: String) : CheckResult()
}

sealed class UpdateUiState {
    data object Idle : UpdateUiState()
    data object Checking : UpdateUiState()
    /** An update is offered (pending record exists in settings). */
    data class Available(val pending: PendingUpdate) : UpdateUiState()
    data class Downloading(val pending: PendingUpdate, val progress: Float) : UpdateUiState()
    /** File downloaded + hash verified; one tap installs it. */
    data class ReadyToInstall(val pending: PendingUpdate, val file: File) : UpdateUiState()
    data object NeedsInstallPermission : UpdateUiState()
    data class Failed(val code: String) : UpdateUiState()
}

object AppUpdateSession {
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun setState(s: UpdateUiState) {
        _state.value = s
    }
}

class AppUpdateRepository(private val context: Context) {
    private val store = SettingsStore(context)

    /** File the APK is downloaded to (app-private cache). */
    fun downloadFile(): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "app-update.apk")
    }

    /**
     * One check pass. When an update is offered, the pending
     * record is stored (only if its code is newer than any
     * stored pending code — an older manifest never overwrites
     * a newer pending update) and the session state is set.
     * Returns the raw result for workers/callers that need it.
     */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val settings = store.settings.first()
        if (!settings.updateCheckEnabled) return@withContext CheckResult.NoInfo
        val base = EdgeConfig.BASE_URL.trimEnd('/')
        if (base.isBlank() || !base.startsWith("https://")) {
            return@withContext CheckResult.NoInfo
        }
        AppUpdateSession.setState(UpdateUiState.Checking)
        val result = fetchManifest("$base/version.json")
        when (result) {
            is CheckResult.Available -> {
                val manifest = result.manifest
                val installed = installedVersion(context)
                val offered = shouldOffer(manifest, installed, settings.dismissedUpdateCode)
                if (!offered) {
                    // Up to date, or this optional version was
                    // dismissed: clear any stale pending record
                    // for an older code, but never resurrect it.
                    val pending = settings.pendingUpdate
                    if (pending != null && pending.versionCode < manifest.versionCode) {
                        store.clearPendingUpdate()
                    }
                    AppUpdateSession.setState(UpdateUiState.Idle)
                    CheckResult.UpToDate
                } else {
                    val pending = settings.pendingUpdate
                    if (pending == null || manifest.versionCode > pending.versionCode) {
                        store.setPendingUpdate(manifest.toPending())
                    }
                    val current = (store.settings.first().pendingUpdate ?: manifest.toPending())
                    AppUpdateSession.setState(UpdateUiState.Available(current))
                    CheckResult.Available(manifest)
                }
            }
            is CheckResult.NoInfo -> {
                AppUpdateSession.setState(UpdateUiState.Idle)
                result
            }
            is CheckResult.UpToDate -> {
                AppUpdateSession.setState(UpdateUiState.Idle)
                result
            }
            is CheckResult.Failed -> {
                AppUpdateSession.setState(UpdateUiState.Failed(result.code))
                result
            }
        }
    }

    private fun fetchManifest(url: String): CheckResult {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return CheckResult.Failed("network")
        }
        conn.apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ECO-JOURNAL-Android/1.0")
        }
        return try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val manifest = parseManifest(body)
                    if (manifest == null) {
                        CheckResult.Failed("bad_manifest")
                    } else {
                        val installed = installedVersion(context)
                        if (manifest.versionCode > installed.versionCode) {
                            CheckResult.Available(manifest)
                        } else {
                            CheckResult.UpToDate
                        }
                    }
                }
                HttpURLConnection.HTTP_NOT_FOUND -> CheckResult.NoInfo
                403 -> CheckResult.Failed("http_403")
                else -> CheckResult.Failed("http_other")
            }
        } catch (_: Exception) {
            CheckResult.Failed("network")
        } finally {
            conn.disconnect()
        }
    }

    /** Download the pending APK with progress; verify its hash. */
    suspend fun download(pending: PendingUpdate) = withContext(Dispatchers.IO) {
        AppUpdateSession.setState(UpdateUiState.Downloading(pending, 0f))
        val file = downloadFile()
        val conn = try {
            URL(pending.apkUrl).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            AppUpdateSession.setState(UpdateUiState.Failed("network"))
            return@withContext
        }
        conn.apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "ECO-JOURNAL-Android/1.0")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                AppUpdateSession.setState(
                    UpdateUiState.Failed(
                        when (conn.responseCode) {
                            404 -> "http_404"
                            403 -> "http_403"
                            else -> "http_other"
                        },
                    ),
                )
                return@withContext
            }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            FileOutputStream(file).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null) {
                            AppUpdateSession.setState(
                                UpdateUiState.Downloading(pending, (done.toFloat() / total).coerceIn(0f, 1f)),
                            )
                        }
                    }
                }
            }
            if (sha256Of(file) != pending.apkSha256) {
                file.delete()
                AppUpdateSession.setState(UpdateUiState.Failed("hash_mismatch"))
                return@withContext
            }
            AppUpdateSession.setState(UpdateUiState.ReadyToInstall(pending, file))
        } catch (_: Exception) {
            file.delete()
            AppUpdateSession.setState(UpdateUiState.Failed("network"))
        } finally {
            conn.disconnect()
        }
    }

    /** One tap: hand the verified file to the system installer. */
    fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = context.packageManager
            if (!pm.canRequestPackageInstalls()) {
                AppUpdateSession.setState(UpdateUiState.NeedsInstallPermission)
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
                return
            }
        }
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    /** Dismiss an OPTIONAL update (mandatory updates ignore this). */
    suspend fun dismiss(pending: PendingUpdate) {
        if (pending.mandatory) return
        store.setDismissedUpdateCode(pending.versionCode)
        store.clearPendingUpdate()
        AppUpdateSession.setState(UpdateUiState.Idle)
    }

    /** Restore the session state from the stored pending record. */
    suspend fun restorePending() {
        val pending = store.settings.first().pendingUpdate ?: return
        val installed = installedVersion(context)
        val dismissed = store.settings.first().dismissedUpdateCode
        if (shouldOffer(pending.toManifest(), installed, dismissed)) {
            AppUpdateSession.setState(UpdateUiState.Available(pending))
        }
    }
}
