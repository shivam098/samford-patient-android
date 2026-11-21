package com.samford.patient.data

import com.samford.patient.core.blob.BlobStore
import com.samford.patient.core.db.SamfordDatabase
import com.samford.patient.core.network.MobileApi
import com.samford.patient.core.notify.Notifier
import com.samford.patient.core.security.TokenStore
import com.samford.patient.core.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What sign-out and "session ended by the hospital" must remove from the phone. */
interface WorkCanceller {
    fun cancelAll()
}

/**
 * Owns sign-out. Signing out removes every cached record, page image, exported PDF, the token,
 * pending acknowledgements, scheduled background work and visible notifications. Device-level
 * preferences (server address, language) are kept.
 */
class SessionManager(
    private val db: SamfordDatabase,
    private val blobs: BlobStore,
    private val tokens: TokenStore,
    private val settings: SettingsStore,
    private val work: WorkCanceller,
    private val notifier: Notifier?,
    private val api: MobileApi,
    private val shareDir: File,
    private val renderCacheDir: File,
) {
    private val _sessionExpired = MutableStateFlow(false)

    /** True when the server rejected the token: records stay readable, but syncing needs a new sign-in. */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    suspend fun signOut(notifyServer: Boolean = true) {
        if (notifyServer && tokens.token() != null) {
            withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { api.logout() }
        }
        wipeLocalData()
        tokens.clear()
        settings.clearAccountData()
        _sessionExpired.value = false
    }

    suspend fun wipeLocalData() {
        work.cancelAll()
        notifier?.cancelAll()
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            shareDir.deleteRecursively()
            renderCacheDir.deleteRecursively()
        }
        blobs.deleteAllFiles()
    }

    /** A revoked device token wipes the phone; an expired one only asks the person to sign in again. */
    suspend fun onServerSignedOut(revoked: Boolean) {
        if (revoked) signOut(notifyServer = false) else _sessionExpired.value = true
    }

    fun clearExpired() {
        _sessionExpired.value = false
    }

    private companion object {
        const val LOGOUT_TIMEOUT_MS = 3_000L
    }
}
