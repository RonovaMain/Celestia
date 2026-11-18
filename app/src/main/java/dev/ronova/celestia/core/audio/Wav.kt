package dev.ronova.celestia.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/** Минимальный WAV: чтение PCM8/16/24/32 и float32, запись PCM16. */
object Wav {
    fun header(dataBytes: Long, sr: Int, ch: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort()).putInt(sr)
        b.putInt(sr * ch * 2).putShort((ch * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }

    /** float -1..1 -> PCM16 с мягким ограничением пиков. */
    fun toPcm16(v: Float): Int {
        val a = abs(v)
        if (a <= 0.95f) return (v * 32767f).toInt()
        val s = 0.95f + 0.05f * tanh((a - 0.95f) / 0.05f)
        return ((if (v < 0f) -s else s) * 32767f).toInt()
    }

    fun writeMono16(dest: File, x: FloatArray, sr: Int) {
        dest.outputStream().buffered(1 shl 16).use { o ->
            o.write(header(x.size * 2L, sr, 1))
            val b = ByteArray(1 shl 16)
            var i = 0
            while (i < x.size) {
                val n = minOf(b.size / 2, x.size - i)
                AudioIO.floatToPcm16(x, i, n, b)
                o.write(b, 0, n * 2)
                i += n
            }
        }
    }

    fun read(f: File): Pcm {
        RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(12)
            raf.readFully(h)
            require(String(h, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(h, 8, 4, Charsets.ISO_8859_1) == "WAVE") { "Файл не является WAV" }
            var tag = 1; var ch = 1; var sr = 22050; var bits = 16
            val ck = ByteArray(8)
            var dataLen = -1L
            loop@ while (true) {
                if (raf.read(ck) < 8) break@loop
                val id = String(ck, 0, 4, Charsets.ISO_8859_1)
                val size = ByteBuffer.wrap(ck, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val b = ByteArray(size.toInt())
                    raf.readFully(b)
                    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                    tag = bb.getShort(0).toInt() and 0xFFFF
                    ch = bb.getShort(2).toInt()
                    sr = bb.getInt(4)
                    bits = bb.getShort(14).toInt()
                    if (tag == 0xFFFE && size >= 26) tag = bb.getShort(24).toInt() and 0xFFFF
                    if (size and 1L == 1L) raf.skipBytes(1)
                } else if (id == "data") {
                    val rem = raf.length() - raf.filePointer
                    dataLen = if (size == 0L || size > rem) rem else size   // «потоковые» WAV пишут 0 или 0xFFFFFFFF
                    break@loop
                } else {
                    raf.seek(raf.filePointer + size + (size and 1L))
                }
            }
            require(dataLen >= 0) { "В WAV нет аудиоданных" }
            require(ch in 1..8 && sr > 0) { "Неверный заголовок WAV" }
            val bytes = bits / 8
            require(bytes in 1..4) { "Неподдерживаемая разрядность WAV: $bits" }
            val total = (dataLen / bytes).toInt()
            val out = FloatArray(total - total % ch)
            val blockSamples = (1 shl 18) / bytes
            val buf = ByteArray(blockSamples * bytes)
            var done = 0
            while (done < out.size) {
                val n = minOf(blockSamples, out.size - done)
                raf.readFully(buf, 0, n * bytes)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) {
                    out[done + i] = when {
                        bits == 16 -> bb.getShort(i * 2) / 32768f
                        bits == 32 && tag == 3 -> bb.getFloat(i * 4)
                        bits == 32 -> bb.getInt(i * 4) / 2147483648f
                        bits == 24 -> {
                            val o = i * 3
                            val v = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8) or (buf[o + 2].toInt() shl 16)
                            v / 8388608f
                        }
                        else -> ((buf[i].toInt() and 0xFF) - 128) / 128f
                    }
                }
                done += n
            }
            return Pcm(sr, ch, out)
        }
    }
}
