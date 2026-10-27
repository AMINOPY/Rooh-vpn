package com.aminopy.roohvpn.core

import io.nekohasekai.libbox.Libbox
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Native sing-box config validation.
 *
 * The application must initialize libbox with Libbox.setup(...) before calling
 * this validator. Structural preflight remains useful for rejecting oversized
 * and malformed input before passing it to native code.
 */
object SingBoxNativeConfigValidator {
    fun validate(configuration: ByteArray): EngineFailure? {
        SingBoxProfilePreflight.validate(configuration)?.let { return it }

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
