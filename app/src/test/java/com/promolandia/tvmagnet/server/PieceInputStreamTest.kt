package com.promolandia.tvmagnet.server

import com.promolandia.tvmagnet.PieceReader
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PieceInputStreamTest {
    private class Source(private val knownLength:Long):PieceReader {
        override val fileLength:Long get()=knownLength
        override fun isAvailable(offset:Long,length:Int)=true
        override fun prioritize(offset:Long,length:Int) {}
        override fun complete()=false
        override fun signal(){}
        override fun throwIfStopped(){}
        override fun isStopped()=false
    }
    @Test fun rangeReadsExactBytesAndHonorsStartAndLength() {
        val data=ByteArray(3000){ (it%251).toByte() }
        val file=File.createTempFile("tvmagnet",".mp4").apply { writeBytes(data);deleteOnExit() }
        val input=PieceInputStream(Source(3000),20,file,100)
        assertArrayEquals(data.copyOfRange(100,120),input.readBytes())
        assertEquals(-1,input.read())
        input.close()
    }
    @Test fun zeroCountReadDoesNotAdvance() {
        val file=File.createTempFile("tvmagnet",".mp4").apply { writeBytes(byteArrayOf(1,2,3));deleteOnExit() }
        val input=PieceInputStream(Source(3),3,file,0)
        assertEquals(0,input.read(ByteArray(0)))
        assertEquals(1,input.read())
        input.close()
    }
}
