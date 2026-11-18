package com.ecojournal.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    // v4-I remote app update. The pending record is the offered
    // binary only (version + URL + hash + changelog + flag) —
    // never an APK, never a token, never device data. A missing
    // pending record means "no update offered", never an error.
    val UPDATE_CHECK_ENABLED = booleanPreferencesKey("update_check_enabled")
    val DISMISSED_UPDATE_CODE = longPreferencesKey("dismissed_update_code")
    val PENDING_UPDATE_NAME = stringPreferencesKey("pending_update_name")
    val PENDING_UPDATE_CODE = longPreferencesKey("pending_update_code")
    val PENDING_UPDATE_URL = stringPreferencesKey("pending_update_url")
    val PENDING_UPDATE_SHA = stringPreferencesKey("pending_update_sha")
    val PENDING_UPDATE_CHANGELOG = stringPreferencesKey("pending_update_changelog")
    val PENDING_UPDATE_MANDATORY = booleanPreferencesKey("pending_update_mandatory")
    val BROADCAST_SENDER = booleanPreferencesKey("broadcast_sender")
    val BROADCAST_NOTIFICATIONS = booleanPreferencesKey("broadcast_notifications")
    val SENDER_DISPLAY_NAME = stringPreferencesKey("sender_display_name")
    // Phase 6 (v5.5) signed broadcast (ECOJ-BCAST/2, design
    // tarahi-faz6.md §3.4): ONLY the PBKDF2-derived key (hex)
    // is stored — never the shared password itself, never in a
    // log, never in a packet. keyVersion 0 = no key set yet.
    // The previous key is kept for the 7-day grace after a
    // rotation, for packets already in flight.
    val BROADCAST_KEY_HEX = stringPreferencesKey("broadcast_key_hex")
    val BROADCAST_KEY_VERSION = longPreferencesKey("broadcast_key_version")
    val BROADCAST_KEY_SINCE = longPreferencesKey("broadcast_key_since")
    val BROADCAST_PREV_KEY_HEX = stringPreferencesKey("broadcast_prev_key_hex")
    val BROADCAST_PREV_KEY_VERSION = longPreferencesKey("broadcast_prev_key_version")
    val BROADCAST_PREV_KEY_UNTIL = longPreferencesKey("broadcast_prev_key_until")
    // Member-list version (design §1.3): rises by 1 on every
    // applied member-add / member-remove control packet.
    val BROADCAST_LIST_VERSION = longPreferencesKey("broadcast_list_version")
    // Latest key version announced by a verified key-announce
    // packet; the new password still has to arrive via Bale.
    val BROADCAST_ANNOUNCED_KEY_VERSION = longPreferencesKey("broadcast_announced_key_version")
}

