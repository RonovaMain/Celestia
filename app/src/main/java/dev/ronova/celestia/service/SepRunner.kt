package dev.ronova.celestia.service

import dev.ronova.celestia.R
import dev.ronova.celestia.tr

import android.content.Context
import dev.ronova.celestia.Store
import dev.ronova.celestia.core.audio.AudioIO
import dev.ronova.celestia.core.audio.Dsp
import dev.ronova.celestia.core.audio.Wav
import dev.ronova.celestia.core.sep.MdxEngine
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Разделение трека на вокал и минус (MDX-Net из UVR). Модель загружается только на время работы:
 * кусок ~5,9 с -> STFT -> ONNX -> iSTFT; минус = исходник − вокал. Результат пишется на диск потоком,
 * в памяти живёт только исходник (стерео 44,1 кГц) и один кусок.
 */
object SepRunner {
    private const val SR = MdxEngine.SR

    private class Sink(private val os: OutputStream) {
        private val buf = ByteArray(1 shl 16)
        private var n = 0
        fun put(l: Float, r: Float) {
            if (n + 4 > buf.size) flush()
            val a = Wav.toPcm16(l)
            val b = Wav.toPcm16(r)
            buf[n] = a.toByte(); buf[n + 1] = (a shr 8).toByte()
            buf[n + 2] = b.toByte(); buf[n + 3] = (b shr 8).toByte()
            n += 4
        }
        fun flush() { os.write(buf, 0, n); n = 0 }
    }

    private fun load(ctx: Context, spec: SepSpec): Pair<FloatArray, FloatArray> {
        val pcm = AudioIO.decode(ctx, spec.input) { JobBus.cancel }
        val l = AudioIO.resample(pcm.channel(0), pcm.sampleRate, SR)
        val r = if (pcm.channels > 1) AudioIO.resample(pcm.channel(1), pcm.sampleRate, SR) else l
        return l to r
    }

    fun run(ctx: Context, store: Store, spec: SepSpec, report: (Float, String) -> Unit): File {
        val cancelled = { JobBus.cancel }
        val modelFile = store.engineFile(spec.model.kind)
        require(modelFile.exists()) { tr(R.string.vm_no_model, tr(spec.model.kind.title)) }

        report(0.01f, tr(R.string.job_decode_track))
        val (left, right) = load(ctx, spec)
        val total = left.size
        require(total >= SR) { tr(R.string.err_track_short) }
        check(!cancelled()) { tr(R.string.job_cancelled) }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = spec.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(60).ifBlank { "track" }
        val tmpDir = File(store.stemsDir, ".tmp-$stamp").apply { deleteRecursively(); mkdirs() }
        var finalDir: File? = null
        try {
            val gen = MdxEngine.GEN
            val trim = MdxEngine.TRIM
            val chunk = MdxEngine.CHUNK
            val ovl = if (spec.smooth) SR else 0              // 1 с плавного стыка между кусками
            val step = gen - ovl
            val n = if (total <= gen) 1 else (total - gen + step - 1) / step + 1

            val vOut = File(tmpDir, "vocals.wav").outputStream().buffered(1 shl 17)
            val iOut = File(tmpDir, "instrumental.wav").outputStream().buffered(1 shl 17)
            vOut.write(Wav.header(total * 4L, SR, 2))
            iOut.write(Wav.header(total * 4L, SR, 2))
            val voc = Sink(vOut)
            val ins = Sink(iOut)
            var pos = 0
            fun emit(vl: Float, vr: Float) {
                voc.put(vl, vr)
                ins.put(left[pos] - vl, right[pos] - vr)
                pos++
            }

            val cl = FloatArray(chunk)
            val cr = FloatArray(chunk)
            val tailL = FloatArray(ovl)
            val tailR = FloatArray(ovl)
            val t0 = System.nanoTime()

            report(0.06f, tr(R.string.job_loading_model))
            MdxEngine(modelFile.absolutePath, spec.threads, spec.lowMem, spec.model.compensate, spec.denoise).use { eng ->
                for (i in 0 until n) {
                    check(!cancelled()) { tr(R.string.job_cancelled) }
                    val start = i * step
                    val s0 = start - trim
                    java.util.Arrays.fill(cl, 0f)
                    java.util.Arrays.fill(cr, 0f)
                    val lo = maxOf(0, s0)
                    val hi = minOf(total, s0 + chunk)
                    if (hi > lo) {
                        System.arraycopy(left, lo, cl, lo - s0, hi - lo)
                        System.arraycopy(right, lo, cr, lo - s0, hi - lo)
                    }
                    val out = eng.vocals(cl, cr)
                    val vl = out[0]
                    val vr = out[1]
                    val valid = minOf(gen, total - start)
                    val hasNext = start + gen < total

                    var k = 0
                    if (i > 0 && ovl > 0) {
                        for (q in 0 until ovl) {
                            val fi = Dsp.fadeIn(q, ovl)
                            emit(tailL[q] * (1f - fi) + vl[trim + q] * fi, tailR[q] * (1f - fi) + vr[trim + q] * fi)
                        }
                        k = ovl
                    }
                    val bodyEnd = if (hasNext) gen - ovl else valid
                    for (q in k until bodyEnd) emit(vl[trim + q], vr[trim + q])
                    if (hasNext && ovl > 0) {
                        System.arraycopy(vl, trim + gen - ovl, tailL, 0, ovl)
                        System.arraycopy(vr, trim + gen - ovl, tailR, 0, ovl)
                    }

                    val done = (pos.toFloat() / total).coerceIn(0f, 1f)
                    val el = (System.nanoTime() - t0) / 1e9
                    val eta = if (i >= 1) (el / (i + 1) * (n - i - 1)).toInt() else -1
                    report(0.08f + 0.9f * done, if (eta >= 0) tr(R.string.sep_chunk_eta, i + 1, n, eta / 60, eta % 60) else tr(R.string.sep_chunk, i + 1, n))
                }
            }
            check(pos == total) { "Internal error: wrote $pos of $total samples" }
            voc.flush(); ins.flush()
            vOut.close(); iOut.close()

            val meta = JSONObject()
                .put("name", spec.name.substringBeforeLast('.').ifBlank { safe })
                .put("model", tr(spec.model.title).substringBefore(" ·"))
                .put("created", System.currentTimeMillis())
                .put("seconds", total / SR)
            File(tmpDir, "meta.json").writeText(meta.toString())

            var dest = File(store.stemsDir, safe.substringBeforeLast('.').ifBlank { safe })
            var c = 1
            while (dest.exists()) dest = File(store.stemsDir, safe.substringBeforeLast('.').ifBlank { safe } + " (${c++})")
            check(tmpDir.renameTo(dest)) { tr(R.string.err_save_result) }
            finalDir = dest
            report(1f, tr(R.string.job_done))
            return dest
        } finally {
            if (finalDir == null) tmpDir.deleteRecursively()
        }
    }
}
