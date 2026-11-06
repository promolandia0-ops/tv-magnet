package com.promolandia.tvmagnet.server

import org.junit.Assert.*
import org.junit.Test

class HttpRangeTest {
    @Test fun noRangeReadsWholeFile() { assertEquals(ByteRange(0,999), HttpRange.parse(null,1000)) }
    @Test fun openEndedRange() { assertEquals(ByteRange(8,99), HttpRange.parse("bytes=8-",100)) }
    @Test fun closedRange() { assertEquals(ByteRange(8,12), HttpRange.parse("bytes=8-12",100)) }
    @Test fun saturatesTail() { assertEquals(ByteRange(8,99), HttpRange.parse("bytes=8-199",100)) }
    @Test fun suffixRange() { assertEquals(ByteRange(90,99), HttpRange.parse("bytes=-10",100)) }
    @Test fun suffixLongerThanFile() { assertEquals(ByteRange(0,9), HttpRange.parse("bytes=-99",10)) }
    @Test(expected=BadRange::class) fun zeroSuffixRejected() { HttpRange.parse("bytes=-0",100) }
    @Test(expected=BadRange::class) fun multipartRejected() { HttpRange.parse("bytes=0-1,5-8",100) }
    @Test(expected=BadRange::class) fun reversedRangeRejected() { HttpRange.parse("bytes=8-2",100) }
    @Test(expected=BadRange::class) fun negativeRejected() { HttpRange.parse("bytes=-2-4",100) }
    @Test(expected=BadRange::class) fun garbageRejected() { HttpRange.parse("something=0-2",100) }
    @Test(expected=BadRange::class) fun startPastEndRejected() { HttpRange.validate(ByteRange(100,100),100) }
    @Test fun streamingHeaderFlags() {
        assertTrue(StreamServer.DLNA_FEATURES.startsWith("DLNA.ORG_OP=01"))
        assertTrue(StreamServer.DLNA_FEATURES.contains("DLNA.ORG_FLAGS=01700000000000000000000000000000"))
    }
}
