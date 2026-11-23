package ru.slashlib.app

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import android.widget.ImageView
import okhttp3.Request
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

// Загрузка картинок: диск (кэш файлов) → память (LruCache). Большие страницы режутся на полосы и
// декодируются по частям (BitmapRegionDecoder), поэтому длинные ленты не убивают приложение на слабых armv7-телефонах.
object Images {
    class Server(val url: String, val siteIds: List<Int>, val rank: Int)
    class PageData(val file: File, val w: Int, val h: Int)
    class Res(val file: File?, val error: String?)

    @Volatile var servers: List<Server> = emptyList()
    var lowRam = false
        private set

    private lateinit var dir: File
    private lateinit var mem: LruCache<String, Bitmap>
    private val stripExecutor = Executors.newSingleThreadExecutor()     // полосы декодируем по одной — экономит память

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "img")
        dir.mkdirs()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        lowRam = am.isLowRamDevice || am.memoryClass <= 64
        val kb = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
        mem = object : LruCache<String, Bitmap>(Math.max(kb, 2048)) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
        Util.bg { trim(if (lowRam) 150L * 1024 * 1024 else 400L * 1024 * 1024) }
    }

    fun clearAll() {
        mem.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    fun cacheBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun trim(limit: Long) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limit) break
            total -= f.length()
            f.delete()
        }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { String.format("%02x", it) }
    }

    // ---------- адреса ----------

    private fun serverUrls(site: Int?): List<String> {
        val sid = site ?: Config.SITE_SLASH
        val sorted = servers.sortedWith(compareBy<Server>({ if (it.siteIds.isEmpty() || it.siteIds.contains(sid)) 0 else 1 }, { it.rank }))
        val out = ArrayList<String>()
        for (raw in sorted.map { it.url } + Config.IMAGE_BASE) {
            val t = raw.trimEnd('/')
            if (t.isNotEmpty() && !out.contains(t)) out.add(t)
        }
        return out
    }

    private fun candidates(path: String, site: Int?): List<String> {
        val servers = serverUrls(site)
        val out = ArrayList<String>()
        fun add(s: String) { if (!out.contains(s)) out.add(s) }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            add(path)
            try {
                val u = URL(path)
                if (servers.any { URL(it).host == u.host }) {
                    val rel = u.path + (if (u.query != null) "?" + u.query else "")
                    for (s in servers) add(s + rel)
                }
            } catch (e: Exception) { }
        } else if (path.startsWith("//")) {
            val rest = path.substring(2)
            val first = rest.substringBefore('/')
            if (first.contains('.')) add("https:$path") else for (s in servers) add("$s/$rest")
        } else {
            val p = if (path.startsWith("/")) path else "/$path"
            for (s in servers) add(s + p)
        }
        return out
    }

    // ---------- файлы ----------

    private fun isImage(f: File): Boolean {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    // Блокирующая: возвращает файл из кэша или скачивает (перебирая запасные хосты).
    fun file(path: String, site: Int?): Res {
        val f = File(dir, md5(path))
        if (f.exists() && f.length() > 0 && isImage(f)) {
            f.setLastModified(System.currentTimeMillis())
            return Res(f, null)
        }
        f.delete()
        var error = "нет адреса"
        for (url in candidates(path, site)) {
            for (attempt in 0..1) {
                try {
                    val req = Request.Builder().url(url)
                        .header("Referer", Config.referer(site))
                        .header("User-Agent", Config.UA)
                        .build()
                    Api.client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) {
                            error = "Сервер картинок ответил HTTP ${r.code}"
                        } else {
                            val tmp = File(dir, md5(path) + ".tmp")
                            r.body!!.byteStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
                            if (isImage(tmp) && tmp.renameTo(f)) return Res(f, null)
                            tmp.delete()
                            error = "Сервер вернул не картинку (или формат не поддерживается этим Android)"
                        }
                    }
                    break      // ответ получили (пусть и плохой) — пробуем следующий хост
                } catch (e: Exception) {
                    error = "Ошибка сети: ${e.message}"
                }
            }
        }
        return Res(null, error)
    }

    fun prefetch(path: String, site: Int?) {
        Util.bg { file(path, site) }
    }

    // ---------- декодирование ----------

    private fun config(): Bitmap.Config = if (lowRam) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

    fun decodeSampled(f: File, reqW: Int, reqH: Int): Bitmap? {
        val b = BitmapFactory.Options()
        b.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.absolutePath, b)
        var sample = 1
        while (b.outWidth / (sample * 2) >= reqW && b.outHeight / (sample * 2) >= reqH) sample *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        o.inPreferredConfig = config()
        return try {
            BitmapFactory.decodeFile(f.absolutePath, o)
        } catch (e: OutOfMemoryError) {
            mem.evictAll()
            null
        }
    }

    fun pageData(path: String, site: Int?): Pair<PageData?, String?> {
        val r = file(path, site)
        val f = r.file ?: return Pair(null, r.error)
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.absolutePath, o)
        return Pair(PageData(f, o.outWidth, o.outHeight), null)
    }

    // Полоса страницы [srcTop, srcTop+srcH) сразу в уменьшенном виде (ширина ≈ outW).
    fun decodeStrip(d: PageData, srcTop: Int, srcH: Int, outW: Int): Bitmap? {
        var sample = 1
        while (d.w / (sample * 2) >= outW) sample *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        o.inPreferredConfig = config()
        return try {
            val dec = BitmapRegionDecoder.newInstance(d.file.absolutePath, false)
            try {
                dec.decodeRegion(Rect(0, srcTop, d.w, Math.min(srcTop + srcH, d.h)), o)
            } finally {
                dec.recycle()
            }
        } catch (e: OutOfMemoryError) {
            mem.evictAll()
            null
        } catch (e: Exception) {
            null
        }
    }

    // Асинхронно, по одной (очередь). cancelled() проверяется перед декодированием.
    fun requestStrip(d: PageData, srcTop: Int, srcH: Int, outW: Int, cancelled: () -> Boolean, done: (Bitmap?) -> Unit) {
        stripExecutor.execute {
            if (cancelled()) return@execute
            val key = "${d.file.name}#$srcTop#$outW"
            val bmp = mem.get(key) ?: decodeStrip(d, srcTop, srcH, outW)?.also { mem.put(key, it) }
            Util.ui { if (!cancelled()) done(bmp) }
        }
    }

    // Страница целиком, уменьшенная до maxPx по длинной стороне (постраничный режим).
    fun loadFit(path: String, site: Int?, maxPx: Int, done: (Bitmap?, String?) -> Unit) {
        Util.bg {
            val key = "fit#$path#$maxPx"
            val hit = mem.get(key)
            if (hit != null) { Util.ui { done(hit, null) }; return@bg }
            val r = file(path, site)
            val f = r.file
            if (f == null) { Util.ui { done(null, r.error) }; return@bg }
            val bmp = decodeSampled(f, maxPx, maxPx)
            if (bmp != null) mem.put(key, bmp)
            Util.ui { done(bmp, if (bmp == null) "Не хватило памяти для этой страницы" else null) }
        }
    }

    // ---------- миниатюры (обложки) ----------

    fun loadThumb(path: String, site: Int?, reqW: Int, reqH: Int, done: (Bitmap?) -> Unit) {
        val key = "thumb#$path"
        val hit = mem.get(key)
        if (hit != null) { done(hit); return }
        Util.bg {
            val r = file(path, site)
            val f = r.file
            val bmp = if (f != null) decodeSampled(f, reqW, reqH) else null
            if (bmp != null) mem.put(key, bmp)
            Util.ui { done(bmp) }
        }
    }
}

// Удобное расширение: ImageView.loadCover(...) — не путает картинки при переиспользовании строк списка.
fun ImageView.loadCover(path: String?, site: Int?, placeholder: Int) {
    setImageDrawable(null)
    setBackgroundColor(placeholder)
    tag = path
    if (path == null) return
    val reqW = Math.max(width, context.dp(60))
    Images.loadThumb(path, site, reqW, reqW * 3 / 2) { bmp ->
        if (tag == path && bmp != null) {
            setBackgroundColor(0)
            setImageBitmap(bmp)
        }
    }
}
