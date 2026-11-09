package com.promolandia.tvmagnet.torrent

import android.content.Context
import android.os.StatFs
import java.io.File

/** Private cache folder for sparse pieces. Wiped at startup, on stop and on service destroy. */
class TemporaryBuffer(context: Context) {
    val root = File(context.cacheDir, "stream-buffer")
    fun prepare(): File {
        root.deleteRecursively()
        check(root.mkdirs() || root.isDirectory) { "Não foi possível criar o buffer temporário." }
        return root
    }
    fun ensureRoom(bytes: Long = 64L * 1024L * 1024L) {
        if (StatFs(root.absolutePath).availableBytes < bytes)
            throw BufferException("Armazenamento livre insuficiente. Libere espaço no celular e tente novamente.")
    }
    fun clear() { try { root.deleteRecursively() } catch (_: Throwable) {} }
}
class BufferException(message: String) : Exception(message)
