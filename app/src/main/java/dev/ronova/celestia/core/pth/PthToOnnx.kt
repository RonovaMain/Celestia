package dev.ronova.celestia.core.pth

import android.content.Context
import org.json.JSONObject
import java.io.File
import android.net.Uri
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class TemplateInfo(val id: String, val sr: Int, val hop: Int, val totalBytes: Long, val tensors: Map<String, Pair<Long, Int>>)

/**
 * .pth -> готовая к запуску модель:
 *   model.onnx  — шаблонный граф из assets (его делает tools/export_templates.py в CI)
 *   weights.bin — внешний файл весов; тензоры пишутся в него потоково по смещениям из манифеста
 * Дискриминатор, enc_q и состояние Adam не читаются вообще (их хранилища пропускаются).
 * weight_norm сворачивается на лету: w = v * g / ||v||.
 */
object PthToOnnx {
    private fun readJson(ctx: Context, id: String) =
        JSONObject(ctx.assets.open("templates/$id.json").bufferedReader().use { it.readText() })

    private fun loadTemplate(ctx: Context, id: String): TemplateInfo {
        val j = readJson(ctx, id)
        val ts = j.getJSONObject("tensors")
        val map = HashMap<String, Pair<Long, Int>>(ts.length() * 2)
        for (k in ts.keys()) {
            val o = ts.getJSONObject(k)
            map[k] = o.getLong("offset") to o.getInt("numel")
        }
        return TemplateInfo(id, j.getInt("sr"), j.getInt("hop"), j.getLong("total_bytes"), map)
    }

    private fun srOf(h: PthHeader): Int? {
        val s = h.sr ?: (h.config?.lastOrNull())?.toString()
        return when (s) { "32k", "32000" -> 32000; "40k", "40000" -> 40000; "48k", "48000" -> 48000; else -> null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pickTemplate(ctx: Context, h: PthHeader): TemplateInfo {
        require(h.version == "v2") { "Поддерживаются только RVC v2 (в файле version=${h.version ?: "v1"})" }
        require((h.f0 ?: 1) == 1) { "Модели без F0 (f0=0) не поддерживаются" }
        val sr = srOf(h) ?: error("Не удалось определить частоту модели (поддержаны 32k/40k/48k)")
        val id = "v2_${sr / 1000}k"
        val rates = (h.config?.getOrNull(12) as? List<Any?>)?.map { (it as Number).toInt() }
        val ex = readJson(ctx, id).getJSONArray("upsample_rates").let { a -> List(a.length()) { a.getInt(it) } }
        require(rates == null || rates == ex) { "Нестандартная архитектура генератора: $rates (ждали $ex)" }
        return loadTemplate(ctx, id)
    }

    /** weight_norm: w[i,...] = v[i,...] * g[i] / ||v[i,...]|| (g имеет форму [d0,1,1]). */
    private fun fuse(g: FloatArray, v: FloatArray): FloatArray {
        val d0 = g.size
        require(d0 > 0 && v.size % d0 == 0) { "Несовместимые weight_g/weight_v" }
        val per = v.size / d0
        val out = FloatArray(v.size)
        for (i in 0 until d0) {
            var s = 0.0
            for (j in 0 until per) { val x = v[i * per + j].toDouble(); s += x * x }
            val k = (g[i] / sqrt(s.coerceAtLeast(1e-24))).toFloat()
            for (j in 0 until per) out[i * per + j] = v[i * per + j] * k
        }
        return out
    }

    fun convert(ctx: Context, pth: Uri, dest: File, name: String, onProgress: (Float) -> Unit) {
        dest.mkdirs()
        var tpl: TemplateInfo? = null
        var raf: RandomAccessFile? = null
        val written = HashSet<String>()
        val pendingG = HashMap<String, FloatArray>()
        val pendingV = HashMap<String, FloatArray>()
        try {
            fun put(key: String, data: FloatArray) {
                val (off, numel) = tpl!!.tensors.getValue(key)
                // emb_g: в шаблоне один спикер; в .pth их может быть 109 — берём нулевую строку
                val src = if (data.size > numel && key == "emb_g.weight") data.copyOf(numel) else data
                check(src.size == numel) { "Форма $key не совпала: ${src.size} вместо $numel" }
                val bytes = ByteArray(numel * 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(src)
                raf!!.seek(off)
                raf!!.write(bytes)
                written.add(key)
            }

            PthStream.withZip(ctx, pth) { zf -> PthStream.read(
                zf,
                chooseKeys = { h ->
                    val t = pickTemplate(ctx, h).also { tpl = it }
                    ctx.assets.open("templates/${t.id}.onnx").use { i -> File(dest, "model.onnx").outputStream().use { o -> i.copyTo(o) } }
                    raf = RandomAccessFile(File(dest, "weights.bin"), "rw").also { it.setLength(t.totalBytes) }
                    val keys = HashSet<String>()
                    for (k in t.tensors.keys) {
                        if (h.tensors.containsKey(k)) keys.add(k)
                        else { keys.add(k + "_g"); keys.add(k + "_v") }   // weight_norm
                    }
                    keys
                },
                sink = { key, data ->
                    if (tpl!!.tensors.containsKey(key)) put(key, data)
                    else if (key.endsWith("_g")) {
                        val base = key.dropLast(2)
                        val v = pendingV.remove(base)
                        if (v != null) put(base, fuse(data, v)) else pendingG[base] = data
                    } else {
                        val base = key.dropLast(2)
                        val g = pendingG.remove(base)
                        if (g != null) put(base, fuse(g, data)) else pendingV[base] = data
                    }
                },
                progress = onProgress,
            ) }
            val t = tpl ?: error("Не удалось прочитать .pth")
            val missing = t.tensors.keys - written
            check(missing.isEmpty()) { "В .pth нет весов: ${missing.take(5).joinToString()}" }
            raf?.close()
            File(dest, "voice.json").writeText(JSONObject().put("name", name).put("sr", t.sr).put("hop", t.hop).toString())
        } catch (e: Throwable) {
            runCatching { raf?.close() }
            dest.deleteRecursively()
            throw e
        } finally {
            runCatching { raf?.close() }
        }
    }
}
