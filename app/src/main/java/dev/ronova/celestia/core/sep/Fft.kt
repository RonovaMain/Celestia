package dev.ronova.celestia.core.sep

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * БПФ смешанного основания (размер — любое число с небольшими простыми множителями, например 7680 = 2^9·3·5).
 * Алгоритм проверен против numpy.fft (ошибка ~1e-12). Объект неизменяем и потокобезопасен; рабочие
 * массивы каждый поток берёт свои (Work).
 */
class Fft(val n: Int) {
    private val factors: IntArray
    private val cs = DoubleArray(n)
    private val sn = DoubleArray(n)
    val maxFactor: Int

    init {
        val f = ArrayList<Int>()
        var m = n
        var p = 2
        while (m > 1) {
            while (m % p == 0) { f.add(p); m /= p }
            p++
        }
        factors = f.toIntArray()
        maxFactor = factors.maxOrNull() ?: 1
        for (j in 0 until n) {
            cs[j] = cos(2.0 * PI * j / n)
            sn[j] = -sin(2.0 * PI * j / n)      // W = exp(-2πi·j/n)
        }
    }

    class Work(n: Int, maxP: Int) {
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        val tr = DoubleArray(maxP)
        val ti = DoubleArray(maxP)
    }

    fun newWork() = Work(n, maxFactor)

    /** Прямое преобразование на месте. */
    fun forward(re: DoubleArray, im: DoubleArray, w: Work) {
        rec(re, im, 0, 1, w.outRe, w.outIm, 0, n, 0, w.tr, w.ti)
        System.arraycopy(w.outRe, 0, re, 0, n)
        System.arraycopy(w.outIm, 0, im, 0, n)
    }

    private fun rec(
        ir: DoubleArray, ii: DoubleArray, io: Int, st: Int,
        orr: DoubleArray, oi: DoubleArray, oo: Int, len: Int, fi: Int,
        tr: DoubleArray, ti: DoubleArray,
    ) {
        if (len == 1) { orr[oo] = ir[io]; oi[oo] = ii[io]; return }
        val p = factors[fi]
        val m = len / p
        for (q in 0 until p) rec(ir, ii, io + q * st, st * p, orr, oi, oo + q * m, m, fi + 1, tr, ti)
        val step = n / len
        for (k in 0 until m) {
            for (q in 0 until p) {
                val idx = q * k * step
                val a = orr[oo + q * m + k]
                val b = oi[oo + q * m + k]
                val wr = cs[idx]
                val wi = sn[idx]
                tr[q] = a * wr - b * wi
                ti[q] = a * wi + b * wr
            }
            for (r in 0 until p) {
                var sr = 0.0
                var si = 0.0
                for (q in 0 until p) {
                    val j = ((q * r) % p) * m * step
                    val wr = cs[j]
                    val wi = sn[j]
                    sr += tr[q] * wr - ti[q] * wi
                    si += tr[q] * wi + ti[q] * wr
                }
                orr[oo + k + r * m] = sr
                oi[oo + k + r * m] = si
            }
        }
    }
}
