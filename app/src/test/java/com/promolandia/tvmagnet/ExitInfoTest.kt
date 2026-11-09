package com.promolandia.tvmagnet

import org.junit.Assert.assertTrue
import org.junit.Test

class ExitInfoTest {
    @Test fun nativeTombstoneKeepsReadableFrames() {
        val bytes = byteArrayOf(0x08, 0x0b, 0x12) + "Fatal signal 11 (SIGSEGV)".toByteArray() +
            byteArrayOf(0x00, 0x1a) + "libtorrent4j.so".toByteArray() + byteArrayOf(0x01)
        val text = ExitInfoReader.printable(bytes)
        assertTrue(text.contains("SIGSEGV"))
        assertTrue(text.contains("libtorrent4j.so"))
    }
}
