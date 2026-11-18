package dev.ronova.celestia.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.ronova.celestia.MainActivity
import dev.ronova.celestia.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ConvertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wake: PowerManager.WakeLock? = null
    private var title = "Celestia"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { JobBus.cancel = true; return START_NOT_STICKY }
        val req = JobBus.pending.also { JobBus.pending = null }
        if (req == null) { stopSelf(); return START_NOT_STICKY }
        title = req.title

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Задачи Celestia", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, ID, notif(0, "Запуск…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val store = Store(applicationContext)

        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "celestia:convert").apply { acquire(3 * 60 * 60 * 1000L) }

        JobBus.cancel = false
        JobBus.state.value = JobState(running = true, message = "Запуск…", kind = req.title)
        scope.launch {
            var last = -1
            try {
                val report = { prog: Float, msg: String ->
                    JobBus.state.value = JobState(running = true, progress = prog, message = msg, kind = req.title)
                    val pct = (prog * 100).toInt()
                    if (pct != last) { last = pct; nm.notify(ID, notif(pct, msg)) }
                }
                val ctx = applicationContext
                val file = when (req) {
                    is JobRequest.Convert -> JobRunner.run(ctx, store, req.spec, report)
                    is JobRequest.Tts -> TtsRunner.run(ctx, store, req.spec, report)
                    is JobRequest.Separate -> SepRunner.run(ctx, store, req.spec, report)
                }
                JobBus.state.value = JobState(progress = 1f, message = "Готово", result = file, kind = req.title)
                nm.notify(ID + 1, NotificationCompat.Builder(this@ConvertService, CH)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle(req.title + ": готово")
                    .setContentText(file.name).setAutoCancel(true).setContentIntent(openApp()).build())
            } catch (e: Throwable) {
                val msg = when {
                    JobBus.cancel -> "Отменено"
                    e is OutOfMemoryError -> "Не хватило памяти. Закройте другие приложения; для разделения включите «Экономию памяти», для RVC уберите .index или уменьшите фрагмент"
                    else -> e.message ?: e.toString()
                }
                JobBus.state.value = JobState(error = msg, kind = req.title)
            } finally {
                runCatching { wake?.release() }
                ServiceCompat.stopForeground(this@ConvertService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun notif(pct: Int, text: String): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ConvertService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("$title · $pct%")
            .setContentText(text)
            .setProgress(100, pct, false)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Отмена", cancel)
            .build()
    }

    override fun onDestroy() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }

    companion object {
        private const val CH = "convert"
        private const val ID = 77
        const val ACTION_CANCEL = "dev.ronova.celestia.CANCEL"
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, ConvertService::class.java)) }
    }
}
