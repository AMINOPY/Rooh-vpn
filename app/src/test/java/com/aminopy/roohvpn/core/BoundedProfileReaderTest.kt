package com.aminopy.roohvpn.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedProfileReaderTest {
    @Test
    fun readsContentAcrossBufferGrowthBoundaries() {
        val input = ByteArray(10_001) { (it % 251).toByte() }

        val actual = BoundedProfileReader.read(ByteArrayInputStream(input), 12_000)

        assertArrayEquals(input, actual)
    }

    @Test
    fun acceptsContentExactlyAtLimit() {
        val input = byteArrayOf(1, 2, 3, 4)

        assertArrayEquals(input, BoundedProfileReader.read(ByteArrayInputStream(input), input.size))
    }

    @Test(expected = ProfileTooLargeException::class)
    fun rejectsContentOneByteOverLimit() {
        BoundedProfileReader.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3)
    }

    @Test
    fun returnsEmptyArrayForEmptyInput() {
        assertEquals(0, BoundedProfileReader.read(ByteArrayInputStream(byteArrayOf()), 64).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveLimit() {
        BoundedProfileReader.read(ByteArrayInputStream(byteArrayOf(1)), 0)
    }
}
