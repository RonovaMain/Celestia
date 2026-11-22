package dev.ronova.celestia.core.audio

import dev.ronova.celestia.R

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class ExportFmt(val ext: String, val mime: String, val title: Int) {
    WAV("wav", "audio/wav", R.string.fmt_wav),
    FLAC("flac", "audio/flac", R.string.fmt_flac),
    M4A("m4a", "audio/mp4", R.string.fmt_m4a),
}

/** Кодирует стерео 16-бит WAV приложения (44-байтовый заголовок) в выбранный формат. */
object Export {
    fun encode(srcWav: File, fmt: ExportFmt, dest: File, progress: (Float) -> Unit = {}) {
        when (fmt) {
            ExportFmt.WAV -> srcWav.copyTo(dest, overwrite = true)
            ExportFmt.FLAC -> Flac.encode(srcWav, dest, progress)
            ExportFmt.M4A -> Aac.encode(srcWav, dest, 256_000, progress)
        }
    }

    class Src(val sr: Int, val ch: Int, val frames: Long)

    fun probe(f: File): Src {
        RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(44)
            raf.readFully(h)
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val ch = bb.getShort(22).toInt()
            val sr = bb.getInt(24)
            val dataBytes = raf.length() - 44
            return Src(sr, ch, dataBytes / (2L * ch))
        }
    }
}

