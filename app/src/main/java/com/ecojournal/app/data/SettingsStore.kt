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
    // M7: only the SHA-256 hash of the entered code is stored, never
    // the code itself. Clearing it (with inviteOk=false) is the
    // re-lock path used by revocation and by the Settings lock button.
    val INVITE_HASH = stringPreferencesKey("invite_hash")
    val FCM_TOKEN = stringPreferencesKey("fcm_token")
}

data class Settings(
    val fontScale: Float = 1.0f,
    val theme: String = "system",
    val notificationsEnabled: Boolean = true,
    val inviteOk: Boolean = false,
    val inviteHash: String? = null,
    val fcmToken: String? = null,
)

/**
 * Invite gate (M7): the code itself is never baked into the app
 * and never stored. The app hashes the entered code with SHA-256
 * (InviteGate) and compares hashes only, against the bundled seed
 * list on first run and the edge list after a sync. The old
 * phase-1 note about an "open gate until the edge config exists"
 * is superseded: the gate is closed by default and the feed stays
 * locked until a valid code is entered.
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
                inviteHash = p[Keys.INVITE_HASH],
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

    suspend fun setInviteHash(hash: String?) =
        context.settingsStore.edit {
            if (hash == null) it.remove(Keys.INVITE_HASH) else it[Keys.INVITE_HASH] = hash
        }

    /** Re-lock: clear both the flag and the stored hash. */
    suspend fun clearInvite() =
        context.settingsStore.edit {
            it[Keys.INVITE_OK] = false
            it.remove(Keys.INVITE_HASH)
        }

    suspend fun setFcmToken(v: String) =
        context.settingsStore.edit { it[Keys.FCM_TOKEN] = v }
}
