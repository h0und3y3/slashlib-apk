package ru.slashlib.app

import android.graphics.Color
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.R as MR

class ReaderActivity : AppCompatActivity() {
    private lateinit var ref: TitleRef
    private lateinit var chapter: Chapter
    private var chapters: List<Chapter> = emptyList()
    private var pages: List<Page> = emptyList()
    private var current = 0
    private var pendingStart = 0
    private var mode = 0                     // 0 лента, 1 страницы →, 2 страницы ←
    private var token = 0
    private var barsHidden = false

    private lateinit var root: FrameLayout
    private lateinit var rv: RecyclerView
    private lateinit var dim: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottom: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var slider: Slider
    private lateinit var center: LinearLayout
    private lateinit var message: TextView
    private lateinit var retry: MaterialButton
    private lateinit var spinner: CircularProgressIndicator
    private val snap = PagerSnapHelper()
    private val saveRunnable = Runnable { saveProgress() }

    private class VH(v: View) : RecyclerView.ViewHolder(v)

    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.apply(this)
        super.onCreate(savedInstanceState)
        val r = ReaderArgs.ref
        val c = ReaderArgs.chapter
        if (r == null || c == null) { finish(); return }
        ref = r
        chapter = c
        chapters = Chapter.sortedAscending(ReaderArgs.chapters)
        pendingStart = ReaderArgs.startPage
        mode = Prefs.readMode
        buildUi()
        applyAppearance()
        if (chapters.isEmpty()) {
            Util.bg {
                try {
                    val list = Chapter.sortedAscending(Api.chapters(ref.slug, ref.site).first)
                    Util.ui { chapters = list; updateChrome() }
                } catch (e: Exception) { }
            }
        }
        loadChapter()
    }

    // ---------- интерфейс ----------

    private fun buildUi() {
        root = FrameLayout(this)
        rv = RecyclerView(this)
        rv.itemAnimator = null
        rv.layoutManager = LinearLayoutManager(this)
        root.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))

        dim = View(this)
        dim.setBackgroundColor(Color.BLACK)
        root.addView(dim, FrameLayout.LayoutParams(MATCH, MATCH))

        // Сообщение/загрузка по центру
        center = LinearLayout(this)
        center.orientation = LinearLayout.VERTICAL
        center.gravity = Gravity.CENTER
        center.setPadding(dp(24), 0, dp(24), 0)
        spinner = CircularProgressIndicator(this)
        spinner.isIndeterminate = true
        message = TextView(this)
        message.gravity = Gravity.CENTER
        message.textSize = 15f
        message.setPadding(0, dp(12), 0, dp(12))
        retry = MaterialButton(this)
        retry.text = "Повторить"
        retry.setOnClickListener { loadChapter() }
        center.addView(spinner)
        center.addView(message)
        center.addView(retry)
        root.addView(center, FrameLayout.LayoutParams(MATCH, MATCH))

        // Панели (тёмные, поверх страниц)
        val dark = ContextThemeWrapper(this, MR.style.ThemeOverlay_Material3_Dark)
        toolbar = MaterialToolbar(dark)
        toolbar.setBackgroundColor(0xE6121212.toInt())
        toolbar.setNavigationIcon(R.drawable.ic_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add(0, 1, 1, "Закладка").setIcon(R.drawable.ic_bookmark_border).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.menu.add(0, 2, 2, "Вид").setIcon(R.drawable.ic_tune).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) toggleBookmark() else showSettings()
            true
        }
        root.addView(toolbar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        bottom = LinearLayout(dark)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setBackgroundColor(0xE6121212.toInt())
        bottom.setPadding(dp(8), dp(4), dp(8), dp(4))
        val prev = ImageButton(dark)
        prev.setImageResource(R.drawable.ic_prev)
        prev.setBackgroundResource(selectableBackground(dark))
        prev.setOnClickListener { neighbor(-1)?.let { openChapter(it, 0) } }
        val next = ImageButton(dark)
        next.setImageResource(R.drawable.ic_next)
        next.setBackgroundResource(selectableBackground(dark))
        next.setOnClickListener { neighbor(1)?.let { openChapter(it, 0) } }
        slider = Slider(dark)
        slider.stepSize = 1f
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) scrollToPage(value.toInt() - 1)
        }
        pageLabel = TextView(dark)
        pageLabel.setTextColor(Color.WHITE)
        pageLabel.textSize = 13f
        pageLabel.setPadding(dp(8), 0, dp(8), 0)
        bottom.addView(prev, LinearLayout.LayoutParams(dp(44), dp(44)))
        bottom.addView(slider, LinearLayout.LayoutParams(0, WRAP, 1f))
        bottom.addView(pageLabel)
        bottom.addView(next, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap(e.x / Math.max(rv.width, 1))
                return true
            }
        })
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gd.onTouchEvent(e)
                return false
            }
        })
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recycler: RecyclerView, dx: Int, dy: Int) {
                for (i in 0 until recycler.childCount) (recycler.getChildAt(i) as? PageStripsView)?.updateVisible()
                updateCurrentPage()
                if (mode == 0 && Prefs.autoHide) {
                    if (dy > 24 && !barsHidden) setBars(true) else if (dy < -24 && barsHidden) setBars(false)
                }
            }

            override fun onScrollStateChanged(recycler: RecyclerView, state: Int) {
                if (mode != 0 && state == RecyclerView.SCROLL_STATE_DRAGGING && Prefs.autoHide) setBars(true)
            }
        })
    }

    private fun pageColor(): Int = when (Prefs.readerBg) { 1 -> 0xFF333333.toInt(); 2 -> Color.WHITE; else -> Color.BLACK }
    private fun placeholderColor(): Int = if (Prefs.readerBg == 2) 0xFFEAEAEA.toInt() else 0xFF161616.toInt()
    private fun textColor(): Int = if (Prefs.readerBg == 2) Color.BLACK else Color.WHITE

    private fun applyAppearance() {
        root.setBackgroundColor(pageColor())
        dim.alpha = Prefs.readerDim / 100f
        dim.isClickable = false
        message.setTextColor(textColor())
        if (Prefs.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rv.adapter?.notifyDataSetChanged()
    }

    @Suppress("DEPRECATION")
    private fun setBars(hidden: Boolean) {
        if (hidden == barsHidden) return
        barsHidden = hidden
        val v = if (hidden) View.GONE else View.VISIBLE
        toolbar.visibility = v
        bottom.visibility = v
        window.decorView.systemUiVisibility = if (hidden)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        else View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun showLoading() {
        setBars(false)
        center.visibility = View.VISIBLE
        spinner.visibility = View.VISIBLE
        message.text = "Загрузка главы…"
        retry.visibility = View.GONE
    }

    private fun showMessage(text: String) {
        setBars(false)
        center.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        message.text = text
        retry.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        center.visibility = View.GONE
    }

    private fun updateChrome() {
        toolbar.title = chapter.shortDisplay
        pageLabel.text = if (pages.isEmpty()) "" else "${current + 1} / ${pages.size}"
        if (pages.size > 1) {
            slider.visibility = View.VISIBLE
            slider.valueFrom = 1f
            slider.valueTo = pages.size.toFloat()
            slider.value = (current + 1).toFloat().coerceIn(1f, pages.size.toFloat())
        } else {
            slider.visibility = View.INVISIBLE
        }
        val marked = Bookmarks.get(ref.slug, chapter.id)?.page == current && pages.isNotEmpty()
        toolbar.menu.findItem(1)?.setIcon(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
    }

    // ---------- главы и страницы ----------

    private fun loadChapter() {
        token++
        val t = token
        pages = emptyList()
        rv.adapter = null
        showLoading()
        updateChrome()
        Util.bg {
            try {
                val p = Api.pages(ref.slug, chapter, ref.site)
                Util.ui { if (t == token) onPages(p) }
            } catch (e: Exception) {
                Util.ui { if (t == token) showMessage(e.message ?: e.toString()) }
            }
        }
    }

    private fun onPages(p: List<Page>) {
        pages = p
        hideOverlay()
        val start = Math.min(Math.max(pendingStart, 0), p.size - 1)
        pendingStart = 0
        current = start
        applyMode()
        scrollToPage(start)
        updateChrome()
        scheduleSave()
        if (start > 0) Toast.makeText(this, "Открыто на стр. ${start + 1}", Toast.LENGTH_SHORT).show()
    }

    private fun neighbor(delta: Int): Chapter? {
        val i = chapters.indexOfFirst { it.id == chapter.id }
        if (i < 0) return null
        return chapters.getOrNull(i + delta)
    }

    private fun openChapter(c: Chapter, start: Int) {
        handler().removeCallbacks(saveRunnable)
        saveProgress()
        chapter = c
        pendingStart = start
        current = 0
        loadChapter()
    }

    private fun savedPage(c: Chapter): Int = Bookmarks.get(ref.slug, c.id)?.page ?: 0

    // ---------- режимы ----------

    private fun applyMode() {
        mode = Prefs.readMode
        snap.attachToRecyclerView(null)
        if (mode == 0) {
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = ScrollAdapter()
        } else {
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, mode == 2)
            rv.adapter = PagedAdapter()
            snap.attachToRecyclerView(rv)
        }
    }

    private fun scrollToPage(p: Int) {
        if (pages.isEmpty()) return
        val t = Math.min(Math.max(p, 0), pages.size - 1)
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        lm.scrollToPositionWithOffset(t, 0)
    }

    private fun updateCurrentPage() {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        var p = -1
        if (mode == 0) {
            val y = rv.height * 0.25f
            for (i in 0 until rv.childCount) {
                val c = rv.getChildAt(i)
                if (c.top <= y && c.bottom > y) { p = rv.getChildAdapterPosition(c); break }
            }
            if (p < 0) p = lm.findFirstVisibleItemPosition()
        } else {
            val sv = snap.findSnapView(lm)
            p = if (sv != null) rv.getChildAdapterPosition(sv) else lm.findFirstVisibleItemPosition()
        }
        if (p in pages.indices && p != current) {
            current = p
            updateChrome()
            scheduleSave()
            prefetch()
        }
    }

    private fun prefetch() {
        for (i in current + 1..current + 2) if (i in pages.indices) Images.prefetch(pages[i].url, ref.site)
    }

    private fun handleTap(x: Float) {
        if (pages.isEmpty()) return
        if (mode != 0) {
            val rtl = mode == 2
            if (x < 0.3f) { turn(if (rtl) 1 else -1); return }
            if (x > 0.7f) { turn(if (rtl) -1 else 1); return }
        }
        setBars(!barsHidden)
    }

    private fun turn(delta: Int) {
        val t = current + delta
        if (t >= pages.size) {
            val n = neighbor(1)
            if (n != null) openChapter(n, savedPage(n)) else Toast.makeText(this, "Это последняя глава", Toast.LENGTH_SHORT).show()
        } else if (t < 0) {
            val p = neighbor(-1)
            if (p != null) openChapter(p, Int.MAX_VALUE) else Toast.makeText(this, "Это первая глава", Toast.LENGTH_SHORT).show()
        } else {
            rv.smoothScrollToPosition(t)
        }
    }

    // ---------- закладки и прогресс ----------

    private fun makeBookmark(): Bookmark = Bookmark(
        java.util.UUID.randomUUID().toString(), ref.slug, ref.name, ref.cover, ref.site,
        chapter.id, chapter.volume, chapter.number, chapter.name, chapter.branchIds,
        current, pages.size, System.currentTimeMillis()
    )

    private fun toggleBookmark() {
        if (pages.isEmpty()) return
        val existing = Bookmarks.get(ref.slug, chapter.id)
        if (existing != null && existing.page == current) {
            Bookmarks.remove(existing.id)
            Toast.makeText(this, "Закладка убрана", Toast.LENGTH_SHORT).show()
        } else {
            Bookmarks.set(makeBookmark())
            Toast.makeText(this, "Закладка: стр. ${current + 1} из ${pages.size}", Toast.LENGTH_SHORT).show()
        }
        updateChrome()
    }

    private fun handler() = Util.main

    private fun scheduleSave() {
        handler().removeCallbacks(saveRunnable)
        handler().postDelayed(saveRunnable, 1000)
    }

    private fun saveProgress() {
        if (pages.isEmpty()) return
        Progress.save(makeBookmark())
        if (current >= pages.size - 1) Progress.markRead(ref.slug, chapter.id)
    }

    override fun onPause() {
        super.onPause()
        handler().removeCallbacks(saveRunnable)
        saveProgress()
    }

    // ---------- настройки читалки ----------

    private fun showSettings() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(16), dp(24), dp(8))

        fun caption(s: String) {
            val t = label(this, s, Ty.LABEL, Theme.onSurfaceVariant(this))
            t.setPadding(0, dp(12), 0, dp(4))
            col.addView(t)
        }

        fun group(names: List<String>, selected: Int, onSelect: (Int) -> Unit) {
            val g = MaterialButtonToggleGroup(this)
            g.isSingleSelection = true
            g.isSelectionRequired = true
            val ids = ArrayList<Int>()
            for (n in names) {
                val b = MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle)
                b.text = n
                b.id = View.generateViewId()
                ids.add(b.id)
                g.addView(b)
            }
            g.check(ids[selected.coerceIn(0, names.size - 1)])
            g.addOnButtonCheckedListener { _, id, checked -> if (checked) onSelect(ids.indexOf(id)) }
            col.addView(g)
        }

        caption("Режим чтения")
        group(listOf("Лента", "Стр. →", "Стр. ←"), Prefs.readMode) { m ->
            if (m != Prefs.readMode) {
                Prefs.readMode = m
                if (pages.isNotEmpty()) { val keep = current; applyMode(); scrollToPage(keep) }
            }
        }
        caption("Фон страницы")
        group(listOf("Чёрный", "Серый", "Белый"), Prefs.readerBg) { b -> Prefs.readerBg = b; applyAppearance() }
        caption("Затемнение")
        val s = Slider(this)
        s.valueFrom = 0f
        s.valueTo = 60f
        s.stepSize = 5f
        s.value = Prefs.readerDim.toFloat().coerceIn(0f, 60f)
        s.addOnChangeListener { _, v, _ -> Prefs.readerDim = v.toInt(); dim.alpha = v / 100f }
        col.addView(s)

        fun switchRow(text: String, on: Boolean, change: (Boolean) -> Unit) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(label(this, text, Ty.BODY), LinearLayout.LayoutParams(0, WRAP, 1f))
            val sw = MaterialSwitch(this)
            sw.isChecked = on
            sw.setOnCheckedChangeListener { _, v -> change(v) }
            row.addView(sw)
            col.addView(row)
        }
        switchRow("Прятать панели при листании", Prefs.autoHide) { Prefs.autoHide = it }
        switchRow("Не гасить экран", Prefs.keepAwake) { Prefs.keepAwake = it; applyAppearance() }

        MaterialAlertDialogBuilder(this).setTitle("Вид").setView(col).setPositiveButton("Готово", null).show()
    }

    // ---------- адаптеры ----------

    private inner class ScrollAdapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = PageStripsView(parent.context)
            v.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            (h.itemView as PageStripsView).bind(pages[pos], ref.site, placeholderColor(), textColor())
        }

        override fun onViewRecycled(h: VH) {
            (h.itemView as PageStripsView).release()
        }
    }

    private inner class PagedAdapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount(): Int = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = ZoomImageView(parent.context)
            v.layoutParams = RecyclerView.LayoutParams(MATCH, MATCH)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val v = h.itemView as ZoomImageView
            val url = pages[pos].url
            v.tag = url
            v.setImageDrawable(null)
            val maxPx = if (Images.lowRam) 1600 else 2400
            Images.loadFit(url, ref.site, maxPx) { bmp, err ->
                if (v.tag == url) {
                    if (bmp != null) v.setBitmap(bmp)
                    else Toast.makeText(this@ReaderActivity, err ?: "Не удалось загрузить страницу", Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun onViewRecycled(h: VH) {
            val v = h.itemView as ZoomImageView
            v.tag = null
            v.setImageDrawable(null)
        }
    }
}
