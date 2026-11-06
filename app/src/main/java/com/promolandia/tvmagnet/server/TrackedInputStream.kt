package com.promolandia.tvmagnet.server

import java.io.FilterInputStream
import java.io.InputStream

/** Updates the UI and NanoHTTPD's client-disconnect state around actual socket reads. */
class TrackedInputStream(source: InputStream): FilterInputStream(source) {
    init { StreamServer.addConnection() }
    private var closed = false
    override fun read(): Int = try { super.read() } catch (e: Exception) { StreamServer.clientError(e); throw e }
    override fun read(bytes: ByteArray, off: Int, count: Int): Int {
        try { return super.read(bytes, off, count) }
        catch (e: Exception) { StreamServer.clientError(e); throw e }
    }
    override fun close() { if (!closed) { closed = true; try { super.close() } finally { StreamServer.removeConnection() } } }
}
