package com.aminopy.roohvpn.core

import java.io.InputStream

/**
 * Reads an imported profile with a strict byte limit and clears temporary buffers.
 *
 * The caller owns and should close [input]. The returned array belongs to the
 * caller and must be cleared when no longer needed because it may contain keys.
 */
object BoundedProfileReader {
    private const val CHUNK_SIZE = 4096

    @Throws(ProfileTooLargeException::class)
    fun read(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        var buffer = ByteArray(minOf(CHUNK_SIZE, maxBytes))
        val chunk = ByteArray(minOf(CHUNK_SIZE, maxBytes))
        var size = 0

        try {
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (count == 0) continue
                if (size > maxBytes - count) throw ProfileTooLargeException()

                val required = size + count
                if (required > buffer.size) {
                    var capacity = buffer.size
                    while (capacity < required) {
                        capacity = minOf(maxBytes, maxOf(capacity * 2, required))
                    }
                    val expanded = buffer.copyOf(capacity)
                    buffer.fill(0)
                    buffer = expanded
                }
                chunk.copyInto(buffer, destinationOffset = size, startIndex = 0, endIndex = count)
                size = required
            }
            return buffer.copyOf(size)
        } finally {
            buffer.fill(0)
            chunk.fill(0)
        }
    }
}

class ProfileTooLargeException : IllegalArgumentException("Profile exceeds the configured size limit")
