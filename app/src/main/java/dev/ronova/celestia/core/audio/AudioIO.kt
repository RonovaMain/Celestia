package dev.ronova.celestia.core.audio

import dev.ronova.celestia.R
import dev.ronova.celestia.tr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Декодированный звук: чередующиеся каналы, float -1..1. */
class Pcm(val sampleRate: Int, val channels: Int, val data: FloatArray) {
    val frames: Int get() = data.size / channels
    fun channel(c: Int): FloatArray = FloatArray(frames) { data[it * channels + c] }
    fun mono(): FloatArray = if (channels == 1) data else FloatArray(frames) { i ->
        var s = 0f
        for (c in 0 until channels) s += data[i * channels + c]
        s / channels
    }
}

object AudioIO {
    /** Любой формат, который умеет MediaCodec устройства (mp3/aac/flac/wav/ogg…). */
    fun decode(ctx: Context, uri: Uri, isCancelled: () -> Boolean = { false }): Pcm {
        // WAV из собственной папки приложения (стемы, озвучка) читаем напрямую, без MediaCodec
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.extension.equals("wav", ignoreCase = true)) runCatching { return Wav.read(f) }
        }
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        require(track >= 0) { tr(R.string.err_no_audio_track) }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var out = FloatArray(1 shl 20)
        var n = 0
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        var floatEnc = false
        try {
            while (!outDone) {
                check(!isCancelled()) { tr(R.string.job_cancelled) }
                if (!inDone) {
                    val ib = codec.dequeueInputBuffer(10_000)
                    if (ib >= 0) {
                        val sz = ex.readSampleData(codec.getInputBuffer(ib)!!, 0)
                        if (sz < 0) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ib, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatEnc = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == 4 /* ENCODING_PCM_FLOAT */
                    }
                    ob >= 0 -> {
                        val buf = codec.getOutputBuffer(ob)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val cnt = if (floatEnc) info.size / 4 else info.size / 2
                        if (n + cnt > out.size) out = out.copyOf(maxOf(out.size * 2, n + cnt))
                        if (floatEnc) { val fb = buf.asFloatBuffer(); fb.get(out, n, cnt) }
                        else { val sb = buf.asShortBuffer(); for (i in 0 until cnt) out[n + i] = sb.get() / 32768f }
                        n += cnt
                        codec.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
        } finally { codec.stop(); codec.release(); ex.release() }
        return Pcm(sr, ch, out.copyOf(n - n % ch))
    }

    /** Взвешенный sinc (окно Ханна, 16 нулей) с таблицей ядра: качество ≫ линейной интерполяции. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        val ratio = to.toDouble() / from
        val cutoff = min(1.0, ratio) * 0.97
        val zeros = 16
        val half = zeros / min(1.0, ratio)
        val res = 512
        val tab = FloatArray((2 * half * res).toInt() + 2) { i ->
            val u = i.toDouble() / res - half
            val a = PI * cutoff * u
            val s = if (u == 0.0) 1.0 else sin(a) / a
            (cutoff * s * (0.5 + 0.5 * cos(PI * u / half))).toFloat()
        }
        val nOut = floor(x.size * ratio).toInt()
        val out = FloatArray(nOut)
        for (i in 0 until nOut) {
            val t = i / ratio
            val j0 = ceil(t - half).toInt()
            val j1 = floor(t + half).toInt()
            var acc = 0f
            for (j in maxOf(j0, 0)..min(j1, x.size - 1)) {
                val pos = (t - j + half) * res
                val k = pos.toInt()
                val fr = (pos - k).toFloat()
                acc += x[j] * (tab[k] * (1f - fr) + tab[k + 1] * fr)
            }
            out[i] = acc
        }
        return out
    }

    private fun wavHeader(dataBytes: Long, sr: Int, ch: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort()).putInt(sr)
        b.putInt(sr * ch * 2).putShort((ch * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }

    fun floatToPcm16(x: FloatArray, off: Int, len: Int, dst: ByteArray) {
        val bb = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until len) bb.putShort(i * 2, softClip(x[off + i]).times(32767f).toInt().toShort())
    }

    private fun softClip(v: Float): Float {
        val a = kotlin.math.abs(v)
        if (a <= 0.95f) return v
        val s = 0.95f + 0.05f * kotlin.math.tanh((a - 0.95f) / 0.05f)
        return if (v < 0) -s else s
    }

    /** Сырой PCM16 (моно) -> WAV. */
    fun rawToWav(raw: File, sr: Int, dest: File) {
        dest.outputStream().buffered().use { o ->
            o.write(wavHeader(raw.length(), sr, 1))
            raw.inputStream().use { it.copyTo(o) }
        }
    }

    /** Вокал (PCM16 моно из файла) + минус (стерео float) -> стерео WAV с регулировкой громкости. */
    fun mixToWav(vocalRaw: File, minus: Pcm, sr: Int, vocalGain: Float, minusGain: Float, dest: File) {
        val l = resample(minus.channel(0), minus.sampleRate, sr)
        val r = if (minus.channels > 1) resample(minus.channel(1), minus.sampleRate, sr) else l
        val vocalN = (vocalRaw.length() / 2).toInt()
        val total = maxOf(vocalN, l.size)
        dest.outputStream().buffered().use { o ->
            o.write(wavHeader(total * 4L, sr, 2))
            val vin = RandomAccessFile(vocalRaw, "r")
            val vb = ByteArray(1 shl 16)
            val vbb = ByteBuffer.wrap(vb).order(ByteOrder.LITTLE_ENDIAN)
            val ob = ByteArray(1 shl 17)
            val obb = ByteBuffer.wrap(ob).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < total) {
                val cnt = min(vb.size / 2, total - i)
                val got = if (i < vocalN) vin.read(vb, 0, min(cnt, vocalN - i) * 2) / 2 else 0
                obb.clear()
                for (k in 0 until cnt) {
                    val v = if (k < got) vbb.getShort(k * 2) / 32768f * vocalGain else 0f
                    val a = if (i + k < l.size) l[i + k] * minusGain else 0f
                    val b = if (i + k < r.size) r[i + k] * minusGain else 0f
                    obb.putShort(softClip(a + v).times(32767f).toInt().toShort())
                    obb.putShort(softClip(b + v).times(32767f).toInt().toShort())
                }
                o.write(ob, 0, cnt * 4)
                i += cnt
            }
            vin.close()
        }
    }
}
