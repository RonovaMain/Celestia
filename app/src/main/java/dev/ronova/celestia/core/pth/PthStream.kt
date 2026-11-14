package dev.ronova.celestia.core.pth

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

enum class DType { F32, F16, BF16, F64, OTHER }

class StorageRef(val dtype: DType, val key: String, val numel: Long)

class TensorRef(val storage: StorageRef, val offset: Long, val size: IntArray, val stride: LongArray) {
    val numel: Long get() = size.fold(1L) { a, b -> a * b }
    fun isContiguous(): Boolean {
        var expect = 1L
        for (i in size.indices.reversed()) {
            if (size[i] != 1 && stride[i] != expect) return false
            expect *= size[i]
        }
        return true
    }
}

class GlobalRef(val module: String, val name: String)

/** Заголовок чекпоинта RVC: карта «имя тензора → описание» + метаданные. Сами веса не читаются. */
class PthHeader(
    val tensors: Map<String, TensorRef>,
    val config: List<Any?>?,
    val sr: String?,
    val version: String?,
    val f0: Int?,
)

/** Минимальная виртуальная машина Pickle: достаточно для data.pkl из torch.save (протоколы 2–5). */
private class PickleVM(private val b: ByteArray) {
    private object Mark
    private var p = 0
    private val stack = ArrayList<Any?>()
    private val memo = HashMap<Int, Any?>()

    private fun u8() = b[p++].toInt() and 0xFF
    private fun i32(): Int {
        val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
        p += 4
        return v
    }
    private fun str(n: Int): String { val s = String(b, p, n, Charsets.UTF_8); p += n; return s }
    private fun line(): String {
        val st = p
        while (b[p] != '\n'.code.toByte()) p++
        val s = String(b, st, p - st, Charsets.UTF_8)
        p++
        return s
    }
    private fun longN(n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((b[p + i].toLong() and 0xFF) shl (8 * i))
        if (n in 1..7 && (b[p + n - 1].toInt() and 0x80) != 0) v = v or (-1L shl (8 * n))
        p += n
        return v
    }
    private fun pop(): Any? = stack.removeAt(stack.size - 1)
    private fun popMark(): List<Any?> {
        var i = stack.size - 1
        while (stack[i] !== Mark) i--
        val items = ArrayList(stack.subList(i + 1, stack.size))
        while (stack.size > i) stack.removeAt(stack.size - 1)
        return items
    }

