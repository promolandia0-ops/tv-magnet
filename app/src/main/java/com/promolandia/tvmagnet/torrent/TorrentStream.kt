package com.promolandia.tvmagnet.torrent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.promolandia.tvmagnet.*
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
import org.libtorrent4j.swig.remove_flags_t
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One magnet session. Loading the native library, building the session and every
 * libtorrent call run on the single "torrent-native-control" thread, never on main.
 * Storage is private cache only, sparse (no preallocation), and only a bounded
 * window of pieces around the playback position is requested.
 */
class TorrentStream(
    context: Context,
    private val onActive: (ActiveStream?) -> Unit,
    private val onError: (String) -> Unit,
    private val update: (StreamState) -> Unit
) : PieceReader {
    private val app = context.applicationContext
    private val buffer = TemporaryBuffer(app)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "torrent-native-control") }
    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val active = AtomicReference<ActiveStream?>(null)
    @Volatile private var current = StreamState()
    @Volatile private var handle: TorrentHandle? = null
    @Volatile private var selected: VideoFile? = null
    @Volatile private var firstPiece = 0
    @Volatile private var lastPiece = 0
    @Volatile private var pieceLength = 1L
    @Volatile private var fileStart = 0L
    @Volatile private var readOffset = 0L
    @Volatile private var finished = false
    @Volatile private var fatal = false
    private var session: SessionManager? = null
    private var listener: AlertListener? = null
    private var filesInfo: FileStorage? = null
    @Volatile private var infoHash: Sha1Hash? = null
    private var windowFrom = -1
    private var windowTo = -1
    private val startedAt = SystemClock.elapsedRealtime()

    override val error: String? get() = current.error
    override val fileLength: Long get() = selected?.size ?: 0L

    init {
        exec("Não há uma biblioteca nativa compatível com este celular.") {
            buffer.prepare(); buffer.ensureRoom()
            System.loadLibrary("torrent4j")
            val s = SessionManager()
            val l = object : AlertListener {
                override fun types(): IntArray? = null
                override fun alert(alert: Alert<*>) {
                    when (alert.type()) {
                        AlertType.ADD_TORRENT -> {
                            // Alert objects are only valid inside this callback: a TorrentHandle taken from an
                            // alert and used later (v1.0.1 did) points at freed native memory -> SIGSEGV or
                            // "invalid torrent handle". Copy the info-hash now, re-resolve the handle via find().
                            val added = alert as AddTorrentAlert
                            val failed = added.error().value != 0
                            val hex = try { added.handle()?.infoHash()?.toHex() } catch (_: Throwable) { null }
                            exec("Não consegui abrir esse magnet.") {
                                if (failed || hex == null) fail("Não consegui abrir esse magnet.")
                                else { infoHash = Sha1Hash.parseHex(hex); liveHandle() ?: fail("Não consegui abrir esse magnet.") }
                            }
                        }
                        AlertType.METADATA_RECEIVED -> exec("Erro ao ler os arquivos do magnet.") { prepareFiles() }
                        AlertType.PIECE_FINISHED, AlertType.TORRENT_FINISHED -> signal()
                        AlertType.TORRENT_ERROR, AlertType.FILE_ERROR -> exec("") {
                            fail("O torrent foi interrompido. Verifique os seeds e o espaço livre do celular.")
                        }
                        else -> Unit
                    }
                }
            }
            session = s; listener = l
            s.addListener(l)
            // The library's default start() sets the alert mask and DHT bootstrap nodes;
            // a bare SettingsPack would leave alerts (and metadata) unreliable.
            Breadcrumbs.mark(app, "libtorrent: iniciando sessão")
            // Plain pread/pwrite storage instead of libtorrent 2.x's default memory-mapped files:
            // mmap over a sparse cache file raises SIGBUS (an uncatchable native kill) as soon as
            // the file is truncated/removed or the disk fills up.
            val params = SessionParams().also { it.setPosixDiskIO() }
            s.start(params)
            runCatching { s.applySettings(s.settings().uploadRateLimit(8 * 1024)) }
            Breadcrumbs.mark(app, "libtorrent: sessão iniciada")
            post(current.copy(phase = Phase.METADATA))
        }
    }

    fun start(magnet: String) {
        if (!started.compareAndSet(false, true)) return
        exec("Magnet inválido.") {
            post(current.copy(phase = Phase.METADATA, title = titleOf(magnet)))
            // Default storage is sparse: files grow as pieces arrive, never preallocated.
            // Only the metadata is fetched here; files are set to "ignore" once it arrives.
            Breadcrumbs.mark(app, "Buscar: adicionando magnet")
            session!!.download(magnet, buffer.root, torrent_flags_t())
        }
    }

    /** Metadata arrived: list every file (videos first), download nothing yet. */
    private fun prepareFiles() {
        val h = liveHandle() ?: return
        val storage = h.torrentFile()?.files() ?: return
        if (stopped.get() || current.files.isNotEmpty()) return
        // Nothing meaningful may download before the user chooses a video. Keeping the peers interested
        // (instead of ignoring every file) lets them stay connected; the 1 B/s cap makes the transfer negligible.
        h.setDownloadLimit(1)
        val all = (0 until storage.numFiles()).map { i ->
            val name = storage.filePath(i).replace('\\', '/').removePrefix("/")
            VideoFile(i, name.substringAfterLast('/'), name, storage.fileSize(i))
        }.filter { it.size > 0 && !("/" + it.path).contains("/.pad/") }
        val videos = all.filter { it.isVideo }.sortedByDescending { it.size }
        if (videos.isEmpty()) { fail("Não encontrei nenhum vídeo nesse magnet."); return }
        val listed = (videos + all.filter { !it.isVideo }.sortedByDescending { it.size }).take(300)
        filesInfo = storage
        Breadcrumbs.mark(app, "Buscar: ${listed.size} arquivos listados")
        post(current.copy(phase = Phase.LISTED, files = listed, suggestedIndex = videos.first().index, selectedIndex = -1,
            title = (h.torrentFile()?.name() ?: "").ifBlank { current.title }))
    }

    fun select(fileIndex: Int) = exec("Erro ao preparar o streaming.") { selectInternal(fileIndex) }

    private fun selectInternal(fileIndex: Int) {
        val file = current.files.firstOrNull { it.index == fileIndex && it.isVideo } ?: return
        Breadcrumbs.mark(app, "Transmitir: preparando arquivo $fileIndex")
        val fs = filesInfo ?: return
        val h = liveHandle() ?: return
        active.set(null); onActive(null)
        selected = file; finished = false; readOffset = 0L; windowFrom = -1; windowTo = -1
        pieceLength = fs.pieceLength().toLong().coerceAtLeast(1)
        fileStart = fs.fileOffset(file.index)
        firstPiece = (fileStart / pieceLength).toInt()
        lastPiece = ((fileStart + file.size - 1) / pieceLength).toInt().coerceAtLeast(firstPiece)
        // One scalar JNI call per file: the vector variant (prioritizeFiles) segfaults in
        // libtorrent4j 2.1.0-39 (reproduced as SIGSEGV in torrent_handle.prioritize_files_ex).
        for (i in 0 until fs.numFiles()) h.filePriority(i, if (i == file.index) Priority.DEFAULT else Priority.IGNORE)
        // Everything outside the window is ignored; the window moves with playback.
        for (i in firstPiece..lastPiece) h.piecePriority(i, Priority.IGNORE)
        for (i in (lastPiece - 2).coerceAtLeast(firstPiece)..lastPiece) h.piecePriority(i, Priority.TOP_PRIORITY)
        post(current.copy(phase = Phase.BUFFERING, title = file.name, selectedIndex = fileIndex, downloadedBytes = 0, totalBytes = file.size, clientConnected = false))
        moveWindow(firstPiece)
        h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        h.setDownloadLimit(0)
        h.resume(); h.forceReannounce(0, -1)
        active.set(ActiveStream(file.name, file.mimeType, cacheFile(file), this).also(onActive))
        signal()
    }

    /** A handle that is valid right now, always obtained from the session (never from an alert). */
    private fun liveHandle(): TorrentHandle? {
        handle?.takeIf { it.isValid() }?.let { return it }
        val hash = infoHash ?: return null
        val found = session?.find(hash)?.takeIf { it.isValid() }
        handle = found
        if (found == null) Breadcrumbs.mark(app, "libtorrent: handle inválido")
        return found
    }

    private fun cacheFile(file: VideoFile): File {
        val root = buffer.root.canonicalFile
        val target = File(root, file.path).canonicalFile
        check(target.path.startsWith(root.path + File.separator)) { "Caminho do torrent inválido." }
        target.parentFile?.mkdirs()
        return target
    }

    /** Requests [from, from + window) with urgent deadlines at the front; clears the rest. */
    private fun moveWindow(from: Int) {
        val h = handle ?: return
        val span = span()
        val start = from.coerceIn(firstPiece, lastPiece)
        val end = (start + span - 1).coerceAtMost(lastPiece)
        val lo = if (windowFrom < 0) start else minOf(windowFrom, start)
        val hi = if (windowTo < 0) end else maxOf(windowTo, end)
        for (i in lo..hi) {
            val edge = i >= lastPiece - 2 || i < firstPiece + 2
            val keep = i in start..end || edge
            // Edges (container header/index) first; the window follows in sequential order.
            h.piecePriority(i, if (edge) Priority.TOP_PRIORITY else if (keep) Priority.DEFAULT else Priority.IGNORE)
        }
        windowFrom = start; windowTo = end
    }

    private fun span() = (WINDOW_BYTES / pieceLength).toInt().coerceIn(8, 1024)
    private fun ensureWindow(piece: Int) {
        if (piece < windowFrom || piece > windowFrom + span() / 2) moveWindow(piece - 1)
    }

    private fun pieceAt(offset: Long) = ((fileStart + offset) / pieceLength).toInt()

    override fun isAvailable(offset: Long, length: Int): Boolean {
        val h = handle ?: return false
        if (fileLength == 0L || length <= 0) return false
        readOffset = offset
        val a = pieceAt(offset); val b = pieceAt((offset + length - 1).coerceAtMost(fileLength - 1))
        if (a < firstPiece || b > lastPiece) return false
        return (a..b).all(h::havePiece)
    }

    override fun prioritize(offset: Long, length: Int) {
        if (offset < 0 || offset >= fileLength || isStopped()) return
        readOffset = offset
        exec("") { if (selected != null) ensureWindow(pieceAt(readOffset)) }
    }

    override fun complete() = finished
    override fun signal() { synchronized(lock) { lock.notifyAll() } }
    override fun throwIfStopped() { if (isStopped()) throw InterruptedException("Stream encerrado") }
    override fun isStopped() = stopped.get() || fatal

    /** Called from the service ticker on main: only queues work onto the native thread. */
    fun updateSnapshot() = exec("O streaming parou inesperadamente.") { sample() }

    private fun sample() {
        val h = handle ?: run { timeoutCheck(); return }
        val f = selected ?: run {
            if (current.phase == Phase.METADATA || current.phase == Phase.LISTED) post(current.copy(peers = h.status().numPeers()))
            timeoutCheck(); return
        }
        buffer.ensureRoom(32L * 1024L * 1024L)
        // Slide the window with playback so the next pieces are requested early.
        ensureWindow(pieceAt(readOffset))
        val status = h.status()
        val progress = (h.fileProgress().getOrNull(f.index) ?: 0L).coerceIn(0L, f.size)
        finished = (firstPiece..lastPiece).all(h::havePiece)
        var next = current.copy(downloadedBytes = progress, totalBytes = f.size,
            speedBytes = status.downloadPayloadRate().toLong(), peers = status.numPeers(),
            clientConnected = com.promolandia.tvmagnet.server.StreamServer.clientConnected)
        if (finished && next.phase == Phase.BUFFERING) next = next.copy(phase = Phase.READY)
        post(next)
    }

    private fun timeoutCheck() {
        if (current.phase == Phase.METADATA && SystemClock.elapsedRealtime() - startedAt > 3 * 60_000) fail("Não consegui ler o magnet em 3 minutos. Talvez não haja peers.")
    }

    private fun post(state: StreamState) { current = state; main.post { if (!stopped.get()) update(current) } }
    private fun fail(message: String) {
        fatal = true
        post(current.copy(phase = Phase.ERROR, error = message))
        onError(message); signal(); active.set(null); onActive(null)
    }

    private fun friendly(t: Throwable, fallback: String) = when (t) {
        is OutOfMemoryError -> "Memória insuficiente neste celular. Feche outros aplicativos e tente novamente."
        is UnsatisfiedLinkError -> "Este aparelho não tem uma biblioteca nativa compatível. O diagnóstico foi salvo."
        is BufferException -> t.message ?: fallback
        else -> if (fallback.isNotEmpty()) fallback else "Falha ao reproduzir: ${t.message?.take(200) ?: t.javaClass.simpleName}"
    }

    /** Runs on the native thread; every Throwable (including JNI link errors) is reported, never fatal. */
    private fun exec(message: String, action: () -> Unit) {
        if (executor.isShutdown) return
        try {
            executor.execute {
                if (stopped.get()) return@execute
                try { action() } catch (t: Throwable) {
                    CrashReporter.capture(app, t)
                    if (message.isNotEmpty() || t is BufferException) fail(friendly(t, message))
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    fun activeFile() = active.get()

    /** True when [offset, offset+length) of the chosen file is on disk. No side effects on the window. */
    fun hasRange(offset: Long, length: Int): Boolean {
        val h = handle ?: return false
        val f = selected ?: return false
        if (length <= 0 || f.size == 0L || stopped.get()) return false
        val a = ((fileStart + offset) / pieceLength).toInt()
        val b = ((fileStart + (offset + length - 1).coerceAtMost(f.size - 1)) / pieceLength).toInt()
        return a >= firstPiece && b <= lastPiece && (a..b).all(h::havePiece)
    }

    /** Waits (off the main thread) for stop() to finish releasing the native session. */
    fun awaitStopped(millis: Long) { runCatching { executor.awaitTermination(millis, java.util.concurrent.TimeUnit.MILLISECONDS) } }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        signal(); active.set(null); onActive(null)
        try {
            executor.execute {
                try { handle?.let { session?.remove(it, remove_flags_t.from_int(1)) } } catch (_: Throwable) {}
                try { listener?.let { session?.removeListener(it) }; session?.stop() } catch (_: Throwable) {}
                session = null; handle = null; filesInfo = null
                buffer.clear()
                executor.shutdown()
            }
        } catch (_: Throwable) { buffer.clear() }
    }

    companion object {
        private const val WINDOW_BYTES = 96L * 1024L * 1024L
        private fun titleOf(uri: String) = Regex("""(?:^|[?&])dn=([^&]*)""").find(uri)?.groupValues?.get(1)
            ?.replace('+', ' ')?.let { android.net.Uri.decode(it) } ?: "Vídeo do magnet"
    }
}
