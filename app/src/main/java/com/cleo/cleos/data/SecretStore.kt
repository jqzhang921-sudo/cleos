package com.cleo.cleos.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cleo.cleos.ai.ApiEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretsStore by preferencesDataStore(name = "secrets")

/**
 * API keys, encrypted with an AES key that lives in the Android Keystore. They are filed
 * by service address, not by TA: two TAs on the same service share one key, and a TA moved
 * back to a service used before finds its key still there.
 *
 * The keystore key cannot be exported, so the stored ciphertext is useless anywhere but
 * this install on this phone. That is also why the file is excluded from backups (see
 * data_extraction_rules.xml): a restored copy could only ever fail to decrypt.
 * A failed decrypt is treated as "no key", so the user is asked again rather than the
 * app crashing.
 */
class SecretStore(private val context: Context) {
    /** The one key of before there were several TAs; [adoptLegacyKey] files it under its address. */
    private val legacyPref = stringPreferencesKey("api_key")

    private fun pref(baseUrl: String) = stringPreferencesKey("api_key:" + addressOf(baseUrl))
    private fun revisionPref(baseUrl: String) = stringPreferencesKey("api_key_revision:" + addressOf(baseUrl))
    /** A local revision also changes when a previous key is put back. It contains no key material. */
    suspend fun revision(baseUrl: String): String = context.secretsStore.data.first()[revisionPref(baseUrl)].orEmpty()

    fun hasKey(baseUrl: String): Flow<Boolean> = context.secretsStore.data.map { !it[pref(baseUrl)].isNullOrEmpty() }

    suspend fun key(baseUrl: String): String? {
        val stored = context.secretsStore.data.first()[pref(baseUrl)] ?: return null
        return runCatching { decrypt(stored) }
            .onFailure { Log.w(TAG, "stored API key could not be decrypted; treating as unset", it) }
            .getOrNull()
    }

    suspend fun setKey(baseUrl: String, value: String?) {
        val trimmed = value?.trim().orEmpty()
        context.secretsStore.edit {
            val old = it[pref(baseUrl)]?.let { cipher -> runCatching { decrypt(cipher) }.getOrNull() }.orEmpty()
            if (old != trimmed) it[revisionPref(baseUrl)] = java.util.UUID.randomUUID().toString()
            if (trimmed.isEmpty()) it.remove(pref(baseUrl)) else it[pref(baseUrl)] = encrypt(trimmed)
        }
    }

    private fun named(name: String) = stringPreferencesKey("secret:$name")

    /** Any other secret by name, decrypted; null when it is unset or can't be read back. */
    fun secret(name: String): Flow<String?> = context.secretsStore.data.map { prefs ->
        prefs[named(name)]?.let { stored ->
            runCatching { decrypt(stored) }
                .onFailure { Log.w(TAG, "stored secret $name could not be decrypted; treating as unset", it) }
                .getOrNull()
        }
    }

    suspend fun setSecret(name: String, value: String?) {
        context.secretsStore.edit { if (value.isNullOrEmpty()) it.remove(named(name)) else it[named(name)] = encrypt(value) }
    }

    /** Files the one key of before under the address it was used with; the ciphertext moves as is. */
    suspend fun adoptLegacyKey(baseUrl: String) {
        context.secretsStore.edit { prefs ->
            val old = prefs[legacyPref] ?: return@edit
            if (prefs[pref(baseUrl)] == null) prefs[pref(baseUrl)] = old
            prefs.remove(legacyPref)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(ct)
    }

    private fun decrypt(stored: String): String {
        val (iv, ct) = stored.split(":", limit = 2).let { unb64(it[0]) to unb64(it[1]) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val TAG = "SecretStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "cleos_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** An address the way keys are filed: the chat URL, lower-cased, so ".../v1" and ".../v1/" are one. */
internal fun addressOf(baseUrl: String): String = ApiEndpoint(baseUrl, "", "").chatUrl.lowercase()
