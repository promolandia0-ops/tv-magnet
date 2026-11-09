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

object LooperUtil { val mainThread get() = android.os.Looper.getMainLooper()?.thread }
