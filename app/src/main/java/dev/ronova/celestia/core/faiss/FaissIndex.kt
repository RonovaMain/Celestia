package dev.ronova.celestia.core.faiss

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max

/** Чтение little-endian примитивов из потока (формат FAISS нативный LE). */
internal class LE(private val s: InputStream) {
    private val tmp = ByteArray(8)
    private fun fill(n: Int) {
        var r = 0
        while (r < n) { val c = s.read(tmp, r, n - r); if (c < 0) throw EOFException(); r += c }
    }
    fun int(): Int { fill(4); return ByteBuffer.wrap(tmp, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int }
    fun long(): Long { fill(8); return ByteBuffer.wrap(tmp, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long }
    fun byte(): Int { fill(1); return tmp[0].toInt() and 0xFF }
    fun float(): Float { fill(4); return ByteBuffer.wrap(tmp, 0, 4).order(ByteOrder.LITTLE_ENDIAN).float }
    fun fourcc(): String { fill(4); return String(tmp, 0, 4, Charsets.ISO_8859_1) }
    fun floats(n: Int): FloatArray {
        val out = FloatArray(n)
        val buf = ByteArray(1 shl 16)
        var i = 0
        while (i < n) {
            val chunk = minOf(n - i, buf.size / 4)
            var r = 0
            while (r < chunk * 4) { val c = s.read(buf, r, chunk * 4 - r); if (c < 0) throw EOFException(); r += c }
            ByteBuffer.wrap(buf, 0, chunk * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out, i, chunk)
            i += chunk
        }
        return out
    }
    fun skip(n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) { if (s.read() < 0) throw EOFException(); left-- } else left -= k
        }
    }
}

/**
 * Индекс RVC (FAISS IndexIVFFlat, L2) целиком в памяти.
 * Поиск как в FAISS: ближайшие [nprobe] ячеек по центроидам, затем точный перебор внутри.
 * Если индекс «плоский» (IndexFlat) — одна ячейка, полный перебор.
 */
