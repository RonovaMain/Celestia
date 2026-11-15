package dev.ronova.celestia

import android.app.Application
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ronova.celestia.core.engine.F0Mode
import dev.ronova.celestia.core.engine.Ort
import dev.ronova.celestia.core.engine.Params
import dev.ronova.celestia.core.faiss.FaissIndex
import dev.ronova.celestia.core.pth.PthToOnnx
import dev.ronova.celestia.service.ConvertService
import dev.ronova.celestia.service.JobBus
import dev.ronova.celestia.service.JobSpec
import dev.ronova.celestia.service.JobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainVm(private val app: Application) : AndroidViewModel(app) {
    val store = Store(app)

    var voices by mutableStateOf(store.voices()); private set
    var outputs by mutableStateOf(store.outputs()); private set
    var engineReady by mutableStateOf(EngineKind.entries.associateWith { store.engineFile(it).exists() }); private set

    var tab by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)

    // установка встроенных моделей (ContentVec/RMVPE/FCPE из APK)
    var installing by mutableStateOf<String?>(null); private set
    var installProgress by mutableFloatStateOf(0f); private set

    // импорт голоса / индекса / замена модели
    var importing by mutableStateOf<String?>(null); private set
    var importProgress by mutableFloatStateOf(-1f); private set

    // форма «Добавить голос»
    var newPth by mutableStateOf<Uri?>(null); private set
    var newPthName by mutableStateOf(""); private set
    var newIndex by mutableStateOf<Uri?>(null); private set
    var newIndexName by mutableStateOf(""); private set
    var newName by mutableStateOf("")

    // настройки (хранятся)
    var threads by mutableIntStateOf(store.getInt("threads", 4)); private set
    var xnnpack by mutableStateOf(store.getBool("xnnpack", false)); private set
    var windowSec by mutableFloatStateOf(store.getFloat("window", 6f)); private set

    // параметры конвертации
    var voiceId by mutableStateOf<String?>(voices.firstOrNull()?.id)
    var input by mutableStateOf<Uri?>(null); private set
    var inputName by mutableStateOf(""); private set
    var minus by mutableStateOf<Uri?>(null); private set
    var minusName by mutableStateOf(""); private set
    var mixer by mutableStateOf(false)
    var vocalGain by mutableFloatStateOf(1f)
    var minusGain by mutableFloatStateOf(0.8f)
    var pitch by mutableIntStateOf(0)
    var indexRate by mutableFloatStateOf(0.5f)
    var protect by mutableFloatStateOf(0.33f)
    var rmsMix by mutableFloatStateOf(0.25f)
    var temperature by mutableFloatStateOf(0.667f)
    var f0Mode by mutableStateOf(F0Mode.RMVPE)

    var saveTarget: File? = null
    private var player: MediaPlayer? = null
    var playing by mutableStateOf<String?>(null); private set

    val voice: VoiceMeta? get() = voices.firstOrNull { it.id == voiceId }

    init { installModels() }

    fun changeThreads(v: Int) { threads = v; store.put("threads", v) }
    fun changeXnnpack(v: Boolean) { xnnpack = v; store.put("xnnpack", v) }
    fun changeWindow(v: Float) { windowSec = v; store.put("window", v) }

    private fun grant(uri: Uri) {
        runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is OutOfMemoryError -> "Не хватило памяти. Закройте другие приложения или уберите .index."
        else -> (e.message ?: e.toString()).take(400)
    }

    private fun refresh() {
        voices = store.voices(); outputs = store.outputs()
        engineReady = EngineKind.entries.associateWith { store.engineFile(it).exists() }
        if (voice == null) voiceId = voices.firstOrNull()?.id
    }

    /** Все импорты идут через одну точку; если один уже идёт, пользователь это видит, а не получает тишину. */
    private fun launchImport(title: String, block: () -> Unit) {
        if (importing != null) { message = "Подождите, ещё идёт: $importing"; return }
        importing = title; importProgress = -1f
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (e: Throwable) { message = friendly(e) }
            finally { importing = null; importProgress = -1f; refresh() }
        }
    }

    // ---------- встроенные модели ----------
    fun installModels() {
        if (installing != null) return
        installing = "Подготавливаю встроенные модели (один раз)…"; installProgress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            try { store.installBundled { t, p -> installing = "Копирую модель: $t"; installProgress = p } }
            catch (e: Throwable) { message = "Не удалось установить встроенные модели: ${friendly(e)}" }
            finally { installing = null; refresh() }
        }
    }

    fun importEngine(kind: EngineKind, uri: Uri) = launchImport("Копирую ${kind.title}…") {
        val tmp = File(store.engineDir, kind.file + ".tmp")
        store.copy(uri, tmp)
        try { Ort.open(tmp.absolutePath, 1, false).close() } catch (e: Throwable) {
            tmp.delete(); throw IllegalStateException("Файл не открывается как ONNX: ${e.message}")
        }
        tmp.renameTo(store.engineFile(kind))
    }

    // ---------- голоса ----------
    fun pickPth(uri: Uri) {
        grant(uri); newPth = uri; newPthName = store.displayName(uri)
        if (newName.isBlank()) newName = newPthName.substringBeforeLast('.')
    }

    fun pickNewIndex(uri: Uri) { grant(uri); newIndex = uri; newIndexName = store.displayName(uri) }

    fun clearForm() { newPth = null; newPthName = ""; newIndex = null; newIndexName = ""; newName = "" }

    private fun copyIndex(uri: Uri, dir: File) {
        val tmp = File(store.tmpDir, "idx.tmp")
        store.copy(uri, tmp)
        try {
            val idx = FaissIndex.load(tmp)
            check(idx.dim == 768) { "Индекс на ${idx.dim} измерений: нужен RVC v2 (768)" }
            tmp.copyTo(File(dir, "voice.index"), overwrite = true)
        } finally { tmp.delete() }
    }

    /** .pth -> ONNX (+ по желанию .index) одним действием. */
    fun addVoice() {
        val pth = newPth ?: run { message = "Сначала выберите файл .pth"; return }
        val idx = newIndex
        val name = newName.trim().ifEmpty { newPthName.substringBeforeLast('.') }
        launchImport("Конвертирую «$name» в ONNX…") {
            val dir = File(store.voicesDir, UUID.randomUUID().toString().take(8))
            PthToOnnx.convert(app, pth, dir, name) { p -> importProgress = p }
            voiceId = dir.name
            var note = "Голос «$name» добавлен."
            if (idx != null) {
                importProgress = -1f; importing = "Проверяю .index…"
                note += try { copyIndex(idx, dir); " Index подключён." } catch (e: Throwable) { " Но .index не подошёл: ${friendly(e)}" }
            }
            clearForm()
            message = note
        }
    }

    fun attachIndex(v: VoiceMeta, uri: Uri) = launchImport("Проверяю .index…") {
        copyIndex(uri, v.dir)
        message = "Index подключён к «${v.name}»."
    }

    fun removeIndex(v: VoiceMeta) { File(v.dir, "voice.index").delete(); refresh() }
    fun deleteVoice(v: VoiceMeta) { v.dir.deleteRecursively(); refresh() }
    fun selectVoice(v: VoiceMeta) { voiceId = v.id; tab = 1 }

    // ---------- конвертация ----------
    fun pickInput(uri: Uri) { grant(uri); input = uri; inputName = store.displayName(uri) }
    fun pickMinus(uri: Uri) { grant(uri); minus = uri; minusName = store.displayName(uri) }

    fun start(): Boolean {
        val v = voice ?: run { message = "Выберите голос (вкладка «Голоса»)"; return false }
        val inp = input ?: run { message = "Выберите аудиофайл"; return false }
        if (mixer && minus == null) { message = "Выберите минусовку или выключите микшер"; return false }
        if (installing != null) { message = "Модели ещё устанавливаются, подождите"; return false }
        if (engineReady[EngineKind.HUBERT] != true) { message = "Нет модели ContentVec: вкладка «Настройки»"; return false }
        val f0k = if (f0Mode == F0Mode.RMVPE) EngineKind.RMVPE else EngineKind.FCPE
        if (engineReady[f0k] != true) { message = "Нет модели ${f0k.title}: вкладка «Настройки»"; return false }
        JobBus.pending = JobSpec(
            voice = v, input = inp, minus = if (mixer) minus else null,
            vocalGain = vocalGain, minusGain = minusGain,
            params = Params(
                pitchShift = pitch, indexRate = if (v.hasIndex) indexRate else 0f, protect = protect,
                rmsMixRate = rmsMix, temperature = temperature, f0Mode = f0Mode,
                threads = threads, windowSec = windowSec, xnnpack = xnnpack,
            ),
            outName = inputName.substringBeforeLast('.') + " · " + v.name,
        )
        JobBus.state.value = JobState(running = true, message = "Запуск…")
        ConvertService.start(app)
        return true
    }

    fun cancel() { JobBus.cancel = true }
    fun refreshOutputs() { outputs = store.outputs() }

    fun togglePlay(f: File) {
        if (playing == f.name) { player?.release(); player = null; playing = null; return }
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath); prepare(); start()
            setOnCompletionListener { playing = null }
        }
        playing = f.name
    }

    fun saveTo(uri: Uri) {
        val f = saveTarget ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } } }
                .onFailure { message = "Не удалось сохранить: ${it.message}" }
        }
    }

    fun deleteOutput(f: File) { if (playing == f.name) togglePlay(f); f.delete(); refresh() }

    override fun onCleared() { player?.release(); super.onCleared() }
}
