package com.example.gametranslator

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer

class FramePackerTest {

    /** Уникальный «пиксель» RGBA для координат (x, y). */
    private fun pixel(x: Int, y: Int) = byteArrayOf(x.toByte(), y.toByte(), (x + y).toByte(), 0x7F)

    /** Буфер кадра так, как его отдаёт ImageReader: строки по rowStride байт. */
    private fun frame(width: Int, height: Int, pixelStride: Int, rowStride: Int, size: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val at = y * rowStride + x * pixelStride
                pixel(x, y).forEachIndexed { k, b -> buffer.put(at + k, b) }
            }
        }
        return buffer
    }

    private fun expected(width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            pixel(x, y).copyInto(out, (y * width + x) * 4)
        }
        return out
    }

    private fun bytes(buffer: ByteBuffer): ByteArray {
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    @Test
    fun tightRows() {
        val src = frame(5, 3, 4, 20, 5 * 3 * 4)
        assertArrayEquals(expected(5, 3), bytes(FramePacker.pack(src, 5, 3, 4, 20)))
    }

    @Test
    fun paddedRows() {
        val rowStride = 5 * 4 + 12
        val src = frame(5, 3, 4, rowStride, rowStride * 3)
        assertArrayEquals(expected(5, 3), bytes(FramePacker.pack(src, 5, 3, 4, rowStride)))
    }

    @Test
    fun paddedRowsWithShortLastRow() {
        // Случай, на котором раньше было «Buffer not large enough for pixels»
        val rowStride = 5 * 4 + 12
        val src = frame(5, 3, 4, rowStride, rowStride * 2 + 5 * 4)
        assertArrayEquals(expected(5, 3), bytes(FramePacker.pack(src, 5, 3, 4, rowStride)))
    }

    @Test
    fun widePixelStride() {
        val pixelStride = 8
        val rowStride = 5 * pixelStride
        val src = frame(5, 3, pixelStride, rowStride, rowStride * 3)
        assertArrayEquals(expected(5, 3), bytes(FramePacker.pack(src, 5, 3, pixelStride, rowStride)))
    }
}
