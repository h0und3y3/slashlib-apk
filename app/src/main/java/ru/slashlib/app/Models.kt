package ru.slashlib.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------- терпимое чтение JSON (сервер иногда отдаёт числа строками и наоборот) ----------

fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is String -> if (v.isEmpty()) null else v
        is Number -> v.toString()
        else -> null
    }
}

fun JSONObject.int(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}

fun JSONObject.label(key: String): String? = optJSONObject(key)?.str("label")

// Даты в обмене с iOS-версией: ISO-8601 «2026-11-20T12:34:56Z» (UTC).
object Iso {
    private fun fmt(): SimpleDateFormat {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f
    }

    fun format(ms: Long): String = fmt().format(Date(ms))

    fun parse(s: String?): Long {
        if (s == null) return 0L
        return try {
            fmt().parse(s.substringBefore('.').removeSuffix("Z") + "Z")?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

// ---------- модели ----------

data class TitleRef(val slug: String, val name: String, val cover: String?, val site: Int?) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("slug", slug)
        o.put("name", name)
        o.putOpt("cover", cover)
        o.putOpt("site", site)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TitleRef =
            TitleRef(o.str("slug") ?: "", o.str("name") ?: "—", o.str("cover"), o.int("site"))
    }
}

data class Title(val ref: TitleRef, val engName: String?, val chips: List<String>)

data class Chapter(
    val id: Int, val volume: String, val number: String, val name: String, val branchIds: List<Int>
) {
    val display: String
        get() {
            val v = if (volume.isEmpty()) "?" else volume
            val n = if (number.isEmpty()) "?" else number
            return "Том $v · Глава $n" + (if (name.isEmpty()) "" else " — $name")
        }

    val shortDisplay: String
        get() {
            val v = if (volume.isEmpty()) "?" else volume
            val n = if (number.isEmpty()) "?" else number
            return "Т$v · Гл. $n"
        }

    val sortVolume: Double get() = volume.toDoubleOrNull() ?: 0.0
    val sortNumber: Double get() = number.toDoubleOrNull() ?: 0.0

    companion object {
        fun sortedAscending(list: List<Chapter>): List<Chapter> {
            return list.withIndex().sortedWith(
                compareBy<IndexedValue<Chapter>>({ it.value.sortVolume }, { it.value.sortNumber }, { it.index })
            ).map { it.value }
        }

        fun fromJson(o: JSONObject): Chapter {
            val ids = ArrayList<Int>()
            val br = o.optJSONArray("branches")
            if (br != null) {
                for (i in 0 until br.length()) {
                    val b = br.optJSONObject(i) ?: continue
                    val id = b.int("branch_id")
                    if (id != null && !ids.contains(id)) ids.add(id)
                }
            }
            return Chapter(o.int("id") ?: 0, o.str("volume") ?: "", o.str("number") ?: "", o.str("name") ?: "", ids)
        }
    }
}

data class Page(val url: String, val width: Int?, val height: Int?)

data class Details(
    val summary: String?, val meta: List<String>, val cover: String?
)

// Закладка / запись «недавних». Поля и формат JSON совпадают с iOS-версией (синхронизация между платформами).
data class Bookmark(
    val id: String, val slug: String, val titleName: String, val cover: String?, val site: Int?,
    val chapterId: Int, val volume: String, val number: String, val chapterName: String,
    val branchIds: List<Int>, val page: Int, val pageCount: Int, val dateMs: Long
) {
    val chapter: Chapter get() = Chapter(chapterId, volume, number, chapterName, branchIds)
    val ref: TitleRef get() = TitleRef(slug, titleName, cover, site)
    val progress: Float
        get() = if (pageCount <= 0) 0f else Math.min(1f, Math.max(0f, (page + 1f) / pageCount))

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("slug", slug)
        o.put("titleName", titleName)
        o.putOpt("cover", cover)
        o.putOpt("site", site)
        o.put("chapterId", chapterId)
        o.put("volume", volume)
        o.put("number", number)
        o.put("chapterName", chapterName)
        o.put("branchIds", JSONArray(branchIds))
        o.put("page", page)
        o.put("pageCount", pageCount)
        o.put("date", Iso.format(dateMs))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Bookmark? {
            val slug = o.str("slug") ?: return null
            val ids = ArrayList<Int>()
            val arr = o.optJSONArray("branchIds")
            if (arr != null) for (i in 0 until arr.length()) ids.add(arr.optInt(i))
            return Bookmark(
                o.str("id") ?: java.util.UUID.randomUUID().toString(), slug,
                o.str("titleName") ?: "—", o.str("cover"), o.int("site"),
                o.int("chapterId") ?: 0, o.str("volume") ?: "", o.str("number") ?: "",
                o.str("chapterName") ?: "", ids, o.int("page") ?: 0, o.int("pageCount") ?: 0,
                Iso.parse(o.str("date"))
            )
        }
    }
}

data class FavoriteItem(val ref: TitleRef, val dateMs: Long) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("ref", ref.toJson())
        o.put("date", Iso.format(dateMs))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): FavoriteItem? {
            val r = o.optJSONObject("ref") ?: return null
            return FavoriteItem(TitleRef.fromJson(r), Iso.parse(o.str("date")))
        }
    }
}
