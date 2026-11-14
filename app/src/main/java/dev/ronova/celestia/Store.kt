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
}

data class VoiceMeta(val id: String, val name: String, val sr: Int, val dir: File) {
    val hasIndex: Boolean get() = File(dir, "voice.index").exists()

    companion object {
        fun read(dir: File): VoiceMeta? = runCatching {
            val j = JSONObject(File(dir, "voice.json").readText())
            VoiceMeta(dir.name, j.getString("name"), j.getInt("sr"), dir)
        }.getOrNull()
    }
}

class Store(private val ctx: Context) {
    val voicesDir = File(ctx.filesDir, "voices").apply { mkdirs() }
    val engineDir = File(ctx.filesDir, "engine").apply { mkdirs() }
    val outDir = File(ctx.filesDir, "out").apply { mkdirs() }
    val tmpDir = File(ctx.cacheDir, "work").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("celestia", Context.MODE_PRIVATE)

    fun voices(): List<VoiceMeta> =
        voicesDir.listFiles()?.mapNotNull { VoiceMeta.read(it) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun outputs(): List<File> = outDir.listFiles()?.filter { it.extension == "wav" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun engineFile(k: EngineKind) = File(engineDir, k.file)

    fun getInt(k: String, d: Int) = prefs.getInt(k, d)
    fun getFloat(k: String, d: Float) = prefs.getFloat(k, d)
    fun getBool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    fun put(k: String, v: Any) = prefs.edit().apply {
        when (v) { is Int -> putInt(k, v); is Float -> putFloat(k, v); is Boolean -> putBoolean(k, v) }
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
