package com.samford.patient.core.settings

import android.content.Context
import android.content.res.Configuration
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val serverOverride: String? = null,
    val discoveryUrlOverride: String? = null,
    val lastGoodRoot: String? = null,
    val activeProfileId: String? = null,
    val appLockEnabled: Boolean = false,
    val autoDownloadOnWifi: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val sampleMode: Boolean = false,
)

class SettingsStore(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            serverOverride = p[SERVER_OVERRIDE],
            discoveryUrlOverride = p[DISCOVERY_OVERRIDE],
            lastGoodRoot = p[LAST_GOOD_ROOT],
            activeProfileId = p[ACTIVE_PROFILE],
            appLockEnabled = p[LOCK_ENABLED] == true && p[LOCK_HASH] != null,
            autoDownloadOnWifi = p[AUTO_DOWNLOAD] ?: true,
            notificationsEnabled = p[NOTIFICATIONS] ?: true,
            sampleMode = p[SAMPLE_MODE] == true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun installId(): String {
        store.data.first()[INSTALL_ID]?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { if (it[INSTALL_ID] == null) it[INSTALL_ID] = id }
        return store.data.first()[INSTALL_ID] ?: id
    }

    suspend fun setServerOverride(root: String?) = store.edit {
        if (root == null) it.remove(SERVER_OVERRIDE) else it[SERVER_OVERRIDE] = root
    }

    suspend fun setDiscoveryOverride(url: String?) = store.edit {
        if (url == null) it.remove(DISCOVERY_OVERRIDE) else it[DISCOVERY_OVERRIDE] = url
    }

    suspend fun setLastGoodRoot(root: String) = store.edit { it[LAST_GOOD_ROOT] = root }

    suspend fun setActiveProfile(id: String?) = store.edit {
        if (id == null) it.remove(ACTIVE_PROFILE) else it[ACTIVE_PROFILE] = id
    }

    suspend fun setAutoDownload(enabled: Boolean) = store.edit { it[AUTO_DOWNLOAD] = enabled }

    suspend fun setNotifications(enabled: Boolean) = store.edit { it[NOTIFICATIONS] = enabled }

    suspend fun setSampleMode(enabled: Boolean) = store.edit { it[SAMPLE_MODE] = enabled }

    suspend fun setAppLock(salt: ByteArray, hash: ByteArray) = store.edit {
        it[LOCK_SALT] = Base64.encodeToString(salt, Base64.NO_WRAP)
        it[LOCK_HASH] = Base64.encodeToString(hash, Base64.NO_WRAP)
        it[LOCK_ENABLED] = true
    }

    suspend fun appLockSecret(): Pair<ByteArray, ByteArray>? {
        val p = store.data.first()
        val salt = p[LOCK_SALT] ?: return null
        val hash = p[LOCK_HASH] ?: return null
        return Base64.decode(salt, Base64.NO_WRAP) to Base64.decode(hash, Base64.NO_WRAP)
    }

    suspend fun clearAppLock() = store.edit {
        it.remove(LOCK_SALT)
        it.remove(LOCK_HASH)
        it.remove(LOCK_ENABLED)
    }

    suspend fun lastForegroundSync(): Long = store.data.first()[LAST_FOREGROUND_SYNC] ?: 0L

    suspend fun setLastForegroundSync(at: Long) = store.edit { it[LAST_FOREGROUND_SYNC] = at }

    /** Sign-out: forget everything tied to the person; keep device preferences (server, language, install id). */
    suspend fun clearAccountData() = store.edit {
        it.remove(ACTIVE_PROFILE)
        it.remove(LOCK_SALT)
        it.remove(LOCK_HASH)
        it.remove(LOCK_ENABLED)
        it.remove(SAMPLE_MODE)
        it.remove(LAST_FOREGROUND_SYNC)
    }

    private companion object {
        val SERVER_OVERRIDE = stringPreferencesKey("server_override")
        val DISCOVERY_OVERRIDE = stringPreferencesKey("discovery_override")
        val LAST_GOOD_ROOT = stringPreferencesKey("last_good_root")
        val ACTIVE_PROFILE = stringPreferencesKey("active_profile")
        val LOCK_ENABLED = booleanPreferencesKey("lock_enabled")
        val LOCK_SALT = stringPreferencesKey("lock_salt")
        val LOCK_HASH = stringPreferencesKey("lock_hash")
        val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download_wifi")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val SAMPLE_MODE = booleanPreferencesKey("sample_mode")
        val INSTALL_ID = stringPreferencesKey("install_id")
        val LAST_FOREGROUND_SYNC = longPreferencesKey("last_foreground_sync")
    }
}

/**
 * App language (English or Hindi). Read synchronously at activity start, so it lives in
 * SharedPreferences rather than DataStore. An empty value follows the phone's language.
 */
object LocaleStore {
    private const val PREFS = "locale"
    private const val KEY = "language"
    val supported = listOf("en", "hi")

    fun language(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun setLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }

    fun effectiveLocale(context: Context): Locale {
        val chosen = language(context)
        if (chosen in supported) return Locale.forLanguageTag(if (chosen == "hi") "hi-IN" else "en-IN")
        val system = context.resources.configuration.locales[0]
        return if (system.language == "hi") Locale.forLanguageTag("hi-IN") else Locale.forLanguageTag("en-IN")
    }

    fun wrap(base: Context): Context {
        val chosen = language(base)
        if (chosen !in supported) return base
        val locale = effectiveLocale(base)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
