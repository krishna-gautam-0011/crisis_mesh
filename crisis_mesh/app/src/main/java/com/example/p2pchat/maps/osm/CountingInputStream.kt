package com.example.p2pchat.maps.osm

import java.io.FilterInputStream
import java.io.InputStream

/** Wraps a stream so bytes-read progress can be reported while parsing a large .osm file. */
class CountingInputStream(
    inStream: InputStream,
    private val onBytesRead: (Long) -> Unit
) : FilterInputStream(inStream) {

    private var total = 0L

    override fun read(): Int {
        val b = super.read()
        if (b != -1) {
            total++
            onBytesRead(total)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            total += n
            onBytesRead(total)
        }
        return n
    }
}
