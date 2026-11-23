package ru.slashlib.app

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// Экран тайтла: сверху обложка (уезжает при прокрутке), «О тайтле», кнопка «Продолжить» и выбор главы.
class TitleScreen(act: MainActivity, ref0: TitleRef) : Screen(act) {
    private var ref = ref0
    private var details: Details? = null
    private var baseChapters: List<Chapter> = emptyList()
    private var loadingChapters = true
    private var error: String? = null
    private var summaryExpanded = false
    private val rows = ArrayList<Any>()          // TCover / TAbout / TAction / String(заголовок) / Chapter / TMessage
    private val adapter = TAdapter()
    private val list = RecyclerView(act)
    private val toolbar = titleBar(act, ref0.name, { act.pop() }, listOf(
        Action("В избранное", R.drawable.ic_star_border) { toggleFavorite() },
        Action("Порядок") { Prefs.descending = !Prefs.descending; rebuild() }
    ))

    private object TCover
    private object TAbout
    private object TAction
    private class TMessage(val text: String)

    override val view: View = build()

    private fun build(): View {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.addView(toolbar)
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        updateStar()
        rebuild()
        loadDetails()
        loadChapters()
        return root
    }

    override fun onShown() {
        updateStar()
        adapter.notifyDataSetChanged()     // отметки закладок и прочитанного могли измениться в читалке
    }

    private fun updateStar() {
        toolbar.menu.findItem(0)?.setIcon(if (Favorites.isFavorite(ref.slug)) R.drawable.ic_star else R.drawable.ic_star_border)
    }

    private fun toggleFavorite() {
        Favorites.toggle(ref)
        updateStar()
    }

    private fun ordered(): List<Chapter> = if (Prefs.descending) baseChapters.reversed() else baseChapters

    private fun rebuild() {
        rows.clear()
        rows.add(TCover)
        rows.add(TAbout)
        rows.add(TAction)
        val n = baseChapters.size
        rows.add(if (n > 0) "Главы ($n)" else "Главы")
        if (loadingChapters && baseChapters.isEmpty()) rows.add(TMessage("Загрузка глав…"))
        else if (baseChapters.isEmpty()) rows.add(TMessage(error ?: "Глав не найдено"))
        else rows.addAll(ordered())
        adapter.notifyDataSetChanged()
    }

    private fun loadDetails() {
        Util.bg {
            try {
                val d = Api.details(ref.slug, ref.site)
                Util.ui { details = d; adapter.notifyDataSetChanged() }
            } catch (e: Exception) { }
        }
    }

    private fun loadChapters() {
        loadingChapters = true
        Util.bg {
            var list: List<Chapter> = emptyList()
            var site: Int? = null
            var err: String? = null
            try {
                val r = Api.chapters(ref.slug, ref.site)
                list = Chapter.sortedAscending(r.first)
                site = r.second
            } catch (e: Exception) {
                err = e.message ?: e.toString()
            }
            Util.ui {
                loadingChapters = false
                baseChapters = list
                error = err
                if (ref.site == null && site != null && Prefs.mangalib) ref = TitleRef(ref.slug, ref.name, ref.cover, site)
                rebuild()
            }
        }
    }

    private fun resumePoint(): Bookmark? = Progress.last(ref.slug) ?: Bookmarks.forSlug(ref.slug).firstOrNull()

    private fun startPage(c: Chapter): Int {
        Bookmarks.get(ref.slug, c.id)?.let { return it.page }
        val r = Progress.last(ref.slug)
        if (r != null && r.chapterId == c.id && r.page < r.pageCount - 1) return r.page
        return 0
    }

    private fun openChapter(c: Chapter, page: Int) = ReaderArgs.open(act, ref, c, baseChapters, page)

    private fun resumeOrStart() {
        val p = resumePoint()
        if (p != null) openChapter(p.chapter, p.page)
        else if (baseChapters.isNotEmpty()) openChapter(baseChapters[0], startPage(baseChapters[0]))
    }

