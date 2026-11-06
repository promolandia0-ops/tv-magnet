package com.promolandia.tvmagnet.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.*
import java.util.concurrent.Executors

/** SSDP discovery plus direct Samsung description probes. Never probes arbitrary IPs. */
class RendererDiscovery(private val context: Context) {
    data class Candidate(val base: URL, val control: URL, val volumeControl: URL?)
    private val executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "dlna-discovery").apply { isDaemon = true } }
    private val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private val mutableDevices = MutableStateFlow(loadSavedDevices())
    val devices = mutableDevices.asStateFlow()
    private var scanNumber = 0
    private var lock: WifiManager.MulticastLock? = null
    private var closed = false

    fun choose(ip: String) = deviceForSavedIp(ip)
    fun initialTV(): TvDevice? = mutableDevices.value.firstOrNull()

    fun scan(now: Int = ++scanNumber) {
        if (closed) return
        executor.execute { discoverSsdp(now) }
    }

    fun scanFresh() = scan(++scanNumber)
    fun manual(ip: String, onResult: (TvDevice?, String) -> Unit) {
        val clean = cleanIp(ip) ?: run { onResult(null, "Digite um endereço IPv4 válido."); return }
        val n = ++scanNumber
        executor.submit {
            val tv = probeSamsung(clean)
            if (tv != null) add(tv, n)
            onResult(tv, if (tv == null) "Nenhuma TV respondeu nesse IP. Confira o Wi-Fi e o IP." else "")
        }
    }

    private fun discoverSsdp(generation: Int) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("tv-magnet").apply { setReferenceCounted(false); acquire() }
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 600
                val targets = listOf("urn:schemas-upnp-org:device:MediaRenderer:1", AV_TRANSPORT, "upnp:rootdevice")
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { targets.forEach { target ->
                    val request = "M-SEARCH * HTTP/1.1\r\nHOST:239.255.255.250:1900\r\nMAN:\"ssdp:discover\"\r\nMX:2\r\nST:$target\r\n\r\n"
                    socket.send(DatagramPacket(request.toByteArray(), request.length, group, 1900))
                } }
                val end = System.currentTimeMillis() + 4_200
                val buf = ByteArray(8192)
                while (System.currentTimeMillis() < end) {
                    try {
                        val packet = DatagramPacket(buf, buf.size); socket.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                        val location = Regex("(?im)^LOCATION:\\s*(.*?)\\s*$").find(text)?.groupValues?.get(1)?.trim()
                        if (!location.isNullOrBlank()) bootstrap(URL(location), generation)
                    } catch (_: SocketTimeoutException) { continue
                    } catch (_: java.net.PortUnreachableException) { continue
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { Log.i("TVMagnet/SSDP", "Discovery unavailable: ${e.message}") }
        finally { lock?.let { if (it.isHeld) it.release() }; lock = null }
    }

    private fun bootstrap(url: URL, generation: Int) {
        try { tryUrl(url)?.let { add(it, generation) } } catch (_: Exception) {}
    }

    /** Direct ports and description path permutations used by Samsung DMRs. */
    fun probeSamsung(ip: String): TvDevice? {
        val known = listOf(9197 to "/dmr", 9197 to "/dmr/description.xml", 9197 to "/description.xml",
            9197 to "/samsung-dmr.xml", 7676 to "/dmr", 7676 to "/description.xml", 7676 to "/rootDesc.xml",
            8001 to "/dmr", 8001 to "/description.xml", 52235 to "/dmr", 1900 to "/description.xml")
        val last = mutableDevices.value.firstOrNull { it.host == ip }
        last?.let { tryUrl(it.description)?.let { return it } }
        for ((port, path) in known) {
            try { tryUrl(URL("http://$ip:$port$path"))?.let { return it } } catch (_: Exception) {}
        }
        return null
    }

    internal fun tryUrl(url: URL): TvDevice? {
        val response = get(url, readTimeout = 1800)
        val services = serviceControls(response, url)
        val control = services[AV_TRANSPORT] ?: return null
        val volume = services[RENDERING_CONTROL]
        val title = Soap.fields(response)["friendlyName"] ?: url.host
        return TvDevice(title, url.host, url, control, volume)
    }

    private fun serviceControls(xml: String, root: URL): Map<String, URL> {
        val parsed = Soap.fields(xml)
        if (!parsed.containsKey("serviceType")) return emptyMap()
        val all = LinkedHashMap<String, URL>()
        // Most devices put only one service of each type. The fallback picks the first.
        var tail = xml
        while (tail.contains("<service>")) {
            val service = tail.substringAfter("<service>"); tail = service.substringAfter("</service>")
            val f = Soap.fields("<service>${service.substringBefore("</service>")}</service>")
            val type = f["serviceType"]?: continue; val control = f["controlURL"]?:continue
            try { all[type] = URL(root, control) } catch (_: Exception) {}
        }
        return all
    }

    private fun add(tv: TvDevice, generation: Int) {
        if (closed) return
        if (generation != scanNumber) return
        val next = (listOf(tv) + mutableDevices.value.filterNot { it.host == tv.host || it.control == tv.control }).distinctBy { it.control.toString() }
        mutableDevices.value = next
        prefs.edit().putString("last_tv", tv.serialize()).apply()
    }

    private fun loadSavedDevices(): List<TvDevice> = prefs.getString("last_tv", null)?.let { TvDevice.deserialize(it) }?.let(::listOf) ?: emptyList()
    private fun deviceForSavedIp(ip: String) = mutableDevices.value.firstOrNull { it.host == ip }

    fun close() { closed = true; ++scanNumber; lock?.let { if (it.isHeld) it.release() }; executor.shutdownNow() }

    companion object {
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        fun get(url: URL, connectTimeout: Int = 2200, readTimeout: Int = 2500): String {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = connectTimeout; c.readTimeout = readTimeout
            c.setRequestProperty("User-Agent", "TV-Magnet/1.0 UPnP/1.0")
            if (c.responseCode != 200) throw java.net.ConnectException("Description unavailable: ${c.responseCode}")
            return c.inputStream.reader(Charsets.UTF_8).use { it.readText() }
        }
        fun cleanIp(raw: String): String? {
            val parts = raw.trim().split('.')
            if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
            return parts.joinToString(".")
        }
    }
}


data class TvDevice(val name: String, val host: String, val description: URL, val control: URL, val volumeControl: URL?) {
    val key: String get() = control.toString()
    override fun toString(): String = "$name · $host"
    fun serialize() = listOf(name, description.toString(), control.toString(), volumeControl?.toString() ?: "").joinToString("\n") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
    companion object { fun deserialize(value: String): TvDevice? = try { val a = value.split('\n').map { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }; val desc=URL(a[1]); TvDevice(a[0],desc.host,desc,URL(a[2]),a[3].takeIf { it.isNotEmpty() }?.let(::URL)) } catch (_: Throwable) { null } }
}
/** Adapter calls standard toString(), not an extension member. */
