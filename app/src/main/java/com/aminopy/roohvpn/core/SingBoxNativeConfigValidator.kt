package com.aminopy.roohvpn.core

import android.content.Context
import io.nekohasekai.libbox.Libbox
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Native sing-box config validation.
 *
 * This initializes libbox before validation, but does not start a tunnel.
 * Structural preflight rejects oversized and malformed input before native code.
 */
object SingBoxNativeConfigValidator {
    fun validate(context: Context, configuration: ByteArray): EngineFailure? {
        SingBoxProfilePreflight.validate(configuration)?.let { return it }
        SingBoxLibboxRuntime.initialize(context)?.let { return it }

        val content = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(configuration))
                .toString()
        } catch (_: Exception) {
            return invalid()
        }

        return try {
            Libbox.checkConfig(content)
            null
        } catch (_: LinkageError) {
            EngineFailure(
                code = "SING_BOX_NATIVE_UNAVAILABLE",
                safeMessage = "The sing-box native validator is unavailable or incompatible.",
                retryable = false
            )
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun invalid() = EngineFailure(
        code = "SING_BOX_CONFIG_INVALID",
        safeMessage = "The sing-box profile failed native configuration validation.",
        retryable = false
    )
}
