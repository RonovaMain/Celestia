package dev.ronova.celestia.core.sep

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import dev.ronova.celestia.core.engine.Ort
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos

/**
 * STFT/iSTFT как в UVR (torch.stft: center=True, режим reflect, окно Хэнна periodic; torch.istft с
 * нормировкой на огибающую окна). Спектр обрезается до DIM_F бинов. Кадры считаются в нескольких потоках.
 */
class Stft(threads: Int) : AutoCloseable {
    companion object {
        const val N_FFT = 7680
        const val HOP = 1024
        const val DIM_F = 3072
        const val DIM_T = 256
        const val CHUNK = HOP * (DIM_T - 1)      // 261120 отсчётов = 5,92 с при 44,1 кГц
        const val PAD = N_FFT / 2
    }

    private val fft = Fft(N_FFT)
    private val win = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2.0 * PI * it / N_FFT) }
    private val wsum = FloatArray(CHUNK + N_FFT)
    private val frames = FloatArray(DIM_T * N_FFT)
    private val acc = FloatArray(CHUNK + N_FFT)
    private val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 8))
    private val works = Array(threads.coerceIn(1, 8)) { fft.newWork() }
    private val bufRe = Array(works.size) { DoubleArray(N_FFT) }
    private val bufIm = Array(works.size) { DoubleArray(N_FFT) }

    init {
        for (t in 0 until DIM_T) for (n in 0 until N_FFT) wsum[t * HOP + n] += (win[n] * win[n]).toFloat()
    }

    private fun par(n: Int, body: (worker: Int, from: Int, to: Int) -> Unit) {
        val parts = minOf(works.size, n)
        val per = (n + parts - 1) / parts
        val tasks = (0 until parts).map { p ->
            Callable { body(p, p * per, minOf(n, (p + 1) * per)) }
        }
        pool.invokeAll(tasks).forEach { it.get() }
    }

    private fun refl(i: Int): Int = if (i < 0) -i else if (i >= CHUNK) 2 * (CHUNK - 1) - i else i

    /** x: CHUNK отсчётов -> dst[reOff + f*DIM_T + t], dst[imOff + f*DIM_T + t]. */
    fun forward(x: FloatArray, dst: FloatArray, reOff: Int, imOff: Int) {
        par(DIM_T) { w, from, to ->
            val re = bufRe[w]
            val im = bufIm[w]
            for (t in from until to) {
                val base = t * HOP - PAD
                for (n in 0 until N_FFT) { re[n] = x[refl(base + n)] * win[n]; im[n] = 0.0 }
                fft.forward(re, im, works[w])
                for (f in 0 until DIM_F) {
                    dst[reOff + f * DIM_T + t] = re[f].toFloat()
                    dst[imOff + f * DIM_T + t] = im[f].toFloat()
                }
            }
        }
    }

    /** Обратное: спектр -> CHUNK отсчётов (бины выше DIM_F — нули). */
    fun inverse(src: FloatArray, reOff: Int, imOff: Int, out: FloatArray) {
        par(DIM_T) { w, from, to ->
            val re = bufRe[w]
            val im = bufIm[w]
            for (t in from until to) {
                java.util.Arrays.fill(re, 0.0)
                java.util.Arrays.fill(im, 0.0)
                for (k in 0 until DIM_F) {
                    val a = src[reOff + k * DIM_T + t].toDouble()
                    val b = src[imOff + k * DIM_T + t].toDouble()
                    re[k] = a
                    im[k] = -b                       // сразу сопряжение: ifft(x) = conj(fft(conj(x)))/N
                }
                im[0] = 0.0
                for (k in 1 until N_FFT / 2) { re[N_FFT - k] = re[k]; im[N_FFT - k] = -im[k] }
                fft.forward(re, im, works[w])
                val o = t * N_FFT
                for (n in 0 until N_FFT) frames[o + n] = (re[n] / N_FFT * win[n]).toFloat()
            }
        }
        java.util.Arrays.fill(acc, 0f)
        for (t in 0 until DIM_T) {
            val o = t * N_FFT
            val b = t * HOP
            for (n in 0 until N_FFT) acc[b + n] += frames[o + n]
        }
        for (i in 0 until CHUNK) {
            val ws = wsum[i + PAD]
            out[i] = if (ws > 1e-8f) acc[i + PAD] / ws else 0f
        }
    }

    override fun close() { pool.shutdownNow() }
}

/** MDX-Net (UVR): ONNX принимает [1,4,3072,256] = (L.re, L.im, R.re, R.im), отдаёт то же самое для вокала. */
class MdxEngine(
    modelPath: String,
    threads: Int,
    lowMem: Boolean,
    private val compensate: Float,
    private val denoise: Boolean,
) : AutoCloseable {
    companion object {
        const val SR = 44100
        const val CHUNK = Stft.CHUNK
        const val TRIM = Stft.N_FFT / 2
        const val GEN = CHUNK - 2 * TRIM                // полезная часть куска: края STFT отбрасываются
    }

    private val session: OrtSession = Ort.open(modelPath, threads, false, arena = !lowMem)
    private val inName = session.inputNames.first()
    private val stft = Stft(threads)
    private val size = 4 * Stft.DIM_F * Stft.DIM_T
    private val spec = FloatArray(size)
    private val neg = if (denoise) FloatArray(size) else null
    private val plane = Stft.DIM_F * Stft.DIM_T

    init {
        val sh = (session.inputInfo.values.first().info as TensorInfo).shape
        require(sh.size == 4 && sh[1] == 4L && sh[2] == Stft.DIM_F.toLong() && sh[3] == Stft.DIM_T.toLong()) {
            "Это не MDX-Net модель формата 4×3072×256 (форма входа: ${sh.toList()})"
        }
    }

    private fun run(x: FloatArray): FloatArray {
        val t = OnnxTensor.createTensor(Ort.env, FloatBuffer.wrap(x), longArrayOf(1, 4, Stft.DIM_F.toLong(), Stft.DIM_T.toLong()))
        t.use {
            session.run(mapOf(inName to t)).use { res ->
                return Ort.floats(res.get(0) as OnnxTensor)
            }
        }
    }

    /** Вокал для куска стерео (CHUNK отсчётов на канал) -> [левый, правый], уже с компенсацией. */
    fun vocals(l: FloatArray, r: FloatArray): Array<FloatArray> {
        stft.forward(l, spec, 0, plane)
        stft.forward(r, spec, 2 * plane, 3 * plane)
        for (c in 0 until 4) java.util.Arrays.fill(spec, c * plane, c * plane + 3 * Stft.DIM_T, 0f)   // UVR обнуляет 3 нижних бина

        val pred: FloatArray
        if (denoise) {
            val n = neg!!
            for (i in 0 until size) n[i] = -spec[i]
            val a = run(spec)
            val b = run(n)
            for (i in 0 until size) a[i] = 0.5f * a[i] - 0.5f * b[i]
            pred = a
        } else pred = run(spec)

        val outL = FloatArray(CHUNK)
        val outR = FloatArray(CHUNK)
        stft.inverse(pred, 0, plane, outL)
        stft.inverse(pred, 2 * plane, 3 * plane, outR)
        for (i in 0 until CHUNK) { outL[i] *= compensate; outR[i] *= compensate }
        return arrayOf(outL, outR)
    }

    override fun close() {
        runCatching { stft.close() }
        runCatching { session.close() }
    }
}
