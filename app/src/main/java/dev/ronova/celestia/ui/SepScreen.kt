package dev.ronova.celestia.ui

import dev.ronova.celestia.R
import dev.ronova.celestia.tr

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
        Text(tr(R.string.sep_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            tr(R.string.sep_intro),
            style = MaterialTheme.typography.bodySmall,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(R.string.sep_model), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SepModel.entries.forEach { m ->
                        val ok = vm.engineReady[m.kind] == true
                        FilterChip(vm.sepModel == m, { vm.sepModel = m; vm.saveSep() }, label = { Text(if (ok) tr(m.title) else tr(R.string.sep_model_missing, tr(m.title))) })
                    }
                }
                Text(
                    tr(R.string.sep_model_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                SwitchRow(tr(R.string.sep_lowmem_t), tr(R.string.sep_lowmem_h), vm.sepLowMem) { vm.sepLowMem = it; vm.saveSep() }
                SwitchRow(tr(R.string.sep_smooth_t), tr(R.string.sep_smooth_h), vm.sepSmooth) { vm.sepSmooth = it; vm.saveSep() }
                SwitchRow(tr(R.string.sep_denoise_t), tr(R.string.sep_denoise_h), vm.sepDenoise) { vm.sepDenoise = it; vm.saveSep() }

                OutlinedButton({ pick.launch(arrayOf("audio/*", "video/*", "*/*")) }, Modifier.fillMaxWidth()) {
                    Text(if (vm.sepInputName.isEmpty()) tr(R.string.sep_pick) else "✓ ${vm.sepInputName}")
                }
                if (job.running) {
                    JobCard(job) { vm.cancel() }
                } else {
                    Button({ go() }, Modifier.fillMaxWidth()) { Text(tr(R.string.sep_go)) }
                    job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(
                        tr(R.string.sep_heavy),
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
        Text(tr(R.string.sep_list_title), style = MaterialTheme.typography.titleMedium)
        if (vm.stems.isEmpty()) Text(tr(R.string.sep_empty), style = MaterialTheme.typography.bodyMedium)
        vm.stems.forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text("${s.model} · %d:%02d".format(s.seconds / 60, s.seconds % 60), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ vm.togglePlayStem(s.vocals) }) { Text(if (vm.playing == vm.playingKey(s.vocals)) tr(R.string.vocals_stop) else tr(R.string.vocals_play)) }
                        TextButton({ vm.togglePlayStem(s.instrumental) }) { Text(if (vm.playing == vm.playingKey(s.instrumental)) tr(R.string.minus_stop) else tr(R.string.minus_play)) }
                    }
                    Button({ vm.useStems(s) }, Modifier.fillMaxWidth()) { Text(tr(R.string.sep_use_rvc)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ dialogFor = s }) { Text(tr(R.string.save_dots)) }
                        TextButton({ vm.deleteStem(s) }) { Text(tr(R.string.delete)) }
                    }
                }
            }
        }
    }

    dialogFor?.let { s ->
        AlertDialog(
            onDismissRequest = { dialogFor = null },
            confirmButton = {},
            dismissButton = { TextButton({ dialogFor = null }) { Text(tr(R.string.cancel)) } },
            title = { Text(tr(R.string.sep_save_title, s.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr(R.string.format_label), style = MaterialTheme.typography.labelLarge)
                    ExportFmt.entries.forEach { f ->
                        Row(Modifier.fillMaxWidth().clickable { fmt = f }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(fmt == f, { fmt = f })
                            Text(tr(f.title), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button({ dialogFor = null; saveOne(s, StemPart.VOCALS) }, Modifier.fillMaxWidth()) { Text(tr(R.string.sep_save_vocals)) }
                    Button({ dialogFor = null; saveOne(s, StemPart.INSTRUMENTAL) }, Modifier.fillMaxWidth()) { Text(tr(R.string.sep_save_minus)) }
                    OutlinedButton({
                        dialogFor = null
                        vm.exportPlan = ExportPlan(s, StemPart.BOTH, fmt)
                        saveTree.launch(null)
                    }, Modifier.fillMaxWidth()) { Text(tr(R.string.sep_save_both)) }
                }
            },
        )
    }
}
