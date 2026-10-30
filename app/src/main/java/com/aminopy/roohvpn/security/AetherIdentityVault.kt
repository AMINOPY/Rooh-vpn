package com.aminopy.roohvpn.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Protects Aether's WARP identity and WireGuard private-key TOML files at rest.
 * Plaintext exists only while the native engine process is alive.
 */
internal object AetherIdentityVault {
    private const val ALIAS = "roohvpn.aether.identity.v1"
    private const val SUFFIX = ".sealed"
    private val identityName = Regex("^aether[A-Za-z0-9._-]*\\.toml(?:\\.corrupt)?$")

    fun sealPlaintext(dir: File) {
        dir.listFiles()?.filter { it.isFile && identityName.matches(it.name) }?.forEach { file ->
            runCatching {
                val clear = file.readBytes()
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key())
                    val sealed = cipher.iv + cipher.doFinal(clear)
                    val target = File(dir, file.name + SUFFIX)
                    target.writeBytes(sealed)
                    target.setReadable(false, false)
                    target.setReadable(true, true)
                    target.setWritable(false, false)
                    target.setWritable(true, true)
                    file.writeBytes(ByteArray(clear.size))
                    file.delete()
                } finally {
                    clear.fill(0)
                }
            }
        }
    }

    fun unseal(dir: File) {
        dir.listFiles()?.filter {
            it.isFile && it.name.endsWith(SUFFIX) &&
                identityName.matches(it.name.removeSuffix(SUFFIX))
        }?.forEach { sealedFile ->
            runCatching {
                val bytes = sealedFile.readBytes()
                require(bytes.size > 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                val clear = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
                try {
                    val target = File(dir, sealedFile.name.removeSuffix(SUFFIX))
                    if (!target.exists()) target.writeBytes(clear)
                    target.setReadable(false, false)
                    target.setReadable(true, true)
                    target.setWritable(false, false)
                    target.setWritable(true, true)
                } finally {
                    clear.fill(0)
                    bytes.fill(0)
                }
            }
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }
}
