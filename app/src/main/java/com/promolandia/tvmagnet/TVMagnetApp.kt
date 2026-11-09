package com.promolandia.tvmagnet

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import com.promolandia.tvmagnet.torrent.TemporaryBuffer
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.io.IOException
import java.nio.charset.StandardCharsets

/** Minimal process-wide crash-to-visible-diagnostic path. It never sends data to the network. */
class TVMagnetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AsyncSafety.initialize(this)
        CrashReporter.install(this)
    }
}

object AsyncSafety {
    @Volatile private var initialized = false
    fun initialize(context: Context): Boolean {
        if (initialized) return true
        return try {
            TemporaryBuffer(context).prepare()
            initialized = true
            true
        } catch (t: Throwable) {
            runCatching { CrashReporter.capture(context, RuntimeException("No temporary cache available", t)) }
            false
        }
    }
    inline fun <T> guarded(context: Context, action: () -> T): T? = try { action() } catch (t: Throwable) {
        CrashReporter.capture(context, t); null
    }
}

object CrashReporter {
    @Volatile private var path: File? = null
    fun install(context: Context) {
        val app = context.applicationContext
        path = File(app.filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            capture(app, error, thread)
            runCatching { previous?.uncaughtException(thread, error) }
        }
    }
    @Synchronized fun capture(context: Context, error: Throwable, thread: Thread = Thread.currentThread()) {
        try {
            val app = context.applicationContext
            val trace = StringWriter(24 * 1024).also { error.printStackTrace(PrintWriter(it)) }.toString()
            val info = buildMap {
                put("Package", app.packageName)
                runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()?.let { put("Version", "${it.versionName} (${it.longVersionCode})") }
                put("Android", "${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}")
                put("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("64-bit ABIs", Build.SUPPORTED_64_BIT_ABIS.joinToString())
                put("32-bit ABIs", Build.SUPPORTED_32_BIT_ABIS.joinToString())
                put("Thread", "${thread.name} (fatal=${thread == LooperUtil.mainThread})")
                put("Trace", trace)
            }
            val text = "TV Magnet - diagnóstico local\n" + info.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                .replace(Regex("magnet:\\?[^\\s<]+"), "magnet:[removido]")
            val file = path ?: File(app.filesDir, "last-crash.txt").also { path = it }
            AtomicFile(file).let { atomic ->
                var output: FileOutputStream? = null
                try {
                    output = atomic.startWrite()
                    output.write(text.toByteArray(StandardCharsets.UTF_8).let { bytes -> if (bytes.size <= 160 * 1024) bytes else bytes.copyOf(160 * 1024) })
                    output.fd.sync()
                    atomic.finishWrite(output)
                } catch (t: Throwable) {
                    atomic.failWrite(output)
                    Log.e("TVMagnet", "Unable to save local crash report", t)
                }
            }
        } catch (t: Throwable) { Log.e("TVMagnet", "Crash reporter failure", t) }
    }
    fun clear(context: Context) { runCatching { File(context.applicationContext.filesDir, "last-crash.txt").delete() } }
    fun read(context: Context): String? = try {
        val file = path ?: File(context.applicationContext.filesDir, "last-crash.txt")
        if (!file.isFile) null else file.readText().ifBlank { null }
    } catch (_: Throwable) { null }
}

/** Tiny breadcrumb file: the last steps taken before a process death, shown with the exit reason. */
object Breadcrumbs {
    private const val FILE = "last-steps.txt"
    @Synchronized fun mark(context: Context, step: String) {
        try {
            val f = File(context.applicationContext.filesDir, FILE)
            val old = if (f.isFile) f.readLines().takeLast(14) else emptyList()
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.writeText((old + "$stamp $step").joinToString("\n"))
        } catch (_: Throwable) {}
    }
    fun read(context: Context): String? = try {
        File(context.applicationContext.filesDir, FILE).takeIf { it.isFile }?.readText()?.ifBlank { null }
    } catch (_: Throwable) { null }
    fun clear(context: Context) { runCatching { File(context.applicationContext.filesDir, FILE).delete() } }
}

/** Android 11+: why the previous process died (native crash, ANR, killed...). Local only. */
object ExitInfoReader {
    private const val PREFS = "exit-info"
    fun report(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val app = context.applicationContext
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong("seen", 0L)
            val bad = setOf(
                android.app.ApplicationExitInfo.REASON_CRASH, android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
                android.app.ApplicationExitInfo.REASON_ANR, android.app.ApplicationExitInfo.REASON_SIGNALED,
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
            val item = am.getHistoricalProcessExitReasons(app.packageName, 0, 6)
                .filter { it.timestamp > seen && it.reason in bad }.maxByOrNull { it.timestamp } ?: return null
            prefs.edit().putLong("seen", item.timestamp).apply()
            val raw = try { item.traceInputStream?.use { it.readBytes() } } catch (_: Throwable) { null }
            val text = raw?.let { bytes ->
                if (item.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) printable(bytes)
                else String(bytes, Charsets.UTF_8)
            }?.take(6000)
            buildString {
                append("Motivo da última saída: ").append(reasonName(item.reason)).append('\n')
                append("Descrição: ").append(item.description ?: "-").append('\n')
                append("Status/sinal: ").append(item.status).append('\n')
                append("Importância: ").append(item.importance).append('\n')
                append("Horário: ").append(java.util.Date(item.timestamp)).append('\n')
                if (!text.isNullOrBlank()) append("Rastro:\n").append(text)
            }
        } catch (t: Throwable) { null }
    }
    /** Native tombstones are protobuf; keep readable runs (signal, abort message, frames). */
    internal fun printable(bytes: ByteArray): String {
        val out = StringBuilder(); val run = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 32..126) run.append(c.toChar())
            else { if (run.length >= 6) out.append(run).append('\n'); run.setLength(0) }
        }
        if (run.length >= 6) out.append(run)
        return out.toString()
    }
    private fun reasonName(r: Int) = when (r) {
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH (exceção Java)"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (falha na biblioteca nativa)"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR (app travou)"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (processo morto por sinal)"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (sem memória)"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        else -> r.toString()
    }
}

object LooperUtil { val mainThread get() = android.os.Looper.getMainLooper()?.thread }
