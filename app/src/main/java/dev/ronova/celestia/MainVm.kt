package dev.ronova.celestia

import android.app.Application
import android.content.Intent
import android.provider.DocumentsContract
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ronova.celestia.core.audio.Export
import dev.ronova.celestia.core.audio.ExportFmt
import dev.ronova.celestia.core.engine.F0Mode
import dev.ronova.celestia.core.engine.Ort
import dev.ronova.celestia.core.engine.Params
import dev.ronova.celestia.core.faiss.FaissIndex
import dev.ronova.celestia.core.pth.PthToOnnx
import dev.ronova.celestia.core.tts.SystemTts
import dev.ronova.celestia.core.tts.TtsVoiceInfo
import dev.ronova.celestia.service.ConvertService
import dev.ronova.celestia.service.JobBus
import dev.ronova.celestia.service.JobRequest
import dev.ronova.celestia.service.JobSpec
import dev.ronova.celestia.service.SepSpec
import dev.ronova.celestia.service.TtsSpec
import dev.ronova.celestia.service.JobState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.util.UUID

enum class StemPart { VOCALS, INSTRUMENTAL, BOTH }

class ExportPlan(val stem: StemSet, val part: StemPart, val fmt: ExportFmt)

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

    // подсказка на экране конвертации (например, «подставлено из разделения»)
    var note by mutableStateOf<String?>(null)

    // ---- озвучка (TTS) ----
    var ttsText by mutableStateOf("")
    var ttsVoices by mutableStateOf<List<TtsVoiceInfo>>(emptyList()); private set
    var ttsVoicesState by mutableStateOf("idle"); private set        // idle / loading / ok / fail
    var ttsLang by mutableStateOf(store.getString("tts_lang"))
    var ttsVoiceName by mutableStateOf(store.getString("tts_voice"))
    var ttsRate by mutableFloatStateOf(store.getFloat("tts_rate", 1f))
    var ttsPitchSys by mutableFloatStateOf(store.getFloat("tts_pitch", 1f))
    var ttsPause by mutableIntStateOf(store.getInt("tts_pause", 250))
    var ttsRvc by mutableStateOf(store.getBool("tts_rvc", true))
    var tPitch by mutableIntStateOf(store.getInt("t_pitch", 0))
    var tIndex by mutableFloatStateOf(store.getFloat("t_index", 0.5f))
    var tProtect by mutableFloatStateOf(store.getFloat("t_protect", 0.33f))
    var tRms by mutableFloatStateOf(store.getFloat("t_rms", 0.25f))
    var tTemp by mutableFloatStateOf(store.getFloat("t_temp", 0.667f))
    var tFcpe by mutableStateOf(store.getBool("t_fcpe", true))

    // ---- разделение вокала / минуса ----
    var stems by mutableStateOf(store.stems()); private set
    var sepModel by mutableStateOf(SepModel.entries.firstOrNull { it.name == store.getString("sep_model") } ?: SepModel.VOC_FT)
    var sepDenoise by mutableStateOf(store.getBool("sep_denoise", false))
    var sepSmooth by mutableStateOf(store.getBool("sep_smooth", true))
    var sepLowMem by mutableStateOf(store.getBool("sep_lowmem", true))
    var sepInput by mutableStateOf<Uri?>(null); private set
    var sepInputName by mutableStateOf(""); private set
    var exporting by mutableStateOf<String?>(null); private set
    var exportProgress by mutableFloatStateOf(0f); private set
    var exportPlan: ExportPlan? = null

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
        voices = store.voices(); outputs = store.outputs(); stems = store.stems()
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

    /** Тяжёлые задачи идут строго по одной: иначе две модели окажутся в памяти одновременно. */
    private fun busy(): Boolean {
        val what = when {
            JobBus.state.value.running -> "идёт другая задача"
            installing != null -> "устанавливаются модели"
            importing != null -> "идёт импорт голоса"
            exporting != null -> "идёт сохранение файла"
            else -> return false
        }
        message = "Подождите: $what"
        return true
    }

    fun start(): Boolean {
        if (busy()) return false
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
    fun refreshStems() { stems = store.stems() }

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

    // ---------- озвучка ----------
    fun saveTts() {
        store.put("tts_lang", ttsLang); store.put("tts_voice", ttsVoiceName)
        store.put("tts_rate", ttsRate); store.put("tts_pitch", ttsPitchSys); store.put("tts_pause", ttsPause)
        store.put("tts_rvc", ttsRvc); store.put("t_pitch", tPitch); store.put("t_index", tIndex)
        store.put("t_protect", tProtect); store.put("t_rms", tRms); store.put("t_temp", tTemp); store.put("t_fcpe", tFcpe)
    }

    fun loadTtsVoices(force: Boolean = false) {
        if (ttsVoicesState == "loading" || (!force && ttsVoicesState == "ok")) return
        ttsVoicesState = "loading"
        viewModelScope.launch(Dispatchers.IO) {
            val list = try { SystemTts.listVoices(app) } catch (e: Throwable) { null }
            ttsVoices = list ?: emptyList()
            val langs = ttsVoices.map { it.lang }.distinct()
            if (langs.isNotEmpty() && (ttsLang == null || ttsLang !in langs)) ttsLang = if ("ru" in langs) "ru" else langs.first()
            if (ttsLang == null) ttsLang = "ru"
            if (ttsVoiceName != null && ttsVoices.none { it.name == ttsVoiceName }) ttsVoiceName = null
            ttsVoicesState = if (list == null) "fail" else "ok"
        }
    }

    fun startTts(): Boolean {
        if (busy()) return false
        val text = ttsText.trim()
        if (text.isEmpty()) { message = "Введите текст для озвучки"; return false }
        val label = text.replace(Regex("\\s+"), " ").take(30)
        var rvc: JobSpec? = null
        var outName = "$label · TTS"
        if (ttsRvc) {
            val v = voice ?: run { message = "Выберите голос RVC (вкладка «Голоса») или включите «Только озвучка»"; return false }
            if (engineReady[EngineKind.HUBERT] != true) { message = "Нет модели ContentVec: вкладка «Настройки»"; return false }
            val f0k = if (tFcpe) EngineKind.FCPE else EngineKind.RMVPE
            if (engineReady[f0k] != true) { message = "Нет модели ${f0k.title}: вкладка «Настройки»"; return false }
            outName = "$label · ${v.name}"
            rvc = JobSpec(
                voice = v, input = null, minus = null, vocalGain = 1f, minusGain = 0.8f,
                params = Params(
                    pitchShift = tPitch, indexRate = if (v.hasIndex) tIndex else 0f, protect = tProtect,
                    rmsMixRate = tRms, temperature = tTemp, f0Mode = if (tFcpe) F0Mode.FCPE else F0Mode.RMVPE,
                    threads = threads, windowSec = windowSec, xnnpack = xnnpack,
                ),
                outName = outName,
            )
        }
        saveTts()
        JobBus.pending = JobRequest.Tts(TtsSpec(text, ttsVoiceName, ttsLang, ttsRate, ttsPitchSys, ttsPause, rvc, outName))
        JobBus.state.value = JobState(running = true, message = "Запуск…")
        ConvertService.start(app)
        return true
    }

    // ---------- разделение ----------
    fun saveSep() {
        store.put("sep_model", sepModel.name); store.put("sep_denoise", sepDenoise)
        store.put("sep_smooth", sepSmooth); store.put("sep_lowmem", sepLowMem)
    }

    fun pickSepInput(uri: Uri) { grant(uri); sepInput = uri; sepInputName = store.displayName(uri) }

    fun startSep(): Boolean {
        if (busy()) return false
        val inp = sepInput ?: run { message = "Выберите аудиофайл"; return false }
        if (engineReady[sepModel.kind] != true) { message = "Нет модели «${sepModel.kind.title}»: вкладка «Настройки»"; return false }
        saveSep()
        JobBus.pending = JobRequest.Separate(SepSpec(inp, sepInputName, sepModel, sepDenoise, sepSmooth, sepLowMem, threads))
        JobBus.state.value = JobState(running = true, message = "Запуск…")
        ConvertService.start(app)
        return true
    }

    fun deleteStem(s: StemSet) {
        if (playing == playingKey(s.vocals) || playing == playingKey(s.instrumental)) { player?.release(); player = null; playing = null }
        s.dir.deleteRecursively(); refresh()
    }

    /** Подставляет вокал и минус в экран конвертации; кнопку «Конвертировать» нажимает сам пользователь. */
    fun useStems(s: StemSet) {
        input = Uri.fromFile(s.vocals); inputName = s.name + " · вокал"
        minus = Uri.fromFile(s.instrumental); minusName = s.name + " · минус"
        mixer = true
        f0Mode = F0Mode.RMVPE
        note = "Подставлено из разделения: вокал и минус «${s.name}». Включены микшер и RMVPE (для пения) — проверьте голос и настройки и нажмите «Конвертировать»."
        tab = 1
    }

    fun togglePlayStem(f: File) {
        if (playing == f.parentFile?.name + "/" + f.name) { player?.release(); player = null; playing = null; return }
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath); prepare(); start()
            setOnCompletionListener { playing = null }
        }
        playing = f.parentFile?.name + "/" + f.name
    }

    fun playingKey(f: File) = f.parentFile?.name + "/" + f.name

    private fun safeName(s: String) = s.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(80).ifBlank { "track" }

    fun exportFileName(plan: ExportPlan): String =
        safeName(plan.stem.name) + (if (plan.part == StemPart.VOCALS) " - вокал." else " - минус.") + plan.fmt.ext

    private fun encodeTo(src: File, fmt: ExportFmt, out: OutputStream) {
        val tmp = File(store.tmpDir, "export." + fmt.ext).also { it.delete() }
        try {
            Export.encode(src, fmt, tmp) { p -> exportProgress = p }
            tmp.inputStream().use { it.copyTo(out, 1 shl 16) }
        } finally { tmp.delete() }
    }

    /** Один файл (вокал или минус) в выбранный пользователем документ. */
    fun exportToUri(uri: Uri) {
        val plan = exportPlan ?: return
        if (busy()) return
        exporting = "Кодирую ${plan.fmt.ext.uppercase()}…"; exportProgress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val src = if (plan.part == StemPart.VOCALS) plan.stem.vocals else plan.stem.instrumental
                app.contentResolver.openOutputStream(uri)!!.use { encodeTo(src, plan.fmt, it) }
                message = "Сохранено."
            } catch (e: Throwable) { message = "Не удалось сохранить: ${friendly(e)}" }
            finally { exporting = null }
        }
    }

    /** Оба файла в выбранную папку. */
    fun exportBothToTree(tree: Uri) {
        val plan = exportPlan ?: return
        if (busy()) return
        exporting = "Кодирую ${plan.fmt.ext.uppercase()}…"; exportProgress = 0f
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val base = safeName(plan.stem.name)
                for ((label, src) in listOf("вокал" to plan.stem.vocals, "минус" to plan.stem.instrumental)) {
                    exporting = "Кодирую $label (${plan.fmt.ext.uppercase()})…"; exportProgress = 0f
                    val doc = DocumentsContract.createDocument(app.contentResolver, parent, plan.fmt.mime, "$base - $label.${plan.fmt.ext}")
                        ?: error("Не удалось создать файл в выбранной папке")
                    app.contentResolver.openOutputStream(doc)!!.use { encodeTo(src, plan.fmt, it) }
                }
                message = "Вокал и минус сохранены в выбранную папку."
            } catch (e: Throwable) { message = "Не удалось сохранить: ${friendly(e)}" }
            finally { exporting = null }
        }
    }

    override fun onCleared() { player?.release(); super.onCleared() }
}