class FaissIndex private constructor(
    val dim: Int,
    private val nlist: Int,
    private val centroids: FloatArray,
    private val listStart: IntArray,   // nlist+1, смещения в строках
    private val vectors: FloatArray,   // строки по dim, сгруппированы по ячейкам
    private val norms: FloatArray,
) {
    val count: Int get() = listStart[nlist]

    /** RVC: k=8, вес = 1/dist², смесь = Σw·v / Σw. Результат подмешивается в [feats] с долей [rate]. */
    fun blend(feats: FloatArray, frames: Int, rate: Float, threads: Int, k: Int = 8, nprobe: Int = 8) {
        require(feats.size == frames * dim) { "Размерность индекса ($dim) не совпала с признаками" }
        val nt = threads.coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(nt)
        try {
            val per = max((frames + nt - 1) / nt, 1)
            val tasks = (0 until frames step per).map { s0 ->
                Callable {
                    val e0 = minOf(frames, s0 + per)
                    val best = FloatArray(k); val bestId = IntArray(k)
                    val cd = FloatArray(nlist)
                    val acc = FloatArray(dim)
                    for (f in s0 until e0) {
                        val q = f * dim
                        search(feats, q, k, nprobe, best, bestId, cd)
                        java.util.Arrays.fill(acc, 0f)
                        var wsum = 0f
                        for (j in 0 until k) {
                            if (bestId[j] < 0) continue
                            val w = 1f / max(best[j], 1e-6f).let { it * it }
                            wsum += w
                            val base = bestId[j] * dim
                            for (c in 0 until dim) acc[c] += w * vectors[base + c]
                        }
                        if (wsum > 0f) for (c in 0 until dim) feats[q + c] = feats[q + c] * (1f - rate) + acc[c] / wsum * rate
                    }
                }
            }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally { pool.shutdown() }
    }

    private fun search(x: FloatArray, q: Int, k: Int, nprobe: Int, bestD: FloatArray, bestId: IntArray, cd: FloatArray) {
        java.util.Arrays.fill(bestD, Float.MAX_VALUE); java.util.Arrays.fill(bestId, -1)
        var qn = 0f
        for (c in 0 until dim) qn += x[q + c] * x[q + c]
        val probes: IntArray
        if (nlist == 1) probes = intArrayOf(0) else {
            for (l in 0 until nlist) {
                var d = 0f; val b = l * dim
                for (c in 0 until dim) { val t = x[q + c] - centroids[b + c]; d += t * t }
                cd[l] = d
            }
            val np = minOf(nprobe, nlist)
            val idx = (0 until nlist).sortedBy { cd[it] }.take(np)
            probes = idx.toIntArray()
        }
        for (l in probes) for (r in listStart[l] until listStart[l + 1]) {
            var dot = 0f; val b = r * dim
            for (c in 0 until dim) dot += x[q + c] * vectors[b + c]
            val d = qn - 2f * dot + norms[r]
            if (d < bestD[k - 1]) {
                var j = k - 1
                while (j > 0 && bestD[j - 1] > d) { bestD[j] = bestD[j - 1]; bestId[j] = bestId[j - 1]; j-- }
                bestD[j] = d; bestId[j] = r
            }
        }
    }

    companion object {
        fun load(file: File): FaissIndex = BufferedInputStream(file.inputStream(), 1 shl 16).use { read(LE(it)) }

        private fun header(r: LE): Pair<Int, Long> {
            val d = r.int(); val ntotal = r.long()
            r.long(); r.long()           // dummy1, dummy2
            r.byte()                     // is_trained
            val metric = r.int()
            if (metric > 1) r.float()    // metric_arg
            return d to ntotal
        }

        /** Вектор FAISS: size_t n, затем n элементов. Старые версии: float, новые: байты кодов. */
        private fun flatBody(r: LE, d: Int, ntotal: Long): FloatArray {
            val n = r.long()
            val cnt = d.toLong() * ntotal
            return when (n) {
                cnt -> r.floats(cnt.toInt())
                cnt * 4 -> r.floats(cnt.toInt())
                else -> error("Неожиданный размер векторов в индексе ($n)")
            }
        }

        private fun read(r: LE): FaissIndex {
            when (val cc = r.fourcc()) {
                "IxF2", "IxFl", "IxFI" -> {
                    val (d, n) = header(r)
                    val data = flatBody(r, d, n)
                    return build(d, 1, FloatArray(d), intArrayOf(0, n.toInt()), data)
                }
                "IwFl" -> return readIvfFlat(r)
                else -> error("Тип индекса «$cc» не поддержан: нужен IVF,Flat (как создаёт RVC)")
            }
        }

        private fun readIvfFlat(r: LE): FaissIndex {
            val (d, ntotal) = header(r)
            val nlist = r.long().toInt()
            r.long()                                     // nprobe
            // quantizer: вложенный плоский индекс с центроидами
            val qcc = r.fourcc()
            check(qcc.startsWith("IxF")) { "Квантайзер «$qcc» не поддержан" }
            val (qd, qn) = header(r)
            val centroids = flatBody(r, qd, qn)
            // direct map
            val dmType = r.byte()
            r.skip(r.long() * 8)
            if (dmType == 2) r.skip(r.long() * 16)
            // invlists
            val ilcc = r.fourcc()
            check(ilcc == "ilar") { "Списки «$ilcc» не поддержаны (нужен ArrayInvertedLists)" }
            val nl = r.long().toInt(); val codeSize = r.long().toInt()
            check(nl == nlist && codeSize == d * 4) { "Индекс не Flat-типа (code_size=$codeSize)" }
            val kind = r.fourcc()
            val nSizes = r.long().toInt()
            val sizes = IntArray(nlist)
            if (kind == "full") for (i in 0 until nSizes) sizes[i] = r.long().toInt()
            else if (kind == "sprs") { var i = 0; while (i < nSizes) { val li = r.long().toInt(); sizes[li] = r.long().toInt(); i += 2 } }
            else error("Неизвестная раскладка списков «$kind»")
            val start = IntArray(nlist + 1)
            for (i in 0 until nlist) start[i + 1] = start[i] + sizes[i]
            val total = start[nlist]
            check(total.toLong() == ntotal) { "ntotal=$ntotal, в списках $total" }
            val vec = FloatArray(total * d)
            for (l in 0 until nlist) if (sizes[l] > 0) {
                val part = r.floats(sizes[l] * d)
                System.arraycopy(part, 0, vec, start[l] * d, part.size)
                r.skip(sizes[l].toLong() * 8)            // ids нам не нужны: смешиваем сами векторы
            }
            return build(d, nlist, centroids, start, vec)
        }

        private fun build(d: Int, nlist: Int, cent: FloatArray, start: IntArray, vec: FloatArray): FaissIndex {
            val n = start[nlist]
            val norms = FloatArray(n)
            for (i in 0 until n) { var s = 0f; for (c in 0 until d) s += vec[i * d + c] * vec[i * d + c]; norms[i] = s }
            return FaissIndex(d, nlist, cent, start, vec, norms)
        }
    }
}
