package dev.ronova.celestia.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ronova.celestia.EngineKind
import dev.ronova.celestia.MainVm
import dev.ronova.celestia.core.engine.F0Mode
import dev.ronova.celestia.service.JobBus
import java.util.Locale

@Composable
fun App(vm: MainVm) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Голоса", "Конвертация", "Движок")
    val icons = listOf("🎙", "🎚", "⚙")
    Scaffold(bottomBar = {
        NavigationBar {
            titles.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icons[i]) }, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> VoicesScreen(vm)
                1 -> ConvertScreen(vm)
                else -> EngineScreen(vm)
            }
        }
        vm.message?.let { m ->
            AlertDialog(onDismissRequest = { vm.message = null }, confirmButton = { TextButton({ vm.message = null }) { Text("OK") } }, text = { Text(m) })
        }
    }
}

/** Облако с розово-голубым переливом — тот же рисунок, что в иконке приложения. */
@Composable
fun CloudLogo(logoSize: Dp = 48.dp) {
    val brush = Brush.linearGradient(
        listOf(Color(0xFFFF8BC7), Color(0xFF7DD3FC)),
        start = Offset.Zero, end = Offset(1000f, 1000f),
    )
    Canvas(Modifier.size(logoSize)) {
        val w = size.width
        val u = w / 49f                       // облако занимает 49×36 условных единиц
        val oy = (size.height - 36f * u) / 2f
        fun x(v: Float) = (v - 29f) * u
        fun y(v: Float) = (v - 34f) * u + oy
        val b = Brush.linearGradient(listOf(Color(0xFFFF8BC7), Color(0xFF7DD3FC)), start = Offset(0f, 0f), end = Offset(w, size.height))
        drawCircle(b, 11f * u, Offset(x(40f), y(58f)))
        drawCircle(b, 14f * u, Offset(x(52f), y(48f)))
        drawCircle(b, 12f * u, Offset(x(66f), y(55f)))
        drawRect(b, Offset(x(40f), y(58f)), Size(26f * u, 12f * u))
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CloudLogo(56.dp)
        Column {
            Text("Celestia", style = MaterialTheme.typography.headlineMedium)
            Text("Смена голоса офлайн · RVC v2", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun Busy(vm: MainVm) {
    vm.busy?.let { t ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t)
                if (vm.busyProgress >= 0f) LinearProgressIndicator(progress = { vm.busyProgress }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Labeled(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = v, onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
fun VoicesScreen(vm: MainVm) {
    val pickPth = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importPth) }
    var indexFor by remember { mutableStateOf<String?>(null) }
    val pickIndex = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val v = vm.voices.firstOrNull { it.id == indexFor }
        if (uri != null && v != null) vm.importIndex(v, uri)
    }
    Page {
        Brand()
        Text("Выберите .pth — он будет потоково собран в ONNX прямо на телефоне (только веса генератора).", style = MaterialTheme.typography.bodySmall)
        Button({ pickPth.launch(arrayOf("*/*")) }, enabled = vm.busy == null, modifier = Modifier.fillMaxWidth()) { Text("Импортировать .pth") }
        Busy(vm)
        if (vm.voices.isEmpty()) Text("Пока нет голосов.")
        vm.voices.forEach { v ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(v.name, style = MaterialTheme.typography.titleMedium)
                    Text("${v.sr / 1000} кГц · индекс: ${if (v.hasIndex) "прикреплён" else "нет"}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (v.hasIndex) OutlinedButton({ vm.removeIndex(v) }) { Text("Убрать .index") }
                        else OutlinedButton({ indexFor = v.id; pickIndex.launch(arrayOf("*/*")) }, enabled = vm.busy == null) { Text("Прикрепить .index") }
                        TextButton({ vm.deleteVoice(v) }) { Text("Удалить") }
                    }
                }
            }
        }
    }
}

@Composable
fun ConvertScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val job by JobBus.state.collectAsStateWithLifecycle()
    val pickIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickInput) }
    val pickMinus = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickMinus) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { it?.let(vm::saveTo) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(job.result) { vm.refreshOutputs() }

    Page {
        Text("Конвертация", style = MaterialTheme.typography.headlineSmall)

        Box {
            OutlinedButton({ menu = true }, Modifier.fillMaxWidth()) { Text(vm.voice?.name ?: "Выберите голос") }
            DropdownMenu(menu, { menu = false }) {
                vm.voices.forEach { v -> DropdownMenuItem(text = { Text(v.name) }, onClick = { vm.voiceId = v.id; menu = false }) }
            }
        }
        OutlinedButton({ pickIn.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text(if (vm.inputName.isEmpty()) "Выбрать аудио" else vm.inputName) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("F0:")
            FilterChip(vm.f0Mode == F0Mode.RMVPE, { vm.f0Mode = F0Mode.RMVPE }, label = { Text("RMVPE · пение") })
            FilterChip(vm.f0Mode == F0Mode.FCPE, { vm.f0Mode = F0Mode.FCPE }, label = { Text("FCPE · речь") })
        }
        Labeled("Высота голоса (Pitch)", "%+d пт".format(vm.pitch), vm.pitch.toFloat(), -24f..24f, 47) { vm.pitch = it.toInt() }
        val hasIdx = vm.voice?.hasIndex == true
        Labeled("Сходство с голосом (Index Rate)" + if (hasIdx) "" else " — нет .index", String.format(Locale.US, "%.2f", if (hasIdx) vm.indexRate else 0f), vm.indexRate, 0f..1f, enabled = hasIdx) { vm.indexRate = it }
        Labeled("Защита согласных (Protect)", String.format(Locale.US, "%.2f", vm.protect), vm.protect, 0f..0.5f) { vm.protect = it }
        Labeled("Громкость: исходник ↔ модель (RMS Mix)", String.format(Locale.US, "%.2f", vm.rmsMix), vm.rmsMix, 0f..1f) { vm.rmsMix = it }
        Labeled("Температура (0 — ровно, выше — живее)", String.format(Locale.US, "%.2f", vm.temperature), vm.temperature, 0f..1f) { vm.temperature = it }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Микшер с минусом"); Switch(vm.mixer, { vm.mixer = it })
        }
        if (vm.mixer) {
            OutlinedButton({ pickMinus.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text(if (vm.minusName.isEmpty()) "Выбрать минусовку" else vm.minusName) }
            Labeled("Громкость вокала", "%d%%".format((vm.vocalGain * 100).toInt()), vm.vocalGain, 0f..2f) { vm.vocalGain = it }
            Labeled("Громкость минуса", "%d%%".format((vm.minusGain * 100).toInt()), vm.minusGain, 0f..2f) { vm.minusGain = it }
        }

        if (job.running) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(job.message)
                    LinearProgressIndicator(progress = { job.progress }, Modifier.fillMaxWidth())
                    TextButton(vm::cancel) { Text("Отменить") }
                }
            }
        } else {
            Button({
                val need = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                if (need) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
            }, Modifier.fillMaxWidth()) { Text("Конвертировать") }
            job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        if (vm.outputs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Результаты", style = MaterialTheme.typography.titleMedium)
            vm.outputs.forEach { f ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.nameWithoutExtension, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ vm.togglePlay(f) }) { Text(if (vm.playing == f.name) "Стоп" else "Играть") }
                            TextButton({ vm.saveTarget = f; save.launch(f.name) }) { Text("Сохранить…") }
                            TextButton({ vm.deleteOutput(f) }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EngineScreen(vm: MainVm) {
    var target by remember { mutableStateOf<EngineKind?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val k = target; if (uri != null && k != null) vm.importEngine(k, uri)
    }
    Page {
        Brand()
        Text("Модели вшиты в приложение и устанавливаются при первом запуске. Здесь их можно заменить своими .onnx.", style = MaterialTheme.typography.bodySmall)
        Busy(vm)
        EngineKind.entries.forEach { k ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(k.title, style = MaterialTheme.typography.titleMedium)
                    Text(k.hint, style = MaterialTheme.typography.bodySmall)
                    Text(if (vm.engineReady[k] == true) "✓ загружен" else "не загружен")
                    OutlinedButton({ target = k; pick.launch(arrayOf("*/*")) }, enabled = vm.busy == null) { Text("Заменить .onnx") }
                }
            }
        }
        Text("Производительность", style = MaterialTheme.typography.titleMedium)
        Labeled("Потоков ONNX Runtime", "${vm.threads}", vm.threads.toFloat(), 1f..8f, 6) { vm.changeThreads(it.toInt()) }
        Labeled("Длина фрагмента", String.format(Locale.US, "%.0f с", vm.windowSec), vm.windowSec, 4f..8f, 3) { vm.changeWindow(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("XNNPACK (экспериментально)")
                Text("Только CPU. Для 1D-свёрток RVC обычно не быстрее стандартного CPU; при проблемах держите выключенным.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(vm.xnnpack, { vm.changeXnnpack(it) })
        }
        Text("NNAPI и GPU-делегаты в приложении не используются вообще.", style = MaterialTheme.typography.bodySmall)
    }
}
