package dev.ronova.celestia.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ronova.celestia.ExportPlan
import dev.ronova.celestia.MainVm
import dev.ronova.celestia.SepModel
import dev.ronova.celestia.StemPart
import dev.ronova.celestia.StemSet
import dev.ronova.celestia.core.audio.ExportFmt
import dev.ronova.celestia.service.JobBus

@Composable
private fun SwitchRow(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange)
    }
}

@Composable
fun SepScreen(vm: MainVm) {
    val job by JobBus.state.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickSepInput) }
    val go = rememberNotifGate { vm.startSep() }
    val saveWav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { it?.let(vm::exportToUri) }
    val saveFlac = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/flac")) { it?.let(vm::exportToUri) }
    val saveM4a = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { it?.let(vm::exportToUri) }
    val saveTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::exportBothToTree) }
    var dialogFor by remember { mutableStateOf<StemSet?>(null) }
    var fmt by remember { mutableStateOf(ExportFmt.FLAC) }

    LaunchedEffect(job.result) { vm.refreshStems() }

    fun saveOne(s: StemSet, part: StemPart) {
        val plan = ExportPlan(s, part, fmt)
        vm.exportPlan = plan
        val name = vm.exportFileName(plan)
        when (fmt) {
            ExportFmt.WAV -> saveWav.launch(name)
            ExportFmt.FLAC -> saveFlac.launch(name)
            ExportFmt.M4A -> saveM4a.launch(name)
        }
    }

    Page {
        Text("Разделение вокала и минуса", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Трек делится на два файла: вокал и инструментал (минус). Они остаются в приложении, и любой результат можно " +
                "одним нажатием отправить в конвертацию RVC. Модель загружается только на время работы и потом выгружается.",
            style = MaterialTheme.typography.bodySmall,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Модель", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SepModel.entries.forEach { m ->
                        val ok = vm.engineReady[m.kind] == true
                        FilterChip(vm.sepModel == m, { vm.sepModel = m; vm.saveSep() }, label = { Text(m.title + if (ok) "" else " (нет)") })
                    }
                }
                Text(
                    "Voc FT — чище всего вычищает вокал. Kim Vocal 2 — запасная: иногда лучше сохраняет тихие инструменты. " +
                        "Обе — MDX-Net из Ultimate Vocal Remover, офлайн, ~67 МБ.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SwitchRow("Экономия памяти", "Меньше ОЗУ на пике, немного медленнее. Для слабых устройств оставьте включённым.", vm.sepLowMem) { vm.sepLowMem = it; vm.saveSep() }
                SwitchRow("Плавные стыки", "Куски трека склеиваются с перекрытием: без щелчков, +20% времени.", vm.sepSmooth) { vm.sepSmooth = it; vm.saveSep() }
                SwitchRow("Шумоподавление результата", "Модель запускается дважды: чуть чище, но вдвое дольше.", vm.sepDenoise) { vm.sepDenoise = it; vm.saveSep() }

                OutlinedButton({ pick.launch(arrayOf("audio/*", "video/*", "*/*")) }, Modifier.fillMaxWidth()) {
                    Text(if (vm.sepInputName.isEmpty()) "Выбрать трек" else "✓ ${vm.sepInputName}")
                }
                if (job.running) {
                    JobCard(job) { vm.cancel() }
                } else {
                    Button({ go() }, Modifier.fillMaxWidth()) { Text("Разделить") }
                    job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(
                        "Разделение — самая тяжёлая операция: трек обрабатывается кусками по ~6 с, на телефоне это занимает " +
                            "заметное время. Экран можно выключить: работа идёт в фоне с уведомлением.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        vm.exporting?.let { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t)
                    LinearProgressIndicator(progress = { vm.exportProgress }, Modifier.fillMaxWidth())
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("Разделённые песни", style = MaterialTheme.typography.titleMedium)
        if (vm.stems.isEmpty()) Text("Пока ничего нет. Выберите трек и нажмите «Разделить».", style = MaterialTheme.typography.bodyMedium)
        vm.stems.forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text("${s.model} · %d:%02d".format(s.seconds / 60, s.seconds % 60), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ vm.togglePlayStem(s.vocals) }) { Text(if (vm.playing == vm.playingKey(s.vocals)) "■ Вокал" else "▶ Вокал") }
                        TextButton({ vm.togglePlayStem(s.instrumental) }) { Text(if (vm.playing == vm.playingKey(s.instrumental)) "■ Минус" else "▶ Минус") }
                    }
                    Button({ vm.useStems(s) }, Modifier.fillMaxWidth()) { Text("Использовать в RVC") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ dialogFor = s }) { Text("Сохранить…") }
                        TextButton({ vm.deleteStem(s) }) { Text("Удалить") }
                    }
                }
            }
        }
    }

    dialogFor?.let { s ->
        AlertDialog(
            onDismissRequest = { dialogFor = null },
            confirmButton = {},
            dismissButton = { TextButton({ dialogFor = null }) { Text("Отмена") } },
            title = { Text("Сохранить «${s.name}»") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Формат", style = MaterialTheme.typography.labelLarge)
                    ExportFmt.entries.forEach { f ->
                        Row(Modifier.fillMaxWidth().clickable { fmt = f }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(fmt == f, { fmt = f })
                            Text(f.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button({ dialogFor = null; saveOne(s, StemPart.VOCALS) }, Modifier.fillMaxWidth()) { Text("Вокал…") }
                    Button({ dialogFor = null; saveOne(s, StemPart.INSTRUMENTAL) }, Modifier.fillMaxWidth()) { Text("Минус…") }
                    OutlinedButton({
                        dialogFor = null
                        vm.exportPlan = ExportPlan(s, StemPart.BOTH, fmt)
                        saveTree.launch(null)
                    }, Modifier.fillMaxWidth()) { Text("Оба файла в папку…") }
                }
            },
        )
    }
}
