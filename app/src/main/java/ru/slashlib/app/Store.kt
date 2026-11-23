package ru.slashlib.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Store {
    lateinit var sp: SharedPreferences
    @Volatile var applying = false      // true, пока синхронизация записывает данные (чтобы не запускать её снова)

    fun init(c: Context) {
        sp = c.getSharedPreferences("slashlib", Context.MODE_PRIVATE)
        Bookmarks.load()
        Favorites.load()
        Progress.load()
        Tombstones.load()
    }

    fun changed() {
        if (!applying) Sync.localChanged()
    }
}

object Prefs {
    private val sp: SharedPreferences get() = Store.sp

    var token: String?
        get() = sp.getString("token", null)
        set(v) { sp.edit().putString("token", v).apply() }

    var themeMode: Int                    // 0 светлая, 1 тёмная, 2 как в системе
        get() = sp.getInt("themeMode", 2)
        set(v) { sp.edit().putInt("themeMode", v).apply() }

    var accent: Int
        get() = sp.getInt("accent", 0)
        set(v) { sp.edit().putInt("accent", v).apply() }

    var mangalib: Boolean
        get() = sp.getBoolean("mangalib", false)
        set(v) { sp.edit().putBoolean("mangalib", v).apply() }

    var readMode: Int                     // 0 лента, 1 страницы →, 2 страницы ←
        get() = sp.getInt("readMode", 0)
        set(v) { sp.edit().putInt("readMode", v).apply() }

    var readerBg: Int                     // 0 чёрный, 1 серый, 2 белый
        get() = sp.getInt("readerBg", 0)
        set(v) { sp.edit().putInt("readerBg", v).apply() }

    var readerDim: Int                    // затемнение, % (0…60)
        get() = sp.getInt("readerDim", 0)
        set(v) { sp.edit().putInt("readerDim", v).apply() }

    var autoHide: Boolean
        get() = sp.getBoolean("autoHide", true)
        set(v) { sp.edit().putBoolean("autoHide", v).apply() }

    var keepAwake: Boolean
        get() = sp.getBoolean("keepAwake", true)
        set(v) { sp.edit().putBoolean("keepAwake", v).apply() }

    var descending: Boolean
        get() = sp.getBoolean("descending", false)
        set(v) { sp.edit().putBoolean("descending", v).apply() }

    var syncToken: String?
        get() = sp.getString("syncToken", null)
        set(v) { sp.edit().putString("syncToken", v).apply() }

    var gistId: String?
        get() = sp.getString("gistId", null)
        set(v) { sp.edit().putString("gistId", v).apply() }

    var syncStatus: String
        get() = sp.getString("syncStatus", "") ?: ""
        set(v) { sp.edit().putString("syncStatus", v).apply() }

    var lastSync: Long
        get() = sp.getLong("lastSync", 0L)
        set(v) { sp.edit().putLong("lastSync", v).apply() }

    // Сайты, которые сейчас включены (MangaLib включается в «Аккаунт»).
    fun sites(): List<Int> = if (mangalib) listOf(Config.SITE_SLASH, Config.SITE_MANGA) else listOf(Config.SITE_SLASH)
}

object Tombstones {
    val items = HashMap<String, Long>()

    fun load() {
        items.clear()
        val s = Store.sp.getString("tombstones", null) ?: return
        try {
            val o = JSONObject(s)
            for (k in o.keys()) items[k] = Iso.parse(o.optString(k))
        } catch (e: Exception) { }
    }

    private fun save() {
        val o = JSONObject()
        for ((k, v) in items) o.put(k, Iso.format(v))
        Store.sp.edit().putString("tombstones", o.toString()).apply()
    }

    fun mark(key: String) {
        items[key] = System.currentTimeMillis()
        save()
    }

    fun replaceAll(map: Map<String, Long>) {
        items.clear()
        items.putAll(map)
        save()
    }
}

object Bookmarks {
    val all = ArrayList<Bookmark>()      // новые сверху

