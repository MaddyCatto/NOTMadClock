package com.madclock.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SongImportTest {
    private class GeneratedInput(private var remaining: Long) : InputStream() {
        var largestRead = 0
        override fun read(): Int = error("Use the bounded buffer")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            largestRead = maxOf(largestRead, len)
            if (remaining == 0L) return -1
            val n = minOf(remaining, len.toLong()).toInt(); remaining -= n; return n
        }
    }
    private class CountOutput : OutputStream() {
        var bytes = 0L
        override fun write(b: Int) { bytes++ }
        override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
    }
    @Test fun exactly200MiBStreamsThrough64KiBWithoutAllocatingTheWholeSong() {
        assertEquals(209_715_200L, SongImport.MAX_BYTES)
        val source = GeneratedInput(SongImport.MAX_BYTES); val target = CountOutput()
        SongImport.copy(source, target, { false })
        assertEquals(SongImport.MAX_BYTES, target.bytes)
        assertEquals(65_536, source.largestRead)
    }
    @Test fun unknownSizeOrDishonestProviderCannotWriteMoreThan200MiB() {
        val source = GeneratedInput(SongImport.MAX_BYTES + 1); val target = CountOutput()
        assertThrows(IOException::class.java) { SongImport.copy(source, target, { false }) }
        assertEquals(SongImport.MAX_BYTES, target.bytes)
    }
    @Test fun emptyFailedAndCancelledStreamsAreRejected() {
        assertThrows(IOException::class.java) { SongImport.copy(ByteArrayInputStream(byteArrayOf()), CountOutput(), { false }) }
        assertThrows(IOException::class.java) { SongImport.copy(object : InputStream() {
            override fun read(): Int = throw IOException("Unavailable")
        }, CountOutput(), { false }) }
        assertThrows(IOException::class.java) { SongImport.copy(GeneratedInput(5), CountOutput(), { true }) }
    }
    @Test fun smallSongIsCopiedByteForByte() {
        val data = byteArrayOf(1, 3, 9); val out = ByteArrayOutputStream()
        SongImport.copy(ByteArrayInputStream(data), out, { false })
        assertArrayEquals(data, out.toByteArray())
    }
}
