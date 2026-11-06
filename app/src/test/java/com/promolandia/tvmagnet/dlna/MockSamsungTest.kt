package com.promolandia.tvmagnet.dlna

import android.app.Application
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MockSamsungTest {
    private class Samsung(port: Int, private val bodies: MutableList<String>) : NanoHTTPD("127.0.0.1", port) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/dmr") return newFixedLengthResponse(description())
            if (session.method != Method.POST || !session.uri.contains("control-av")) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            val parts = HashMap<String, String>()
            session.parseBody(parts)
            val body = parts["postData"] ?: ""
            bodies += body
            val action = Regex("<u:([A-Za-z0-9]+)[ >]").find(body)?.groupValues?.get(1)
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad SOAP")
            return newFixedLengthResponse("""<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="${RendererDiscovery.AV_TRANSPORT}"><TrackDuration>00:20:00</TrackDuration><RelTime>00:00:05</RelTime><TransportState>PLAYING</TransportState></u:${action}Response></s:Body></s:Envelope>""")
        }
        fun device(): TvDevice { val base = "http://127.0.0.1:$listeningPort"; return TvDevice("Samsung", "127.0.0.1", URL("$base/dmr"), URL("$base/upnp/control-av"), URL("$base/rendering")) }
        private fun description() = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>Samsung E Television</friendlyName><serviceList><service><serviceType>${RendererDiscovery.AV_TRANSPORT}</serviceType><controlURL>/upnp/control-av</controlURL></service></serviceList></device></root>"""
    }

    @Test fun findsSamsungDmrAndSendsStrictSoap() {
        val bodies = CopyOnWriteArrayList<String>()
        val server = Samsung(0, bodies)
        server.start()
        try {
            val tv = server.device()
            val controller = RendererController(tv)
            assertEquals("Samsung E Television", RendererDiscovery(RuntimeEnvironment.getApplication()).tryUrl(tv.description)?.name)
            controller.play("http://wifi/secret?title=A%26B", "A & B <é>", "video/mp4")
            controller.pause()
            controller.playAgain()
            controller.seek("00:00:10")
            assertEquals(5L, controller.position()?.position)
            assertEquals(listOf("Stop", "SetAVTransportURI", "Play", "Pause", "Play", "Seek", "GetPositionInfo"), bodies.map { Regex("<u:([A-Za-z0-9]+)[ >]").find(it)!!.groupValues[1] })
            assertTrue(bodies[1].contains("http-get:*:video/mp4"))
            assertTrue(bodies[1].contains("&lt;DIDL-Lite"))
            assertTrue(bodies[1].contains("A &amp;amp; B &amp;lt;é&amp;gt;"))
            assertEquals(3726L, controller.parseClock("01:02:06"))
        } finally { server.stop() }
    }
}