    fun load() {
        all.clear()
        val s = Store.sp.getString("bookmarks", null) ?: return
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) Bookmark.fromJson(a.getJSONObject(i))?.let { all.add(it) }
        } catch (e: Exception) { }
        all.sortByDescending { it.dateMs }
    }

    private fun save() {
        all.sortByDescending { it.dateMs }
        val a = JSONArray()
        for (b in all) a.put(b.toJson())
        Store.sp.edit().putString("bookmarks", a.toString()).apply()
        Store.changed()
    }

    // В каждой главе одна закладка: новая заменяет старую.
    fun get(slug: String, chapterId: Int): Bookmark? = all.firstOrNull { it.slug == slug && it.chapterId == chapterId }

    fun forSlug(slug: String): List<Bookmark> = all.filter { it.slug == slug }

    fun set(b: Bookmark) {
        all.removeAll { it.slug == b.slug && it.chapterId == b.chapterId }
        all.add(b)
        save()
    }

    fun remove(id: String) {
        val b = all.firstOrNull { it.id == id }
        if (b != null) Tombstones.mark("b:${b.slug}#${b.chapterId}")
        all.removeAll { it.id == id }
        save()
    }

    fun replaceAll(list: List<Bookmark>) {
        all.clear()
        all.addAll(list)
        save()
    }
}

object Favorites {
    val all = ArrayList<FavoriteItem>()

    fun load() {
        all.clear()
        val s = Store.sp.getString("favorites", null) ?: return
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) FavoriteItem.fromJson(a.getJSONObject(i))?.let { all.add(it) }
        } catch (e: Exception) { }
        all.sortByDescending { it.dateMs }
    }

    private fun save() {
        val a = JSONArray()
        for (f in all) a.put(f.toJson())
        Store.sp.edit().putString("favorites", a.toString()).apply()
        Store.changed()
    }

    fun isFavorite(slug: String): Boolean = all.any { it.ref.slug == slug }

    // true — тайтл теперь в избранном.
    fun toggle(ref: TitleRef): Boolean {
        val i = all.indexOfFirst { it.ref.slug == ref.slug }
        if (i >= 0) {
            all.removeAt(i)
            Tombstones.mark("f:${ref.slug}")
            save()
            return false
        }
        all.add(0, FavoriteItem(ref, System.currentTimeMillis()))
        save()
        return true
    }

    fun remove(slug: String) {
        Tombstones.mark("f:$slug")
        all.removeAll { it.ref.slug == slug }
        save()
    }

    fun replaceAll(list: List<FavoriteItem>) {
        all.clear()
        all.addAll(list.sortedByDescending { it.dateMs })
        save()
    }
}

// «Недавние» (по одной записи на тайтл) и отметки прочитанных глав.
object Progress {
    val recent = ArrayList<Bookmark>()
    val read = HashMap<String, HashSet<Int>>()

    fun load() {
        recent.clear()
        read.clear()
        try {
            val s = Store.sp.getString("recent", null)
            if (s != null) {
                val a = JSONArray(s)
                for (i in 0 until a.length()) Bookmark.fromJson(a.getJSONObject(i))?.let { recent.add(it) }
            }
            val r = Store.sp.getString("read", null)
            if (r != null) {
                val o = JSONObject(r)
                for (k in o.keys()) {
                    val arr = o.getJSONArray(k)
                    val set = HashSet<Int>()
                    for (i in 0 until arr.length()) set.add(arr.getInt(i))
                    read[k] = set
                }
            }
        } catch (e: Exception) { }
        recent.sortByDescending { it.dateMs }
    }

    private fun save() {
        val a = JSONArray()
        for (b in recent) a.put(b.toJson())
        val o = JSONObject()
        for ((k, v) in read) o.put(k, JSONArray(v.toList()))
        Store.sp.edit().putString("recent", a.toString()).putString("read", o.toString()).apply()
        Store.changed()
    }

    fun save(b: Bookmark) {
        recent.removeAll { it.slug == b.slug }
        recent.add(0, b)
        while (recent.size > 100) recent.removeAt(recent.size - 1)
        save()
    }

    fun last(slug: String): Bookmark? = recent.firstOrNull { it.slug == slug }

    fun remove(slug: String) {
        Tombstones.mark("r:$slug")
        recent.removeAll { it.slug == slug }
        save()
    }

    fun markRead(slug: String, chapterId: Int) {
        val set = read.getOrPut(slug) { HashSet() }
        if (set.add(chapterId)) save()
    }

    fun isRead(slug: String, chapterId: Int): Boolean = read[slug]?.contains(chapterId) == true

    fun replace(list: List<Bookmark>, map: Map<String, Set<Int>>) {
        recent.clear()
        recent.addAll(list.sortedByDescending { it.dateMs })
        while (recent.size > 100) recent.removeAt(recent.size - 1)
        read.clear()
        for ((k, v) in map) read[k] = HashSet(v)
        save()
    }
}
