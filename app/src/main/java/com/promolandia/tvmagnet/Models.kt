package com.promolandia.tvmagnet

import android.net.Uri
import java.io.File
import java.io.Serializable

/** Accepted magnet forms: pasted URI, VIEW intent, and plain-text share. */
object MagnetLink {
    private val encodedHash = Regex("""xt=urn:btih:([A-Za-z0-9]{32}|[A-Fa-f0-9]{40})""")

    fun from(raw: String?): String? = try { fromUnsafe(raw) } catch (_: Throwable) { null }

    private fun fromUnsafe(raw: String?): String? {
        if (raw == null || raw.length > 32_768) return null
        return Regex("""magnet:\?[^\s]+""").find(raw.trim())?.value
            ?.replace("&amp;", "&")?.takeIf(encodedHash::containsMatchIn)
    }

    fun fromShare(text: String?, uri: Uri?) = from(uri?.toString()) ?: from(text)
}

data class VideoFile(val index: Int, val name: String, val path: String, val size: Long) : Serializable {
    val mimeType: String get() = mimeForName(name)
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    override fun toString(): String = "$name · ${formatBytes(size)}"
}

fun mimeForName(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts", "m2ts" -> "video/mpeg"
    "mpg", "mpeg" -> "video/mpeg"
    else -> "application/octet-stream"
}

enum class Phase { IDLE, METADATA, LISTED, BUFFERING, READY, STREAMING, STOPPING, ERROR }

data class StreamState(
    val phase: Phase = Phase.IDLE,
    val title: String = "",
    val files: List<VideoFile> = emptyList(),
    val selectedIndex: Int = -1,
    /** Largest video, preselected in the list; nothing streams until the user confirms. */
    val suggestedIndex: Int = -1,
    val downloadedBytes: Long = 0,
    /** Contiguous bytes ready at the play position (what the player can read right now). */
    val bufferedAhead: Long = 0,
    val totalBytes: Long = 0,
    val speedBytes: Long = 0,
    val peers: Int = 0,
    val error: String? = null,
    val clientConnected: Boolean = false
)

interface PieceReader {
    /** Total file length; availability is checked in torrent piece units. */
    val fileLength: Long
    fun isAvailable(offset: Long, length: Int): Boolean
    fun prioritize(offset: Long, length: Int)
    fun complete(): Boolean
    val error: String? get() = null
    fun signal()
    fun throwIfStopped()
    fun isStopped(): Boolean
}

data class ActiveStream(val title: String, val mimeType: String, val file: File, val pieces: PieceReader)
