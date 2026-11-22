package dev.ronova.celestia.ui

import dev.ronova.celestia.R
import dev.ronova.celestia.tr

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ronova.celestia.EngineKind
import dev.ronova.celestia.MainVm
import dev.ronova.celestia.core.engine.F0Mode
import dev.ronova.celestia.service.JobBus
import java.util.Locale

@Composable
fun App(vm: MainVm) {
    val titles = listOf(tr(R.string.tab_voices), tr(R.string.tab_convert), tr(R.string.tab_tts), tr(R.string.tab_sep), tr(R.string.tab_settings))
    val icons = listOf("🎙", "🎚", "🗣", "✂", "⚙")
    Scaffold(bottomBar = {
        NavigationBar {
            titles.forEachIndexed { i, t ->
                NavigationBarItem(selected = vm.tab == i, onClick = { vm.tab = i }, icon = { Text(icons[i]) }, label = { Text(t, maxLines = 1, fontSize = 11.sp) })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            vm.installing?.let { t ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { vm.installProgress }, Modifier.fillMaxWidth())
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (vm.tab) {
                    0 -> VoicesScreen(vm)
                    1 -> ConvertScreen(vm)
                    2 -> TtsScreen(vm)
                    3 -> SepScreen(vm)
                    else -> EngineScreen(vm)
                }
            }
        }
        vm.message?.let { m ->
            AlertDialog(
                onDismissRequest = { vm.message = null },
                confirmButton = { TextButton({ vm.message = null }) { Text("OK") } },
                text = { Text(m) },
            )
        }
    }
}

/** Облако с розово-голубым переливом — тот же рисунок, что в иконке приложения. */
@Composable
fun CloudLogo(logoSize: Dp = 48.dp) {
    Canvas(Modifier.size(logoSize)) {
        val w = size.width
        val u = w / 52f                          // облако: 52 x 35 условных единиц
        val oy = (size.height - 35f * u) / 2f
        fun x(v: Float) = (v - 28f) * u
        fun y(v: Float) = (v - 36f) * u + oy
        val b = Brush.linearGradient(
            listOf(Color(0xFFFF8BC7), Color(0xFF7DD3FC)),
            start = Offset(0f, 0f), end = Offset(w, size.height),
        )
        drawRoundRect(b, Offset(x(28f), y(54f)), Size(52f * u, 17f * u), CornerRadius(8.5f * u, 8.5f * u))
        drawCircle(b, 10f * u, Offset(x(43f), y(55f)))
        drawCircle(b, 14f * u, Offset(x(56f), y(50f)))
        drawCircle(b, 9f * u, Offset(x(68f), y(57f)))
    }
}

@Composable
internal fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CloudLogo(56.dp)
        Column {
            Text("Celestia", style = MaterialTheme.typography.headlineMedium)
            Text(tr(R.string.brand_tagline), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
internal fun Importing(vm: MainVm) {
    vm.importing?.let { t ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t)
                if (vm.importProgress >= 0f) LinearProgressIndicator(progress = { vm.importProgress }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun Labeled(
    label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int = 0, enabled: Boolean = true, onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = v, onValueChange = onChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

// ───────────────────────── Голоса ─────────────────────────

@Composable
fun VoicesScreen(vm: MainVm) {
    val pickPth = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickPth) }
    val pickNewIndex = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickNewIndex) }
    var indexFor by remember { mutableStateOf<String?>(null) }
    val pickIndex = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val v = vm.voices.firstOrNull { it.id == indexFor }
        if (uri != null && v != null) vm.attachIndex(v, uri)
    }

    Page {
        Brand()

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(R.string.voices_add_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr(R.string.voices_add_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton({ pickPth.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                    Text(if (vm.newPthName.isEmpty()) tr(R.string.voices_pick_pth) else "✓ ${vm.newPthName}")
                }
                OutlinedButton({ pickNewIndex.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                    Text(if (vm.newIndexName.isEmpty()) tr(R.string.voices_pick_index) else "✓ ${vm.newIndexName}")
                }
                OutlinedTextField(
                    value = vm.newName, onValueChange = { vm.newName = it },
                    label = { Text(tr(R.string.voices_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.addVoice() }, enabled = vm.newPth != null && vm.importing == null, modifier = Modifier.fillMaxWidth()) {
                    Text(tr(R.string.voices_convert_add))
                }
                if (vm.newPth != null || vm.newIndex != null) TextButton({ vm.clearForm() }) { Text(tr(R.string.voices_reset)) }
            }
        }

        Importing(vm)

        Text(tr(R.string.voices_mine), style = MaterialTheme.typography.titleMedium)
        if (vm.voices.isEmpty()) Text(tr(R.string.voices_empty), style = MaterialTheme.typography.bodyMedium)
        vm.voices.forEach { v ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(v.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (vm.voiceId == v.id) Text(tr(R.string.voice_selected), style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        tr(R.string.voice_meta, v.sr / 1000, tr(if (v.hasIndex) R.string.index_connected else R.string.index_none)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button({ vm.selectVoice(v) }, Modifier.fillMaxWidth()) { Text(tr(R.string.voice_use)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ indexFor = v.id; pickIndex.launch(arrayOf("*/*")) }) {
                            Text(if (v.hasIndex) tr(R.string.index_replace) else tr(R.string.index_add))
                        }
                        if (v.hasIndex) TextButton({ vm.removeIndex(v) }) { Text(tr(R.string.index_remove)) }
                        TextButton({ vm.deleteVoice(v) }) { Text(tr(R.string.delete)) }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Конвертация ─────────────────────────

@Composable
fun ConvertScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val job by JobBus.state.collectAsStateWithLifecycle()
    val pickIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickInput) }
    val pickMinus = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::pickMinus) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { it?.let(vm::saveTo) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }

    LaunchedEffect(job.result) { vm.refreshOutputs() }

    Page {
        Text(tr(R.string.convert_title), style = MaterialTheme.typography.headlineSmall)

        vm.note?.let { n ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(n, style = MaterialTheme.typography.bodyMedium)
                    TextButton({ vm.note = null }) { Text(tr(R.string.hide)) }
                }
            }
        }

        Text(tr(R.string.voice_label), style = MaterialTheme.typography.titleMedium)
        if (vm.voices.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(R.string.convert_no_voices))
                    Button({ vm.tab = 0 }) { Text(tr(R.string.voices_add_title)) }
                }
            }
        } else {
            vm.voices.forEach { v ->
                Card(Modifier.fillMaxWidth().clickable { vm.voiceId = v.id }) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = vm.voiceId == v.id, onClick = { vm.voiceId = v.id })
                        Column {
                            Text(v.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                tr(R.string.voice_meta, v.sr / 1000, tr(if (v.hasIndex) R.string.index_present else R.string.index_none)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Text(tr(R.string.audio_label), style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ pickIn.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
            Text(if (vm.inputName.isEmpty()) tr(R.string.pick_audio_file) else "✓ ${vm.inputName}")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(R.string.pitch_mode_label))
            FilterChip(vm.f0Mode == F0Mode.RMVPE, { vm.f0Mode = F0Mode.RMVPE }, label = { Text(tr(R.string.f0_rmvpe_chip)) })
            FilterChip(vm.f0Mode == F0Mode.FCPE, { vm.f0Mode = F0Mode.FCPE }, label = { Text(tr(R.string.f0_fcpe_chip)) })
        }
        Labeled(tr(R.string.lbl_pitch), tr(R.string.fmt_st, vm.pitch), vm.pitch.toFloat(), -24f..24f, 47) { vm.pitch = it.toInt() }
        val hasIdx = vm.voice?.hasIndex == true
        Labeled(
            tr(if (hasIdx) R.string.lbl_index_rate else R.string.lbl_index_rate_noidx),
            String.format(Locale.US, "%.2f", if (hasIdx) vm.indexRate else 0f), vm.indexRate, 0f..1f, enabled = hasIdx,
        ) { vm.indexRate = it }
        Labeled(tr(R.string.lbl_protect), String.format(Locale.US, "%.2f", vm.protect), vm.protect, 0f..0.5f) { vm.protect = it }
        Labeled(tr(R.string.lbl_rms), String.format(Locale.US, "%.2f", vm.rmsMix), vm.rmsMix, 0f..1f) { vm.rmsMix = it }
        Labeled(tr(R.string.lbl_temp_long), String.format(Locale.US, "%.2f", vm.temperature), vm.temperature, 0f..1f) { vm.temperature = it }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(tr(R.string.mixer_switch)); Switch(vm.mixer, { vm.mixer = it })
        }
        if (vm.mixer) {
            OutlinedButton({ pickMinus.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) {
                Text(if (vm.minusName.isEmpty()) tr(R.string.pick_minus) else "✓ ${vm.minusName}")
            }
            Labeled(tr(R.string.gain_vocal), "%d%%".format((vm.vocalGain * 100).toInt()), vm.vocalGain, 0f..2f) { vm.vocalGain = it }
            Labeled(tr(R.string.gain_minus), "%d%%".format((vm.minusGain * 100).toInt()), vm.minusGain, 0f..2f) { vm.minusGain = it }
        }

        if (job.running) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(job.message)
                    LinearProgressIndicator(progress = { job.progress }, Modifier.fillMaxWidth())
                    TextButton({ vm.cancel() }) { Text(tr(R.string.cancel_job)) }
                }
            }
        } else {
            Button({
                val need = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                if (need) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
            }, Modifier.fillMaxWidth()) { Text(tr(R.string.convert_btn)) }
            job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        if (vm.outputs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(tr(R.string.results), style = MaterialTheme.typography.titleMedium)
            vm.outputs.forEach { f ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.nameWithoutExtension, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton({ vm.togglePlay(f) }) { Text(if (vm.playing == f.name) tr(R.string.stop) else tr(R.string.play)) }
                            TextButton({ vm.saveTarget = f; save.launch(f.name) }) { Text(tr(R.string.save_dots)) }
                            TextButton({ vm.deleteOutput(f) }) { Text(tr(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Движок ─────────────────────────

@Composable
fun EngineScreen(vm: MainVm) {
    var target by remember { mutableStateOf<EngineKind?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val k = target
        if (uri != null && k != null) vm.importEngine(k, uri)
    }
    Page {
        Brand()
        LanguagePicker()
        Text(
            tr(R.string.engine_intro),
            style = MaterialTheme.typography.bodySmall,
        )
        Importing(vm)
        if (vm.engineReady.values.any { !it }) {
            Button({ vm.installModels() }, enabled = vm.installing == null, modifier = Modifier.fillMaxWidth()) {
                Text(tr(R.string.engine_install_btn))
            }
        }
        EngineKind.entries.forEach { k ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr(k.title), style = MaterialTheme.typography.titleMedium)
                    Text(tr(k.hint), style = MaterialTheme.typography.bodySmall)
                    val f = vm.store.engineFile(k)
                    Text(if (vm.engineReady[k] == true) tr(R.string.engine_installed, f.length() / 1e6) else tr(R.string.engine_not_installed))
                    OutlinedButton({ target = k; pick.launch(arrayOf("*/*")) }) { Text(tr(R.string.engine_pick_own)) }
                }
            }
        }
        Text(tr(R.string.perf_title), style = MaterialTheme.typography.titleMedium)
        Labeled(tr(R.string.lbl_threads), "${vm.threads}", vm.threads.toFloat(), 1f..8f, 6) { vm.changeThreads(it.toInt()) }
        Labeled(tr(R.string.lbl_window), tr(R.string.fmt_sec, vm.windowSec), vm.windowSec, 4f..8f, 3) { vm.changeWindow(it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(tr(R.string.xnn_title))
                Text(tr(R.string.xnn_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(vm.xnnpack, { vm.changeXnnpack(it) })
        }
        Text(tr(R.string.no_nnapi), style = MaterialTheme.typography.bodySmall)

        val ctx = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val version = remember {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—"
        }
        Spacer(Modifier.height(8.dp))
        Text(tr(R.string.about_title), style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CloudLogo(48.dp)
                    Column {
                        Text("Celestia", style = MaterialTheme.typography.titleLarge)
                        Text(tr(R.string.about_version, version), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(tr(R.string.about_desc), style = MaterialTheme.typography.bodyMedium)
                Text(tr(R.string.about_author), style = MaterialTheme.typography.bodyLarge)
                TextButton({ runCatching { uriHandler.openUri("https://github.com/RonovaMain") } }) {
                    Text("GitHub: RonovaMain")
                }
            }
        }
    }
}
