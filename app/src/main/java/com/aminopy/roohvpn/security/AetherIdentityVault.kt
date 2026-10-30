package com.aminopy.roohvpn.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Protects Aether's WARP identity and WireGuard private-key TOML files at rest.
 *
 * Vault failures are fatal to Aether startup/shutdown: callers must never
 * silently continue with a missing or newly provisioned identity after an
 * authentication/decryption failure.
 */
internal object AetherIdentityVault {
    private const val ALIAS = "roohvpn.aether.identity.v1"
    private const val SUFFIX = ".sealed"
    private const val IV_BYTES = 12
    private val identityName = Regex("^aether[A-Za-z0-9._-]*\\.toml(?:\\.corrupt)?$")

    fun sealPlaintext(dir: File) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create Aether identity directory")
        }
        val files = dir.listFiles()
            ?: throw IllegalStateException("Cannot enumerate Aether identity directory")
        files.filter { it.isFile && identityName.matches(it.name) }.forEach { file ->
            val clear = file.readBytes()
            var encrypted: ByteArray? = null
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val iv = cipher.iv
                try {
                    val ciphertext = cipher.doFinal(clear)
                    encrypted = ByteArray(iv.size + ciphertext.size).also { output ->
                        System.arraycopy(iv, 0, output, 0, iv.size)
                        System.arraycopy(ciphertext, 0, output, iv.size, ciphertext.size)
                    }
                    ciphertext.fill(0)
                } finally {
                    iv.fill(0)
                }

                val target = File(dir, file.name + SUFFIX)
                writeAtomically(target, encrypted!!)
                restrictToOwner(target)

                // Best-effort logical overwrite before unlink. Flash storage may
                // retain old physical blocks; this is not claimed as secure erase.
                FileOutputStream(file, false).use { stream ->
                    stream.write(ByteArray(clear.size))
                    stream.fd.sync()
                }
                if (!file.delete()) {
                    throw IllegalStateException("Could not remove plaintext Aether identity")
                }
            } finally {
                clear.fill(0)
                encrypted?.fill(0)
            }
        }
    }

    fun unseal(dir: File) {
        if (!dir.exists()) return
        val files = dir.listFiles()
            ?: throw IllegalStateException("Cannot enumerate sealed Aether identities")
        files.filter {
            it.isFile && it.name.endsWith(SUFFIX) &&
                identityName.matches(it.name.removeSuffix(SUFFIX))
        }.forEach { sealedFile ->
            val bytes = sealedFile.readBytes()
            var clear: ByteArray? = null
            try {
                require(bytes.size > IV_BYTES + 16) { "Sealed Aether identity is truncated" }
                val iv = bytes.copyOfRange(0, IV_BYTES)
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        key(),
                        GCMParameterSpec(128, iv)
                    )
                    clear = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
                } finally {
                    iv.fill(0)
                }

                val target = File(dir, sealedFile.name.removeSuffix(SUFFIX))
                if (!target.exists()) {
                    writeAtomically(target, clear!!)
                    restrictToOwner(target)
                }
            } finally {
                clear?.fill(0)
                bytes.fill(0)
            }
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File.createTempFile("aether-identity-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temp, false).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (unsupported: Exception) {
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("Cannot replace Aether identity vault file", unsupported)
                }
                if (!temp.renameTo(target)) {
                    throw IllegalStateException("Cannot commit Aether identity vault file", unsupported)
                }
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun restrictToOwner(file: File) {
        if (!file.setReadable(false, false) ||
            !file.setReadable(true, true) ||
            !file.setWritable(false, false) ||
            !file.setWritable(true, true)
        ) {
            throw IllegalStateException("Cannot restrict Aether identity file permissions")
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
