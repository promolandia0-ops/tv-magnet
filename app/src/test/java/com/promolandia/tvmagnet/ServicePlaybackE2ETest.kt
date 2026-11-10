package com.promolandia.tvmagnet

import android.os.Looper
import com.promolandia.tvmagnet.dlna.TvDevice
import com.promolandia.tvmagnet.service.StreamService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.net.URL
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in (TVM_E2E_MAGNET + TVM_E2E_NATIVE_DIR): the real StreamService flow Buscar -> pick -> play, with real libtorrent,
 * the real HTTP server, ffprobe/ffmpeg reading the URL while it is still downloading, and a fake DLNA renderer
 * (tools/fake_renderer.py, port TVM_E2E_RENDERER_PORT, result file TVM_E2E_RENDERER_JSON) that fetches like a TV.
 */
@RunWith(RobolectricTestRunner::class)
class ServicePlaybackE2ETest {
    private fun log(m: String) { println(m); System.getenv("TVM_E2E_LOG")?.let { File(it).appendText(m + "\n") } }
    private fun pump(seconds: Int, until: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            if (until()) return true
            Thread.sleep(100)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return until()
    }
    private fun run(vararg cmd: String, seconds: Int): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val t = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.also { it.start() }
        val done = pump(seconds) { !p.isAlive }
        if (!done) p.destroyForcibly()
        t.join(2000)
        return (if (done) p.exitValue() else -999) to out.toString()
    }

    @Test fun searchPlayLocalAndCastToFakeTv() {
        val magnet = System.getenv("TVM_E2E_MAGNET"); val native = System.getenv("TVM_E2E_NATIVE_DIR")
        assumeTrue(magnet != null && native != null && File(native, "libtorrent4j.so").isFile)
        val service = Robolectric.buildService(StreamService::class.java).create().get()
        try {
            val started = System.currentTimeMillis()
            assertNull(service.search(magnet!!))
            assertTrue("never listed: ${service.state.value}", pump(120) { service.state.value.phase == Phase.LISTED })
            val listed = service.state.value
            log("S listed in ${System.currentTimeMillis() - started} ms: ${listed.files.map { it.name }} suggested=${listed.suggestedIndex}")
            val index = listed.suggestedIndex
            System.getenv("TVM_E2E_DWELL")?.toInt()?.let { d -> log("S user dwells $d s on the list"); pump(d) { false } }
            // --- Tocar no celular: URL must stream through ffprobe/ffmpeg while the download goes on
            val local = AtomicReference<Pair<String?, String?>>()
            val t0 = System.currentTimeMillis()
            service.prepareLocal(index) { u, e -> local.set(u to e) }
            assertTrue("prepareLocal timed out: ${service.state.value}", pump(150) { local.get() != null })
            log("S prepareLocal -> ${local.get()} after ${System.currentTimeMillis() - t0} ms; state=${service.state.value.phase} ${service.state.value.downloadedBytes}/${service.state.value.totalBytes} peers=${service.state.value.peers}")
            val url = local.get().first ?: fail("prepareLocal error: ${local.get().second}") as Nothing
            val (code, probe) = run("ffprobe", "-v", "error", "-show_entries", "format=duration,format_name", "-of", "default=nw=1", url, seconds = 120)
            log("S ffprobe exit=$code ${probe.trim().replace('\n', ' ')} | state ${service.state.value.downloadedBytes}/${service.state.value.totalBytes}")
            assertEquals("ffprobe failed: $probe", 0, code)
            assertTrue(probe.contains("duration="))
            val (code2, dec) = run("ffmpeg", "-v", "error", "-t", "20", "-i", url, "-f", "null", "-", seconds = 180)
            log("S ffmpeg decode 20s exit=$code2 ${dec.trim().take(200)} | state ${service.state.value.downloadedBytes}/${service.state.value.totalBytes}")
            assertEquals("ffmpeg decode failed: $dec", 0, code2)
            // seek far into the file (Exo/TV seek): must block until pieces arrive, then succeed
            val (code3, seek) = run("ffmpeg", "-v", "error", "-ss", "90", "-t", "3", "-i", url, "-f", "null", "-", seconds = 180)
            log("S ffmpeg seek@90s exit=$code3 ${seek.trim().take(200)}")
            assertEquals("seek failed: $seek", 0, code3)
            // --- Transmitir pra TV: fake renderer
            val rport = System.getenv("TVM_E2E_RENDERER_PORT")?.toInt()
            val rjson = System.getenv("TVM_E2E_RENDERER_JSON")?.let(::File)
            if (rport != null && rjson != null) {
                val tv = TvDevice("Fake TV", "127.0.0.1", URL("http://127.0.0.1:$rport/desc.xml"), URL("http://127.0.0.1:$rport/ctl"), null)
                val cast = AtomicReference<Pair<Boolean, String?>>()
                val c0 = System.currentTimeMillis()
                service.cast(tv, index) { e -> cast.set((e == null) to e) }
                assertTrue("cast timed out", pump(150) { cast.get() != null })
                log("S cast -> ${cast.get()} after ${System.currentTimeMillis() - c0} ms")
                assertTrue("cast error: ${cast.get()}", cast.get().first)
                assertTrue("renderer never finished fetching: ${rjson.readText().take(800)}", pump(150) { rjson.readText().contains("\"ok\": true") || rjson.readText().contains("\"ok\": false") })
                log("S renderer: ${rjson.readText()}")
                assertTrue("renderer failed: ${rjson.readText()}", rjson.readText().contains("\"ok\": true"))
            }
        } finally { service.onDestroy() }
    }
}
