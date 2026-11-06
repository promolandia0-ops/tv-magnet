package com.promolandia.tvmagnet.dlna

import org.junit.Assert.*
import org.junit.Test
import com.promolandia.tvmagnet.mimeForName
import org.xmlpull.v1.XmlPullParserFactory

class DlnaXmlTest {
    @Test fun soapEscapesUriAndMetadata() {
        val xml=Soap.envelope(RendererDiscovery.AV_TRANSPORT,"SetAVTransportURI",mapOf(
            "CurrentURI" to "http://10.0.0.3:8080/a.mp4?t=abc&x=1",
            "CurrentURIMetaData" to DlnaMetadata.didl("Aula <Guitarra> & \"Tom\"", "http://10.0.0.3/video.mp4", "video/mp4")))
        assertTrue(xml.contains("abc&amp;x=1"))
        val parser=XmlPullParserFactory.newInstance().newPullParser().apply { setInput(xml.reader()) }
        var tags=0
        while(parser.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) if(parser.eventType==org.xmlpull.v1.XmlPullParser.START_TAG) tags++
        assertTrue(tags>2)
        assertEquals("SetAVTransportURI",Soap.fields("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:action xmlns:u=\"urn:foo\"><u:Action>SetAVTransportURI</u:Action></u:action></s:Body></s:Envelope>")["Action"])
    }
    @Test fun mkvHasRightProtocolAndSamsungFlags() {
        assertEquals("video/x-matroska",mimeForName("Guitarra.MKV"))
        val metadata=DlnaMetadata.didl("Aula 1","http://10.0.0.3/video.mkv",mimeForName("Aula1.mkv"))
        assertTrue(metadata.contains("video/x-matroska"))
        assertTrue(metadata.contains(DlnaMetadata.PROTOCOL_FLAGS))
    }
    @Test fun arbitraryNamespacePrefixesAndDefaultUIs() {
        val f=Soap.fields("<s:Envelope xmlns:s=\"urn:s\"><s:Body><a:GetPositionInfoResponse xmlns:a=\"urn:av\"><a:RelTime>00:01:25</a:RelTime><a:TrackDuration>01:30:10</a:TrackDuration></a:GetPositionInfoResponse></s:Body></s:Envelope>")
        val c=RendererController(TvDevice("TV","127.0.0.1",java.net.URL("http://127.0.0.1"),java.net.URL("http://127.0.0.1"),null))
        assertEquals(85L,c.parseClock(f["RelTime"]!!))
        assertEquals(5410L,c.parseClock(f["TrackDuration"]!!))
        assertEquals("01:30:10",c.clock(5410))
        assertNull(c.parseClock("NOT AVAILABLE"))
    }
}
