package ru.slashlib.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import java.util.concurrent.Callable

class HomeTitle(val ref: TitleRef, val chips: List<String>)

sealed class HItem {
    class Header(val title: String, val action: String? = null, val onAction: (() -> Unit)? = null) : HItem()
    class Recent(val b: Bookmark) : HItem()
    class Hint(val text: String) : HItem()
    class Shelf(val titles: List<HomeTitle>) : HItem()
    object Loading : HItem()
}

// Главная: сверху «Продолжить чтение» с полосой прогресса, ниже случайные подборки по жанрам с тегами-плашками.
class HomeScreen(act: MainActivity) : Screen(act) {
    private val items = ArrayList<HItem>()
    private var shelves: List<Pair<String, List<HomeTitle>>> = emptyList()
    private var loading = false
    private var error: String? = null
    private var gen = 0
    private var lastSites: List<Int> = Prefs.sites()
    private var loadedOnce = false
    private val adapter = HomeAdapter()
    private val list = RecyclerView(act)

    override val view: View = build()

    private fun build(): View {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.addView(titleBar(act, "Главная", null, listOf(Action("Другие", R.drawable.ic_shuffle) { load() })))
        list.layoutManager = LinearLayoutManager(act)
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    override fun onShown() {
        if (!loadedOnce || Prefs.sites() != lastSites) {
            loadedOnce = true
            lastSites = Prefs.sites()
            load()
        } else {
            rebuild()
        }
    }

    private fun rebuild() {
        items.clear()
        val recent = Progress.recent.take(4)
        items.add(HItem.Header("Продолжить чтение", if (recent.isEmpty()) null else "История") {
            BookmarksScreen.preferRecent = true
            act.selectTab(3)
        })
        if (recent.isEmpty()) items.add(HItem.Hint("Здесь появится ваш прогресс: откройте любую главу — и она будет тут, с процентами."))
        for (b in recent) items.add(HItem.Recent(b))
        if (loading && shelves.isEmpty()) {
            items.add(HItem.Header("Подбираю для вас…"))
            items.add(HItem.Loading)
        } else if (error != null && shelves.isEmpty()) {
            items.add(HItem.Header("Подборки"))
            items.add(HItem.Hint(error!!))
        } else {
            for ((name, titles) in shelves) {
                items.add(HItem.Header(name))
                items.add(HItem.Shelf(titles))
            }
        }
        adapter.notifyDataSetChanged()
    }

    private class Req(val name: String, val genreId: Int?, val page: Int)

    private fun fetch(r: Req): List<Title> {
        val res = Api.catalog(null, r.page, r.genreId)
        if (res.isEmpty() && r.page > 1) return Api.catalog(null, 1, r.genreId)
        return res
    }

    private fun load() {
        gen++
        val g = gen
        loading = true
        error = null
        rebuild()
        Util.bg {
            val genres = Api.genres()
            val reqs = ArrayList<Req>()
            reqs.add(Req("Случайные находки", null, (1..20).random()))
            for ((id, name) in genres.shuffled().take(5)) reqs.add(Req(name, id, (1..3).random()))
            val futures = reqs.map { r -> Util.io.submit(Callable<List<Title>> { fetch(r) }) }
            var lastError: String? = null
            val results = futures.map { f ->
                try {
                    f.get()
                } catch (e: Exception) {
                    lastError = e.cause?.message ?: e.message
                    emptyList<Title>()
                }
            }
            val seen = HashSet<String>()
            val out = ArrayList<Pair<String, List<HomeTitle>>>()
            for ((i, r) in reqs.withIndex()) {
                val pool = results[i].filter { !seen.contains(it.ref.slug) }.shuffled().take(10)
                if (pool.size < 2) continue
                val list = pool.map { t ->
                    seen.add(t.ref.slug)
                    val chips = ArrayList(t.chips)
                    if (r.genreId != null) { chips.remove(r.name); chips.add(0, r.name) }
                    HomeTitle(t.ref, chips)
                }
                out.add(r.name to list)
            }
            Util.ui {
                if (g == gen) {
                    loading = false
                    shelves = out
                    error = if (out.isEmpty()) "Не удалось загрузить подборки.${if (lastError != null) "\n$lastError" else ""}\nНажмите «Другие», чтобы повторить." else null
                    rebuild()
                }
            }
        }
    }

    private inner class HomeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is HItem.Header -> 0
            is HItem.Recent -> 1
            is HItem.Hint -> 2
            is HItem.Shelf -> 3
            else -> 4
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val v: View = when (viewType) {
                0 -> LinearLayout(c).also {
                    it.orientation = LinearLayout.HORIZONTAL
                    it.gravity = Gravity.CENTER_VERTICAL
                    it.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                }
                1 -> RecentCard(c)
                2 -> emptyHint(c, "").also {
                    it.setPadding(c.dp(24), c.dp(8), c.dp(24), c.dp(8))
                    it.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                }
                3 -> RecyclerView(c).also {
                    it.layoutManager = LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false)
                    it.setPadding(c.dp(12), 0, c.dp(12), c.dp(8))
                    it.clipToPadding = false
                    it.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                }
                else -> FrameLayout(c).also {
                    val p = CircularProgressIndicator(c)
                    p.isIndeterminate = true
                    it.addView(p, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                    it.setPadding(0, c.dp(40), 0, c.dp(40))
                    it.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val item = items[pos]
            val c = h.itemView.context
            when (item) {
                is HItem.Header -> {
                    val row = h.itemView as LinearLayout
                    row.removeAllViews()
                    row.addView(sectionHeader(c, item.title), LinearLayout.LayoutParams(0, WRAP, 1f))
                    if (item.action != null) row.addView(textButton(c, item.action) { item.onAction?.invoke() })
                }
                is HItem.Recent -> (h.itemView as RecentCard).bind(item.b) {
                    ReaderArgs.open(act, item.b.ref, item.b.chapter, emptyList(), item.b.page)
                }
                is HItem.Hint -> (h.itemView as android.widget.TextView).text = item.text
                is HItem.Shelf -> (h.itemView as RecyclerView).adapter = PosterAdapter(item.titles)
                else -> {}
            }
        }
    }

    // Карточка «продолжить чтение»: обложка, название, глава и полоса прогресса.
    private class RecentCard(ctx: Context) : MaterialCardView(ctx) {
        private val cover = ShapeableImageView(ctx)
        private val title = label(ctx, "", Ty.TITLE)
        private val chapter = label(ctx, "", Ty.SMALL, Theme.onSurfaceVariant(ctx))
        private val bar = LinearProgressIndicator(ctx)
        private val percent = label(ctx, "", Ty.LABEL, Theme.onSurfaceVariant(ctx))

        init {
            val lp = RecyclerView.LayoutParams(MATCH, WRAP)
            lp.setMargins(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(4))
            layoutParams = lp
            radius = ctx.dp(16).toFloat()
            isClickable = true
            isFocusable = true
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            row.gravity = Gravity.CENTER_VERTICAL
            cover.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            cover.shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(ctx.dp(8).toFloat()).build()
            row.addView(cover, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(68)))
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(ctx.dp(14), 0, 0, 0)
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            chapter.maxLines = 1
            chapter.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(title)
            col.addView(chapter)
            val pr = LinearLayout(ctx)
            pr.orientation = LinearLayout.HORIZONTAL
            pr.gravity = Gravity.CENTER_VERTICAL
            bar.max = 100
            bar.trackThickness = ctx.dp(6)
            bar.trackCornerRadius = ctx.dp(3)
            pr.addView(bar, LinearLayout.LayoutParams(0, WRAP, 1f))
            percent.setPadding(ctx.dp(8), 0, 0, 0)
            pr.addView(percent)
            val plp = LinearLayout.LayoutParams(MATCH, WRAP)
            plp.topMargin = ctx.dp(10)
            col.addView(pr, plp)
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(row)
        }

        fun bind(b: Bookmark, onClick: () -> Unit) {
            title.text = b.titleName
            chapter.text = "${b.chapter.display} · стр. ${b.page + 1} из ${b.pageCount}"
            val p = b.progress
            bar.setProgressCompat(Math.round(p * 100), true)
            percent.text = if (p >= 0.995f) "прочитано" else "${Math.round(p * 100)}%"
            cover.loadCover(b.cover, b.site, Theme.secondaryContainer(context))
            setOnClickListener { onClick() }
        }
    }

