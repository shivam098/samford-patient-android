package com.samford.patient.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.samford.patient.core.network.TokenProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Encrypts small secrets. Production uses an Android Keystore key; JVM tests use a fake. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore (hardware-backed where the phone
 * supports it). The output is IV (12 bytes) followed by ciphertext and tag.
 */
class KeystoreTokenCipher(private val alias: String = "samford_patient_session_v1") : TokenCipher {

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    fun deleteKey() {
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

data class SessionInfo(
    val accountId: String?,
    val mobileMasked: String?,
    val mobileDigestHint: String?,
    val expiresAt: String?,
)

/**
 * Holds the device-bound bearer token. Only ciphertext is persisted (DataStore); the plaintext
 * token exists in memory only while a request is being built.
 */
class TokenStore(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
) : TokenProvider {

    @Volatile
    private var memo: String? = null

    val session: Flow<SessionInfo?> = store.data.map { prefs ->
        if (prefs[TOKEN] == null) {
            null
        } else {
            SessionInfo(prefs[ACCOUNT], prefs[MOBILE_MASKED], prefs[MOBILE_HINT], prefs[EXPIRES])
        }
    }

    suspend fun isSignedIn(): Boolean = store.data.first()[TOKEN] != null

    suspend fun save(token: String, accountId: String?, mobileMasked: String?, mobileHint: String?, expiresAt: String?) {
        val encrypted = Base64.encodeToString(cipher.encrypt(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        store.edit { prefs ->
            prefs[TOKEN] = encrypted
            accountId?.let { prefs[ACCOUNT] = it } ?: prefs.remove(ACCOUNT)
            mobileMasked?.let { prefs[MOBILE_MASKED] = it } ?: prefs.remove(MOBILE_MASKED)
            mobileHint?.let { prefs[MOBILE_HINT] = it } ?: prefs.remove(MOBILE_HINT)
            expiresAt?.let { prefs[EXPIRES] = it } ?: prefs.remove(EXPIRES)
        }
        memo = token
    }

    override suspend fun token(): String? {
        memo?.let { return it }
        val encrypted = store.data.first()[TOKEN] ?: return null
        return try {
            String(cipher.decrypt(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8).also { memo = it }
        } catch (_: Exception) {
            // Key invalidated (for example after a device credential reset): treat as signed out.
            null
        }
    }

    /** Previous sign-in's mobile hint, used to wipe records if a different mobile signs in. */
    suspend fun mobileHint(): String? = store.data.first()[MOBILE_HINT]

    suspend fun clear() {
        memo = null
        store.edit { it.clear() }
    }

    private companion object {
        val TOKEN = stringPreferencesKey("token_ct")
        val ACCOUNT = stringPreferencesKey("account_id")
        val MOBILE_MASKED = stringPreferencesKey("mobile_masked")
        val MOBILE_HINT = stringPreferencesKey("mobile_hint")
        val EXPIRES = stringPreferencesKey("expires_at")
    }
}
