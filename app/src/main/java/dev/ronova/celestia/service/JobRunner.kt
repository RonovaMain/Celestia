package dev.ronova.celestia.service

import android.content.Context
import android.net.Uri
import dev.ronova.celestia.Store
import dev.ronova.celestia.VoiceMeta
import dev.ronova.celestia.core.audio.AudioIO
import dev.ronova.celestia.core.audio.Dsp
import dev.ronova.celestia.core.engine.Params
import dev.ronova.celestia.core.engine.RvcEngine
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

data class JobSpec(
    val voice: VoiceMeta,
    val input: Uri?,
    val minus: Uri?,
    val vocalGain: Float,
    val minusGain: Float,
    val params: Params,
    val outName: String,
)

/** Озвучка текста: системный TTS -> (выгрузка синтезатора) -> RVC. rvc == null — только озвучка без смены голоса. */
data class TtsSpec(
    val text: String,
    val voiceName: String?,
    val lang: String?,
    val rate: Float,
    val pitch: Float,
    val pauseMs: Int,
    val rvc: JobSpec?,
    val outName: String,
)

/** Разделение трека на вокал и минус. */
data class SepSpec(
    val input: Uri,
    val name: String,
    val model: dev.ronova.celestia.SepModel,
    val denoise: Boolean,
    val smooth: Boolean,
    val lowMem: Boolean,
    val threads: Int,
)

sealed class JobRequest(val title: String) {
    class Convert(val spec: JobSpec) : JobRequest("Конвертация голоса")
    class Tts(val spec: TtsSpec) : JobRequest("Озвучка текста")
    class Separate(val spec: SepSpec) : JobRequest("Разделение трека")
}

data class JobState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val result: File? = null,
    val error: String? = null,
    val kind: String = "",
)

object JobBus {
    val state = MutableStateFlow(JobState())
    @Volatile var cancel = false
    @Volatile var pending: JobRequest? = null
}

object JobRunner {
    private const val STEP = 320   // кратность чанка: шаг энкодера (320) и F0 (160)

    private fun roundTo(sec: Float) = ((sec * 16000f / STEP).roundToInt().coerceAtLeast(1)) * STEP

    /** Границы чанков (в отсчётах 16 кГц): окна 4–8 с с перекрытием; хвост <2·overlap приклеивается к последнему окну. */
    fun plan(total: Int, win: Int, ov: Int): List<IntRange> {
        val res = ArrayList<IntRange>()
        var s = 0
        while (true) {
            var e = minOf(s + win, total)
            if (total - e < ov + STEP) e = total
            res.add(s until e)
            if (e >= total) break
            s = e - ov
        }
        return res
    }

    fun run(ctx: Context, store: Store, spec: JobSpec, report: (Float, String) -> Unit): File {
        val cancelled = { JobBus.cancel }
        report(0.01f, "Декодирую аудио…")
        val pcm = AudioIO.decode(ctx, spec.input ?: error("Не выбран аудиофайл"), cancelled)
        val x = AudioIO.resample(pcm.mono(), pcm.sampleRate, 16000)
        return convert(ctx, store, x, spec, 0.04f, 0.94f, report)
    }

    /** Смена голоса для уже подготовленного моно-звука 16 кГц. Прогресс идёт в диапазоне p0..p1. */
    fun convert(ctx: Context, store: Store, x: FloatArray, spec: JobSpec, p0: Float, p1: Float, report: (Float, String) -> Unit): File {
        val p = spec.params
        val sr = spec.voice.sr
        val cancelled = { JobBus.cancel }
        require(x.size >= 3200) { "Аудио слишком короткое" }

        val win = roundTo(p.windowSec.coerceIn(4f, 8f))
        val ov = roundTo(p.overlapSec.coerceIn(0.25f, 2f)).coerceAtMost(win / 2)
        val chunks = plan(x.size, win, ov)
        val ovT = (ov.toLong() * sr / 16000).toInt()
        val totalOut = x.size.toLong() * sr / 16000

        val raw = File(store.tmpDir, "vocal.pcm").also { it.delete() }
        val t0 = System.nanoTime()
        var tail: FloatArray? = null
        var written = 0L

        RvcEngine(spec.voice.dir, store.engineDir, p, sr).use { eng ->
            raw.outputStream().buffered(1 shl 16).use { os ->
                fun emit(buf: FloatArray, off: Int, len: Int) {
                    if (len <= 0) return
                    val b = ByteArray(len * 2)
                    AudioIO.floatToPcm16(buf, off, len, b)
                    os.write(b)
                    written += len
                }
                for ((i, r) in chunks.withIndex()) {
                    check(!cancelled()) { "Отменено" }
                    val len = r.last - r.first + 1
                    val padded = (len + STEP - 1) / STEP * STEP
                    val chunk = FloatArray(padded)
                    System.arraycopy(x, r.first, chunk, 0, len)

                    val out = eng.convert(chunk)
                    val valid = (len.toLong() * sr / 16000).toInt()
                    val hasNext = i < chunks.size - 1

                    var bodyStart = 0
                    val t = tail
                    if (i > 0 && t != null) {
                        val m = minOf(t.size, valid)
                        emit(FloatArray(m) { k ->
                            val fi = Dsp.fadeIn(k, m)
                            t[k] * (1f - fi) + out[k] * fi
                        }, 0, m)
                        bodyStart = m
                    }
                    val bodyEnd = if (hasNext) valid - ovT else valid
                    emit(out, bodyStart, bodyEnd - bodyStart)
                    tail = if (hasNext) out.copyOfRange(valid - ovT, valid) else null

                    val done = (r.last + 1).toFloat() / x.size
                    val el = (System.nanoTime() - t0) / 1e9
                    val eta = if (done > 0.02f) (el / done - el).toInt() else -1
                    report(
                        p0 + (p1 - p0) * done,
                        "Фрагмент ${i + 1}/${chunks.size}" + if (eta >= 0) " · осталось ~%d:%02d".format(eta / 60, eta % 60) else "",
                    )
                }
            }
        }
        if (written > totalOut) RandomAccessFile(raw, "rw").use { it.setLength(totalOut * 2) }

        var name = spec.outName.ifBlank { "result" }.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        var dest = File(store.outDir, "$name.wav")
        var n = 1
        while (dest.exists()) { dest = File(store.outDir, "$name (${n++}).wav") }

        if (spec.minus != null) {
            report(p1 + 0.02f, "Микширую с минусом…")
            val minus = AudioIO.decode(ctx, spec.minus, cancelled)
            AudioIO.mixToWav(raw, minus, sr, spec.vocalGain, spec.minusGain, dest)
        } else {
            AudioIO.rawToWav(raw, sr, dest)
        }
        raw.delete()
        report(1f, "Готово")
        return dest
    }
}