    @Suppress("UNCHECKED_CAST")
    fun run(): Any? {
        while (true) {
            when (val op = u8()) {
                0x80 -> p++                                   // PROTO
                0x95 -> p += 8                                // FRAME
                '.'.code -> return pop()                      // STOP
                '('.code -> stack.add(Mark)
                '0'.code -> pop()
                '2'.code -> stack.add(stack.last())
                'N'.code -> stack.add(null)
                0x88 -> stack.add(true)
                0x89 -> stack.add(false)
                'K'.code -> stack.add(u8())
                'M'.code -> { val lo = u8(); stack.add(lo or (u8() shl 8)) }
                'J'.code -> stack.add(i32())
                0x8a -> stack.add(longN(u8()))
                0x8b -> stack.add(longN(i32()))
                'G'.code -> { stack.add(ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).double); p += 8 }
                'X'.code -> stack.add(str(i32()))
                0x8c -> stack.add(str(u8()))
                'T'.code -> stack.add(str(i32()))
                'U'.code -> stack.add(str(u8()))
                'B'.code -> { val n = i32(); stack.add(b.copyOfRange(p, p + n)); p += n }
                'C'.code -> { val n = u8(); stack.add(b.copyOfRange(p, p + n)); p += n }
                ']'.code -> stack.add(ArrayList<Any?>())
                '}'.code -> stack.add(LinkedHashMap<Any?, Any?>())
                ')'.code -> stack.add(emptyList<Any?>())
                't'.code -> stack.add(popMark())
                0x85 -> { val a = pop(); stack.add(listOf(a)) }
                0x86 -> { val c = pop(); val a = pop(); stack.add(listOf(a, c)) }
                0x87 -> { val c = pop(); val m = pop(); val a = pop(); stack.add(listOf(a, m, c)) }
                'a'.code -> { val v = pop(); (stack.last() as MutableList<Any?>).add(v) }
                'e'.code -> { val items = popMark(); (stack.last() as MutableList<Any?>).addAll(items) }
                's'.code -> { val v = pop(); val k = pop(); (stack.last() as MutableMap<Any?, Any?>)[k] = v }
                'u'.code -> {
                    val items = popMark()
                    val m = stack.last() as MutableMap<Any?, Any?>
                    var i = 0
                    while (i + 1 < items.size) { m[items[i]] = items[i + 1]; i += 2 }
                }
                'q'.code -> memo[u8()] = stack.last()
                'r'.code -> memo[i32()] = stack.last()
                0x94 -> memo[memo.size] = stack.last()
                'h'.code -> stack.add(memo[u8()])
                'j'.code -> stack.add(memo[i32()])
                'c'.code -> { val m = line(); val n = line(); stack.add(GlobalRef(m, n)) }
                0x93 -> { val n = pop() as String; val m = pop() as String; stack.add(GlobalRef(m, n)) }
                'R'.code -> { val args = pop() as List<Any?>; val f = pop(); stack.add(reduce(f, args)) }
                0x81 -> { val args = pop() as List<Any?>; val f = pop(); stack.add(reduce(f, args)) }
                'b'.code -> pop()                             // BUILD: состояние объекта нам не нужно
                'Q'.code -> stack.add(persistent(pop() as List<Any?>))
                else -> error("Неподдерживаемый опкод pickle 0x${op.toString(16)} (смещение ${p - 1})")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun reduce(f: Any?, a: List<Any?>): Any? {
        if (f is GlobalRef) when (f.name) {
            "OrderedDict" -> return LinkedHashMap<Any?, Any?>()
            "_rebuild_tensor_v2", "_rebuild_tensor" -> return TensorRef(
                a[0] as StorageRef,
                (a[1] as Number).toLong(),
                (a[2] as List<Any?>).map { (it as Number).toInt() }.toIntArray(),
                (a[3] as List<Any?>).map { (it as Number).toLong() }.toLongArray(),
            )
            "_rebuild_parameter" -> return a[0]
        }
        return Any()
    }

    private fun persistent(pid: List<Any?>): Any? {
        if (pid.size >= 5 && pid[0] == "storage") {
            val dt = when ((pid[1] as? GlobalRef)?.name) {
                "FloatStorage" -> DType.F32
                "HalfStorage" -> DType.F16
                "BFloat16Storage" -> DType.BF16
                "DoubleStorage" -> DType.F64
                else -> DType.OTHER
            }
            return StorageRef(dt, pid[2].toString(), (pid[4] as Number).toLong())
        }
        return null
    }
}

object PthStream {
    @Suppress("UNCHECKED_CAST")
    fun parseHeader(pkl: ByteArray): PthHeader {
        val root = PickleVM(pkl).run() as? Map<Any?, Any?> ?: error("data.pkl: корень не словарь")
        val weights = (root["weight"] ?: root["model"] ?: root) as Map<Any?, Any?>
        val tensors = LinkedHashMap<String, TensorRef>()
        for ((k, v) in weights) if (k is String && v is TensorRef) tensors[k] = v
        return PthHeader(
            tensors = tensors,
            config = root["config"] as? List<Any?>,
            sr = (root["sr"] as? String),
            version = (root["version"] as? String),
            f0 = (root["f0"] as? Number)?.toInt(),
        )
    }

    /**
     * Открывает .pth как zip через центральный каталог (java.util.zip.ZipFile): не зависит от порядка записей,
     * размеров в локальных заголовках и data descriptor — всё, на чём спотыкается ZipInputStream.
     * Весь файл в память не читается: читаются только data.pkl и хранилища нужных тензоров.
     */
    fun <T> withZip(ctx: Context, uri: Uri, block: (ZipFile) -> T): T {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("Не удалось открыть файл")
        var copy: File? = null
        try {
            // 1) прямо по дескриптору (без копирования); 2) если не вышло — копия в кэш
            val zf = runCatching { ZipFile("/proc/self/fd/${pfd.fd}") }.getOrNull() ?: run {
                val f = File(ctx.cacheDir, "pick.pth").also { copy = it }
                java.io.FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    f.outputStream().channel.use { out ->
                        val size = ch.size()
                        var pos = 0L
                        while (pos < size) {
                            val n = ch.transferTo(pos, size - pos, out)
                            if (n <= 0) break
                            pos += n
                        }
                    }
                }
                try { ZipFile(f) } catch (e: java.util.zip.ZipException) {
                    error("Это не .pth в формате torch.save (zip): ${e.message}")
                }
            }
            return zf.use(block)
        } finally {
            runCatching { pfd.close() }
            copy?.delete()
        }
    }

    /**
     * [chooseKeys] получает заголовок и возвращает нужные имена тензоров (остальные хранилища не читаются).
     * [sink] вызывается по мере чтения, в RAM одновременно не больше одного хранилища (одного тензора).
     */
    fun read(
        zf: ZipFile,
        chooseKeys: (PthHeader) -> Set<String>,
        sink: (key: String, data: FloatArray) -> Unit,
        progress: (Float) -> Unit = {},
    ) {
        var pklEntry: ZipEntry? = null
        val storages = HashMap<String, ZipEntry>()
        for (e in zf.entries()) {
            if (e.isDirectory) continue
            val n = e.name
            if (n.endsWith("/data.pkl") || n == "data.pkl") pklEntry = e
            else if (n.contains("/data/") || n.startsWith("data/")) storages[n.substringAfterLast('/')] = e
        }
        val pe = pklEntry ?: error("Это не .pth в формате torch.save: в архиве нет data.pkl")
        val pkl = zf.getInputStream(pe).use { it.readBytes() }
        check(pkl.isNotEmpty()) { "data.pkl пустой (${pe.name}, size=${pe.size})" }
        val header = parseHeader(pkl)
        val keys = chooseKeys(header)

        val byStorage = HashMap<String, MutableList<Pair<String, TensorRef>>>()
        for (k in keys) {
            val t = header.tensors[k] ?: continue
            byStorage.getOrPut(t.storage.key) { ArrayList() }.add(k to t)
        }
        var done = 0
        val total = keys.size.coerceAtLeast(1)
        for ((sk, list) in byStorage) {
            val entry = storages[sk] ?: error("В архиве нет хранилища $sk")
            val bytes = zf.getInputStream(entry).use { it.readBytes() }
            for ((k, t) in list) {
                sink(k, extract(bytes, t))
                done++
                progress(done.toFloat() / total)
            }
        }
    }

    private fun extract(raw: ByteArray, t: TensorRef): FloatArray {
        check(t.isContiguous()) { "Нестандартные strides у тензора — не поддержано" }
        val n = t.numel.toInt()
        val off = t.offset.toInt()
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(n)
        when (t.storage.dtype) {
            DType.F32 -> for (i in 0 until n) out[i] = bb.getFloat((off + i) * 4)
            DType.F16 -> for (i in 0 until n) out[i] = halfToFloat(bb.getShort((off + i) * 2).toInt() and 0xFFFF)
            DType.BF16 -> for (i in 0 until n) out[i] = Float.fromBits(bb.getShort((off + i) * 2).toInt() shl 16)
            DType.F64 -> for (i in 0 until n) out[i] = bb.getDouble((off + i) * 8).toFloat()
            DType.OTHER -> error("Неподдерживаемый тип данных тензора")
        }
        return out
    }

    private fun halfToFloat(h: Int): Float {
        val s = if ((h shr 15) != 0) -1f else 1f
        val e = (h shr 10) and 0x1F
        val m = h and 0x3FF
        return when (e) {
            0 -> s * m * 5.9604645e-8f
            31 -> if (m == 0) s * Float.POSITIVE_INFINITY else Float.NaN
            else -> s * Float.fromBits(((e + 112) shl 23) or (m shl 13))
        }
    }
}
