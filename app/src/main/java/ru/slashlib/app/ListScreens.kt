package ru.slashlib.app

import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MR
import java.text.DateFormat
import java.util.Date

// Передача данных в читалку (Intent не годится для больших списков).
object ReaderArgs {
    var ref: TitleRef? = null
    var chapter: Chapter? = null
    var chapters: List<Chapter> = emptyList()
    var startPage = 0

    fun open(ctx: Context, ref: TitleRef, chapter: Chapter, chapters: List<Chapter>, startPage: Int) {
        this.ref = ref
        this.chapter = chapter
        this.chapters = chapters
        this.startPage = startPage
        ctx.startActivity(Intent(ctx, ReaderActivity::class.java))
    }
}

fun listView(ctx: Context): RecyclerView {
    val rv = RecyclerView(ctx)
    rv.layoutManager = LinearLayoutManager(ctx)
    rv.addItemDecoration(Divider(ctx))
    return rv
}

fun emptyHint(ctx: Context, s: String): TextView {
    val t = label(ctx, s, Ty.BODY, Theme.onSurfaceVariant(ctx))
    t.gravity = Gravity.CENTER
    t.setPadding(ctx.dp(24), ctx.dp(48), ctx.dp(24), 0)
    return t
}

fun confirm(ctx: Context, message: String, action: String, onYes: () -> Unit) {
    MaterialAlertDialogBuilder(ctx).setMessage(message)
        .setPositiveButton(action) { _, _ -> onYes() }
        .setNegativeButton("Отмена", null).show()
}

// ---------- Поиск ----------

class SearchScreen(act: MainActivity) : Screen(act) {
    private val adapter = RowAdapter({ r -> act.push(TitleScreen(act, r.tag as TitleRef)) })
    private val titles = ArrayList<Title>()
    private var page = 1
    private var loading = false
    private var finished = false
    private var gen = 0
    private var query: String? = null
    private var lastSites: List<Int> = Prefs.sites()
    private val message = emptyHint(act, "")
    private val list = listView(act)

    override val view: View = build()

    private fun build(): View {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.addView(titleBar(act, "Поиск", null))

        val til = TextInputLayout(act, null, MR.attr.textInputOutlinedStyle)
        til.hint = "Название тайтла"
        val edit = TextInputEditText(til.context)
        edit.setSingleLine(true)
        edit.inputType = InputType.TYPE_CLASS_TEXT
        edit.imeOptions = EditorInfo.IME_ACTION_SEARCH
        edit.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                reload(edit.text.toString().trim())
                true
            } else false
        }
        til.addView(edit, LinearLayout.LayoutParams(MATCH, WRAP))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(act.dp(16), act.dp(8), act.dp(16), act.dp(4))
        root.addView(til, lp)

        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 5) loadNext()
            }
        })
        root.addView(message)
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        reload(null)
        return root
    }

    override fun onShown() {
        if (Prefs.sites() != lastSites) {
            lastSites = Prefs.sites()
            reload(query)
        }
    }

    private fun reload(q: String?) {
        gen++
        query = if (q.isNullOrEmpty()) null else q
        page = 1
        finished = false
        loading = false
        loadNext()
    }

    private fun loadNext() {
        if (loading || finished) return
        loading = true
        val g = gen
        val requested = page
        val q = query
        if (requested == 1) {
            message.visibility = View.VISIBLE
            message.text = "Загрузка…"
        }
        Util.bg {
            var result: List<Title>? = null
            var error: String? = null
            try {
                result = Api.catalog(q, requested, null)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            Util.ui {
                if (g == gen) {
                    loading = false
                    if (result != null) {
                        if (requested == 1) titles.clear()
                        val known = titles.map { it.ref.slug }.toHashSet()
                        val fresh = result.filter { !known.contains(it.ref.slug) }
                        if (fresh.isEmpty()) finished = true else { titles.addAll(fresh); page = requested + 1 }
                        message.text = "Ничего не найдено"
                        message.visibility = if (titles.isEmpty()) View.VISIBLE else View.GONE
                        adapter.items = titles.map { t ->
                            var sub = t.engName
                            if (Prefs.mangalib && t.ref.site != null) sub = listOfNotNull(sub, Config.siteName(t.ref.site)).joinToString(" · ")
                            Row(t.ref.name, sub, t.ref.cover, t.ref.site, null, t.ref)
                        }
                    } else if (requested == 1) {
                        message.visibility = View.VISIBLE
                        message.text = "Не удалось загрузить: $error"
                    }
                }
            }
        }
    }
}