object Aac {
    fun encode(src: File, dest: File, bitrate: Int, progress: (Float) -> Unit) {
        val info = Export.probe(src)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, info.sr, info.ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        dest.delete()
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val bi = MediaCodec.BufferInfo()
        val frameBytes = 2 * info.ch
        var fed = 0L
        var inDone = false
        var outDone = false
        try {
            RandomAccessFile(src, "r").use { raf ->
                raf.seek(44)
                val tmp = ByteArray(1 shl 16)
                while (!outDone) {
                    if (!inDone) {
                        val ib = codec.dequeueInputBuffer(10_000)
                        if (ib >= 0) {
                            val buf = codec.getInputBuffer(ib)!!
                            buf.clear()
                            val want = (minOf(buf.remaining(), tmp.size) / frameBytes) * frameBytes
                            val got = raf.read(tmp, 0, want)
                            if (got <= 0) {
                                codec.queueInputBuffer(ib, 0, 0, fed * 1_000_000L / info.sr, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                buf.put(tmp, 0, got)
                                codec.queueInputBuffer(ib, 0, got, fed * 1_000_000L / info.sr, 0)
                                fed += got / frameBytes
                                progress((fed.toFloat() / info.frames.coerceAtLeast(1)).coerceIn(0f, 1f))
                            }
                        }
                    }
                    val ob = codec.dequeueOutputBuffer(bi, 10_000)
                    when {
                        ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                        ob >= 0 -> {
                            val out = codec.getOutputBuffer(ob)!!
                            if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                            if (bi.size > 0 && muxing) {
                                out.position(bi.offset)
                                out.limit(bi.offset + bi.size)
                                muxer.writeSampleData(track, out, bi)
                            }
                            codec.releaseOutputBuffer(ob, false)
                            if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxing) muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}

/**
 * FLAC без внешних библиотек: блоки по 4096, фиксированные предикторы 0–4, разбиение остатка по
 * Райсу (порядок до 6), постоянный/сырой подкадры как запасной вариант. Проверен через ffmpeg
 * (побитовое совпадение декодированного PCM).
 */
object Flac {
    private const val BLOCK = 4096
    private val crc16Table = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
            t[i] = c
        }
    }

    private fun crc8(b: ByteArray, len: Int): Int {
        var c = 0
        for (i in 0 until len) {
            c = c xor (b[i].toInt() and 0xFF)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        }
        return c
    }

    private fun crc16(b: ByteArray, len: Int): Int {
        var c = 0
        for (i in 0 until len) c = ((c shl 8) and 0xFFFF) xor crc16Table[(c shr 8) xor (b[i].toInt() and 0xFF)]
        return c
    }

    private class Bits {
        val out = ByteArrayOutputStream(1 shl 16)
        private var acc = 0L
        private var n = 0
        fun put(v: Long, bits: Int) {
            if (bits == 0) return
            acc = (acc shl bits) or (v and ((1L shl bits) - 1))
            n += bits
            while (n >= 8) { n -= 8; out.write(((acc shr n) and 0xFF).toInt()) }
            acc = acc and ((1L shl n) - 1)
        }
        fun put(v: Int, bits: Int) = put(v.toLong(), bits)
        fun unary(q: Int) { var r = q; while (r >= 32) { put(0L, 32); r -= 32 }; put(1L, r + 1) }
        fun align() { if (n > 0) put(0L, 8 - n) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun utf8(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x800 -> byteArrayOf((0xC0 or (n shr 6)).toByte(), (0x80 or (n and 0x3F)).toByte())
        n < 0x10000 -> byteArrayOf((0xE0 or (n shr 12)).toByte(), (0x80 or ((n shr 6) and 0x3F)).toByte(), (0x80 or (n and 0x3F)).toByte())
        n < 0x200000 -> byteArrayOf((0xF0 or (n shr 18)).toByte(), (0x80 or ((n shr 12) and 0x3F)).toByte(), (0x80 or ((n shr 6) and 0x3F)).toByte(), (0x80 or (n and 0x3F)).toByte())
        else -> byteArrayOf((0xF8 or (n shr 24)).toByte(), (0x80 or ((n shr 18) and 0x3F)).toByte(), (0x80 or ((n shr 12) and 0x3F)).toByte(), (0x80 or ((n shr 6) and 0x3F)).toByte(), (0x80 or (n and 0x3F)).toByte())
    }

    private fun residual(x: IntArray, n: Int, order: Int, r: IntArray) {
        for (i in order until n) {
            val p = when (order) {
                0 -> 0
                1 -> x[i - 1]
                2 -> 2 * x[i - 1] - x[i - 2]
                3 -> 3 * x[i - 1] - 3 * x[i - 2] + x[i - 3]
                else -> 4 * x[i - 1] - 6 * x[i - 2] + 4 * x[i - 3] - x[i - 4]
            }
            r[i - order] = x[i] - p
        }
    }

    private fun zig(v: Int): Int = if (v >= 0) v shl 1 else ((-v) shl 1) - 1

    /** Лучший параметр Райса для u[from until to) -> (k, биты). */
    private fun bestK(u: IntArray, from: Int, to: Int, kOut: IntArray): Long {
        val cnt = to - from
        if (cnt <= 0) { kOut[0] = 0; return 4L }
        var sum = 0L
        for (i in from until to) sum += u[i]
        val mean = sum / cnt
        val k0 = if (mean > 0) (63 - java.lang.Long.numberOfLeadingZeros(mean)).coerceIn(0, 14) else 0
        var best = Long.MAX_VALUE
        var bk = k0
        for (k in (k0 - 1)..(k0 + 1)) {
            if (k < 0 || k > 14) continue
            var c = cnt.toLong() * (k + 1)
            for (i in from until to) c += (u[i] ushr k)
            if (c < best) { best = c; bk = k }
        }
        kOut[0] = bk
        return best + 4
    }

    private fun subframe(bw: Bits, x: IntArray, n: Int) {
        var same = true
        for (i in 1 until n) if (x[i] != x[0]) { same = false; break }
        if (same) { bw.put(0, 1); bw.put(0, 6); bw.put(0, 1); bw.put(x[0], 16); return }

        var bestOrder = 0
        var bestSum = Long.MAX_VALUE
        val tmp = IntArray(n)
        for (order in 0..4) {
            if (order >= n) break
            residual(x, n, order, tmp)
            var s = 0L
            for (i in 0 until n - order) s += kotlin.math.abs(tmp[i].toLong())
            if (s < bestSum) { bestSum = s; bestOrder = order }
        }
        val order = bestOrder
        val res = IntArray(n - order)
        residual(x, n, order, res)
        val u = IntArray(res.size)
        var maxAbs = 0L
        for (i in res.indices) { u[i] = zig(res[i]); maxAbs = maxOf(maxAbs, kotlin.math.abs(res[i].toLong())) }

        var maxPo = 0
        while (maxPo < 6 && n % (1 shl (maxPo + 1)) == 0 && (n shr (maxPo + 1)) > order) maxPo++
        var bestTotal = Long.MAX_VALUE
        var bestPo = 0
        val kOut = IntArray(1)
        for (po in 0..maxPo) {
            val size = n shr po
            var pos = 0
            var total = 0L
            for (p in 0 until (1 shl po)) {
                val cnt = size - (if (p == 0) order else 0)
                total += bestK(u, pos, pos + cnt, kOut)
                pos += cnt
            }
            if (total < bestTotal) { bestTotal = total; bestPo = po }
        }
        val bits = 8L + order * 16L + 2 + 4 + bestTotal
        if (bits >= 8L + n * 16L || maxAbs >= (1L shl 28)) {
            bw.put(0, 1); bw.put(1, 6); bw.put(0, 1)
            for (i in 0 until n) bw.put(x[i], 16)
            return
        }
        bw.put(0, 1); bw.put(8 + order, 6); bw.put(0, 1)
        for (i in 0 until order) bw.put(x[i], 16)
        bw.put(0, 2); bw.put(bestPo, 4)
        val size = n shr bestPo
        var pos = 0
        for (p in 0 until (1 shl bestPo)) {
            val cnt = size - (if (p == 0) order else 0)
            bestK(u, pos, pos + cnt, kOut)
            val k = kOut[0]
            bw.put(k, 4)
            for (i in pos until pos + cnt) {
                bw.unary(u[i] ushr k)
                bw.put(u[i] and ((1 shl k) - 1), k)
            }
            pos += cnt
        }
    }

    fun encode(src: File, dest: File, progress: (Float) -> Unit) {
        val info = Export.probe(src)
        require(info.ch == 2) { "FLAC: stereo input expected" }
        val total = info.frames
        dest.outputStream().buffered(1 shl 17).use { os ->
            os.write("fLaC".toByteArray())
            os.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
            val si = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN)
            si.putShort(BLOCK.toShort()).putShort(BLOCK.toShort())
            si.put(ByteArray(6))                                      // min/max размер кадра неизвестны
            val packed = (info.sr.toLong() shl 44) or (1L shl 41) or (15L shl 36) or total
            si.putLong(packed)
            si.put(ByteArray(16))                                     // MD5 не считаем (0 = неизвестно)
            os.write(si.array())

            RandomAccessFile(src, "r").use { raf ->
                raf.seek(44)
                val raw = ByteArray(BLOCK * 4)
                val l = IntArray(BLOCK)
                val r = IntArray(BLOCK)
                var frameNo = 0
                var done = 0L
                while (done < total) {
                    val bs = minOf(BLOCK.toLong(), total - done).toInt()
                    raf.readFully(raw, 0, bs * 4)
                    val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until bs) { l[i] = bb.getShort(i * 4).toInt(); r[i] = bb.getShort(i * 4 + 2).toInt() }

                    val h = ByteArrayOutputStream()
                    h.write(0xFF); h.write(0xF8)
                    val code = if (bs == 4096) 12 else if (bs <= 256) 6 else 7
                    h.write(code shl 4)
                    h.write((1 shl 4) or (4 shl 1))
                    h.write(utf8(frameNo))
                    if (code == 6) h.write(bs - 1) else if (code == 7) { h.write((bs - 1) shr 8); h.write((bs - 1) and 0xFF) }
                    val hb = h.toByteArray()
                    h.write(crc8(hb, hb.size))

                    val bw = Bits()
                    subframe(bw, l, bs)
                    subframe(bw, r, bs)
                    bw.align()
                    val frame = ByteArrayOutputStream()
                    frame.write(h.toByteArray())
                    frame.write(bw.bytes())
                    val fb = frame.toByteArray()
                    os.write(fb)
                    val c = crc16(fb, fb.size)
                    os.write(c shr 8); os.write(c and 0xFF)

                    frameNo++
                    done += bs
                    if (frameNo % 64 == 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
    }
}
