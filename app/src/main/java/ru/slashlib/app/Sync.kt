package ru.slashlib.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Синхронизация закладок, избранного, «недавних» и прочитанных глав между устройствами — в том числе
// между Android и iOS (формат файла одинаковый). Данные лежат одним JSON в ПРИВАТНОМ Gist вашего GitHub.
// Токен GitHub (classic, право «gist», срок «No expiration») вводится на каждом устройстве один раз.
// Продлевать токен не нужно: у бессрочного токена GitHub срока нет; если его отзовут — приложение попросит новый.

class SyncException(message: String) : Exception(message)

class SyncDoc {
    var bookmarks = ArrayList<Bookmark>()
    var favorites = ArrayList<FavoriteItem>()
    var recent = ArrayList<Bookmark>()
    var read = HashMap<String, HashSet<Int>>()
    var tombstones = HashMap<String, Long>()

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("version", 1)
        o.put("updated", Iso.format(System.currentTimeMillis()))
        o.put("bookmarks", JSONArray().also { a -> bookmarks.forEach { a.put(it.toJson()) } })
        o.put("favorites", JSONArray().also { a -> favorites.forEach { a.put(it.toJson()) } })
        o.put("recent", JSONArray().also { a -> recent.forEach { a.put(it.toJson()) } })
        val r = JSONObject()
        for ((k, v) in read) r.put(k, JSONArray(v.sorted()))
        o.put("read", r)
        val t = JSONObject()
        for ((k, v) in tombstones) t.put(k, Iso.format(v))
        o.put("tombstones", t)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SyncDoc {
            val d = SyncDoc()
            o.optJSONArray("bookmarks")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> Bookmark.fromJson(x)?.let { d.bookmarks.add(it) } } }
            o.optJSONArray("favorites")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> FavoriteItem.fromJson(x)?.let { d.favorites.add(it) } } }
            o.optJSONArray("recent")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x -> Bookmark.fromJson(x)?.let { d.recent.add(it) } } }
            o.optJSONObject("read")?.let { r ->
                for (k in r.keys()) {
                    val arr = r.optJSONArray(k) ?: continue
                    val set = HashSet<Int>()
                    for (i in 0 until arr.length()) set.add(arr.optInt(i))
                    d.read[k] = set
                }
            }
            o.optJSONObject("tombstones")?.let { t -> for (k in t.keys()) d.tombstones[k] = Iso.parse(t.optString(k)) }
            return d
        }
    }
}

object SyncMerge {
    private fun ts(ms: Long): Long = ms / 1000

    private fun <T> newest(items: List<T>, key: (T) -> String, date: (T) -> Long): Map<String, T> {
        val m = HashMap<String, T>()
        for (x in items) {
            val k = key(x)
            val e = m[k]
            if (e != null && ts(date(e)) >= ts(date(x))) continue
            m[k] = x
        }
        return m
    }

    fun merge(a: SyncDoc, b: SyncDoc): SyncDoc {
        val out = SyncDoc()
        val tomb = HashMap<String, Long>(a.tombstones)
        for ((k, d) in b.tombstones) {
            val e = tomb[k]
            if (e != null && ts(e) >= ts(d)) continue
            tomb[k] = d
        }
        val cutoff = System.currentTimeMillis() - 90L * 86400 * 1000
        out.tombstones = HashMap(tomb.filter { it.value > cutoff })

        for ((k, v) in newest(a.bookmarks + b.bookmarks, { "${it.slug}#${it.chapterId}" }, { it.dateMs })) {
            val t = tomb["b:$k"]
            if (t != null && ts(t) >= ts(v.dateMs)) continue
            out.bookmarks.add(v)
        }
        for ((k, v) in newest(a.favorites + b.favorites, { it.ref.slug }, { it.dateMs })) {
            val t = tomb["f:$k"]
            if (t != null && ts(t) >= ts(v.dateMs)) continue
            out.favorites.add(v)
        }
        for ((k, v) in newest(a.recent + b.recent, { it.slug }, { it.dateMs })) {
            val t = tomb["r:$k"]
            if (t != null && ts(t) >= ts(v.dateMs)) continue
            out.recent.add(v)
        }
        for ((k, v) in a.read) out.read.getOrPut(k) { HashSet() }.addAll(v)
        for ((k, v) in b.read) out.read.getOrPut(k) { HashSet() }.addAll(v)
        return out
    }

