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
        // Must come before initializing native buffers/torrent: no FGS timeout on Samsung.
        ServiceCompat.startForeground(this, 101, notification("Pronto para transmitir"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        discovery = RendererDiscovery(this)
        selectedTv = discovery.initialTV()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    fun selectTv(tv: TvDevice) { selectedTv = tv }

    fun startStream(magnet: String): String? {
        if (isStarting || torrent != null) return "Espere o vídeo atual ser preparado."
        try {
            isStarting = true
            val server = http ?: return "Espere o aplicativo iniciar."
            // libtorrent init can be expensive: do it off-thread but before any download.
            val newTorrent = TorrentStream(this,
                { active -> server.active = active },
                { /* state exposes the localized error */ },
                { state -> isStarting = state.phase != Phase.ERROR; torrentState.value = state; updateNotification(state) })
            torrent = newTorrent
            server.start(120_000, false)
            newTorrent.start(magnet)
            startTicker()
            startCommandTimeout()
            return null
        } catch (e: Throwable) {
            Log.e("TVMagnet", "Native stream initialization", e)
            torrent?.stop(); torrent = null; http?.stop(); http = StreamServer()
            isStarting = false
            return "Não consegui iniciar o streaming neste celular. Digite o magnet novamente."
        }
    }

    fun manualTv(ip: String, callback: (TvDevice?, String) -> Unit) = discovery.manual(ip, callback)
    fun chooseFile(index: Int) {
        if (playback != Playback.IDLE) return
        lastPosition.set(0); knownDuration.set(0)
        torrent?.select(index)
    }

    fun streamUrl(): String? {
        val s = torrentState.value
        if (s.selectedIndex < 0) return null
        val h = http ?: return null
        if (!h.wasStarted()) return null
        val ip = lanIp() ?: return null
        val extension = s.title.substringAfterLast('.', "mp4").lowercase()
        return "http://$ip:${h.listeningPort}/${h.token}/video.$extension"
    }

    fun play(tv: TvDevice, callback: (String?) -> Unit = {}) {
        selectedTv = tv
        if (playback == Playback.PLAYING) { callback(null); return }
        if (playback == Playback.STARTING) return
        val url = streamUrl() ?: return callback("Conecte o celular e a TV ao mesmo Wi-Fi.")
        val s = torrentState.value
        val file = s.files.firstOrNull { it.index == s.selectedIndex } ?: return callback("Aguarde o vídeo ficar pronto.")
        // The phone serves sparse data and waits for peers. Let it play immediately.
        commands.execute {
            try {
                val controller = RendererController(tv)
                if (playback == Playback.PAUSED) controller.playAgain()
                else {
                    playback = Playback.STARTING
                    controller.play(url, s.title, file.mimeType)
                }
                startStreamingNotification()
                callback(null)
            } catch (e: Exception) {
                if (playback == Playback.STARTING) playback = Playback.IDLE
                Log.w("TVMagnet", "DLNA playback", e)
                callback("A TV não aceitou o comando. Confira o Wi-Fi e o formato do vídeo.")
            }
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
            Playback.IDLE -> { play(tv, callback); return }
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
            Playback.PAUSED -> play(tv, callback)
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
                torrent?.let { torrentState.value = it.tick() }
                updateNotification(torrentState.value)
                if (current.complete()) current.notifyComplete()
                ticker.postDelayed(this, 1_000)
            }
        })
    }

    private fun startCommandTimeout() {
        val id = sessionId
        commands.execute {
            for (i in 0 until 120) {
                if (torrent?.activeFile() != null) break
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@execute }
            }
            if (id == sessionId && torrent?.activeFile() == null && !stopped) {
                isStarting = false
                torrentState.value = torrentState.value.copy(phase = Phase.ERROR,
                    error = "Nenhum peer respondeu. O vídeo pode estar sem seeders.")
            }
        }
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
            Phase.METADATA -> "Buscando informações do link…"
            Phase.ERROR -> state.error ?: "Falha na transmissão"
            else -> if (streaming) "Tocando na TV · ${state.peers} peers" else "Preparando · ${state.peers} peers · ${speed(state.speedBytes)}"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(101, notification(text))
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
        discovery.close(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val STOP = "com.promolandia.tvmagnet.STOP"
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java)) }
    }
}