    // Постер в подборке: обложка, название, теги в прямоугольниках.
    private inner class PosterAdapter(private val titles: List<HomeTitle>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = titles.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val v = LinearLayout(c)
            v.orientation = LinearLayout.VERTICAL
            val lp = RecyclerView.LayoutParams(c.dp(120), WRAP)
            lp.rightMargin = c.dp(10)
            v.layoutParams = lp
            v.setBackgroundResource(selectableBackground(c))
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val t = titles[pos]
            val v = h.itemView as LinearLayout
            val c = v.context
            v.removeAllViews()
            val cover = ShapeableImageView(c)
            cover.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            cover.shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(c.dp(12).toFloat()).build()
            v.addView(cover, LinearLayout.LayoutParams(c.dp(120), c.dp(172)))
            cover.loadCover(t.ref.cover, t.ref.site, Theme.secondaryContainer(c))
            val name = label(c, t.ref.name, Ty.SMALL)
            name.maxLines = 2
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            name.setPadding(0, c.dp(6), 0, c.dp(4))
            v.addView(name)
            for ((i, text) in t.chips.take(2).withIndex()) {
                val chip = chip(c, text, i == 0)
                val clp = LinearLayout.LayoutParams(WRAP, WRAP)
                clp.topMargin = c.dp(3)
                v.addView(chip, clp)
            }
            v.setOnClickListener { act.push(TitleScreen(act, t.ref)) }
        }
    }
}
