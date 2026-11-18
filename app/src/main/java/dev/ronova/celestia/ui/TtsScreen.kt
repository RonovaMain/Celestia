package dev.ronova.celestia.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ronova.celestia.EngineKind
import dev.ronova.celestia.MainVm
import dev.ronova.celestia.service.JobBus
import java.util.Locale

private val FALLBACK_LANGS = listOf("ru", "en", "uk", "de", "fr", "es", "it", "pl", "tr", "pt", "ja", "ko", "zh")

private fun langTitle(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayLanguage(Locale("ru")).replaceFirstChar { it.uppercase() }.ifBlank { tag }

@Composable
fun TtsScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val job by JobBus.state.collectAsStateWithLifecycle()
    val go = rememberNotifGate { vm.startTts() }
    LaunchedEffect(Unit) { vm.loadTtsVoices() }
    LaunchedEffect(job.result) { vm.refreshOutputs() }

    fun open(i: Intent) { runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    Page {
        Text("Озвучка текста", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Напишите текст — он будет озвучен системным синтезатором речи, а затем голос заменится на выбранный голос RVC. " +
                "Модели работают строго по очереди: сначала синтезатор (после этого он выгружается), потом RVC — одновременно в памяти их нет.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = vm.ttsText, onValueChange = { vm.ttsText = it },
            label = { Text("Текст для озвучки") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp), minLines = 5,
        )
        Text("Символов: ${vm.ttsText.length}", style = MaterialTheme.typography.bodySmall)

        // ── синтезатор ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Синтезатор речи (основа)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Используются офлайн-голоса вашего телефона (Google, Samsung и т. д.), поэтому подходит любой язык, " +
                        "который у вас установлен. Тембр потом всё равно заменит RVC — важна лишь внятность и интонация.",
                    style = MaterialTheme.typography.bodySmall,
                )
                when (vm.ttsVoicesState) {
                    "loading" -> Text("Ищу голоса…")
                    "fail" -> Text("Системный синтезатор не отвечает. Проверьте настройки синтеза речи.", color = MaterialTheme.colorScheme.error)
                    else -> if (vm.ttsVoices.isEmpty()) Text(
                        "Офлайн-голоса не найдены. Установите голос (например, русский) в настройках синтеза речи и нажмите «Обновить». " +
                            "Можно попробовать и язык «по умолчанию».",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val langs = vm.ttsVoices.map { it.lang }.distinct().ifEmpty { FALLBACK_LANGS }
                val lang = vm.ttsLang ?: "ru"
                Picker("Язык", langTitle(lang), langs.map { it to langTitle(it) }) { vm.ttsLang = it; vm.ttsVoiceName = null; vm.saveTts() }
                val inLang = vm.ttsVoices.filter { it.lang == lang }
                val cur = inLang.firstOrNull { it.name == vm.ttsVoiceName }?.title ?: "по умолчанию"
                Picker("Голос", cur, listOf("" to "По умолчанию для языка") + inLang.map { it.name to it.title }) {
                    vm.ttsVoiceName = it.ifEmpty { null }; vm.saveTts()
                }
                Labeled("Скорость речи", String.format(Locale.US, "%.2f×", vm.ttsRate), vm.ttsRate, 0.5f..2f) { vm.ttsRate = it; vm.saveTts() }
                Labeled("Высота синтезатора", String.format(Locale.US, "%.2f", vm.ttsPitchSys), vm.ttsPitchSys, 0.5f..1.5f) { vm.ttsPitchSys = it; vm.saveTts() }
                Labeled("Пауза между фразами", "${vm.ttsPause} мс", vm.ttsPause.toFloat(), 0f..1000f, 9) { vm.ttsPause = it.toInt(); vm.saveTts() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.loadTtsVoices(true) }) { Text("Обновить") }
                    OutlinedButton({
                        open(Intent("com.android.settings.TTS_SETTINGS"))
                    }) { Text("Настройки TTS") }
                }
                OutlinedButton({ open(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }, Modifier.fillMaxWidth()) {
                    Text("Установить голосовые данные")
                }
            }
        }

        // ── RVC ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("2. Голос RVC", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (vm.ttsRvc) "Речь будет преобразована в выбранный голос" else "Только озвучка: результат — голос синтезатора",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(vm.ttsRvc, { vm.ttsRvc = it; vm.saveTts() })
                }
                if (vm.ttsRvc) {
                    if (vm.voices.isEmpty()) {
                        Text("Нет добавленных голосов RVC.")
                        Button({ vm.tab = 0 }) { Text("Добавить голос") }
                    } else {
                        Picker("Голос", vm.voice?.name ?: "не выбран", vm.voices.map { it.id to it.name }) { id -> vm.voiceId = id }
                    }
                    val hasIdx = vm.voice?.hasIndex == true
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Высота тона:")
                        FilterChip(vm.tFcpe, { vm.tFcpe = true; vm.saveTts() }, label = { Text("FCPE · речь") })
                        FilterChip(!vm.tFcpe, { vm.tFcpe = false; vm.saveTts() }, label = { Text("RMVPE · точный") })
                    }
                    Labeled("Высота голоса (Pitch)", "%+d пт".format(vm.tPitch), vm.tPitch.toFloat(), -24f..24f, 47) { vm.tPitch = it.toInt(); vm.saveTts() }
                    Text(
                        "Совет: мужской синтезатор → женский голос RVC обычно +12, и наоборот −12.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Labeled(
                        "Сходство с голосом (Index Rate)" + if (hasIdx) "" else " — нет .index",
                        String.format(Locale.US, "%.2f", if (hasIdx) vm.tIndex else 0f), vm.tIndex, 0f..1f, enabled = hasIdx,
                    ) { vm.tIndex = it; vm.saveTts() }
                    Labeled("Защита согласных (Protect)", String.format(Locale.US, "%.2f", vm.tProtect), vm.tProtect, 0f..0.5f) { vm.tProtect = it; vm.saveTts() }
                    Labeled("Громкость: исходник ↔ модель (RMS Mix)", String.format(Locale.US, "%.2f", vm.tRms), vm.tRms, 0f..1f) { vm.tRms = it; vm.saveTts() }
                    Labeled("Температура", String.format(Locale.US, "%.2f", vm.tTemp), vm.tTemp, 0f..1f) { vm.tTemp = it; vm.saveTts() }
                }
            }
        }

        if (job.running) {
            JobCard(job) { vm.cancel() }
        } else {
            Button({ go() }, Modifier.fillMaxWidth()) { Text(if (vm.ttsRvc) "Озвучить голосом RVC" else "Озвучить") }
            job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        ResultsList(vm)
    }
}
