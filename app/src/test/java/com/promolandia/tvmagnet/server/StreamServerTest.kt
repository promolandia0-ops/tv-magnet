package com.promolandia.tvmagnet.server

import com.promolandia.tvmagnet.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class StreamServerTest {
    private class Fake(val bytes: ByteArray, val delayed: Boolean = false): PieceReader {
        override val fileLength: Long get() = bytes.size.toLong()
        val allowed = AtomicBoolean(!delayed)
        override fun isAvailable(offset: Long, length: Int) = allowed.get()
        override fun prioritize(offset: Long, length: Int) {}
        override fun complete() = false
        override fun signal(){}
        override fun throwIfStopped(){}
        override fun isStopped()=false
    }
    private fun request(url:String, range:String?=null, method:String="GET"):Response {
        val c=URL(url).openConnection() as HttpURLConnection
        c.requestMethod=method;c.readTimeout=8000;c.connectTimeout=3000
        c.instanceFollowRedirects=false
        if(range!=null)c.setRequestProperty("Range",range)
        val code=c.responseCode
        val headers=c.headerFields.mapValues{it.value?.joinToString(",")}
        val input=if(code in 200..299)c.inputStream else c.errorStream
        val body=input?.readBytes() ?: byteArrayOf()
        input?.close();c.disconnect()
        return Response(code,headers,body)
    }
    data class Response(val status:Int,val headers:Map<String,String?>,val body:ByteArray)

    @Test fun realHttpRangeAndUnavailablePieceThenArrives() {
        val bytes=ByteArray(131_072){it.toByte()}
        val file=File.createTempFile("tv-magnet-test",".mp4").apply{writeBytes(bytes);deleteOnExit()}
        val pieces=Fake(bytes,true)
        val serv=StreamServer().apply {
            active=ActiveStream("aula.mp4",mimeForName("aula.mp4"),file,pieces)
            start(5000,false)
        }
        try {
            val base="http://127.0.0.1:${serv.listeningPort}"
            val pool=Executors.newSingleThreadExecutor()
            val next=pool.submit<Response>{request("$base/${serv.token}/video.mp4","bytes=10-29")}
            Thread.sleep(100)
            assertFalse("must wait for unavailable piece",next.isDone)
            pieces.allowed.set(true)
            val result=next.get(5,TimeUnit.SECONDS)
            assertEquals(206,result.status)
            assertArrayEquals(bytes.copyOfRange(10,30),result.body)
            assertEquals("bytes 10-29/131072",result.headers["Content-Range"])
            assertArrayEquals(bytes.copyOfRange(bytes.size-17,bytes.size),request("$base/${serv.token}/video.mp4","bytes=-17").body)
            val head=request("$base/${serv.token}/video.mp4",method="HEAD")
            assertEquals(200,head.status)
            val bad=request("$base/${serv.token}/video.mp4","bytes=0-1,5-8")
            assertEquals(416,bad.status)
            val secret=URL("$base/nope/video.mp4")
            assertEquals(404,request(secret.toString()).status)
            pool.shutdownNow()
        }finally{serv.stop()}
    }
}
