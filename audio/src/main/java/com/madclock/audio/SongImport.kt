package com.madclock.audio

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Count actual bytes, including providers with absent or dishonest size metadata. */
internal object SongImport {
    const val MAX_BYTES = 200L * 1024 * 1024
    fun copy(input: InputStream, output: OutputStream, cancelled: () -> Boolean, limit: Long = MAX_BYTES) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (cancelled()) throw IOException("Import cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            if (count.toLong() > limit - total) throw IOException("Song exceeds import limit")
            output.write(buffer, 0, count)
            total += count
        }
        if (total == 0L) throw IOException("Song is empty")
        if (cancelled()) throw IOException("Import cancelled")
    }
    fun cleanOrphans(directory: File, activeId: String?) {
        directory.listFiles()?.filter { file ->
            (file.name.startsWith("import-") && file.name.endsWith(".tmp")) ||
                (file.name.startsWith("song-") && file.name.endsWith(".audio") && file.name != "song-$activeId.audio")
        }?.forEach { it.delete() }
    }
}
