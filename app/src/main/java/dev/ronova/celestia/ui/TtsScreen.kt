package dev.ronova.celestia.ui

import dev.ronova.celestia.R
import dev.ronova.celestia.tr
import dev.ronova.celestia.Lang

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ronova.celestia.EngineKind
import dev.ronova.celestia.MainVm
import dev.ronova.celestia.core.tts.EmbCatalog
import dev.ronova.celestia.core.tts.EmbPacks
import dev.ronova.celestia.service.JobBus
import java.util.Locale

private val FALLBACK_LANGS = listOf("ru", "en", "uk", "be", "de", "fr", "es", "it", "pl", "tr", "pt", "ja", "ko", "zh")

private fun langTitle(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayLanguage(Lang.locale()).replaceFirstChar { it.uppercase() }.ifBlank { tag }

@Composable
fun TtsScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val job by JobBus.state.collectAsStateWithLifecycle()
    val go = rememberNotifGate { vm.startTts() }
    LaunchedEffect(vm.ttsEngine) { if (vm.ttsEngine == "system") vm.loadTtsVoices() }
    LaunchedEffect(job.result) { vm.refreshOutputs() }

    fun open(i: Intent) { runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    Page {
        Text(tr(R.string.tts_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            tr(R.string.tts_intro),
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = vm.ttsText, onValueChange = { vm.ttsText = it },
            label = { Text(tr(R.string.tts_text_label)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp), minLines = 5,
        )
        Text(tr(R.string.tts_chars, vm.ttsText.length), style = MaterialTheme.typography.bodySmall)

        // ── синтезатор ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.tts_step1), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(vm.ttsEngine == "embedded", { vm.ttsEngine = "embedded"; vm.saveTts() }, label = { Text(tr(R.string.tts_embedded)) })
                    FilterChip(vm.ttsEngine == "system", { vm.ttsEngine = "system"; vm.saveTts() }, label = { Text(tr(R.string.tts_system)) })
                }
                if (vm.ttsEngine == "embedded") {
                    Text(
                        tr(R.string.tts_emb_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val lang = vm.ttsEmbLang
                    Picker(tr(R.string.lang_label), EmbCatalog.langs.firstOrNull { it.first == lang }?.second ?: lang, EmbCatalog.langs) {
                        vm.ttsEmbLang = it
                        vm.ttsEmbVoice = EmbCatalog.voices.first { v -> v.lang == it }.id
                        vm.saveTts()
                    }
                    val vs = EmbCatalog.voices.filter { it.lang == lang }
                    Picker(tr(R.string.voice_label), vs.firstOrNull { it.id == vm.ttsEmbVoice }?.title ?: "—", vs.map { it.id to it.title }) {
                        vm.ttsEmbVoice = it; vm.saveTts()
                    }
                    val ev = EmbCatalog.byId(vm.ttsEmbVoice)
                    val present = remember(vm.ttsEmbVoice) { ev != null && EmbPacks.available(ctx, ev) }
                    if (!present) {
                        Text(tr(R.string.tts_voice_missing), color = MaterialTheme.colorScheme.error)
                    }
                    Labeled(tr(R.string.tts_rate), String.format(Locale.US, "%.2f×", vm.ttsRate), vm.ttsRate, 0.5f..2f) { vm.ttsRate = it; vm.saveTts() }
                } else {
                    Text(
                        tr(R.string.tts_sys_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when (vm.ttsVoicesState) {
                        "loading" -> Text(tr(R.string.tts_searching))
                        "fail" -> Text(tr(R.string.tts_sys_fail), color = MaterialTheme.colorScheme.error)
                        else -> if (vm.ttsVoices.isEmpty()) Text(
                            tr(R.string.tts_no_voices),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    val langs = vm.ttsVoices.map { it.lang }.distinct().ifEmpty { FALLBACK_LANGS }
                    val lang = vm.ttsLang ?: "ru"
                    Picker(tr(R.string.lang_label), langTitle(lang), langs.map { it to langTitle(it) }) { vm.ttsLang = it; vm.ttsVoiceName = null; vm.saveTts() }
                    val inLang = vm.ttsVoices.filter { it.lang == lang }
                    val cur = inLang.firstOrNull { it.name == vm.ttsVoiceName }?.title ?: tr(R.string.voice_default)
                    Picker(tr(R.string.voice_label), cur, listOf("" to tr(R.string.voice_default_for_lang)) + inLang.map { it.name to it.title }) {
                        vm.ttsVoiceName = it.ifEmpty { null }; vm.saveTts()
                    }
                    Labeled(tr(R.string.tts_rate), String.format(Locale.US, "%.2f×", vm.ttsRate), vm.ttsRate, 0.5f..2f) { vm.ttsRate = it; vm.saveTts() }
                    Labeled(tr(R.string.tts_pitch_sys), String.format(Locale.US, "%.2f", vm.ttsPitchSys), vm.ttsPitchSys, 0.5f..1.5f) { vm.ttsPitchSys = it; vm.saveTts() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ vm.loadTtsVoices(true) }) { Text(tr(R.string.refresh)) }
                        OutlinedButton({ open(Intent("com.android.settings.TTS_SETTINGS")) }) { Text(tr(R.string.tts_settings_btn)) }
                    }
                    OutlinedButton({ open(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }, Modifier.fillMaxWidth()) {
                        Text(tr(R.string.tts_install_data))
                    }
                }
                Labeled(tr(R.string.tts_pause), tr(R.string.fmt_ms, vm.ttsPause), vm.ttsPause.toFloat(), 0f..1000f, 9) { vm.ttsPause = it.toInt(); vm.saveTts() }
            }
        }

        // ── RVC ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.tts_step2), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (vm.ttsRvc) tr(R.string.tts_rvc_on) else tr(R.string.tts_rvc_off),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(vm.ttsRvc, { vm.ttsRvc = it; vm.saveTts() })
                }
                if (vm.ttsRvc) {
                    if (vm.voices.isEmpty()) {
                        Text(tr(R.string.tts_no_rvc_voices))
                        Button({ vm.tab = 0 }) { Text(tr(R.string.voices_add_title)) }
                    } else {
                        Picker(tr(R.string.voice_label), vm.voice?.name ?: tr(R.string.not_selected), vm.voices.map { it.id to it.name }) { id -> vm.voiceId = id }
                    }
                    val hasIdx = vm.voice?.hasIndex == true
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(R.string.pitch_mode_label))
                        FilterChip(vm.tFcpe, { vm.tFcpe = true; vm.saveTts() }, label = { Text(tr(R.string.f0_fcpe_chip)) })
                        FilterChip(!vm.tFcpe, { vm.tFcpe = false; vm.saveTts() }, label = { Text(tr(R.string.f0_rmvpe_precise)) })
                    }
                    Labeled(tr(R.string.lbl_pitch), tr(R.string.fmt_st, vm.tPitch), vm.tPitch.toFloat(), -24f..24f, 47) { vm.tPitch = it.toInt(); vm.saveTts() }
                    Text(
                        tr(R.string.tts_pitch_tip),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Labeled(
                        tr(if (hasIdx) R.string.lbl_index_rate else R.string.lbl_index_rate_noidx),
                        String.format(Locale.US, "%.2f", if (hasIdx) vm.tIndex else 0f), vm.tIndex, 0f..1f, enabled = hasIdx,
                    ) { vm.tIndex = it; vm.saveTts() }
                    Labeled(tr(R.string.lbl_protect), String.format(Locale.US, "%.2f", vm.tProtect), vm.tProtect, 0f..0.5f) { vm.tProtect = it; vm.saveTts() }
                    Labeled(tr(R.string.lbl_rms), String.format(Locale.US, "%.2f", vm.tRms), vm.tRms, 0f..1f) { vm.tRms = it; vm.saveTts() }
                    Labeled(tr(R.string.lbl_temp), String.format(Locale.US, "%.2f", vm.tTemp), vm.tTemp, 0f..1f) { vm.tTemp = it; vm.saveTts() }
                }
            }
        }

        if (job.running) {
            JobCard(job) { vm.cancel() }
        } else {
            Button({ go() }, Modifier.fillMaxWidth()) { Text(if (vm.ttsRvc) tr(R.string.tts_go_rvc) else tr(R.string.tts_go)) }
            job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        ResultsList(vm)
    }
}
