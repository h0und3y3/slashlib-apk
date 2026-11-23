package ru.slashlib.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message) {
    val isAuth: Boolean get() = code == 401 || code == 403
}

// Все функции блокирующие: вызывать из фонового потока (Util.bg).
object Api {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun errorText(code: Int, body: String): String {
        val msg = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
        val base = when (code) {
            401, 403 -> "Доступ закрыт (HTTP $code). Если у тайтла возрастное ограничение или он только для авторизованных — откройте «Аккаунт» и вставьте токен."
            404 -> "Не найдено (HTTP 404)."
            429 -> "Слишком много запросов (HTTP 429). Подождите немного и повторите."
            in 500..599 -> "Сервер временно недоступен (HTTP $code)."
            else -> "Ошибка сервера (HTTP $code)."
        }
        return if (msg.isEmpty()) base else "$base\n$msg"
    }

    private fun get(path: String, query: List<Pair<String, String>>, site: Int?): String {
        val b = (Config.API + path).toHttpUrl().newBuilder()
        for ((k, v) in query) b.addQueryParameter(k, v)
        val rb = Request.Builder().url(b.build())
            .header("Site-Id", Config.siteHeader(site))
            .header("Accept", "application/json")
            .header("Referer", Config.referer(site))
            .header("User-Agent", Config.UA)
        val t = Prefs.token
        if (!t.isNullOrEmpty()) rb.header("Authorization", "Bearer $t")
        client.newCall(rb.build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw ApiException(r.code, errorText(r.code, body))
            return body
        }
    }

    // Если сайт тайтла неизвестен — пробуем включённые сайты по очереди.
    private fun <T> resolve(site: Int?, attempt: (Int?) -> T): Pair<T, Int?> {
        val candidates: List<Int?> = if (site != null) listOf(site) else if (Prefs.mangalib) listOf(Config.SITE_SLASH, Config.SITE_MANGA) else listOf(null)
        var last: Exception = ApiException(0, "Нет данных")
        for (c in candidates) {
            try {
                return Pair(attempt(c), c)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last
    }

    fun parseTitle(o: JSONObject): Title {
        val id = o.int("id") ?: 0
        val slug = o.str("slug_url") ?: id.toString()
        val name = o.str("rus_name") ?: o.str("name") ?: o.str("eng_name") ?: "—"
        val cov = o.optJSONObject("cover")
        val cover = if (cov != null) (cov.str("thumbnail") ?: cov.str("default")) else null
        val site = o.int("site") ?: o.int("site_id")
        val chips = ArrayList<String>()
        o.label("type")?.let { chips.add(it) }
        o.label("ageRestriction")?.let { chips.add(it) }
        o.label("status")?.let { chips.add(it) }
        val g = o.optJSONArray("genres")
        if (g != null) {
            for (i in 0 until Math.min(g.length(), 2)) g.optJSONObject(i)?.str("name")?.let { chips.add(it) }
        }
        return Title(TitleRef(slug, name, cover, site), o.str("eng_name"), chips)
    }

    // genreId — фильтр по жанру (ПРОВЕРИТЬ: параметр genres[]).
    fun catalog(query: String?, page: Int, genreId: Int?): List<Title> {
        val q = ArrayList<Pair<String, String>>()
        for (s in Prefs.sites()) q.add("site_id[]" to s.toString())
        if (!query.isNullOrEmpty()) q.add("q" to query)
        if (genreId != null) q.add("genres[]" to genreId.toString())
        if (page > 1) q.add("page" to page.toString())
        val site = if (Prefs.sites().size == 1) Prefs.sites()[0] else null
        val arr = JSONObject(get("/manga", q, site)).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Title>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            try { out.add(parseTitle(o)) } catch (e: Exception) { }
        }
        return out
    }

    // ПРОВЕРИТЬ: список жанров из /constants.
    fun genres(): List<Pair<Int, String>> {
        return try {
            val d = JSONObject(get("/constants", listOf("fields[]" to "genres"), null)).optJSONObject("data")
            val arr = d?.optJSONArray("genres") ?: return emptyList()
            val out = ArrayList<Pair<Int, String>>()
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val id = g.int("id") ?: continue
                val name = g.str("name") ?: continue
                out.add(id to name)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Список глав и сайт, на котором тайтл нашёлся.
    fun chapters(slug: String, site: Int?): Pair<List<Chapter>, Int?> {
        return resolve(site) { s ->
            val arr = JSONObject(get("/manga/$slug/chapters", emptyList(), s)).optJSONArray("data")
            val out = ArrayList<Chapter>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(Chapter.fromJson(o))
                }
            }
            out
        }
    }

    // Страницы главы: перебираем все ветки перевода, в конце — запрос без ветки.
    fun pages(slug: String, chapter: Chapter, site: Int?): List<Page> {
        return resolve(site) { s -> pagesOnSite(slug, chapter, s) }.first
    }

    private fun pagesOnSite(slug: String, chapter: Chapter, site: Int?): List<Page> {
        val base = ArrayList<Pair<String, String>>()
        if (chapter.number.isNotEmpty()) base.add("number" to chapter.number)
        if (chapter.volume.isNotEmpty()) base.add("volume" to chapter.volume)
        val attempts = ArrayList<List<Pair<String, String>>>()
        for (b in chapter.branchIds) attempts.add(base + ("branch_id" to b.toString()))
        attempts.add(base)

        var last: Exception = ApiException(0, "В этой главе нет страниц: сервер вернул пустой список. Возможно, глава закрыта, платная или удалена.")
        for (q in attempts) {
            try {
                val arr = JSONObject(get("/manga/$slug/chapter", q, site)).optJSONObject("data")?.optJSONArray("pages")
                val out = ArrayList<Page>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val url = o.str("url") ?: continue
                        out.add(Page(url, o.int("width"), o.int("height")))
                    }
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                val keep = (last as? ApiException)?.isAuth == true
                if (!keep) last = e
            }
        }
        throw last
    }

