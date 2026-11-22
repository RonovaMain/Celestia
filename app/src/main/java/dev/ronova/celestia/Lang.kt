package dev.ronova.celestia

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Язык интерфейса: русский, английский, белорусский, украинский.
 * «system» — берём первый поддерживаемый язык из списка языков телефона, иначе английский.
 */
object Lang {
    const val SYSTEM = "system"
    val supported = listOf("ru", "en", "be", "uk")

    private const val PREFS = "celestia"
    private const val KEY = "lang"

    private var app: Context? = null
    private var cacheTag = ""
    private var cacheCtx: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun saved(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun save(ctx: Context, tag: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
    }

    /** Язык, который реально применяется сейчас. */
    fun effective(ctx: Context): String {
        val s = saved(ctx)
        if (s in supported) return s
        val locs = ctx.resources.configuration.locales
        for (i in 0 until locs.size()) {
            val l = locs[i].language
            if (l in supported) return l
        }
        return "en"
    }

    private fun wrapTo(base: Context, tag: String): Context {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(cfg)
    }

    /** Для Activity.attachBaseContext. */
    fun wrap(base: Context): Context = wrapTo(base, effective(base))

    @Synchronized
    fun ctx(): Context {
        val a = app ?: error("Lang.init() was not called")
        val tag = effective(a)
        var c = cacheCtx
        if (c == null || tag != cacheTag) { c = wrapTo(a, tag); cacheCtx = c; cacheTag = tag }
        return c
    }

    fun locale(): Locale = Locale.forLanguageTag(effective(app ?: error("Lang.init() was not called")))
}

/** Строка из ресурсов на выбранном языке; работает и в Compose, и в сервисах, и в core. */
fun tr(@StringRes id: Int, vararg args: Any): String {
    val c = Lang.ctx()
    return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
}

class CelestiaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
    }
}
