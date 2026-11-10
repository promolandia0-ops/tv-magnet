package com.promolandia.tvmagnet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.promolandia.tvmagnet.service.StreamService
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs on a device/emulator: real service + real native libtorrent + real ExoPlayer on the app's own local URL. */
class PlaybackInstrumentedTest {
    private fun log(m: String) { Log.i("TVM-E2E", m) }

    @Test fun searchThenPlayLocallyWithExoPlayer() {
        val args = InstrumentationRegistry.getArguments()
        val magnet = args.getString("magnet") ?: error("pass -e magnet")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        StreamService.start(ctx)
        val latch = CountDownLatch(1); val ref = AtomicReference<StreamService>()
        ctx.bindService(Intent(ctx, StreamService::class.java), object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, b: IBinder?) { ref.set((b as StreamService.LocalBinder).service); latch.countDown() }
            override fun onServiceDisconnected(n: ComponentName?) {}
        }, Context.BIND_AUTO_CREATE)
        assertTrue("service never bound", latch.await(120, TimeUnit.SECONDS))
        val service = ref.get()
        val t0 = System.currentTimeMillis()
        assertNull(service.search(magnet))
        fun waitFor(sec: Int, what: String, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + sec * 1000L
            while (System.currentTimeMillis() < end && !cond()) Thread.sleep(250)
            assertTrue("$what timed out; state=${service.state.value}", cond())
        }
        waitFor(180, "listing") { service.state.value.phase == Phase.LISTED }
        val listed = service.state.value
        log("listed after ${System.currentTimeMillis() - t0} ms: ${listed.files.map { it.name }} suggested=${listed.suggestedIndex} peers=${listed.peers}")
        val got = AtomicReference<Pair<String?, String?>>()
        val t1 = System.currentTimeMillis()
        service.prepareLocal(listed.suggestedIndex) { u, e -> got.set(u to e) }
        waitFor(240, "prepareLocal") { got.get() != null }
        log("prepareLocal -> ${got.get()} after ${System.currentTimeMillis() - t1} ms; ${service.state.value.downloadedBytes}/${service.state.value.totalBytes}")
        val url = got.get().first ?: fail("prepare error ${got.get().second}") as Nothing
        // Server-only probe, independent of the player: time to first byte and 1 MB at head and tail.
        for ((label, range) in listOf("head" to "bytes=0-1048575", "tail" to "bytes=${service.state.value.totalBytes - 1048576}-")) {
            val t = System.currentTimeMillis()
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("Range", range)
            val code = c.responseCode
            val first = c.inputStream.read()
            val ttfb = System.currentTimeMillis() - t
            val n = 1 + c.inputStream.readBytes().size
            log("probe $label -> $code ttfb=${ttfb} ms, $n bytes in ${System.currentTimeMillis() - t} ms")
        }
        val title = service.state.value.title
        val player = AtomicReference<androidx.media3.exoplayer.ExoPlayer>()
        val error = AtomicReference<String>()
        val main = Handler(Looper.getMainLooper())
        val created = CountDownLatch(1)
        main.post {
            player.set(PlayerFactory.build(ctx, url, title) { e -> error.set(e.errorCodeName + ": " + e.message) })
            player.get().volume = 0f
            created.countDown()
        }
        assertTrue(created.await(60, TimeUnit.SECONDS))
        var maxPos = 0L; var state = -1
        val end = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < end && maxPos < 6_000 && error.get() == null) {
            val l = CountDownLatch(1)
            main.post { maxPos = maxOf(maxPos, player.get().currentPosition); state = player.get().playbackState; l.countDown() }
            l.await(30, TimeUnit.SECONDS)
            Log.i("TVM-E2E", "exo state=$state pos=$maxPos dl=${service.state.value.downloadedBytes}/${service.state.value.totalBytes} peers=${service.state.value.peers}")
            Thread.sleep(2000)
        }
        main.post { player.get().release() }
        assertNull("player error: ${error.get()}", error.get())
        assertTrue("ExoPlayer position did not advance (pos=$maxPos state=$state)", maxPos >= 6_000)
        log("PLAYBACK OK position=$maxPos ms (state=$state)")
        service.stop()
    }
}
