package com.promolandia.tvmagnet.server

/** RFC 7233 byte Range. Unknown units/malformed/multiple ranges throw BadRange. */
data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

object HttpRange {
    fun parse(header: String?, total: Long): ByteRange {
        if (total <= 0) throw BadRange
        val h = header?.trim() ?: return ByteRange(0, total - 1)
        val numbers = h.removePrefix("bytes=")
        if (numbers == h || numbers.contains(',')) throw BadRange
        val pieces = numbers.split('-')
        if (pieces.size != 2) throw BadRange
        val a = pieces[0].trim(); val b = pieces[1].trim()
        return when {
            a.isNotEmpty() -> {
                val start = a.toLongOrNull()?.takeIf { it >= 0 } ?: throw BadRange
                if (b.isEmpty()) ByteRange(start, total - 1)
                else {
                    val end = b.toLongOrNull()?.takeIf { it >= start } ?: throw BadRange
                    ByteRange(start, end.coerceAtMost(total - 1))
                }
            }
            total > 0 && b.toLongOrNull()?.let { it > 0 } == true -> {
                val suffix = b.toLong()
                ByteRange((total - suffix).coerceAtLeast(0), total - 1)
            }
            else -> throw BadRange
        }
    }
    fun validate(range: ByteRange, total: Long) {
        if (range.start < 0 || range.start >= total || range.end >= total || range.end < range.start) throw BadRange
    }
}
object BadRange: IllegalArgumentException()
