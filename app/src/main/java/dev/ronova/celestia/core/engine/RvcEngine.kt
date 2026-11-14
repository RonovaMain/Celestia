package dev.ronova.celestia.core.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import dev.ronova.celestia.core.audio.Dsp
import dev.ronova.celestia.core.faiss.FaissIndex
import java.io.File
import java.util.Random
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** ContentVec / HuBERT (v2: 768 каналов, слой 12), вход 16 кГц. */
class ContentEncoder(path: String, threads: Int, xnn: Boolean) : AutoCloseable {
    private val s: OrtSession = Ort.open(path, threads, xnn)
    private val inputRank = (s.inputInfo.values.first().info as TensorInfo).shape.size

    /** Возвращает (признаки [F*768], F). */
    fun encode(x: FloatArray): Pair<FloatArray, Int> {
        val t = if (inputRank == 3) Ort.f32(x, 1, 1, x.size.toLong()) else Ort.f32(x, 1, x.size.toLong())
        t.use {
            s.run(mapOf(s.inputNames.first() to t)).use { res ->
                val o = res.get(0) as OnnxTensor
                val sh = Ort.shapeOf(o)
                val v = Ort.floats(o)
                check(sh.size == 3) { "Неожиданная форма выхода энкодера: ${sh.toList()}" }
                if (sh[2] == 768L) return v to sh[1].toInt()
                check(sh[1] == 768L) { "Энкодер выдаёт ${sh.toList()}, нужны 768 каналов (ContentVec v2)" }
                val f = sh[2].toInt()
                val tr = FloatArray(v.size)
                for (c in 0 until 768) for (i in 0 until f) tr[i * 768 + c] = v[c * f + i]
                return tr to f
            }
        }
    }

    override fun close() = s.close()
}

/** Генератор net_g: шаблонный граф + внешний weights.bin. */
class Generator(dir: File, threads: Int, xnn: Boolean) : AutoCloseable {
    private val s: OrtSession = Ort.open(File(dir, "model.onnx").absolutePath, threads, xnn)
    private val rnd = Random()

    fun run(phone: FloatArray, pitch: LongArray, pitchf: FloatArray, p: Int, temperature: Float): FloatArray {
        val noise = FloatArray(192 * p) { (rnd.nextGaussian() * temperature).toFloat() }
        val ts = ArrayList<OnnxTensor>()
        try {
            val known = mapOf(
                "phone" to Ort.f32(phone, 1, p.toLong(), 768),
                "phone_lengths" to Ort.i64(longArrayOf(p.toLong()), 1),
                "pitch" to Ort.i64(pitch, 1, p.toLong()),
                "pitchf" to Ort.f32(pitchf, 1, p.toLong()),
                "ds" to Ort.i64(longArrayOf(0), 1),
                "rnd" to Ort.f32(noise, 1, 192, p.toLong()),
            )
            ts.addAll(known.values)
            val order = known.values.toList()
            val feeds = HashMap<String, OnnxTensor>()
            s.inputNames.forEachIndexed { i, n -> feeds[n] = known[n] ?: order[i] }
            s.run(feeds).use { res -> return Ort.floats(res.get(0) as OnnxTensor) }
        } finally { ts.forEach { it.close() } }
    }

    override fun close() = s.close()
}

class RvcEngine(
    private val voiceDir: File,
    private val engineDir: File,
    private val p: Params,
    private val tgtSr: Int,
) : AutoCloseable {
    private val enc = ContentEncoder(File(engineDir, "hubert.onnx").absolutePath, p.threads, p.xnnpack)
    private val f0: F0Extractor = when (p.f0Mode) {
        F0Mode.RMVPE -> SalienceF0(File(engineDir, "rmvpe.onnx").absolutePath, false, p.threads, p.xnnpack)
        F0Mode.FCPE -> SalienceF0(File(engineDir, "fcpe.onnx").absolutePath, true, p.threads, p.xnnpack)
    }
    private val gen = Generator(voiceDir, p.threads, p.xnnpack)
    private val index: FaissIndex? =
        File(voiceDir, "voice.index").takeIf { p.indexRate > 0f && it.exists() }?.let { FaissIndex.load(it) }

    private val melMin = 1127.0 * ln(1 + 50.0 / 700)
    private val melMax = 1127.0 * ln(1 + 1100.0 / 700)
    private fun coarse(f: Float): Long {
        var m = 1127.0 * ln(1 + f / 700.0)
        if (m > 0) m = (m - melMin) * 254 / (melMax - melMin) + 1
        return m.coerceIn(1.0, 255.0).roundToLong()
    }

    /** chunk16k.size кратен 320. Возвращает звук на частоте модели длиной size*tgtSr/16000. */
    fun convert(chunk16k: FloatArray): FloatArray {
        val n = chunk16k.size
        val frames = n / 160
        // 160 отсчётов с каждой стороны => ровно n/320 кадров энкодера (50 fps)
        val (feat, fCount) = enc.encode(Dsp.reflectPad(chunk16k, 160))
        val pitchHz = f0.extract(chunk16k, frames)
        val ratio = 2.0.pow(p.pitchShift / 12.0).toFloat()
        for (i in pitchHz.indices) pitchHz[i] *= ratio

        val keepOrig = p.protect < 0.5f
        val feat0 = if (keepOrig) feat.copyOf() else null
        index?.let { if (p.indexRate > 0f) it.blend(feat, fCount, p.indexRate, p.threads) }

        val pLen = min(fCount * 2, frames)
        val phone = FloatArray(pLen * 768)
        for (t in 0 until pLen) {
            val src = min(t / 2, fCount - 1) * 768        // ×2 «nearest», как F.interpolate(scale_factor=2)
            val voiced = pitchHz[t] > 0f
            if (feat0 != null && !voiced) {
                val pr = p.protect                         // безголосые кадры: тянем к исходным признакам
                for (c in 0 until 768) phone[t * 768 + c] = feat[src + c] * pr + feat0[src + c] * (1f - pr)
            } else System.arraycopy(feat, src, phone, t * 768, 768)
        }
        val pitch = LongArray(pLen) { coarse(pitchHz[it]) }
        val pitchf = pitchHz.copyOf(pLen)
        val audio = gen.run(phone, pitch, pitchf, pLen, p.temperature)

        val want = (n.toLong() * tgtSr / 16000).toInt()
        val out = if (audio.size == want) audio else audio.copyOf(want)
        Dsp.rmsMix(chunk16k, out, tgtSr, p.rmsMixRate)
        return out
    }

    override fun close() {
        runCatching { enc.close() }; runCatching { f0.close() }; runCatching { gen.close() }
    }
}
