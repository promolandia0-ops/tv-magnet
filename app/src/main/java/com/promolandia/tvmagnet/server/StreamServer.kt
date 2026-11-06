package com.promolandia.tvmagnet.server

import fi.iki.elonen.NanoHTTPD
import com.promolandia.tvmagnet.ActiveStream
import java.io.IOException

class StreamServer: NanoHTTPD("0.0.0.0", 0) {
    @Volatile var active: ActiveStream? = null
    @Volatile var token: String = java.util.UUID.randomUUID().toString()

    override fun serve(session: IHTTPSession): Response {
        val stream = active ?: return emptyResponse(Response.Status.NOT_FOUND)
        // Stream route includes an unguessable per-session UUID, not any library path.
        val allowed = "/$token/video." + session.uri.substringAfterLast('.', "")
        if (session.uri != allowed) return emptyResponse(Response.Status.NOT_FOUND)
        val mime = stream.mimeType
        if (session.method != Method.GET && session.method != Method.HEAD) return emptyResponse(Response.Status.METHOD_NOT_ALLOWED)
        val range = try { HttpRange.parse(session.headers["range"], stream.pieces.fileLength).also { HttpRange.validate(it, stream.pieces.fileLength) } }
        catch (_: BadRange) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, mime, "").apply { addHeader("Content-Range", "bytes */${stream.pieces.fileLength}") }
        }
        val body = TrackedInputStream(PieceInputStream(stream.pieces, range.length, stream.file, range.start))
        val partial = session.headers["range"] != null
        val result = newFixedLengthResponse(if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            mime, body, range.length)
        result.addHeader("Accept-Ranges", "bytes")
        result.addHeader("Content-Range", "bytes ${range.start}-${range.end}/${stream.fileLength()}")
        result.addHeader("Content-Disposition", "inline")
        result.addHeader("transferMode.dlna.org", "Streaming")
        result.addHeader("contentFeatures.dlna.org", DLNA_FEATURES)
        result.addHeader("Cache-Control", "no-cache")
        // WPAD/DLNA HEAD does not need the peer connection. Close immediately and
        // never request a piece during metadata-only probing.
        if (session.method == Method.HEAD) { try { body.close() } catch (_: IOException) {} }
        return result
    }

    private fun ActiveStream.fileLength() = pieces.fileLength
    private fun emptyResponse(status: Response.Status) = newFixedLengthResponse(status, "text/plain", "")
    companion object {
        const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        @Volatile var clientConnected: Boolean = false; private set
        @Volatile var clientFailure: String? = null; private set
        @Volatile private var connections = 0
        fun addConnection() { synchronized(this) { connections++; clientConnected = true }; clientFailure = null }
        fun removeConnection() { synchronized(this) { connections = (connections - 1).coerceAtLeast(0); clientConnected = connections > 0 } }
        fun clientError(error: Exception) { clientFailure = error.message }
    }
}