    // Отпечаток содержимого: по нему понимаем, изменилось ли что-то.
    fun signature(d: SyncDoc): String {
        val parts = ArrayList<String>()
        for (b in d.bookmarks) parts.add("b|${b.slug}#${b.chapterId}|${ts(b.dateMs)}|${b.page}")
        for (f in d.favorites) parts.add("f|${f.ref.slug}|${ts(f.dateMs)}")
        for (r in d.recent) parts.add("r|${r.slug}|${ts(r.dateMs)}|${r.chapterId}|${r.page}")
        for ((k, v) in d.read) parts.add("x|$k|" + v.sorted().joinToString(","))
        for ((k, v) in d.tombstones) parts.add("t|$k|${ts(v)}")
        parts.sort()
        return parts.joinToString("\n")
    }
}

object Sync {
    private const val FILE = "slashlib-sync.json"
    private const val DESCRIPTION = "Slashlib sync — закладки и прогресс чтения (не удаляйте)"
    private val json = "application/json".toMediaType()

    @Volatile private var syncing = false
    var listener: (() -> Unit)? = null

    val isEnabled: Boolean get() = !Prefs.syncToken.isNullOrEmpty()

    val statusText: String
        get() = if (!isEnabled) "Синхронизация выключена" else if (Prefs.syncStatus.isEmpty()) "Включена" else Prefs.syncStatus

    private fun setStatus(s: String) {
        Prefs.syncStatus = s
        Util.ui { listener?.invoke() }
    }

    private var pending: Runnable? = null

    // Любое локальное изменение → отправка через 15 секунд (чтобы не слать на каждую страницу).
    fun localChanged() {
        if (!isEnabled) return
        Util.ui {
            pending?.let { Util.main.removeCallbacks(it) }
            val r = Runnable { syncNow() }
            pending = r
            Util.main.postDelayed(r, 15000)
        }
    }

    fun start() {
        if (isEnabled) syncNow()
    }

    fun syncIfDue() {
        if (!isEnabled) return
        if (System.currentTimeMillis() - Prefs.lastSync < 120000) return
        syncNow()
    }

    fun enable(raw: String, cb: (String?) -> Unit) {
        val token = raw.trim().removePrefix("token ").removePrefix("Bearer ").trim()
        if (token.isEmpty()) { cb("Токен пустой"); return }
        Util.bg {
            val err = try {
                val (code, _) = gh("GET", "/user", null, token)
                if (code == 401) "Токен недействителен." else if (code !in 200..299) "GitHub ответил HTTP $code" else null
            } catch (e: Exception) {
                "Нет связи с GitHub: ${e.message}"
            }
            Util.ui {
                if (err == null) {
                    Prefs.syncToken = token
                    Prefs.gistId = null
                    Prefs.syncStatus = "Включена, синхронизирую…"
                    cb(null)
                    syncNow()
                } else {
                    cb(err)
                }
            }
        }
    }

    fun disable() {
        pending?.let { Util.main.removeCallbacks(it) }
        Prefs.syncToken = null
        Prefs.gistId = null
        Prefs.lastSync = 0
        setStatus("")
    }

    fun syncNow() {
        val token = Prefs.syncToken
        if (token.isNullOrEmpty() || syncing) return
        syncing = true
        setStatus("Синхронизация…")
        Util.bg {
            try {
                run(token, false)
                Prefs.lastSync = System.currentTimeMillis()
                val f = SimpleDateFormat("d MMM, HH:mm", Locale("ru"))
                setStatus("Синхронизировано: " + f.format(Date()))
            } catch (e: Exception) {
                setStatus("Ошибка: " + (e.message ?: e.toString()))
            } finally {
                syncing = false
            }
        }
    }

    private fun localDoc(): SyncDoc = Util.onMainSync {
        val d = SyncDoc()
        d.bookmarks = ArrayList(Bookmarks.all)
        d.favorites = ArrayList(Favorites.all)
        d.recent = ArrayList(Progress.recent)
        for ((k, v) in Progress.read) d.read[k] = HashSet(v)
        d.tombstones = HashMap(Tombstones.items)
        d
    }

