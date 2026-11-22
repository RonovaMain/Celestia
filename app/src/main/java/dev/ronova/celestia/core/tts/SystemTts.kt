package dev.ronova.celestia.core.tts

import dev.ronova.celestia.R
import dev.ronova.celestia.tr
import dev.ronova.celestia.Lang

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class TtsVoiceInfo(val name: String, val lang: String, val title: String)

/**
 * Системный синтезатор Android (Google, Samsung, RHVoice и т. д.). Он живёт в своём процессе, так что
 * его модель не занимает память приложения; здесь мы только просим записать речь в WAV-файл.
 * Работает офлайн (в приложении нет INTERNET), нужны установленные офлайн-голоса нужных языков.
 */
class SystemTts(ctx: Context) : AutoCloseable {
    private val app = ctx.applicationContext
    private var tts: TextToSpeech? = null

    fun open(timeoutMs: Long = 20_000): Boolean {
        val latch = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        tts = TextToSpeech(app, TextToSpeech.OnInitListener { s -> status = s; latch.countDown() })
        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS) && status == TextToSpeech.SUCCESS
        if (!ok) close()
        return ok
    }

    /** Только офлайн-голоса, которые реально установлены. */
    fun voices(): List<TtsVoiceInfo> {
        val raw = try { tts?.voices } catch (e: Throwable) { null }
        val set = raw ?: return emptyList()
        return set.filter { v ->
            !v.isNetworkConnectionRequired && v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }.map { v ->
            TtsVoiceInfo(v.name, v.locale.language, v.locale.displayName + " · " + v.name)
        }.sortedBy { it.title.lowercase() }
    }

    fun select(voiceName: String?, lang: String?, rate: Float, pitch: Float) {
        val t = tts ?: error(tr(R.string.err_synth_not_running))
        var chosen = false
        if (!voiceName.isNullOrEmpty()) {
            val v = t.voices?.firstOrNull { it.name == voiceName }
            if (v != null) chosen = t.setVoice(v) == TextToSpeech.SUCCESS
        }
        if (!chosen) {
            val loc = if (!lang.isNullOrEmpty()) Locale.forLanguageTag(lang) else Locale.getDefault()
            val r = t.setLanguage(loc)
            check(r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                tr(R.string.err_sys_no_voice, loc.getDisplayLanguage(Lang.locale()))
            }
        }
        t.setSpeechRate(rate)
        t.setPitch(pitch)
    }

    fun synth(text: String, out: File, cancelled: () -> Boolean) {
        val t = tts ?: error(tr(R.string.err_synth_not_running))
        val id = "u" + System.nanoTime()
        val latch = CountDownLatch(1)
        val err = AtomicReference<String?>(null)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) latch.countDown() }
            override fun onError(utteranceId: String?) { if (utteranceId == id) { err.set(tr(R.string.synth_err_plain)); latch.countDown() } }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) { err.set(tr(R.string.synth_err_code, errorCode)); latch.countDown() } }
        })
        out.delete()
        val r = t.synthesizeToFile(text, Bundle(), out, id)
        check(r == TextToSpeech.SUCCESS) { tr(R.string.err_synth_rejected) }
        var waited = 0
        while (!latch.await(250, TimeUnit.MILLISECONDS)) {
            if (cancelled()) { t.stop(); error(tr(R.string.job_cancelled)) }
            waited += 250
            if (waited > 120_000) { t.stop(); error(tr(R.string.err_synth_timeout)) }
        }
        err.get()?.let { error(tr(R.string.err_synth_generic, it)) }
        check(out.exists() && out.length() > 44) { tr(R.string.err_synth_empty) }
    }

    override fun close() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    companion object {
        /** Список офлайн-голосов (создаёт и сразу закрывает синтезатор). */
        fun listVoices(ctx: Context): List<TtsVoiceInfo>? {
            val s = SystemTts(ctx)
            return try { if (s.open()) s.voices() else null } finally { s.close() }
        }
    }
}

object TextSplit {
    /** Делит текст на куски до maxLen символов по границам предложений (так проще и устойчивее для движка). */
    fun split(text: String, maxLen: Int = 320): List<String> {
        val out = ArrayList<String>()
        for (para in text.replace("\r", "\n").split(Regex("\n+"))) {
            val p = para.trim()
            if (p.isEmpty()) continue
            var cur = StringBuilder()
            for (s0 in p.split(Regex("(?<=[.!?…;])\\s+"))) {
                val s = s0.trim()
                if (s.isEmpty()) continue
                for (piece in hard(s, maxLen)) {
                    if (cur.isNotEmpty() && cur.length + 1 + piece.length > maxLen) { out.add(cur.toString()); cur = StringBuilder() }
                    if (cur.isNotEmpty()) cur.append(' ')
                    cur.append(piece)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
        }
        return out.filter { c -> c.any { it.isLetterOrDigit() } }
    }

    private fun hard(s: String, maxLen: Int): List<String> {
        if (s.length <= maxLen) return listOf(s)
        val res = ArrayList<String>()
        var rest = s
        while (rest.length > maxLen) {
            var cut = rest.lastIndexOf(',', maxLen)
            if (cut < maxLen / 3) cut = rest.lastIndexOf(' ', maxLen)
            if (cut < maxLen / 3) cut = maxLen
            res.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) res.add(rest)
        return res
    }
}
