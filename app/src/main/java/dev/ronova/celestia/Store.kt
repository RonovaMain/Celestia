package dev.ronova.celestia

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File

enum class EngineKind(val file: String, val title: String, val hint: String) {
    HUBERT("hubert.onnx", "ContentVec (энкодер речи)", "vec-768-layer-12: 16 кГц → 768 признаков"),
    RMVPE("rmvpe.onnx", "RMVPE (высота тона, точный)", "Лучше для пения и музыки"),
    FCPE("fcpe.onnx", "FCPE (высота тона, быстрый)", "Лучше для чистой речи"),
    MDX_VOC("mdx_voc_ft.onnx", "MDX-Net Voc FT (разделение вокала)", "UVR, основная: чистый вокал, ~67 МБ"),
    MDX_KIM("mdx_kim_vocal2.onnx", "MDX-Net Kim Vocal 2 (разделение вокала)", "UVR, запасная: мягче к инструментам, ~67 МБ"),
}

/** Модели разделения вокала/минуса. compensate — поправка громкости вокала из параметров UVR. */
enum class SepModel(val kind: EngineKind, val title: String, val compensate: Float) {
    VOC_FT(EngineKind.MDX_VOC, "Voc FT · лучшая", 1.021f),
    KIM2(EngineKind.MDX_KIM, "Kim Vocal 2 · запасная", 1.009f),
}

/** Разделённая песня: папка с vocals.wav и instrumental.wav (стерео 44,1 кГц, 16 бит). */
data class StemSet(val dir: File, val name: String, val model: String, val created: Long, val seconds: Int) {
    val vocals: File get() = File(dir, "vocals.wav")
    val instrumental: File get() = File(dir, "instrumental.wav")

    companion object {
        fun read(dir: File): StemSet? {
            if (!File(dir, "vocals.wav").exists() || !File(dir, "instrumental.wav").exists()) return null
            return runCatching {
                val j = JSONObject(File(dir, "meta.json").readText())
                StemSet(dir, j.getString("name"), j.optString("model"), j.optLong("created"), j.optInt("seconds"))
            }.getOrNull()
        }
    }
}

class Store(private val ctx: Context) {
    val voicesDir = File(ctx.filesDir, "voices").apply { mkdirs() }
    val engineDir = File(ctx.filesDir, "engine").apply { mkdirs() }
    val outDir = File(ctx.filesDir, "out").apply { mkdirs() }
    val stemsDir = File(ctx.filesDir, "stems").apply { mkdirs() }
    val tmpDir = File(ctx.cacheDir, "work").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("celestia", Context.MODE_PRIVATE)

    fun voices(): List<VoiceMeta> =
        voicesDir.listFiles()?.mapNotNull { VoiceMeta.read(it) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun stems(): List<StemSet> =
        stemsDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.mapNotNull { StemSet.read(it) }
            ?.sortedByDescending { it.created } ?: emptyList()

    fun outputs(): List<File> = outDir.listFiles()?.filter { it.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun engineFile(k: EngineKind) = File(engineDir, k.file)

    fun getInt(k: String, d: Int) = prefs.getInt(k, d)
    fun getFloat(k: String, d: Float) = prefs.getFloat(k, d)
    fun getBool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    fun getString(k: String): String? = prefs.getString(k, null)
    fun put(k: String, v: Any?) = prefs.edit().apply {
        when (v) { is Int -> putInt(k, v); is Float -> putFloat(k, v); is Boolean -> putBoolean(k, v); is String -> putString(k, v); null -> remove(k) }
    }.apply()

    fun displayName(uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "file"

    fun bundled(): Set<String> = ctx.assets.list("models")?.toSet() ?: emptySet()

    /** Копирует модели, вшитые в APK (assets/models), в приватную папку — ORT читает по пути. */
    fun installBundled(onProgress: (String, Float) -> Unit) {
        val have = ctx.assets.list("models")?.toSet() ?: return
        for (k in EngineKind.entries) {
            if (k.file !in have) continue
            val dst = engineFile(k)
            val size = ctx.assets.openFd("models/${k.file}").use { it.length }
            if (dst.exists() && dst.length() == size) continue
            val tmp = File(engineDir, k.file + ".tmp")
            ctx.assets.open("models/${k.file}").use { i ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(1 shl 20); var done = 0L
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        onProgress(k.title, done.toFloat() / size)
                    }
                }
            }
            tmp.renameTo(dst)
        }
    }

    fun copy(uri: Uri, dest: File) {
        ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
    }
}
