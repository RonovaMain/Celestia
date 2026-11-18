package dev.ronova.celestia.service

import android.content.Context
import dev.ronova.celestia.Store
import dev.ronova.celestia.core.audio.AudioIO
import dev.ronova.celestia.core.audio.Wav
import dev.ronova.celestia.core.tts.SystemTts
import dev.ronova.celestia.core.tts.TextSplit
import java.io.File
import kotlin.math.abs

/**
 * Озвучка по очереди, чтобы не держать в памяти всё сразу:
 *  1) системный синтезатор пишет речь в файлы (его модель живёт в другом процессе) и закрывается;
 *  2) только после этого загружается RVC (энкодер + F0 + генератор), конвертирует и тоже выгружается.
 */
object TtsRunner {
    fun run(ctx: Context, store: Store, spec: TtsSpec, report: (Float, String) -> Unit): File {
        val cancelled = { JobBus.cancel }
        val chunks = TextSplit.split(spec.text)
        require(chunks.isNotEmpty()) { "В тексте нет слов для озвучки" }

        report(0.02f, "Запускаю системный синтезатор…")
        val parts = ArrayList<FloatArray>()
        var sr = 0
        SystemTts(ctx).use { tts ->
            check(tts.open()) {
                "Системный синтезатор речи не запустился. Проверьте, что в настройках телефона выбран движок «Синтез речи» и установлены офлайн-голоса."
            }
            tts.select(spec.voiceName, spec.lang, spec.rate, spec.pitch)
            val tmp = File(store.tmpDir, "tts_part.wav")
            for ((i, c) in chunks.withIndex()) {
                check(!cancelled()) { "Отменено" }
                report(0.03f + 0.37f * i / chunks.size, "Синтез речи: ${i + 1}/${chunks.size}")
                tts.synth(c, tmp, cancelled)
                val pcm = Wav.read(tmp)
                var m = pcm.mono()
                if (sr == 0) sr = pcm.sampleRate else if (pcm.sampleRate != sr) m = AudioIO.resample(m, pcm.sampleRate, sr)
                parts.add(m)
            }
            tmp.delete()
        }   // синтезатор закрыт и выгружен здесь

        val pause = FloatArray(sr * spec.pauseMs.coerceIn(0, 3000) / 1000)
        val total = parts.sumOf { it.size } + pause.size * (parts.size - 1)
        val speech = FloatArray(total)
        var o = 0
        for ((i, p) in parts.withIndex()) {
            System.arraycopy(p, 0, speech, o, p.size); o += p.size
            if (i < parts.size - 1) o += pause.size
        }
        parts.clear()
        var peak = 0f
        for (v in speech) peak = maxOf(peak, abs(v))
        if (peak > 1e-4f) { val g = 0.9f / peak; for (i in speech.indices) speech[i] *= g }

        if (spec.rvc == null) {
            report(0.95f, "Сохраняю…")
            val dest = uniqueFile(store.outDir, spec.outName)
            Wav.writeMono16(dest, speech, sr)
            report(1f, "Готово")
            return dest
        }

        val x = AudioIO.resample(speech, sr, 16000)
        System.gc()
        report(0.42f, "Синтезатор выгружен. Загружаю RVC…")
        return JobRunner.convert(ctx, store, x, spec.rvc, 0.45f, 0.96f, report)
    }

    fun uniqueFile(dir: File, base: String): File {
        val name = base.ifBlank { "result" }.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80)
        var dest = File(dir, "$name.wav")
        var n = 1
        while (dest.exists()) dest = File(dir, "$name (${n++}).wav")
        return dest
    }
}
