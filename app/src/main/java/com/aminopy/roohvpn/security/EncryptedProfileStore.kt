package com.aminopy.roohvpn.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted-at-rest store for multiple saved profiles.
 *
 * Each profile uses AES-256-GCM with a fresh random IV and a non-exportable
 * Android Keystore key. Profile JSON is authenticated on decrypt. Callers must
 * still avoid logging profile values and should pass ephemeral copies to engines.
 */
class EncryptedProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(profileId: String, profileJson: String) {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(profileJson.isNotBlank()) { "profile data must not be blank" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(profileJson.toByteArray(Charsets.UTF_8))
        val prefix = keyPrefix(profileId)
        val currentIds = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        currentIds += profileId

        check(preferences.edit()
            .putString("${prefix}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("${prefix}_data", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putStringSet(KEY_IDS, currentIds)
            .commit()) { "Could not persist encrypted profile" }
    }

    fun load(profileId: String): StoredProfile? {
        val prefix = keyPrefix(profileId)
        val iv = preferences.getString("${prefix}_iv", null) ?: return null
        val data = preferences.getString("${prefix}_data", null) ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
            )
            val clear = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))
            StoredProfile(profileId, clear.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            // Fail closed: remove corrupted data and never return unauthenticated text.
            delete(profileId)
            null
        }
    }

    fun listProfiles(): List<StoredProfile> =
        preferences.getStringSet(KEY_IDS, emptySet()).orEmpty()
            .sorted()
            .mapNotNull(::load)

    fun delete(profileId: String) {
        val prefix = keyPrefix(profileId)
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        ids.remove(profileId)
        check(preferences.edit()
            .remove("${prefix}_iv")
            .remove("${prefix}_data")
            .putStringSet(KEY_IDS, ids)
            .commit()) { "Could not delete encrypted profile" }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear encrypted profiles" }
    }

    private fun keyPrefix(profileId: String): String =
        "profile_" + Base64.encodeToString(
            profileId.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    data class StoredProfile(val id: String, val json: String)

    private companion object {
        const val PREFERENCES = "rooh_encrypted_profiles"
        const val KEY_ALIAS = "rooh_profile_aes_gcm_v1"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_IDS = "profile_ids"
    }
}
