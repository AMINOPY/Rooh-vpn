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
 * Small encrypted-at-rest store for a single selected profile.
 *
 * Profile data is encrypted with AES-256-GCM using a non-exportable Android
 * Keystore key. This class deliberately does not synchronize profile data to
 * cloud backup. Callers must still avoid logging profile values.
 */
class EncryptedProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(profileId: String, profileJson: String) {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(profileJson.isNotBlank()) { "profile data must not be blank" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(profileJson.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(ciphertext, Base64.NO_WRAP)

        check(preferences.edit()
            .putString(KEY_ID, profileId)
            .putString(KEY_IV, iv)
            .putString(KEY_DATA, data)
            .commit()) { "Could not persist encrypted profile" }
    }

    fun load(): StoredProfile? {
        val id = preferences.getString(KEY_ID, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val data = preferences.getString(KEY_DATA, null) ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
            )
            val clear = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))
            StoredProfile(id, clear.toString(Charsets.UTF_8))
        } catch (exception: Exception) {
            // Fail closed: do not return corrupted or unauthenticated profile data.
            clear()
            null
        }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear encrypted profile" }
    }

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
        const val KEY_ID = "profile_id"
        const val KEY_IV = "profile_iv"
        const val KEY_DATA = "profile_data"
    }
}
