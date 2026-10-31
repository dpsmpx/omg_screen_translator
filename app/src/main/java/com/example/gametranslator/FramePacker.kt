package com.example.gametranslator

import java.nio.ByteBuffer

/**
 * Упаковывает кадр RGBA из буфера записи экрана в плотный массив width * height * 4 байт.
 *
 * В буфере кадра строка часто длиннее ширины картинки (выравнивание, rowStride),
 * а последняя строка на многих устройствах короче остальных. Если передать такой буфер
 * в Bitmap.copyPixelsFromBuffer напрямую, приложение падает с ошибкой
 * «Buffer not large enough for pixels».
 */
object FramePacker {

    fun pack(src: ByteBuffer, width: Int, height: Int, pixelStride: Int, rowStride: Int): ByteBuffer {
        val rowBytes = width * 4
        val packed = ByteBuffer.allocate(rowBytes * height)
        val row = ByteArray(rowBytes)
        val limit = src.limit()
        for (y in 0 until height) {
            val start = y * rowStride
            if (start >= limit) break
            if (pixelStride == 4) {
                val n = minOf(rowBytes, limit - start)
                src.position(start)
                src.get(row, 0, n)
                packed.position(y * rowBytes)
                packed.put(row, 0, n)
            } else {
                for (x in 0 until width) {
                    val at = start + x * pixelStride
                    if (at + 4 > limit) break
                    val out = y * rowBytes + x * 4
                    for (k in 0 until 4) packed.put(out + k, src.get(at + k))
                }
            }
        }
        packed.rewind()
        return packed
    }
}
