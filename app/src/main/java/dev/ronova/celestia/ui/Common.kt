package dev.ronova.celestia.ui

import dev.ronova.celestia.R
import dev.ronova.celestia.tr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import dev.ronova.celestia.Lang
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
            TextButton(onCancel) { Text(tr(R.string.cancel_job)) }
        }
    }
}

/** Готовые WAV-результаты (озвучка и конвертация): играть, сохранить, удалить. */
@Composable
fun ResultsList(vm: MainVm) {
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { it?.let(vm::saveTo) }
    if (vm.outputs.isEmpty()) return
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

/** Выбор языка интерфейса: «как в системе», русский, English, беларуская, українська. */
@Composable
fun LanguagePicker() {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf(Lang.saved(ctx)) }
    val items = listOf(
        Lang.SYSTEM to tr(R.string.lang_system),
        "ru" to "Русский",
        "en" to "English",
        "be" to "Беларуская",
        "uk" to "Українська",
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(R.string.lang_ui_label), style = MaterialTheme.typography.titleMedium)
            Picker(tr(R.string.lang_label), items.firstOrNull { it.first == cur }?.second ?: items[0].second, items) { id ->
                if (id != cur) {
                    cur = id
                    Lang.save(ctx, id)
                    var c: Context = ctx
                    while (c is ContextWrapper) {
                        if (c is Activity) { c.recreate(); break }
                        c = c.baseContext
                    }
                }
            }
            Text(tr(R.string.lang_ui_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}