    private fun run(token: String, retried: Boolean) {
        var id = Prefs.gistId
        if (id == null) {
            id = findGist(token)
            if (id != null) Prefs.gistId = id
        }
        if (id == null) {
            create(token, localDoc())
            return
        }
        val remote: SyncDoc
        try {
            remote = fetch(token, id)
        } catch (e: SyncException) {
            if (e.message == "404" && !retried) {
                Prefs.gistId = null
                run(token, true)
                return
            }
            throw e
        }
        val local = localDoc()
        val merged = SyncMerge.merge(local, remote)
        if (SyncMerge.signature(merged) != SyncMerge.signature(local)) {
            Util.onMainSync {
                Store.applying = true
                try {
                    Bookmarks.replaceAll(merged.bookmarks)
                    Favorites.replaceAll(merged.favorites)
                    Progress.replace(merged.recent, merged.read)
                    Tombstones.replaceAll(merged.tombstones)
                } finally {
                    Store.applying = false
                }
            }
        }
        if (SyncMerge.signature(merged) == SyncMerge.signature(remote)) return
        val files = JSONObject().put(FILE, JSONObject().put("content", merged.toJson().toString()))
        val (code, body) = gh("PATCH", "/gists/$id", JSONObject().put("files", files), token)
        check(code, body)
    }

    private fun check(code: Int, body: String) {
        if (code in 200..299) return
        if (code == 401) throw SyncException("Токен недействителен или истёк. Создайте новый (github.com/settings/tokens, право gist) и введите его заново.")
        if (code == 403) throw SyncException("GitHub отказал (403): проверьте, что у токена есть право gist, или подождите — возможно, превышен лимит запросов.")
        if (code == 404) throw SyncException("404")
        val msg = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
        throw SyncException("Ошибка GitHub (HTTP $code)" + (if (msg.isEmpty()) "" else ": $msg"))
    }

    private fun findGist(token: String): String? {
        val (code, body) = gh("GET", "/gists?per_page=100", null, token)
        check(code, body)
        val a = JSONArray(body)
        for (i in 0 until a.length()) {
            val g = a.optJSONObject(i) ?: continue
            val files = g.optJSONObject("files") ?: continue
            if (files.has(FILE)) return g.optString("id", "").ifEmpty { null }
        }
        return null
    }

    private fun create(token: String, doc: SyncDoc) {
        val body = JSONObject()
            .put("description", DESCRIPTION)
            .put("public", false)
            .put("files", JSONObject().put(FILE, JSONObject().put("content", doc.toJson().toString())))
        val (code, resp) = gh("POST", "/gists", body, token)
        check(code, resp)
        Prefs.gistId = JSONObject(resp).optString("id", "").ifEmpty { null }
    }

    private fun fetch(token: String, id: String): SyncDoc {
        val (code, body) = gh("GET", "/gists/$id", null, token)
        check(code, body)
        val file = JSONObject(body).optJSONObject("files")?.optJSONObject(FILE) ?: return SyncDoc()
        var content = file.optString("content", "")
        if (file.optBoolean("truncated", false)) {
            val raw = file.optString("raw_url", "")
            if (raw.isNotEmpty()) {
                val req = Request.Builder().url(raw).header("User-Agent", "Slashlib").build()
                Api.client.newCall(req).execute().use { r -> content = r.body?.string() ?: "" }
            }
        }
        if (content.isEmpty()) return SyncDoc()
        return try { SyncDoc.fromJson(JSONObject(content)) } catch (e: Exception) { SyncDoc() }
    }

    // Возвращает пару (HTTP-код, тело).
    private fun gh(method: String, path: String, body: JSONObject?, token: String): Pair<Int, String> {
        val rb = if (method == "GET") null else (body?.toString() ?: "").toRequestBody(json)
        val req = Request.Builder().url("https://api.github.com$path")
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Slashlib")       // GitHub требует User-Agent
            .method(method, rb)
            .build()
        Api.client.newCall(req).execute().use { r ->
            return Pair(r.code, r.body?.string() ?: "")
        }
    }
}
