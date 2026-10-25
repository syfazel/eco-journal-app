package com.ecojournal.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "eco_settings"
)

private object Keys {
    val FONT_SCALE = floatPreferencesKey("font_scale")
    val THEME = stringPreferencesKey("theme") // system | light | dark | sepia
    val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
    val INVITE_OK = booleanPreferencesKey("invite_ok")
    val FCM_TOKEN = stringPreferencesKey("fcm_token")
}

data class Settings(
    val fontScale: Float = 1.0f,
    val theme: String = "system",
    val notificationsEnabled: Boolean = true,
    val inviteOk: Boolean = false,
    val fcmToken: String? = null,
)

/**
 * Invite-code check (Phase 1): the code itself is NOT baked into the
 * app. The control plane publishes the current code in the edge
 * config (see docs/control-plane-spec.md); the app fetches it at
 * first run and compares locally. Until the edge config exists, the
 * gate is open and inviteOk stays false — the gate must not be
 * "secured" by an embedded secret that ships in the APK.
 */
/** Valid theme values (M5): anything else falls back to "system",
 * both on read and on write, so a corrupt stored value can never
 * leave the app theme-less. */
private val VALID_THEMES = setOf("system", "light", "dark", "sepia")

class SettingsStore(private val context: Context) {
    val settings: Flow<Settings> = context.settingsStore.data
        // M5: a corrupt DataStore file falls back to defaults
        // instead of crashing every collector of this flow.
        .catch { emit(emptyPreferences()) }
        .map { p ->
            Settings(
                fontScale = (p[Keys.FONT_SCALE] ?: 1.0f).coerceIn(0.85f, 1.3f),
                theme = (p[Keys.THEME] ?: "system").takeIf { it in VALID_THEMES } ?: "system",
                notificationsEnabled = p[Keys.NOTIFICATIONS] ?: true,
                inviteOk = p[Keys.INVITE_OK] ?: false,
                fcmToken = p[Keys.FCM_TOKEN],
            )
        }

    suspend fun setFontScale(v: Float) =
        context.settingsStore.edit { it[Keys.FONT_SCALE] = v.coerceIn(0.85f, 1.3f) }

    suspend fun setTheme(v: String) =
        context.settingsStore.edit { it[Keys.THEME] = v.takeIf { it in VALID_THEMES } ?: "system" }

    suspend fun setNotifications(v: Boolean) =
        context.settingsStore.edit { it[Keys.NOTIFICATIONS] = v }

    suspend fun setInviteOk(v: Boolean) =
        context.settingsStore.edit { it[Keys.INVITE_OK] = v }

    suspend fun setFcmToken(v: String) =
        context.settingsStore.edit { it[Keys.FCM_TOKEN] = v }
}
