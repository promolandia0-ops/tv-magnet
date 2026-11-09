package com.promolandia.tvmagnet

import android.os.Looper
import com.promolandia.tvmagnet.server.StreamServer
import com.promolandia.tvmagnet.torrent.TorrentStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opt-in: runs the REAL TorrentStream + StreamServer with a desktop libtorrent4j build.
 *   TVM_E2E_MAGNET      magnet to fetch (use a legal test torrent, e.g. Sintel from webtorrent.io)
 *   TVM_E2E_NATIVE_DIR  folder holding the desktop libtorrent4j.so
 *   TVM_E2E_REF         optional: local copy of the chosen video, to compare served bytes
 * Skipped when the variables are missing, so normal builds need no network.
 */
@RunWith(RobolectricTestRunner::class)
class EndToEndTorrentTest {
    private fun log(m: String) { println(m); System.getenv("TVM_E2E_LOG")?.let { File(it).appendText(m + "\n") } }
    private fun pump(seconds: Int, stream: TorrentStream, until: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until()) return true
            stream.updateSnapshot()
            Thread.sleep(250)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return until()
    }

    private fun get(url: String, range: String): Triple<Int, String?, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000; c.readTimeout = 120_000; c.setRequestProperty("Range", range)
        val code = c.responseCode
        val body = c.inputStream.use { it.readBytes() }
        val cr = c.getHeaderField("Content-Range"); c.disconnect()
        return Triple(code, cr, body)
    }

    @Test fun metadataFirstThenRangeServing() {
        val magnet = System.getenv("TVM_E2E_MAGNET"); val native = System.getenv("TVM_E2E_NATIVE_DIR")
        assumeTrue(magnet != null && native != null && File(native, "libtorrent4j.so").isFile)
        val states = ArrayList<StreamState>()
        val server = StreamServer()
        val stream = TorrentStream(org.robolectric.RuntimeEnvironment.getApplication(),
            { server.active = it }, { }, { synchronized(states) { states += it } })
        try {
            stream.start(magnet!!)
            fun last() = synchronized(states) { states.lastOrNull() }
            // Step 1: metadata only.
            val ok = pump(60, stream) { last()?.phase == Phase.LISTED }
            if (!ok) {
                Thread.getAllStackTraces().filterKeys { it.name.contains("torrent") }.forEach { (t, st) -> log("E2E thread ${t.name}: " + st.take(12).joinToString(" | ")) }
                synchronized(states) { states.forEach { log("E2E state: ${it.phase} ${it.error}") } }
                log("E2E crash report: " + CrashReporter.read(org.robolectric.RuntimeEnvironment.getApplication()))
            }
            assertTrue("metadata not received: ${last()}", ok)
            val listed = last()!!
            log("E2E listed: ${listed.files.size} files, peers=${listed.peers}, suggested=${listed.suggestedIndex}")
            listed.files.forEach { log("E2E   #${it.index} ${it.name} ${it.size} video=${it.isVideo}") }
            assertTrue(listed.files.isNotEmpty())
            assertEquals("videos must come first", listed.files.sortedBy { !it.isVideo }.map { it.index }, listed.files.map { it.index })
            assertEquals("largest video preselected", listed.files.filter { it.isVideo }.maxBy { it.size }.index, listed.suggestedIndex)
            assertEquals("nothing may be chosen/streamed yet", -1, listed.selectedIndex)
            log("step: listed ok")
            assertNull("nothing served yet", stream.activeFile())
            Thread.sleep(3_000); shadowOf(Looper.getMainLooper()).idle()
            assertTrue("no real download before the user picks", last()!!.downloadedBytes < 256 * 1024)
            log("step: idle 3s passed, downloaded=${last()!!.downloadedBytes}")
            // Step 2: choose a video, serve it over HTTP with Range.
            log("step: selecting")
            stream.select(listed.suggestedIndex)
            assertTrue("file never became active", pump(30, stream) { stream.activeFile() != null })
            log("step: active")
            val file = listed.files.first { it.index == listed.suggestedIndex }
            assertTrue("first pieces never arrived: ${last()}", pump(60, stream) { stream.hasRange(0, minOf(file.size, 1_048_576L).toInt()) })
            log("step: head available")
            server.start(120_000, true)
            try {
                val url = "http://127.0.0.1:${server.listeningPort}/${server.token}/video.${file.name.substringAfterLast('.')}"
                val ref = System.getenv("TVM_E2E_REF")?.let(::File)?.takeIf { it.isFile }
                val (code, cr, head) = get(url, "bytes=0-1023")
                log("E2E range 0-1023 -> $code $cr (${head.size} bytes)")
                assertEquals(206, code); assertEquals(1024, head.size)
                assertEquals("bytes 0-1023/${file.size}", cr)
                ref?.let { r -> assertArrayEquals(r.inputStream().use { it.readNBytes(1024) }, head) }
                val mid = file.size / 2
                val (code2, cr2, middle) = get(url, "bytes=$mid-${mid + 4095}")
                log("E2E range mid -> $code2 $cr2 (${middle.size} bytes)")
                assertEquals(206, code2); assertEquals(4096, middle.size)
                ref?.let { r -> r.inputStream().use { it.skip(mid); assertArrayEquals(it.readNBytes(4096), middle) } }
                val (code3, cr3, tail) = get(url, "bytes=-1024")
                log("E2E suffix range -> $code3 $cr3 (${tail.size} bytes)")
                assertEquals(206, code3); assertEquals(1024, tail.size)
                ref?.let { r -> assertArrayEquals(r.readBytes().takeLast(1024).toByteArray(), tail) }
                assertTrue("peers seen", (last()?.peers ?: 0) >= 0)
            } finally { server.stop() }
        } finally { stream.stop(); stream.awaitStopped(10_000) }
    }
}
