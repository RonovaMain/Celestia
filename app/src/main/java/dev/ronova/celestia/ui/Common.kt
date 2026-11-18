package dev.ronova.celestia.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ronova.celestia.MainVm
import dev.ronova.celestia.service.JobState

/** Перед запуском фоновой задачи просим разрешение на уведомления (Android 13+), затем запускаем в любом случае. */
@Composable
fun rememberNotifGate(onGo: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onGo() }
    return {
        val need = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (need) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else onGo()
    }
}

/** Выпадающий список: items = (id, подпись). */
@Composable
fun Picker(label: String, current: String, items: List<Pair<String, String>>, enabled: Boolean = true, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth(), enabled = enabled) {
            Text("$label: $current", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

@Composable
fun JobCard(job: JobState, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.kind.isNotEmpty()) Text(job.kind, style = MaterialTheme.typography.labelLarge)
            Text(job.message)
            LinearProgressIndicator(progress = { job.progress }, Modifier.fillMaxWidth())
            TextButton(onCancel) { Text("Отменить") }
        }
    }
}

/** Готовые WAV-результаты (озвучка и конвертация): играть, сохранить, удалить. */
@Composable
fun ResultsList(vm: MainVm) {
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { it?.let(vm::saveTo) }
    if (vm.outputs.isEmpty()) return
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