data class Settings(
    val fontScale: Float = 1.0f,
    val theme: String = "system",
    val notificationsEnabled: Boolean = true,
    val inviteOk: Boolean = false,
    val inviteHash: String? = null,
    // Phase 5 (v5.4) broadcast messaging. broadcastSender = this
    // device may compose broadcasts (in practice only Yasin turns
    // it on); broadcastNotifications gates only broadcast notices,
    // never the app-update notice; senderDisplayName is the name
    // shown on outgoing envelopes (default "یاسین").
    val broadcastSender: Boolean = false,
    val broadcastNotifications: Boolean = true,
    val senderDisplayName: String = "یاسین",
    // Phase 6 (v5.5): derived signing key only — never the
    // password. broadcastKeyVersion 0 = no key set. prev* = the
    // rotated-out key inside its 7-day grace (null when none).
    val broadcastKeyHex: String? = null,
    val broadcastKeyVersion: Int = 0,
    val broadcastKeySince: Long? = null,
    val broadcastPrevKeyHex: String? = null,
    val broadcastPrevKeyVersion: Int = 0,
    val broadcastPrevKeyUntil: Long? = null,
    val broadcastListVersion: Long = 0L,
    val broadcastAnnouncedKeyVersion: Int = 0,
    val updateCheckEnabled: Boolean = true,
    val dismissedUpdateCode: Long = 0L,
    val pendingUpdate: PendingUpdate? = null,
) {
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
                broadcastSender = p[Keys.BROADCAST_SENDER] ?: false,
                broadcastNotifications = p[Keys.BROADCAST_NOTIFICATIONS] ?: true,
                senderDisplayName = (p[Keys.SENDER_DISPLAY_NAME] ?: "یاسین").take(100),
                broadcastKeyHex = p[Keys.BROADCAST_KEY_HEX]?.takeIf { it.length == 64 },
                broadcastKeyVersion = (p[Keys.BROADCAST_KEY_VERSION] ?: 0L).toInt().coerceAtLeast(0),
                broadcastKeySince = p[Keys.BROADCAST_KEY_SINCE],
                broadcastPrevKeyHex = p[Keys.BROADCAST_PREV_KEY_HEX]?.takeIf { it.length == 64 },
                broadcastPrevKeyVersion = (p[Keys.BROADCAST_PREV_KEY_VERSION] ?: 0L).toInt().coerceAtLeast(0),
                broadcastPrevKeyUntil = p[Keys.BROADCAST_PREV_KEY_UNTIL],
                broadcastListVersion = (p[Keys.BROADCAST_LIST_VERSION] ?: 0L).coerceAtLeast(0L),
                broadcastAnnouncedKeyVersion = (p[Keys.BROADCAST_ANNOUNCED_KEY_VERSION] ?: 0L).toInt().coerceAtLeast(0),
                updateCheckEnabled = p[Keys.UPDATE_CHECK_ENABLED] ?: true,
                dismissedUpdateCode = p[Keys.DISMISSED_UPDATE_CODE] ?: 0L,
                pendingUpdate = run {
                    val code = p[Keys.PENDING_UPDATE_CODE]
                    val name = p[Keys.PENDING_UPDATE_NAME]
                    val url = p[Keys.PENDING_UPDATE_URL]
                    val sha = p[Keys.PENDING_UPDATE_SHA]
                    // All four identity fields must be present;
                    // a partial record is treated as no record,
                    // never as an offer to install something.
                    if (code == null || name.isNullOrBlank() ||
                        url.isNullOrBlank() || sha.isNullOrBlank()
                    ) {
                        null
                    } else {
                        PendingUpdate(
                            versionName = name,
                            versionCode = code,
                            apkUrl = url,
                            apkSha256 = sha,
                            changelogFa = p[Keys.PENDING_UPDATE_CHANGELOG] ?: "",
                            mandatory = p[Keys.PENDING_UPDATE_MANDATORY] ?: false,
                        )
                    }
                },
            )
        }

    suspend fun setFontScale(v: Float) =
        context.settingsStore.edit { it[Keys.FONT_SCALE] = v.coerceIn(0.85f, 1.3f) }

    suspend fun setTheme(v: String) =
        context.settingsStore.edit { it[Keys.THEME] = v.takeIf { it in VALID_THEMES } ?: "system" }

    suspend fun setNotifications(v: Boolean) =
        context.settingsStore.edit { it[Keys.NOTIFICATIONS] = v }

    suspend fun setBroadcastSender(v: Boolean) =
        context.settingsStore.edit { it[Keys.BROADCAST_SENDER] = v }

    suspend fun setBroadcastNotifications(v: Boolean) =
        context.settingsStore.edit { it[Keys.BROADCAST_NOTIFICATIONS] = v }

    suspend fun setSenderDisplayName(v: String) =
        context.settingsStore.edit {
            it[Keys.SENDER_DISPLAY_NAME] = v.trim().take(100).ifBlank { "یاسین" }
        }

    /**
     * Phase 6 (v5.5): store a freshly derived signing key. The
     * caller passes ONLY the derived hex — the password never
     * reaches this function. On rotation the previous key moves
     * to the prev* slots with a 7-day expiry; on first set
     * there is no previous key.
     */
    suspend fun setBroadcastSigningKey(
        derivedHex: String,
        version: Int,
        sinceEpoch: Long,
        prevHex: String?,
        prevVersion: Int,
        prevUntilEpoch: Long?,
    ) = context.settingsStore.edit {
        it[Keys.BROADCAST_KEY_HEX] = derivedHex
        it[Keys.BROADCAST_KEY_VERSION] = version.toLong()
        it[Keys.BROADCAST_KEY_SINCE] = sinceEpoch
        if (prevHex == null) {
            it.remove(Keys.BROADCAST_PREV_KEY_HEX)
            it.remove(Keys.BROADCAST_PREV_KEY_VERSION)
            it.remove(Keys.BROADCAST_PREV_KEY_UNTIL)
        } else {
            it[Keys.BROADCAST_PREV_KEY_HEX] = prevHex
            it[Keys.BROADCAST_PREV_KEY_VERSION] = prevVersion.toLong()
            it[Keys.BROADCAST_PREV_KEY_UNTIL] = prevUntilEpoch ?: 0L
        }
    }

    /** Phase 6: drop the signing key entirely (current + previous). */
    suspend fun clearBroadcastSigningKey() =
        context.settingsStore.edit {
            it.remove(Keys.BROADCAST_KEY_HEX)
            it.remove(Keys.BROADCAST_KEY_VERSION)
            it.remove(Keys.BROADCAST_KEY_SINCE)
            it.remove(Keys.BROADCAST_PREV_KEY_HEX)
            it.remove(Keys.BROADCAST_PREV_KEY_VERSION)
            it.remove(Keys.BROADCAST_PREV_KEY_UNTIL)
        }

    suspend fun setBroadcastListVersion(v: Long) =
        context.settingsStore.edit {
            it[Keys.BROADCAST_LIST_VERSION] = v.coerceAtLeast(0L)
        }

    suspend fun setBroadcastAnnouncedKeyVersion(v: Int) =
        context.settingsStore.edit {
            it[Keys.BROADCAST_ANNOUNCED_KEY_VERSION] = v.coerceAtLeast(0).toLong()
        }

    suspend fun setUpdateCheckEnabled(v: Boolean) =
        context.settingsStore.edit { it[Keys.UPDATE_CHECK_ENABLED] = v }

    suspend fun setDismissedUpdateCode(code: Long) =
        context.settingsStore.edit { it[Keys.DISMISSED_UPDATE_CODE] = code }

    suspend fun setPendingUpdate(p: PendingUpdate) =
        context.settingsStore.edit {
            it[Keys.PENDING_UPDATE_NAME] = p.versionName
            it[Keys.PENDING_UPDATE_CODE] = p.versionCode
            it[Keys.PENDING_UPDATE_URL] = p.apkUrl
            it[Keys.PENDING_UPDATE_SHA] = p.apkSha256
            it[Keys.PENDING_UPDATE_CHANGELOG] = p.changelogFa
            it[Keys.PENDING_UPDATE_MANDATORY] = p.mandatory
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

    suspend fun clearPendingUpdate() =
        context.settingsStore.edit {
            it.remove(Keys.PENDING_UPDATE_NAME)
            it.remove(Keys.PENDING_UPDATE_CODE)
            it.remove(Keys.PENDING_UPDATE_URL)
            it.remove(Keys.PENDING_UPDATE_SHA)
            it.remove(Keys.PENDING_UPDATE_CHANGELOG)
            it.remove(Keys.PENDING_UPDATE_MANDATORY)
        }
}
