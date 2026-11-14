package dev.ronova.celestia.core.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

interface F0Extractor : AutoCloseable {
    /** x — 16 кГц; возвращает [frames] значений F0 (Гц, 0 = безголосо) с шагом 10 мс. */
    fun extract(x: FloatArray, frames: Int): FloatArray
}

private fun fit(f0: FloatArray, frames: Int): FloatArray = FloatArray(frames) { if (it < f0.size) f0[it] else 0f }

/** Салиентность [T,360] -> F0: локальное среднее по 9 бинам вокруг максимума (как в RVC/torchfcpe). */
private fun decodeSalience(sal: FloatArray, cents: (Int) -> Double, thr: Float): FloatArray {
    val bins = 360
    return FloatArray(sal.size / bins) { r ->
        val b = r * bins
        var mi = 0; var mv = -Float.MAX_VALUE
        for (i in 0 until bins) if (sal[b + i] > mv) { mv = sal[b + i]; mi = i }
        if (mv <= thr) 0f else {
            var num = 0.0; var den = 0.0
            for (i in max(0, mi - 4) until min(bins, mi + 5)) { num += sal[b + i] * cents(i); den += sal[b + i] }
            (10.0 * 2.0.pow(num / den / 1200.0)).toFloat()
        }
    }
}

/**
 * RMVPE и FCPE (ONNX со встроенным мел-фронтендом): вход audio [1,N] (16 кГц), выход salience [1,T,360],
 * T = N/160 + 1. Если выход не 360-бинный — считаем, что это уже F0 в Гц.
 */
class SalienceF0(path: String, private val fcpe: Boolean, threads: Int, xnn: Boolean) : F0Extractor {
    private val s: OrtSession = Ort.open(path, threads, xnn)
    private val rank = (s.inputInfo.values.first().info as TensorInfo).shape.size

    override fun extract(x: FloatArray, frames: Int): FloatArray {
        val t = if (rank == 3) Ort.f32(x, 1, 1, x.size.toLong()) else Ort.f32(x, 1, x.size.toLong())
        t.use {
            s.run(mapOf(s.inputNames.first() to t)).use { res ->
                val o = res.get(0) as OnnxTensor
                val v = Ort.floats(o)
                if (Ort.shapeOf(o).last() != 360L) return fit(v, frames)
                val f = if (fcpe) {
                    // 360 бинов равномерно в центах между 32.7 и 1975.5 Гц
                    val c0 = 1200 * log2(32.70 / 10.0); val c1 = 1200 * log2(1975.5 / 10.0)
                    decodeSalience(v, { c0 + (c1 - c0) * it / 359.0 }, 0.006f)
                } else decodeSalience(v, { 20.0 * it + 1997.3794084376191 }, 0.03f)
                return fit(f, frames)
            }
        }
    }

    override fun close() = s.close()
}
