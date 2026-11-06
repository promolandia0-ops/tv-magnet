package com.promolandia.tvmagnet.torrent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.promolandia.tvmagnet.*
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
import org.libtorrent4j.swig.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One in-memory magnet session. Storage is exclusively temporary app-private cache. */
class TorrentStream(
    private val context: Context,
    private val onActive: (ActiveStream?) -> Unit,
    private val onError: (String) -> Unit,
    private val update: (StreamState) -> Unit
) : PieceReader {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "torrent-control") }
    private val lock = Object()
    private val session: SessionManager
    private val listener: AlertListener
    private var handle: TorrentHandle? = null
    private var filesInfo: FileStorage? = null
    private var selected: VideoFile? = null
    private var firstPiece = 0
    private var lastPiece = 0
    private var pieceLength = 1
    private var priorityOffset = -1L
    private var isStopped = false
    private var currentState = StreamState()
    private var active = AtomicReference<ActiveStream?>(null)

    private val handler = Handler(Looper.getMainLooper())
    override val error: String? get() = currentState.error

    init {
        System.loadLibrary("torrent4j")
        val cache = File(context.cacheDir, "stream-buffer")
        // Startup cleanup also handles Android killing the service process in background.
        cache.deleteRecursively()
        cache.mkdirs()
        handler.post { update(currentState.copy(phase = Phase.METADATA)) }
        session = SessionManager()
        val settings = SettingsPack().uploadRateLimit(8 * 1024)
        session.start(SessionParams(settings))
        listener = object: AlertListener {
            override fun types(): IntArray? = null
            override fun alert(alert: Alert<*>) {
            when (alert.type()) {
                AlertType.ADD_TORRENT -> executor.execute {
                    val added = alert as AddTorrentAlert
                    val h = added.handle()?.takeIf { it.isValid() }
                    if (added.error().value != 0 || h == null) fail("Não consegui abrir esse magnet.")
                    else handle = h
                }
                AlertType.METADATA_RECEIVED -> executor.execute { prepareFiles() }
                AlertType.PIECE_FINISHED, AlertType.TORRENT_FINISHED -> signal()
                AlertType.TORRENT_ERROR -> fail("O torrent foi interrompido. Tente novamente.")
                else -> Unit
            }
            }
        }
        session.addListener(listener)
    }

    fun start(magnet: String) {
        executor.execute {
            try {
                currentState = currentState.copy(phase = Phase.METADATA, title = MagnetLink.from(magnet)?.let { titleParam(it) } ?: "")
                post()
                session.download(magnet, File(context.cacheDir, "stream-buffer"), torrent_flags_t())
            } catch (e: Exception) { fail(e.message ?: "Magnet inválido.") }
        }
    }

    private fun titleParam(uri: String) = UriParam.name(uri) ?: "Vídeo do magnet"

    private fun prepareFiles() {
        try {
            val h = handle ?: return
            val storage = h.torrentFile()?.files() ?: return
            if (isStopped) return
            val video = (0 until storage.numFiles()).map { i ->
                val name = storeName(storage.filePath(i))
                VideoFile(i, name.substringAfterLast('/'), name, storage.fileSize(i))
            }.filter(::isVideo)
            if (video.isEmpty()) { fail("Não encontrei nenhum vídeo nesse magnet."); return }
            filesInfo = storage
            selected = video.maxBy { it.size }
            currentState = currentState.copy(phase = Phase.BUFFERING, title = selected?.name ?: currentState.title, files = video, selectedIndex = selected?.index ?: -1)
            post()
            select(selected!!.index)
        } catch (e: Exception) { fail(e.message ?: "Erro ao ler os arquivos do magnet.") }
    }

    fun select(fileIndex: Int) {
        executor.execute {
            try {
                val file = currentState.files.firstOrNull { it.index == fileIndex } ?: return@execute
                val h = handle ?: return@execute
                active.set(null)
                selected = file
                currentState = currentState.copy(phase = Phase.BUFFERING, title = file.name, selectedIndex = fileIndex, downloadedBytes = 0, totalBytes = file.size, clientConnected = false)
                post()
                val fs = filesInfo ?: return@execute
                pieceLength = fs.pieceLength()
                firstPiece = (fs.fileOffset(file.index) / pieceLength).toInt()
                lastPiece = ((fs.fileOffset(file.index) + file.size - 1) / pieceLength).toInt().coerceAtLeast(firstPiece)
                h.prioritizeFiles(Array(fs.numFiles()) { i -> if (i == file.index) Priority.DEFAULT else Priority.IGNORE })
                // Automatic queue management is disabled through the session.
                h.setSequentialRange(firstPiece, lastPiece)
                h.resume()
                h.setSequentialRange(firstPiece, lastPiece)
                // Tail pieces are prioritized for MP4/moov metadata, index recovery, and seeking.
                // Promote first/tail pieces before resetting the native default priority;
                // that avoids thousands of ignored-early callbacks for large videos.
                for (i in firstPiece until (firstPiece + 4).coerceAtMost(lastPiece + 1)) h.piecePriority(i, Priority.TOP_PRIORITY)
                for (i in (lastPiece - 2).coerceAtLeast(firstPiece)..lastPiece) h.piecePriority(i, Priority.TOP_PRIORITY)
                // Clear library defaults one piece at a time: no allocation proportional to video size.
                for (i in firstPiece..lastPiece) {
                    if (i >= firstPiece + 4 && i < lastPiece - 2) h.piecePriority(i, Priority.DEFAULT)
                }
                for (i in firstPiece until (firstPiece + 4).coerceAtMost(lastPiece + 1)) h.setPieceDeadline(i, (i - firstPiece) * 100)
                h.forceReannounce(0, -1) // obtains peers from omitted tracker after zero seconds
                active.set(ActiveStream(file.name, file.mimeType, resolveCacheFile(fs.filePath(file.index)), this).also(onActive))
                signal()
            } catch (e: Exception) { fail(e.message ?: "Erro ao preparar o streaming.") }
        }
    }

    private fun resolveCacheFile(path: String): File {
        val safe = path.replace('\\', '/').removePrefix("/")
        val root = File(context.cacheDir, "stream-buffer").canonicalFile
        val target = File(root, safe).canonicalFile
        check(target.path.startsWith(root.path + File.separator)) { "Invalid torrent path" }
        target.parentFile?.mkdirs()
        // If metadata arrives before native allocation, an empty file is harmless as
        // the stream blocks at have_piece until verified data is on disk.
        return target
    }

    private fun pieceAt(offset: Long): Int {
        val fs = filesInfo ?: return -1
        val f = selected ?: return -1
        return ((fs.fileOffset(f.index) + offset) / pieceLength).toInt()
    }

    override val fileLength: Long get() = selected?.size ?: 0L
    override fun isAvailable(offset: Long, length: Int): Boolean {
        val h = handle ?: return false
        if (fileLength == 0L) return false
        val a = pieceAt(offset); val b = pieceAt((offset + length - 1).coerceAtMost(fileLength - 1))
        if (a < firstPiece || b > lastPiece) return false
        return (a..b).all(h::havePiece)
    }

    override fun prioritize(offset: Long, length: Int) {
        val h = handle ?: return
        val a = (pieceAt(offset) - 1).coerceAtLeast(firstPiece)
        val b = (pieceAt(offset + length - 1) + 12).coerceAtMost(lastPiece)
        if (System.currentTimeMillis() - priorityOffset < 1_000) return
        priorityOffset = System.currentTimeMillis()
        for (i in a..b) h.piecePriority(i, Priority.TOP_PRIORITY)
        h.setSequentialRange((pieceAt(offset) - 1).coerceAtLeast(firstPiece), lastPiece)
        // TOP_PRIORITY is reserved for the tail; other playback pieces must not
        // accidentally block the sequential range behind the current read point.
        for (i in a..(a + 2).coerceAtMost(lastPiece)) h.setPieceDeadline(i, 100 * (i - a))
    }

    override fun complete(): Boolean = handle?.let { h -> (firstPiece..lastPiece).all(h::havePiece) } == true
    override fun signal() { synchronized(lock) { lock.notifyAll() } }
    override fun throwIfStopped() { if (isStopped) throw InterruptedException("Stream stopped") }
    override fun isStopped() = isStopped

    fun tick(): StreamState {
        if (isStopped) return currentState
        try {
            val f = selected ?: return currentState
            val h = handle ?: return currentState
            val progress = h.fileProgress().getOrNull(f.index) ?: 0L
            val status = h.status()
            currentState = currentState.copy(downloadedBytes = progress, totalBytes = f.size,
                speedBytes = status.downloadPayloadRate().toLong(), peers = status.numPeers(),
                clientConnected = com.promolandia.tvmagnet.server.StreamServer.clientConnected)
        } catch (_: Throwable) {}
        return currentState
    }

    fun post() { val snapshot = tick(); handler.post { if (!isStopped) update(snapshot) } }

    private fun fail(message: String) {
        currentState = currentState.copy(phase = Phase.ERROR, error = message)
        onError(message)
        post()
    }

    fun activeFile() = active.get()
    fun notifyComplete() { if (currentState.phase != Phase.READY && currentState.phase != Phase.STREAMING) { currentState = currentState.copy(phase = Phase.READY); post() } }

    fun stop() {
        if (isStopped) return
        isStopped = true
        currentState = currentState.copy(phase = Phase.STOPPING)
        signal()
        executor.execute {
            onActive(null)
            try { handle?.let { session.remove(it, remove_flags_t.from_int(1)) } } catch (_: Throwable) {}
            try { session.removeListener(listener); session.stop() } catch (_: Throwable) {}
            try { File(context.cacheDir, "stream-buffer").deleteRecursively() } catch (_: Throwable) {}
            active.set(null)
            handler.post { currentState = StreamState(); update(currentState) }
            executor.shutdown()
        }
    }

    companion object {
        private fun isVideo(f: VideoFile) = f.mimeType.startsWith("video/")
        private fun storeName(path: String) = path.replace('\\', '/').removePrefix("/")
    }
}

private object UriParam {
    fun name(uri: String) = Regex("""(?:^|[?&])dn=([^&]*)""").find(uri)?.groupValues?.get(1)?.replace('+', ' ')?.let { android.net.Uri.decode(it) }
}
