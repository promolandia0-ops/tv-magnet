package com.promolandia.tvmagnet

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.text.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.promolandia.tvmagnet.dlna.TvDevice
import com.promolandia.tvmagnet.service.StreamService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.Locale
import kotlin.math.roundToInt

/** A deliberately small screen: paste a magnet, choose a TV, Play. */
class MainActivity : AppCompatActivity() {
    private lateinit var magnet: EditText
    private lateinit var tvSpinner: Spinner
    private lateinit var manualIp: EditText
    private lateinit var searchButton: Button
    private lateinit var castButton: Button
    private lateinit var localButton: Button
    private lateinit var filesLabel: TextView
    private lateinit var filesGroup: RadioGroup
    private lateinit var pauseButton: Button
    private lateinit var status: TextView
    private lateinit var buffer: ProgressBar
    private lateinit var seek: SeekBar
    private lateinit var mediaPosition: TextView

    private var service: StreamService? = null
    private var bound = false
    private var tvList = listOf<TvDevice>()
    private var chosenIndex = -1
    private var preparing = false
    private var updating = false
    private var seeking = false
    private var remotePlaying = false
    private var remotePlayingRequested = false
    private var lastError: String? = null
    private var generatedStatus = ""
    private var uiJob: Job? = null
    private var currentFilesKey = ""

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder !is StreamService.LocalBinder) return
            bound = true
            val app = binder.service
            service = app
            lifecycleScope.launch { app.state.collect { render(it) } }
            lifecycleScope.launch { app.discovery.devices.collect { renderTvs(it) } }
            app.discovery.scanFresh()
            if (app.streaming) {
                remotePlaying = true
            }
            refreshButtons()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            root.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(-1, -2)
        }
        scroll.addView(content)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, i ->
            v.setPadding(0, i.getInsets(WindowInsetsCompat.Type.systemBars()).top, 0,
                i.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            i
        }
        val name = text("TV Magnet", 30, TEXT); name.typeface = Typeface.DEFAULT_BOLD
        name.gravity = Gravity.CENTER
        content.addView(name)
        content.addView(text("1. Busque o magnet  2. Escolha o vídeo  3. Transmita pra TV", 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(14)) })
        content.addView(label("Link magnet"))
        magnet = input("magnet:?xt=urn:btih…")
        content.addView(magnet)
        searchButton = button("Buscar", accent = true) { searchAction() }
        content.addView(searchButton)
        status = text("Cole o magnet e toque em Buscar.", 14, MUTED)
        status.setPadding(0, dp(12), 0, dp(5))
        content.addView(status)
        buffer = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; layoutParams = LinearLayout.LayoutParams(-1, dp(8))
        }
        content.addView(buffer)
        filesLabel = label("Arquivos do magnet (vídeos primeiro)").apply { visibility = View.GONE }
        content.addView(filesLabel)
        filesGroup = RadioGroup(this).apply { visibility = View.GONE }
        content.addView(filesGroup)
        content.addView(label("Televisão"))
        tvSpinner = spinner().also(content::addView)
        tvSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!updating) tvList.getOrNull(position)?.let { service?.selectTv(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        manualIp = input("Não achou? Digite o IP da TV")
        manualIp.filters = arrayOf(InputFilter.LengthFilter(15))
        manualIp.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        content.addView(manualIp)
        content.addView(button("Encontrar TV pelo IP", small = true) { manualSearch() })
        castButton = button("Transmitir pra TV", accent = true) { castAction() }
        content.addView(castButton)
        localButton = button("Tocar no celular", small = true) { playLocally() }
        content.addView(localButton)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(controls, LinearLayout.LayoutParams(-1, -2))
        pauseButton = button("Pausar", small = true) { pauseAction() }
        controls.addView(pauseButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        controls.addView(button("Parar e apagar", small = true) { stopEverything() }, LinearLayout.LayoutParams(0, -2, 1f))

        content.addView(label("Posição na TV"))
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) seeking = true }
                override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(s: SeekBar) {
                    seeking = false
                    service?.remoteDuration?.takeIf { it > 0 }?.let { duration ->
                        service?.seek((s.progress.toDouble() / 1000 * duration).roundToInt().toLong()) { msg -> if (msg != null) runOnUiThread { toast(msg) } }
                    }
                }
            })
        }
        content.addView(seek)
        mediaPosition = text("A posição aparece depois de começar.", 13, MUTED)
        mediaPosition.gravity = Gravity.CENTER
        content.addView(mediaPosition)
        content.addView(label("Volume da TV"))
        val volume = SeekBar(this).apply { max = 100; progress = 50 }
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) { service?.volume(s.progress) { _, err -> if (err != null) runOnUiThread { toast(err) } } }
        })
        content.addView(volume)
        content.addView(text("Mesmo Wi-Fi no celular e na TV.\nO cache temporário é apagado ao parar ou fechar o app.", 12, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(17), 0, dp(10)) })
        applyIntent(intent)
        lifecycleScope.launch { while (isActive) { delay(1_000); updatePosition() } }.also { uiJob = it }
        // Ask for notifications first, but never wait for the answer: the foreground
        // service works (with a hidden notification) even when it is denied.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 33)
        startOrBind(start = true)
        showLastCrash()
    }

    /** Step 1: metadata only. */
    private fun searchAction() {
        if (!bound) return toast("Preparando o player… Tente novamente.")
        val app = service ?: return toast("Preparando o aplicativo…")
        val link = acceptMagnet(magnet.text)
        if (link == null) { status.text = "Cole um link magnet válido."; return }
        if (app.isStarting) return toast("Buscando… Aguarde os peers.")
        hideKeyboard()
        chosenIndex = -1; currentFilesKey = ""; remotePlaying = false; remotePlayingRequested = false
        filesGroup.removeAllViews(); filesGroup.visibility = View.GONE; filesLabel.visibility = View.GONE
        app.search(link)?.let { message -> toast(message) } ?: run { status.text = "Buscando... (peers: 0)" }
        refreshButtons()
    }

    /** Step 2: send the chosen video to the TV. */
    private fun castAction() {
        val app = service ?: return toast("Preparando o aplicativo…")
        if (chosenIndex < 0) return toast("Escolha um vídeo da lista.")
        val tv = tvList.getOrNull(tvSpinner.selectedItemPosition) ?: return toast("Escolha uma TV.")
        if (app.currentlyPlaying || remotePlaying) return pauseAction()
        preparing = true; remotePlayingRequested = true
        status.text = "Preparando o vídeo para a TV…"
        refreshButtons()
        app.cast(tv, chosenIndex) { error -> runOnUiThread {
            preparing = false; remotePlayingRequested = false
            if (error != null) status.text = error
            refreshButtons()
        } }
    }

    private fun pauseAction() {
        val app = service ?: return
        // The service serializes this with all other TV actions.
        val wasPlaying = app.currentlyPlaying || remotePlaying
        remotePlayingRequested = true
        app.pause { error -> runOnUiThread {
            remotePlayingRequested = false
            if (error != null) status.text = error
            else {
                remotePlaying = !wasPlaying
                pauseButton.text = if (remotePlaying) "Pausar" else "Continuar"
            }
            refreshButtons()
        } }
    }

    private fun stopEverything() {
        hideKeyboard()
        val app = service ?: return
        app.stop()
        remotePlaying = false; remotePlayingRequested = false
        lifecycleScope.launch {
            delay(450)
            stopService(Intent(this@MainActivity, StreamService::class.java))
            toast("Buffer apagado.")
            finishAndRemoveTask()
        }
    }

    private fun playLocally() {
        val app = service ?: return
        if (chosenIndex < 0) return toast("Escolha um vídeo da lista.")
        preparing = true; refreshButtons()
        status.text = "Preparando o vídeo no celular…"
        app.prepareLocal(chosenIndex) { url, error -> runOnUiThread {
            preparing = false; refreshButtons()
            if (url == null) { status.text = error ?: "Falha ao preparar o vídeo."; return@runOnUiThread }
            try { startActivity(Intent(this, PlayerActivity::class.java).putExtra("url", url).putExtra("title", app.state.value.title)) }
            catch (t: Throwable) { CrashReporter.capture(this, t); status.text = "Não consegui abrir o player." }
        } }
    }

    private fun manualSearch() {
        val ip = manualIp.text.toString()
        status.text = "Procurando a TV no IP informado…"
        service?.manualTv(ip) { tv, error -> runOnUiThread { status.text = if (tv != null) "TV encontrada: $tv" else error } }
    }

    private fun renderTvs(tvs: List<TvDevice>) {
        val old = tvList.getOrNull(tvSpinner.selectedItemPosition)?.key ?: service?.firstTv()?.key
        tvList = tvs
        updating = true
        tvSpinner.adapter = StringAdapter(this, if (tvs.isEmpty()) listOf("Procurando TVs…") else tvs.map { it.toString() })
        val index = tvs.indexOfFirst { it.key == old }
        if (index >= 0) tvSpinner.setSelection(index) else if (tvs.isNotEmpty()) service?.selectTv(tvs.first())
        updating = false
    }

    private fun render(state: StreamState) {
        val percent = ((state.downloadedBytes.toDouble() / state.totalBytes.coerceAtLeast(1)) * 100).roundToInt().coerceIn(0, 100)
        buffer.isIndeterminate = state.phase == Phase.METADATA
        if (!buffer.isIndeterminate) buffer.progress = if (state.phase == Phase.LISTED) 0 else percent
        generatedStatus = when (state.phase) {
            Phase.IDLE -> if (service?.streaming == true) "Transmitindo para a TV." else "Cole o magnet e toque em Buscar."
            Phase.METADATA -> "Buscando... (peers: ${state.peers})"
            Phase.LISTED -> "${state.files.size} arquivos. Escolha o vídeo e transmita. (peers: ${state.peers})"
            Phase.BUFFERING -> "Buffer $percent% · ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}\n${formatSpeed(state.speedBytes)} · ${state.peers} peers"
            Phase.READY, Phase.STREAMING -> "Pronto: ${state.title}"
            Phase.STOPPING -> "Apagando o cache…"
            Phase.ERROR -> state.error ?: "Não foi possível abrir o vídeo."
        }
        if (!remotePlaying && (!remotePlayingRequested || state.phase == Phase.BUFFERING || state.phase == Phase.ERROR)) status.text = generatedStatus
        val key = state.files.joinToString(",") { it.index.toString() }
        if (key != currentFilesKey) {
            currentFilesKey = key
            filesGroup.removeAllViews()
            chosenIndex = -1
            val show = state.files.isNotEmpty()
            filesLabel.visibility = if (show) View.VISIBLE else View.GONE
            filesGroup.visibility = if (show) View.VISIBLE else View.GONE
            updating = true
            filesGroup.setOnCheckedChangeListener(null)
            for (f in state.files) {
                filesGroup.addView(RadioButton(this).apply {
                    id = View.generateViewId(); tag = f.index
                    text = if (f.isVideo) f.toString() else "${f}  (não é vídeo)"
                    setTextColor(if (f.isVideo) TEXT else MUTED); isEnabled = f.isVideo; textSize = 14f
                    buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                    isChecked = f.index == state.suggestedIndex
                })
            }
            if (state.suggestedIndex >= 0) chosenIndex = state.suggestedIndex
            filesGroup.setOnCheckedChangeListener { group, checkedId ->
                if (!updating) chosenIndex = (group.findViewById<View>(checkedId)?.tag as? Int) ?: -1
                refreshButtons()
            }
            updating = false
        }
        if (state.phase == Phase.ERROR) { remotePlayingRequested = false; preparing = false }
        refreshButtons()
    }

    private fun updatePosition() {
        val app = service ?: return
        if (app.streaming) {
            remotePlaying = true
            remotePlayingRequested = false
        } else if (!app.currentlyPlaying && !remotePlayingRequested) remotePlaying = false
        val duration = app.remoteDuration
        mediaPosition.text = if (duration > 0) "${clock(app.remotePosition)} / ${clock(duration)}" else "A posição aparece depois de começar."
        if (duration > 0 && !seeking) seek.progress = ((app.remotePosition.toDouble() / duration) * 1000).toInt().coerceIn(0, 1000)
    }
    private fun refreshButtons() {
        val phase = service?.state?.value?.phase ?: Phase.IDLE
        val searching = phase == Phase.METADATA
        val listed = (service?.state?.value?.files?.isNotEmpty() == true) && chosenIndex >= 0
        searchButton.isEnabled = !searching && !preparing
        searchButton.text = if (searching) "Buscando…" else "Buscar"
        castButton.text = if (remotePlayingRequested) "Aguarde…" else if (remotePlaying) "Pausar na TV" else "Transmitir pra TV"
        castButton.isEnabled = !searching && (listed || remotePlaying) && !remotePlayingRequested
        localButton.isEnabled = !searching && listed && !preparing
        pauseButton.text = if (remotePlaying) "Pausar" else "Continuar"
        pauseButton.isEnabled = !remotePlayingRequested && (remotePlaying || service?.remoteDuration ?: 0L > 0L)
        listOf(searchButton, castButton, localButton, pauseButton).forEach { it.alpha = if (it.isEnabled) 1f else 0.4f }
    }
    private fun applyIntent(intent: Intent) { sharedMagnet(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(), intent.data)?.let(magnet::setText) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); applyIntent(intent) }
    override fun onResume() {
        super.onResume()
        if (!magnet.text.isBlank()) return
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString()
            sharedMagnet(text, null)?.let(magnet::setText)
        } catch (_: Exception) {
            // Some Samsung builds hide clipboard access; paste remains available.
        }
    }
    override fun onStart() { super.onStart(); if (!bound) startOrBind(start = false) }
    override fun onStop() { if (bound) { runCatching { unbindService(connection) }; bound = false }; super.onStop() }
    override fun onDestroy() { uiJob?.cancel(); super.onDestroy() }
    private fun startOrBind(start: Boolean) {
        if (start) StreamService.start(this)
        bound = try { bindService(Intent(this, StreamService::class.java), connection, BIND_AUTO_CREATE) } catch (t: Throwable) { CrashReporter.capture(this, t); false }
    }

    /** Shows the stack trace saved by the global crash handler during the previous run. */
    private fun showLastCrash() {
        val exit = ExitInfoReader.report(this)
        val steps = Breadcrumbs.read(this)
        val crash = CrashReporter.read(this)
        if (exit == null && crash == null) return
        val report = listOfNotNull(exit, steps?.let { "Últimos passos:\n$it" }, crash).joinToString("\n\n")
        val body = TextView(this).apply {
            text = report; textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("O app fechou na última vez")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Copiar") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("TV Magnet", report))
                CrashReporter.clear(this); Breadcrumbs.clear(this)
            }
            .setNegativeButton("Fechar") { _, _ -> CrashReporter.clear(this); Breadcrumbs.clear(this) }
            .show()
    }
    private fun label(content: String) = text(content, 13, MUTED).apply { setPadding(0, dp(9), 0, dp(6)) }
    private fun text(content: String, size: Int, color: Int) = TextView(this).apply { text = content; textSize = size.toFloat(); setTextColor(color) }
    private fun input(hint: String) = EditText(this).apply {
        setHint(hint); setHintTextColor(MUTED); setTextColor(TEXT); textSize = 16f; setPadding(dp(16), dp(4), dp(16), dp(4))
        background = panel(CARD, true); minHeight = dp(56); maxLines = 3; isSingleLine = false
    }
    private fun spinner() = Spinner(this).apply { background = panel(CARD, true); setPadding(dp(8), 0, dp(8), 0); layoutParams = LinearLayout.LayoutParams(-1, dp(54)) }
    private fun button(content: String, accent: Boolean = false, small: Boolean = false, click: () -> Unit): Button = Button(this).apply {
        text = content; isAllCaps = false; textSize = if (small) 14f else 17f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (accent) BG else TEXT); background = panel(if (accent) ACCENT else CARD); minHeight = dp(52)
        setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun panel(color: Int, border: Boolean = false) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat(); if (border) setStroke(dp(1), BORDER) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun hideKeyboard() = getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun clock(seconds: Long) = if (seconds >= 3600) String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60) else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    companion object { private val BG = Color.parseColor("#101216"); private val CARD = Color.parseColor("#1B2028"); private val TEXT = Color.parseColor("#F4F6FA"); private val MUTED = Color.parseColor("#9CA9BB"); private val ACCENT = Color.parseColor("#9ACDFF"); private val BORDER = Color.parseColor("#364453") }
}

private fun acceptMagnet(text: Editable) = MagnetLink.from(text.toString())
private fun sharedMagnet(text: String?, uri: android.net.Uri?) = MagnetLink.fromShare(text, uri)
private class StringAdapter(context: Context, values: List<String>) : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, values) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = super.getView(position, convertView, parent).also { (it as? TextView)?.setTextColor(MainActivityColors.TEXT) }
    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = super.getDropDownView(position, convertView, parent).also { (it as? TextView)?.setTextColor(MainActivityColors.TEXT); it.setBackgroundColor(MainActivityColors.CARD) }
}
private object MainActivityColors { val TEXT = Color.WHITE; val CARD = Color.parseColor("#1B2028") }

fun formatBytes(bytes: Long): String = if (bytes >= 1024 * 1024 * 1024) String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0) else String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
fun formatSpeed(bytes: Long): String = if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f MB/s", bytes / 1024.0 / 1024.0) else "${bytes / 1024} KB/s"
