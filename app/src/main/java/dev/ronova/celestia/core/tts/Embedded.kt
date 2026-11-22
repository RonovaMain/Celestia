package dev.ronova.celestia.core.tts

import dev.ronova.celestia.R
import dev.ronova.celestia.tr

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.IOException

/**
 * Встроенные голоса (sherpa-onnx, офлайн): Kokoro-82M (int8) для en/es/fr/it/pt/hi и Piper для русского.
 * Kokoro русский не поддерживает, поэтому русская озвучка — Piper (VITS).
 * pack — папка в assets/tts, dataPack — откуда брать espeak-ng-data (у двух русских голосов она общая).
 */
class EmbVoice(
    val id: String,
    val name: String,
    val female: Boolean,
    val tag: String,          // "", ", US", ", UK", " (Piper)"
    val lang: String,
    val pack: String,
    val sid: Int,
    val espeak: String = "",
    val dataPack: String = pack,
    val quiet: Boolean = false,
) {
    /** Название для списка голосов на текущем языке интерфейса. */
    val title: String get() {
        val n = when (id) {
            "ru-irina" -> tr(R.string.voice_irina)
            "ru-denis" -> tr(R.string.voice_denis)
            else -> name
        }
        var s = n + " · " + tr(if (female) R.string.gender_f else R.string.gender_m) + tag
        if (quiet) s += " (" + tr(R.string.voice_quiet) + ")"
        return s
    }
}

object EmbCatalog {
    val langs = listOf(
        "ru" to "Русский", "en" to "English", "es" to "Español", "fr" to "Français",
        "it" to "Italiano", "pt" to "Português", "hi" to "हिन्दी",
    )

    // sid — номера дикторов в voices.bin Kokoro v1.0 (по алфавиту имён, 53 диктора)
    val voices = listOf(
        EmbVoice("ru-irina", "Irina", true, " (Piper)", "ru", "piper-ru-irina", 0),
        EmbVoice("ru-denis", "Denis", false, " (Piper)", "ru", "piper-ru-denis", 0, dataPack = "piper-ru-irina"),
        EmbVoice("en-heart", "Heart", true, ", US", "en", "kokoro", 3),
        EmbVoice("en-bella", "Bella", true, ", US", "en", "kokoro", 2),
        EmbVoice("en-nicole", "Nicole", true, ", US", "en", "kokoro", 6, quiet = true),
        EmbVoice("en-sarah", "Sarah", true, ", US", "en", "kokoro", 9),
        EmbVoice("en-adam", "Adam", false, ", US", "en", "kokoro", 11),
        EmbVoice("en-michael", "Michael", false, ", US", "en", "kokoro", 16),
        EmbVoice("en-emma", "Emma", true, ", UK", "en", "kokoro", 21, espeak = "en-gb"),
        EmbVoice("en-george", "George", false, ", UK", "en", "kokoro", 26, espeak = "en-gb"),
        EmbVoice("es-dora", "Dora", true, "", "es", "kokoro", 28, espeak = "es"),
        EmbVoice("es-alex", "Alex", false, "", "es", "kokoro", 29, espeak = "es"),
        EmbVoice("fr-siwis", "Siwis", true, "", "fr", "kokoro", 30, espeak = "fr-fr"),
        EmbVoice("it-sara", "Sara", true, "", "it", "kokoro", 35, espeak = "it"),
        EmbVoice("it-nicola", "Nicola", false, "", "it", "kokoro", 36, espeak = "it"),
        EmbVoice("pt-dora", "Dora", true, "", "pt", "kokoro", 42, espeak = "pt-br"),
        EmbVoice("pt-alex", "Alex", false, "", "pt", "kokoro", 43, espeak = "pt-br"),
        EmbVoice("hi-alpha", "Alpha", true, "", "hi", "kokoro", 31, espeak = "hi"),
        EmbVoice("hi-omega", "Omega", false, "", "hi", "kokoro", 33, espeak = "hi"),
    )

    fun byId(id: String?) = voices.firstOrNull { it.id == id }
}

/** Голоса лежат в assets/tts/<pack>; sherpa читает их с диска, поэтому один раз распаковываем в filesDir/tts. */
object EmbPacks {
    fun root(ctx: Context) = File(ctx.filesDir, "tts")
    fun dir(ctx: Context, pack: String) = File(root(ctx), pack)
    private fun inApk(ctx: Context, pack: String) = runCatching { ctx.assets.list("tts")?.contains(pack) == true }.getOrDefault(false)
    fun installed(ctx: Context, pack: String) = File(dir(ctx, pack), ".ok").exists()
    fun available(ctx: Context, v: EmbVoice) =
        (installed(ctx, v.pack) || inApk(ctx, v.pack)) && (installed(ctx, v.dataPack) || inApk(ctx, v.dataPack))

    fun ensure(ctx: Context, pack: String) {
        if (installed(ctx, pack)) return
        check(inApk(ctx, pack)) { tr(R.string.err_voice_pack_missing, pack) }
        val d = dir(ctx, pack)
        d.deleteRecursively(); d.mkdirs()
        copyDir(ctx.assets, "tts/$pack", d)
        File(d, ".ok").writeText("1")
    }

    private fun copyDir(am: AssetManager, path: String, dest: File) {
        val names = am.list(path)
        if (names.isNullOrEmpty()) {
            try {
                am.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            } catch (e: IOException) { dest.mkdirs() }   // пустая папка
            return
        }
        dest.mkdirs()
        for (n in names) copyDir(am, "$path/$n", File(dest, n))
    }
}

/** Обёртка над sherpa-onnx OfflineTts. Модель живёт только между open() и close(). */
class EmbeddedTts(private val ctx: Context) : AutoCloseable {
    private var tts: OfflineTts? = null
    var sampleRate = 0
        private set

    fun open(v: EmbVoice, threads: Int) {
        EmbPacks.ensure(ctx, v.pack)
        if (v.dataPack != v.pack) EmbPacks.ensure(ctx, v.dataPack)
        val d = EmbPacks.dir(ctx, v.pack).absolutePath
        val model = if (v.pack == "kokoro") {
            val lex = listOf("lexicon-us-en.txt", "lexicon-gb-en.txt").filter { File(d, it).exists() }.joinToString(",") { "$d/$it" }
            OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$d/model.onnx", voices = "$d/voices.bin", tokens = "$d/tokens.txt",
                    dataDir = "$d/espeak-ng-data", lexicon = lex, lang = v.espeak,
                ),
                numThreads = threads, debug = false, provider = "cpu",
            )
        } else {
            val data = File(EmbPacks.dir(ctx, v.dataPack), "espeak-ng-data").absolutePath
            OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = "$d/model.onnx", tokens = "$d/tokens.txt", dataDir = data),
                numThreads = threads, debug = false, provider = "cpu",
            )
        }
        val t = OfflineTts(assetManager = null, config = OfflineTtsConfig(model = model))
        tts = t
        sampleRate = t.sampleRate()
    }

    fun synth(text: String, sid: Int, speed: Float): FloatArray {
        val t = tts ?: error(tr(R.string.err_synth_not_running))
        return t.generate(text = text, sid = sid, speed = speed).samples
    }

    override fun close() {
        val t = tts ?: return
        tts = null
        // метод освобождения называется по-разному в разных версиях sherpa-onnx (release / free)
        for (n in listOf("release", "free")) {
            val m = t.javaClass.methods.firstOrNull { it.name == n && it.parameterCount == 0 }
            if (m != null) { runCatching { m.invoke(t) }; break }
        }
    }
}
