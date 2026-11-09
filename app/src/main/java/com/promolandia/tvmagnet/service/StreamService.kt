package com.promolandia.tvmagnet.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.*
import com.promolandia.tvmagnet.*
import com.promolandia.tvmagnet.dlna.*
import androidx.core.content.ContextCompat
import com.promolandia.tvmagnet.server.StreamServer
import com.promolandia.tvmagnet.torrent.TorrentStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A foreground owner for the local server and a single temporary torrent buffer. */
class StreamService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@StreamService }
    private val torrentState = MutableStateFlow(StreamState())
    val state = torrentState.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** All network, server and native work runs here, never on main. */
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val searching = AtomicBoolean(false)
    private val commands = Executors.newSingleThreadExecutor { Thread(it, "tv-commands").apply { isDaemon = true } }
    private val positionCommands = Executors.newSingleThreadExecutor { Thread(it, "tv-position").apply { isDaemon = true } }
    private val ticker = Handler(Looper.getMainLooper())
    private val wiped = AtomicBoolean(false)
    private val startRequested = AtomicBoolean(false)
    private val lastPosition = AtomicLong(0)
    private val knownDuration = AtomicLong(0)
    private var torrent: TorrentStream? = null
    private var stopped = false
    private val sessionId = System.nanoTime()

    lateinit var discovery: RendererDiscovery; private set
    @Volatile private var http: StreamServer? = null
    @Volatile private var selectedTv: TvDevice? = null
    @Volatile private var playback: Playback = Playback.IDLE
    @Volatile var streaming = false; private set
    @Volatile var isStarting = false; private set

    enum class Playback { IDLE, STARTING, PLAYING, PAUSED }
    val remoteDuration get() = knownDuration.get()
    val remotePosition get() = lastPosition.get()
    val currentlyPlaying get() = playback == Playback.PLAYING
    fun firstTv() = selectedTv ?: discovery.initialTV()

    override fun onCreate() {
        super.onCreate()
        http = StreamServer()
        createNotificationChannels()
        // Must precede all discovery, HTTP and native setup on Android 14/15.
        try {
            ServiceCompat.startForeground(this, 101, notification("Pronto para transmitir"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            discovery = RendererDiscovery(this)
            selectedTv = discovery.initialTV()
        } catch (t: Throwable) {
            Log.e("TVMagnet", "Foreground/discovery initialization", t)
            CrashReporter.capture(this, t)
            torrentState.value = StreamState(phase = Phase.ERROR, error = "Falha ao iniciar o serviço em segundo plano. Permita notificações e abra o app novamente.")
            runCatching { http?.stop() }; stopSelf(); return
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    fun selectTv(tv: TvDevice) { selectedTv = tv }

    /** Step 1 ("Buscar"): metadata only. Nothing is downloaded or served yet. */
    fun search(magnet: String): String? {
        if (searching.getAndSet(true)) return "Espere a busca atual terminar."
        isStarting = true
        torrentState.value = StreamState(phase = Phase.METADATA, title = "")
        io.launch {
            try {
                Breadcrumbs.mark(this@StreamService, "Buscar: início")
                resetStreaming()
                val newTorrent = TorrentStream(this@StreamService,
                    { active -> http?.active = active },
                    { /* state exposes the localized error */ },
                    { state -> isStarting = state.phase == Phase.METADATA; torrentState.value = state; updateNotification(state) })
                torrent = newTorrent
                newTorrent.start(magnet)
                withContext(Dispatchers.Main) { startTicker() }
            } catch (e: Throwable) {
                Log.e("TVMagnet", "Search failed", e)
                CrashReporter.capture(this@StreamService, e)
                isStarting = false
                torrentState.value = StreamState(phase = Phase.ERROR, error = "Não consegui iniciar a busca neste celular. Veja o diagnóstico ao reabrir o app.")
            } finally { searching.set(false) }
        }
        return null
    }

    /** Drops any previous torrent and TV session before a new search. */
    private fun resetStreaming() {
        val old = torrent
        torrent = null
        streaming = false; playback = Playback.IDLE
        lastPosition.set(0); knownDuration.set(0)
        http?.active = null
        if (old != null) { old.stop(); old.awaitStopped(8_000) }
    }

    fun manualTv(ip: String, callback: (TvDevice?, String) -> Unit) = discovery.manual(ip, callback)

    private fun ensureServer(): StreamServer {
        val server = http ?: throw IllegalStateException("Servidor local indisponível.")
        if (!server.wasStarted()) {
            Breadcrumbs.mark(this, "Servidor local: iniciando")
            server.start(120_000, false)
            Breadcrumbs.mark(this, "Servidor local: porta ${server.listeningPort}")
        }
        return server
    }

    fun streamUrl(local: Boolean = false): String? {
        val s = torrentState.value
        if (s.selectedIndex < 0) return null
        val h = http ?: return null
        if (!h.wasStarted()) return null
        val ip = if (local) "127.0.0.1" else (runCatching { lanIp() }.getOrNull() ?: return null)
        val extension = s.title.substringAfterLast('.', "mp4").lowercase()
        return "http://$ip:${h.listeningPort}/${h.token}/video.$extension"
    }

    /**
     * Step 2: the chosen video. Starts the local server, selects the file and waits (on IO)
     * for the first and last pieces so the TV/player does not time out. Every failure becomes a message.
     */
    private fun prepare(index: Int, local: Boolean, done: (String?, String?) -> Unit) {
        io.launch {
            try {
                val t = torrent ?: return@launch done(null, "Busque o magnet primeiro.")
                val file = torrentState.value.files.firstOrNull { it.index == index && it.isVideo }
                    ?: return@launch done(null, "Escolha um arquivo de vídeo.")
                if (!local && runCatching { lanIp() }.getOrNull() == null) return@launch done(null, "Conecte o celular e a TV ao mesmo Wi-Fi.")
                ensureServer()
                lastPosition.set(0); knownDuration.set(0)
                t.select(index)
                // The selection is applied on the native thread.
                var waited = 0
                while (t.activeFile() == null || torrentState.value.selectedIndex != index) {
                    if (torrentState.value.phase == Phase.ERROR) return@launch done(null, torrentState.value.error)
                    if (stopped || waited++ > 200) return@launch done(null, "Não consegui preparar o arquivo.")
                    delay(100)
                }
                val head = minOf(file.size, 3L * 1024 * 1024).toInt()
                val tail = minOf(file.size / 4, 1L * 1024 * 1024).toInt()
                val limit = SystemClock.elapsedRealtime() + 120_000
                while (!(t.hasRange(0, head) && (tail <= 0 || t.hasRange(file.size - tail, tail)))) {
                    if (torrentState.value.phase == Phase.ERROR) return@launch done(null, torrentState.value.error)
                    if (stopped) return@launch done(null, "Cancelado.")
                    if (SystemClock.elapsedRealtime() > limit) return@launch done(null, "Poucos peers: o começo do vídeo não chegou em 2 minutos.")
                    delay(500)
                }
                Breadcrumbs.mark(this@StreamService, "Buffer inicial pronto")
                val url = streamUrl(local) ?: return@launch done(null, "Conecte o celular e a TV ao mesmo Wi-Fi.")
                done(url, null)
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                Log.e("TVMagnet", "Prepare stream", e)
                CrashReporter.capture(this@StreamService, e)
                done(null, "Falha ao preparar o vídeo: ${e.message?.take(120) ?: e.javaClass.simpleName}")
            }
        }
    }

    /** "Tocar no celular": same preparation, then the caller opens the local player. */
    fun prepareLocal(index: Int, callback: (String?, String?) -> Unit) = prepare(index, true, callback)

    /** "Transmitir pra TV": prepare, then SetAVTransportURI + Play on the TV. */
    fun cast(tv: TvDevice, index: Int, callback: (String?) -> Unit = {}) {
        selectedTv = tv
        if (playback == Playback.STARTING) return callback("Aguarde, preparando o vídeo.")
        playback = Playback.STARTING
        Breadcrumbs.mark(this, "Transmitir: início")
        prepare(index, false) { url, error ->
            if (url == null) { playback = Playback.IDLE; return@prepare callback(error ?: "Falha ao preparar o vídeo.") }
            try {
                val s = torrentState.value
                val file = s.files.first { it.index == index }
                Breadcrumbs.mark(this, "Transmitir: enviando URL à TV")
                RendererController(tv).play(url, s.title, file.mimeType)
                startStreamingNotification()
                Breadcrumbs.mark(this, "Transmitir: TV tocando")
                callback(null)
            } catch (e: Throwable) {
                playback = Playback.IDLE
                Log.w("TVMagnet", "DLNA playback", e)
                CrashReporter.capture(this, e)
                callback("A TV não aceitou o comando. Confira o Wi-Fi e o formato do vídeo.")
            }
        }
    }

    private fun resume(tv: TvDevice, callback: (String?) -> Unit) {
        commands.execute {
            try { RendererController(tv).playAgain(); startStreamingNotification(); callback(null) }
            catch (e: Throwable) { callback("Não consegui continuar na TV.") }
        }
    }

    private fun startStreamingNotification() {
        streaming = true; playback = Playback.PLAYING
        updateNotification(torrentState.value)
        positionCommands.execute {
            var positionFailures = 0
            while (positionFailures < 2) {
                try {
                    val pos = RendererController(selectedTv ?: return@execute).position()
                    if (pos != null) {
                        lastPosition.set(pos.position); knownDuration.set(pos.duration)
                        if (pos.state == "PAUSED_PLAYBACK") playback = Playback.PAUSED
                        if (pos.state == "PLAYING") playback = Playback.PLAYING
                        positionFailures = 0
                    }
                } catch (_: Exception) { positionFailures++ }
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
            }
            // TV STP still holds URI: fall back to UI paused so Play resumes instead
            // of reinitializing unexpectedly. Buffer remains until explicit stop.
            if (playback == Playback.PLAYING) playback = Playback.PAUSED
        }
    }

    fun pause(callback: (String?) -> Unit = {}) {
        val tv = selectedTv ?: return callback("Escolha uma TV.")
        when (playback) {
            Playback.IDLE -> { callback("Toque em Transmitir pra TV."); return }
            Playback.STARTING, Playback.PLAYING -> commands.execute {
                try {
                    // A Samsung may finish SetURI before Play on another network
                    // callback. Stop cancels that pending request safely.
                    RendererController(tv).pause()
                    playback = Playback.PAUSED; streaming = false
                    updateNotification(torrentState.value)
                    callback(null)
                } catch (_: Exception) { callback("Não consegui pausar a TV.") }
            }
            Playback.PAUSED -> resume(tv, callback)
        }
    }

    /** Pause TV transport only. All cache data is wiped when this Service is destroyed. */
    fun stop() {
        val tv = selectedTv
        streaming = false; playback = Playback.IDLE
        if (tv != null) commands.execute {
            try { RendererController(tv).stop() } catch (_: Exception) {}
        }
        updateNotification(torrentState.value)
    }
    fun seek(seconds: Long, callback: (String?) -> Unit = {}) {
        val tv = selectedTv ?: return callback("Escolha uma TV.")
        commands.execute {
            try {
                val c = RendererController(tv)
                c.seek(c.clock(seconds.coerceAtMost(remoteDuration.coerceAtLeast(seconds))))
                lastPosition.set(seconds)
                callback(null)
            } catch (_: Exception) { callback("Essa TV não permite avançar este vídeo.") }
        }
    }
    fun volume(value: Int, callback: (Int?, String?) -> Unit = { _, _ -> }) {
        val tv = selectedTv ?: return
        commands.execute {
            try { RendererController(tv).setVolume(value); callback(value, null) }
            catch (_: Exception) { callback(null, "Volume não suportado pela TV.") }
        }
    }

    private fun startTicker() {
        ticker.removeCallbacksAndMessages(null)
        ticker.post(object : Runnable {
            override fun run() {
                if (stopped) return
                val current = torrent ?: return
                    // status()/have_piece() are synchronous JNI calls: never on main.
                current.updateSnapshot()
                ticker.postDelayed(this, 1_000)
            }
        })
    }

    private fun notification(text: String): Notification {
        val launch = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pending = PendingIntent.getService(this, 5, Intent(this, StreamService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val style = androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0)
        return NotificationCompat.Builder(this, "stream")
            .setSmallIcon(R.drawable.ic_tv).setContentTitle("TV Magnet").setContentText(text)
            .setContentIntent(launch).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(0, "Parar e apagar", pending).setStyle(style).build()
    }
    private fun createNotificationChannels() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("stream", "Transmissão", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }
    private fun updateNotification(state: StreamState) {
        val text = when (state.phase) {
            Phase.METADATA -> "Buscando… (peers: ${state.peers})"
            Phase.LISTED -> "Escolha o vídeo"
            Phase.ERROR -> state.error ?: "Falha na transmissão"
            else -> if (streaming) "Tocando na TV · ${state.peers} peers" else "Preparando · ${state.peers} peers · ${speed(state.speedBytes)}"
        }
        // Without POST_NOTIFICATIONS the notification is simply hidden; never let that throw.
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(101, notification(text)) } catch (_: Throwable) {}
    }
    private fun speed(bytes: Long) = if (bytes >= 1024 * 1024) "%.1f MB/s".format(bytes / 1024f / 1024f) else "${bytes / 1024} KB/s"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) stopSelf()
        // Never restore a magnet or download when Android restores a service.
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()
    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        if (!wiped.compareAndSet(false, true)) return
        stopped = true; streaming = false; playback = Playback.IDLE
        ticker.removeCallbacksAndMessages(null)
        // Reject queued commands after native destruction for older Samsung devices.
        commands.shutdown(); positionCommands.shutdownNow()
        http?.stop()
        torrent?.stop()
        // The torrent worker clears the buffer after native session/BIO release.
        // Android cache cleanup plus init cleanup handles process-level termination.
        if (::discovery.isInitialized) discovery.close()
        scope.cancel(); io.cancel(); stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val STOP = "com.promolandia.tvmagnet.STOP"
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java)); true
        } catch (t: Throwable) { CrashReporter.capture(context, t); false }
    }
}