    private inner class TAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (val r = rows[position]) {
            TCover -> 0
            TAbout -> 1
            TAction -> 2
            is String -> 3
            is Chapter -> 4
            else -> 5
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val v: View = when (viewType) {
                0 -> ImageView(c).also {
                    it.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    it.layoutParams = RecyclerView.LayoutParams(MATCH, c.dp(280))
                }
                1 -> LinearLayout(c).also {
                    it.orientation = LinearLayout.VERTICAL
                    it.setPadding(c.dp(16), c.dp(12), c.dp(16), c.dp(4))
                    it.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                }
                2 -> LinearLayout(c).also {
                    it.setPadding(c.dp(16), c.dp(4), c.dp(16), c.dp(4))
                    it.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                }
                3 -> sectionHeader(c, "").also { it.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP) }
                4 -> ChapterRow(c)
                else -> emptyHint(c, "").also {
                    it.setPadding(c.dp(24), c.dp(24), c.dp(24), c.dp(24))
                    it.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val item = rows[pos]
            val c = h.itemView.context
            when (item) {
                TCover -> {
                    val iv = h.itemView as ImageView
                    iv.setBackgroundColor(Theme.secondaryContainer(c))
                    iv.loadCover(details?.cover ?: ref.cover, ref.site, Theme.secondaryContainer(c))
                }
                TAbout -> bindAbout(h.itemView as LinearLayout)
                TAction -> {
                    val row = h.itemView as LinearLayout
                    row.removeAllViews()
                    val p = resumePoint()
                    val b = filledButton(c, if (p == null) "Начать читать" else "Продолжить чтение") { resumeOrStart() }
                    row.addView(b, LinearLayout.LayoutParams(MATCH, WRAP))
                }
                is String -> (h.itemView as TextView).text = item
                is Chapter -> (h.itemView as ChapterRow).bind(item)
                is TMessage -> (h.itemView as TextView).text = item.text
                else -> {}
            }
        }
    }

    private fun bindAbout(col: LinearLayout) {
        val c = col.context
        col.removeAllViews()
        col.addView(label(c, ref.name, Ty.TITLE_LARGE))
        val d = details
        if (d != null && d.meta.isNotEmpty()) {
            val meta = label(c, d.meta.joinToString("\n"), Ty.SMALL, Theme.onSurfaceVariant(c))
            meta.setPadding(0, c.dp(8), 0, 0)
            col.addView(meta)
        }
        val summary = d?.summary
        if (summary != null) {
            val t = label(c, summary, Ty.BODY)
            t.setPadding(0, c.dp(10), 0, 0)
            t.maxLines = if (summaryExpanded) Int.MAX_VALUE else 5
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(t)
            col.addView(textButton(c, if (summaryExpanded) "Свернуть" else "Показать полностью") {
                summaryExpanded = !summaryExpanded
                adapter.notifyDataSetChanged()
            }, LinearLayout.LayoutParams(WRAP, WRAP).also { it.gravity = Gravity.START })
        }
    }

    // Строка главы: название, отметки (прочитано / закладка).
    private inner class ChapterRow(ctx: android.content.Context) : LinearLayout(ctx) {
        private val name = label(ctx, "", Ty.TITLE)
        private val sub = label(ctx, "", Ty.SMALL, Theme.onSurfaceVariant(ctx))

        init {
            orientation = VERTICAL
            setBackgroundResource(selectableBackground(ctx))
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            addView(name)
            addView(sub)
        }

        fun bind(c: Chapter) {
            val read = Progress.isRead(ref.slug, c.id)
            name.text = (if (read) "✓ " else "") + c.display
            name.setTextColor(if (read) Theme.onSurfaceVariant(context) else Theme.onSurface(context))
            val parts = ArrayList<String>()
            Bookmarks.get(ref.slug, c.id)?.let { parts.add("🔖 стр. ${it.page + 1}") }
            sub.text = parts.joinToString(" · ")
            sub.visibility = if (parts.isEmpty()) GONE else VISIBLE
            setOnClickListener { openChapter(c, startPage(c)) }
        }
    }
}