// ---------- Избранное ----------

class FavoritesScreen(act: MainActivity) : Screen(act) {
    private val adapter = RowAdapter(
        { r -> act.push(TitleScreen(act, r.tag as TitleRef)) },
        { r ->
            val ref = r.tag as TitleRef
            confirm(act, "Убрать «${ref.name}» из избранного?", "Убрать") { Favorites.remove(ref.slug); refresh() }
        })
    private val message = emptyHint(act, "В избранном пока пусто.\nОткройте тайтл и нажмите на звезду.")
    private val list = listView(act)

    override val view: View = build()

    private fun build(): View {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.addView(titleBar(act, "Избранное", null))
        list.adapter = adapter
        root.addView(message)
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    override fun onShown() = refresh()

    private fun refresh() {
        message.visibility = if (Favorites.all.isEmpty()) View.VISIBLE else View.GONE
        adapter.items = Favorites.all.map { f ->
            val last = Progress.last(f.ref.slug)
            Row(f.ref.name, last?.let { "${it.chapter.shortDisplay}, стр. ${it.page + 1}" }, f.ref.cover, f.ref.site, last?.progress, f.ref)
        }
    }
}

// ---------- Закладки и «Недавние» ----------

class BookmarksScreen(act: MainActivity) : Screen(act) {
    companion object { var preferRecent = false }

    private var showRecent = false
    private val adapter = RowAdapter(
        { r -> val b = r.tag as Bookmark; ReaderArgs.open(act, b.ref, b.chapter, emptyList(), b.page) },
        { r ->
            val b = r.tag as Bookmark
            confirm(act, "Удалить из списка «${b.titleName}»?", "Удалить") {
                if (showRecent) Progress.remove(b.slug) else Bookmarks.remove(b.id)
                refresh()
            }
        })
    private val message = emptyHint(act, "")
    private val list = listView(act)
    private val group = MaterialButtonToggleGroup(act)
    private val btnMarks = MaterialButton(act, null, MR.attr.materialButtonOutlinedStyle)
    private val btnRecent = MaterialButton(act, null, MR.attr.materialButtonOutlinedStyle)

    override val view: View = build()

    private fun build(): View {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.addView(titleBar(act, "Закладки", null))

        btnMarks.text = "Закладки"
        btnMarks.id = View.generateViewId()
        btnRecent.text = "Недавние"
        btnRecent.id = View.generateViewId()
        group.isSingleSelection = true
        group.isSelectionRequired = true
        group.addView(btnMarks)
        group.addView(btnRecent)
        group.check(btnMarks.id)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                showRecent = id == btnRecent.id
                refresh()
            }
        }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(act.dp(16), act.dp(8), act.dp(16), act.dp(8))
        root.addView(group, lp)

        list.adapter = adapter
        root.addView(message)
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    override fun onShown() {
        if (preferRecent) {
            preferRecent = false
            group.check(btnRecent.id)
        }
        refresh()
    }

    private fun refresh() {
        val src = if (showRecent) Progress.recent else Bookmarks.all
        message.text = if (showRecent) "Здесь появятся тайтлы, которые вы читаете.\nМесто, где остановились, сохраняется само."
        else "Закладок пока нет.\nОткройте главу и нажмите значок закладки."
        message.visibility = if (src.isEmpty()) View.VISIBLE else View.GONE
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        adapter.items = src.map { b ->
            Row(b.titleName, "${b.chapter.display}\nстр. ${b.page + 1} из ${b.pageCount} · ${df.format(Date(b.dateMs))}",
                b.cover, b.site, if (showRecent) b.progress else null, b)
        }
    }
}
