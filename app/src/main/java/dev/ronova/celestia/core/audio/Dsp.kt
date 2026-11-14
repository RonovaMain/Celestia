package dev.ronova.celestia.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object Dsp {
    /** Среднеквадратичная огибающая как librosa.feature.rms(frame=sr/2*2, hop=sr/2, center=True). */
    private fun rmsCurve(x: FloatArray, sr: Int): FloatArray {
        val hop = sr / 2
        val frame = hop * 2
        val cum = DoubleArray(x.size + 1)
        for (i in x.indices) cum[i + 1] = cum[i] + x[i].toDouble() * x[i]
        val n = 1 + x.size / hop
        return FloatArray(n) { f ->
            val c = f * hop
            val a = (c - frame / 2).coerceIn(0, x.size)
            val b = (c + frame / 2).coerceIn(0, x.size)
            sqrt(((cum[b] - cum[a]) / frame).toFloat())
        }
    }

    private fun stretch(c: FloatArray, len: Int, i: Int): Float {
        if (c.size == 1 || len == 1) return c[0]
        val pos = i.toDouble() * (c.size - 1) / (len - 1)
        val k = pos.toInt().coerceAtMost(c.size - 2)
        val fr = (pos - k).toFloat()
        return c[k] * (1f - fr) + c[k + 1] * fr
    }

    /**
     * RVC change_rms: rate=1 — динамика модели, rate=0 — громкость исходника.
     * out *= rms_in^(1-rate) * max(rms_out,1e-6)^(rate-1)
     */
    fun rmsMix(input16k: FloatArray, out: FloatArray, outSr: Int, rate: Float) {
        if (rate >= 0.999f || out.isEmpty()) return
        val r1 = rmsCurve(input16k, 16000)
        val r2 = rmsCurve(out, outSr)
        for (i in out.indices) {
            val a = stretch(r1, out.size, i).toDouble()
            val b = max(stretch(r2, out.size, i), 1e-6f).toDouble()
            out[i] = (out[i] * a.pow(1.0 - rate) * b.pow(rate - 1.0)).toFloat()
        }
    }

    /** Отражённое дополнение краёв (как np.pad(mode="reflect")). */
    fun reflectPad(x: FloatArray, pad: Int): FloatArray {
        val out = FloatArray(x.size + 2 * pad)
        System.arraycopy(x, 0, out, pad, x.size)
        for (i in 0 until pad) {
            out[pad - 1 - i] = x[(i + 1).coerceAtMost(x.size - 1)]
            out[pad + x.size + i] = x[(x.size - 2 - i).coerceAtLeast(0)]
        }
        return out
    }

    /** Комплементарные косинусные веса: fadeIn + fadeOut = 1 (суммарная амплитуда сохраняется). */
    fun fadeIn(i: Int, n: Int): Float = (0.5 - 0.5 * cos(PI * (i + 0.5) / n)).toFloat()
}
