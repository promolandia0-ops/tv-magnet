package com.promolandia.tvmagnet.dlna

import org.xmlpull.v1.*
import java.io.*
import java.net.*

/** Minimal UPnP SOAP serialization, with XML-safe percent/metadata payloads. */
object Soap {
    fun envelope(service: String, action: String, args: Map<String, String>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(x(service)).append("\">")
        for ((key, value) in args) { append('<').append(key).append('>'); append(x(value)); append("</").append(key).append('>') }
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    fun action(url: URL, service: String, action: String, args: Map<String, String>, connectTimeout: Int = 5000, readTimeout: Int = 7000): Map<String, String> {
        val body = envelope(service, action, args).toByteArray(Charsets.UTF_8)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = connectTimeout; connection.readTimeout = readTimeout
            connection.useCaches = false; connection.instanceFollowRedirects = false
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPAction", "\"$service#$action\"")
            connection.setRequestProperty("Connection", "close")
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Controle DLNA falhou ($code).")
            val response = readBounded(connection.inputStream, readTimeout.toLong())
            return fields(response.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    private fun readBounded(input: InputStream, timeoutMs: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (output.size() <= 1_000_000) {
            val n = input.read(buf) // bounded by HttpURLConnection readTimeout
            if (n < 0) break
            output.write(buf, 0, n)
        }
        if (output.size() > 1_000_000) throw IOException("Resposta DLNA excessivamente grande.")
        return output.toByteArray()
    }

    /** Collect leaf tag values and concatenate adjacent TEXT and CDATA sections. */
    fun fields(xml: String): Map<String, String> {
        if (xml.length > 1_000_000) throw IllegalArgumentException("Resposta DLNA excessivamente grande.")
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        // Robolectric's xpp3 1.1.3.4c rejects a (false)-valued processing feature.
        // DTDs are never necessary for UPnP descriptions/SOAP responses.
        parser.setInput(StringReader(xml))
        val values = LinkedHashMap<String, String>()
        val stack = ArrayDeque<Pair<String, Boolean>>()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> stack.addLast(parser.name to false)
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (stack.isNotEmpty()) {
                    val (name, _) = stack.removeLast(); stack.addLast(name to true)
                    val path = stack.map { it.first }
                    // No need to keep aggregate fields; fields() is a leaf lookup.
                    path.lastOrNull()?.let { values[it] = (values[it] ?: "") + parser.text }
                }
                XmlPullParser.END_TAG -> if (stack.isNotEmpty()) {
                    val (name, _) = stack.removeLast()
                    if (name != parser.name) throw IllegalArgumentException("Resposta DLNA inválida.")
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return values
    }

    fun x(value: String) = buildString {
        value.forEach { when (it) { '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;"); else -> append(it) } }
    }
}

class RendererController(private val device: TvDevice) {
    private fun turn(name: String, args: Map<String, String>) = Soap.action(
        device.control, RendererDiscovery.AV_TRANSPORT, name, mapOf("InstanceID" to "0") + args)
    fun play(url: String, title: String, mime: String) {
        // Stop is best effort; TVs busy with previous playback can reject it.
        try { turn("Stop", emptyMap()) } catch (_: Exception) {}
        turn("SetAVTransportURI", mapOf("CurrentURI" to url, "CurrentURIMetaData" to DlnaMetadata.didl(title, url, mime)))
        turn("Play", mapOf("Speed" to "1"))
    }
    fun playAgain() = turn("Play", mapOf("Speed" to "1"))
    fun pause() = turn("Pause", emptyMap())
    fun stop() = turn("Stop", emptyMap())
    fun seek(relativeTime: String) = turn("Seek", mapOf("Unit" to "REL_TIME", "Target" to relativeTime))
    fun position(): Tick? {
        val f = turn("GetPositionInfo", emptyMap())
        val duration = parseClock(f["TrackDuration"] ?: return null) ?: return null
        val position = parseClock(f["RelTime"] ?: return null)
        if (duration <= 0 || position == null) return null
        return Tick(duration, position, f["TransportState"] ?: "STOPPED")
    }
    fun setVolume(value: Int) {
        val url = device.volumeControl ?: return
        Soap.action(url, RendererDiscovery.RENDERING_CONTROL, "SetVolume", mapOf(
            "InstanceID" to "0", "Channel" to "Master",
            "DesiredVolume" to value.coerceIn(0, 100).toString()))
    }
    fun parseClock(raw: String): Long? {
        val p = raw.trim().split(':')
        if (p.size != 3) return null
        val h = p[0].toLongOrNull() ?: return null
        val m = p[1].toLongOrNull() ?: return null
        val s = p[2].substringBefore('.').toLongOrNull() ?: return null
        if (h < 0 || m !in 0..59 || s !in 0..59) return null
        return h * 3600 + m * 60 + s
    }
    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }
    data class Tick(val duration: Long, val position: Long, val state: String)
}

object DlnaMetadata {
    const val PROTOCOL_FLAGS = "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    fun protocol(mime: String) = "http-get:*:$mime:$PROTOCOL_FLAGS"
    fun didl(title: String, uri: String, mime: String) =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${Soap.x(title)}</dc:title>" +
        "<upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"${Soap.x(protocol(mime))}\">" +
        "${Soap.x(uri)}</res></item></DIDL-Lite>"
}