    // ПРОВЕРИТЬ: имена полей описания.
    fun details(slug: String, site: Int?): Details {
        return resolve(site) { s ->
            val q = listOf("fields[]" to "summary", "fields[]" to "genres", "fields[]" to "tags")
            val d = JSONObject(get("/manga/$slug", q, s)).optJSONObject("data") ?: throw ApiException(0, "Нет данных")
            val meta = ArrayList<String>()
            d.label("type")?.let { meta.add("Тип: $it") }
            d.label("status")?.let { meta.add("Статус: $it") }
            (d.str("releaseDateString") ?: d.str("releaseDate"))?.let { meta.add("Год: $it") }
            d.label("ageRestriction")?.let { meta.add("Возраст: $it") }
            val r = d.optJSONObject("rating")
            val avg = r?.str("average")
            if (avg != null) meta.add("Рейтинг: $avg" + (r?.str("votes")?.let { " ($it)" } ?: ""))
            fun names(key: String, limit: Int): String {
                val a = d.optJSONArray(key) ?: return ""
                val l = ArrayList<String>()
                for (i in 0 until Math.min(a.length(), limit)) a.optJSONObject(i)?.str("name")?.let { l.add(it) }
                return l.joinToString(", ")
            }
            val genres = names("genres", 30)
            if (genres.isNotEmpty()) meta.add("Жанры: $genres")
            val tags = names("tags", 12)
            if (tags.isNotEmpty()) meta.add("Теги: $tags")
            val cov = d.optJSONObject("cover")
            Details(d.str("summary"), meta, if (cov != null) (cov.str("default") ?: cov.str("thumbnail")) else null)
        }.first
    }

    // Проверка токена сайта (ПРОВЕРИТЬ: путь /auth/me). Возвращает имя пользователя или null.
    fun me(): String? {
        val d = JSONObject(get("/auth/me", emptyList(), null)).optJSONObject("data")
        return d?.str("username")
    }

    // Список хостов картинок от сервера (ПРОВЕРИТЬ: формат). При неудаче остаётся Config.IMAGE_BASE.
    fun loadImageServers() {
        try {
            val d = JSONObject(get("/constants", listOf("fields[]" to "imageServers"), null)).optJSONObject("data")
            val arr = d?.optJSONArray("imageServers") ?: return
            val list = ArrayList<Images.Server>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = s.str("url") ?: continue
                val ids = ArrayList<Int>()
                val sa = s.optJSONArray("site_ids")
                if (sa != null) for (j in 0 until sa.length()) ids.add(sa.optInt(j))
                val rank = when (s.str("id")) { "main" -> -2; "secondary" -> -1; else -> i }
                list.add(Images.Server(url, ids, rank))
            }
            Images.servers = list
        } catch (e: Exception) { }
    }
}
