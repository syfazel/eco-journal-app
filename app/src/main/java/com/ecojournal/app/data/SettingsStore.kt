package com.ecojournal.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    // Phase 4 per-service toggles (spec §4.7). The master switch
    // REMAINS the top-level kill switch: master OFF = nothing
    // notifies, regardless of these. Defaults per spec §4.7/§4.8:
    // special_issues OFF + hidden while frozen/gated, radar OFF
    // so the radar list does not become a notification firehose.
    val NOTIFY_URGENT = booleanPreferencesKey("notify_urgent")
    val NOTIFY_MARKET_ALERTS = booleanPreferencesKey("notify_market_alerts")
    val NOTIFY_BUNDLES = booleanPreferencesKey("notify_bundles")
    val NOTIFY_SITREP = booleanPreferencesKey("notify_sitrep")
    val NOTIFY_NIGHTLY = booleanPreferencesKey("notify_nightly")
    val NOTIFY_SPECIAL_ISSUES = booleanPreferencesKey("notify_special_issues")
    val NOTIFY_RADAR = booleanPreferencesKey("notify_radar")
    // Phase 4 quiet hours (spec §4.8). Default: none (enabled
    // false). Start/end are minutes after midnight Tehran time
    // and may wrap midnight. quiet_mute_urgent defaults false and
    // is a deliberate, separate toggle — never a default.
    val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
    val QUIET_START_MIN = intPreferencesKey("quiet_start_min")
    val QUIET_END_MIN = intPreferencesKey("quiet_end_min")
    val QUIET_MUTE_URGENT = booleanPreferencesKey("quiet_mute_urgent")
    // Phase 4 market-alert cooldown state (spec §4.6, app-local).
    // Written ONLY after a notification was actually posted.
    val MARKET_ALERT_LAST_BUBBLE = longPreferencesKey("market_alert_last_notified_bubble")
    val MARKET_ALERT_LAST_SPREAD = longPreferencesKey("market_alert_last_notified_spread")
}

data class Settings(
    val fontScale: Float = 1.0f,
    val theme: String = "system",
    val notificationsEnabled: Boolean = true,
    val inviteOk: Boolean = false,
    val inviteHash: String? = null,
    val fcmToken: String? = null,
    val notifyUrgent: Boolean = true,
    val notifyMarketAlerts: Boolean = true,
    val notifyBundles: Boolean = true,
    val notifySitrep: Boolean = true,
    val notifyNightly: Boolean = true,
    val notifySpecialIssues: Boolean = false,
    val notifyRadar: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietStartMin: Int = 0,
    val quietEndMin: Int = 0,
    val quietMuteUrgent: Boolean = false,
    val marketAlertLastBubbleMs: Long? = null,
    val marketAlertLastSpreadMs: Long? = null,
) {
    /** Phase 4 NotificationPolicy view of these settings. */
    fun toNotificationPrefs(): NotificationPrefs = NotificationPrefs(
        masterEnabled = notificationsEnabled,
        notifyUrgent = notifyUrgent,
        notifyMarketAlerts = notifyMarketAlerts,
        notifyBundles = notifyBundles,
        notifySitrep = notifySitrep,
        notifyNightly = notifyNightly,
        notifySpecialIssues = notifySpecialIssues,
        notifyRadar = notifyRadar,
        quietHoursEnabled = quietHoursEnabled,
        quietStartMin = quietStartMin,
        quietEndMin = quietEndMin,
        quietMuteUrgent = quietMuteUrgent,
    )
}

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
                notifyUrgent = p[Keys.NOTIFY_URGENT] ?: true,
                notifyMarketAlerts = p[Keys.NOTIFY_MARKET_ALERTS] ?: true,
                notifyBundles = p[Keys.NOTIFY_BUNDLES] ?: true,
                notifySitrep = p[Keys.NOTIFY_SITREP] ?: true,
                notifyNightly = p[Keys.NOTIFY_NIGHTLY] ?: true,
                notifySpecialIssues = p[Keys.NOTIFY_SPECIAL_ISSUES] ?: false,
                notifyRadar = p[Keys.NOTIFY_RADAR] ?: false,
                quietHoursEnabled = p[Keys.QUIET_HOURS_ENABLED] ?: false,
                quietStartMin = (p[Keys.QUIET_START_MIN] ?: 0).coerceIn(0, 1439),
                quietEndMin = (p[Keys.QUIET_END_MIN] ?: 0).coerceIn(0, 1439),
                quietMuteUrgent = p[Keys.QUIET_MUTE_URGENT] ?: false,
                marketAlertLastBubbleMs = p[Keys.MARKET_ALERT_LAST_BUBBLE],
                marketAlertLastSpreadMs = p[Keys.MARKET_ALERT_LAST_SPREAD],
            )
        }

    suspend fun setFontScale(v: Float) =
        context.settingsStore.edit { it[Keys.FONT_SCALE] = v.coerceIn(0.85f, 1.3f) }

    suspend fun setTheme(v: String) =
        context.settingsStore.edit { it[Keys.THEME] = v.takeIf { it in VALID_THEMES } ?: "system" }

    suspend fun setNotifications(v: Boolean) =
        context.settingsStore.edit { it[Keys.NOTIFICATIONS] = v }

    // Phase 4 setters (one key each; a failed write never touches
    // the other toggles).
    suspend fun setNotifyUrgent(v: Boolean) =
        context.settingsStore.edit { it[Keys.NOTIFY_URGENT] = v }

    suspend fun setNotifyMarketAlerts(v: Boolean) =
        context.settingsStore.edit { it[Keys.NOTIFY_MARKET_ALERTS] = v }

    suspend fun setNotifyBundles(v: Boolean) =
        context.settingsStore.edit { it[Keys.NOTIFY_BUNDLES] = v }

    suspend fun setNotifySitrep(v: Boolean) =
        context.settingsStore.edit { it[Keys.NOTIFY_SITREP] = v }

    suspend fun setNotifyNightly(v: Boolean) =
        context.settingsStore.edit { it[Keys.NOTIFY_NIGHTLY] = v }

    suspend fun setNotifySpecialIssues(v: Boolean) =
        context.settingsStore.edit { it[Keys.NOTIFY_SPECIAL_ISSUES] = v }

    suspend fun setNotifyRadar(v: Boolean) =
        context.settingsStore.edit { it[Keys.NOTIFY_RADAR] = v }

    suspend fun setQuietHoursEnabled(v: Boolean) =
        context.settingsStore.edit { it[Keys.QUIET_HOURS_ENABLED] = v }

    suspend fun setQuietStartMin(v: Int) =
        context.settingsStore.edit { it[Keys.QUIET_START_MIN] = v.coerceIn(0, 1439) }

    suspend fun setQuietEndMin(v: Int) =
        context.settingsStore.edit { it[Keys.QUIET_END_MIN] = v.coerceIn(0, 1439) }

    suspend fun setQuietMuteUrgent(v: Boolean) =
        context.settingsStore.edit { it[Keys.QUIET_MUTE_URGENT] = v }

    suspend fun setMarketAlertLastNotified(metric: AlertMetric, epochMs: Long) =
        context.settingsStore.edit {
            it[if (metric == AlertMetric.BUBBLE) Keys.MARKET_ALERT_LAST_BUBBLE else Keys.MARKET_ALERT_LAST_SPREAD] = epochMs
        }

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
