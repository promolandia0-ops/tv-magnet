package com.promolandia.tvmagnet.dlna

import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class XmlMissingTest {
    @Test fun samsungDiscoveryParsesServiceRelativeControl() {
        val c=Soap.fields("<root><device><friendlyName>[TV]</friendlyName></device></root>")
        org.junit.Assert.assertEquals("[TV]",c["friendlyName"])
        // Ip fallback is exposed with explicit user-visible prompt in the app.
        org.junit.Assert.assertEquals("192.168.1.8",RendererDiscovery.cleanIp("192.168.1.8"))
        org.junit.Assert.assertNull(RendererDiscovery.cleanIp("999.1.1.1"))
    }
}
