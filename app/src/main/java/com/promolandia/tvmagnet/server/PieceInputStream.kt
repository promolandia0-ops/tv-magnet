package com.promolandia.tvmagnet.server

import com.promolandia.tvmagnet.PieceReader
import java.io.InputStream
import java.io.IOException
import java.io.RandomAccessFile

/** Relative range reads are bounded; each verified piece is served as soon as it arrives. */
class PieceInputStream(
    private val source: PieceReader,
    private val requestedLength: Long,
    private val disk: java.io.File,
    start: Long
): InputStream() {
    private var position = start
    private val end = start + requestedLength
    private var file: RandomAccessFile? = null
    private var ended = false

    override fun read(): Int { val b = ByteArray(1); return read(b, 0, 1).let { if (it < 0) -1 else b[0].toInt() and 255 } }
    override fun read(bytes: ByteArray, off: Int, count: Int): Int {
        if (ended) return -1
        if (off < 0 || count < 0 || off + count > bytes.size) throw IndexOutOfBoundsException()
        if (count == 0) return 0
        val remaining = end - position
        if (remaining <= 0) { ended = true; return -1 }
        val wanted = minOf(count.toLong(), remaining, 64L * 1024L).toInt()
        source.throwIfStopped()
        if (!ensureAvailable(wanted)) {ended=true;return -1}
        val input = file ?: RandomAccessFile(disk, "r").also { file = it }
        input.seek(position)
        val read = input.read(bytes, off, wanted)
        if (read < 0) return -1
        position += read
        return read
    }
    private fun ensureAvailable(length:Int):Boolean {
        val deadline=System.currentTimeMillis()+120_000
        while(!source.isStopped() && !source.isAvailable(position,length) && !source.complete()) {
            source.prioritize(position,length)
            if(System.currentTimeMillis()>deadline) throw IOException("O trecho do vídeo demorou para chegar.")
            try {Thread.sleep(100)}catch(e:InterruptedException){Thread.currentThread().interrupt();return false}
        }
        if(source.isStopped())return false
        if(!source.isAvailable(position,length) && !source.complete())return false
        // A piece hash failure is an unrecoverable stream error unless another peer later redownloads it.
        return source.error?.let { throw IOException(it) } ?: true
    }
    override fun close() { try { file?.close() } catch(_:Exception) {};file=null;ended=true }
}
