package ru.slashlib.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.RecyclerView

// Страница в режиме ленты: делится на полосы, каждая декодируется отдельно и только когда видна.
class PageStripsView(ctx: Context) : ViewGroup(ctx) {
    private var data: Images.PageData? = null
    private var path: String? = null
    private var site: Int? = null
    private var knownW = 0
    private var knownH = 0
    private val strips = ArrayList<ImageView>()
    private val loaded = HashSet<Int>()
    private val requested = HashSet<Int>()
    private var stripSrcH = 1
    private var outW = 1
    private var failedText: String? = null
    private var errorView: android.widget.TextView? = null

    fun bind(page: Page, site: Int?, placeholder: Int, textColor: Int) {
        release()
        this.site = site
        path = page.url
        knownW = page.width ?: 0
        knownH = page.height ?: 0
        setBackgroundColor(placeholder)
        val url = page.url
        Util.bg {
            val (d, err) = Images.pageData(url, site)
            Util.ui {
                if (path == url) {
                    if (d == null) showError(err ?: "Не удалось загрузить страницу", textColor) else setData(d)
                }
            }
        }
        requestLayout()
    }

    fun release() {
        path = null
        data = null
        loaded.clear()
        requested.clear()
        strips.clear()
        removeAllViews()
        errorView = null
    }

    private fun showError(text: String, color: Int) {
        val t = android.widget.TextView(context)
        t.text = text
        t.setTextColor(color)
        t.gravity = android.view.Gravity.CENTER
        t.setPadding(context.dp(24), context.dp(48), context.dp(24), context.dp(48))
        errorView = t
        addView(t)
        requestLayout()
    }

    private fun setData(d: Images.PageData) {
        data = d
        if (d.w <= 0 || d.h <= 0) return
        outW = Math.max(1, Math.min(d.w, resources.displayMetrics.widthPixels))
        val outH = if (Images.lowRam) 1024 else 1536
        stripSrcH = Math.max(64, outH * d.w / outW)
        val count = (d.h + stripSrcH - 1) / stripSrcH
        for (i in 0 until count) {
            val iv = ImageView(context)
            iv.scaleType = ImageView.ScaleType.FIT_XY
            strips.add(iv)
            addView(iv)
        }
        requestLayout()
        post { updateVisible() }
    }

    private fun heightFor(w: Int): Int {
        val d = data
        return when {
            d != null && d.w > 0 -> (w.toLong() * d.h / d.w).toInt()
            knownW > 0 && knownH > 0 -> (w.toLong() * knownH / knownW).toInt()
            else -> w * 3 / 2
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (errorView != null) context.dp(220) else heightFor(w)
        setMeasuredDimension(w, h)
        val d = data
        if (d != null && d.w > 0) {
            for ((i, iv) in strips.withIndex()) {
                val t = topOf(i, w, d)
                val b = if (i == strips.size - 1) h else topOf(i + 1, w, d)
                iv.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(b - t, 1), MeasureSpec.EXACTLY))
            }
        }
        errorView?.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    private fun topOf(i: Int, w: Int, d: Images.PageData): Int = (i.toLong() * stripSrcH * w / d.w).toInt()

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val d = data
        if (d != null && d.w > 0) {
            for ((i, iv) in strips.withIndex()) {
                val top = topOf(i, w, d)
                val bottom = if (i == strips.size - 1) h else topOf(i + 1, w, d)
                iv.layout(0, top, w, Math.max(bottom, top + 1))
            }
        }
        errorView?.layout(0, 0, w, h)
        post { updateVisible() }
    }

    // Подгружает полосы рядом с экраном и выгружает те, что ушли далеко.
    fun updateVisible() {
        val d = data ?: return
        val rv = parent as? RecyclerView ?: return
        val vh = rv.height
        if (vh <= 0) return
        val margin = (vh * 0.6f).toInt()
        for ((i, iv) in strips.withIndex()) {
            val y0 = top + iv.top
            val y1 = top + iv.bottom
            val near = y1 > -margin && y0 < vh + margin
            val far = y1 < -margin * 3 || y0 > vh + margin * 3
            if (near) {
                if (!loaded.contains(i) && !requested.contains(i)) {
                    requested.add(i)
                    val srcTop = i * stripSrcH
                    Images.requestStrip(d, srcTop, stripSrcH, outW, { !requested.contains(i) || data !== d }) { bmp ->
                        requested.remove(i)
                        if (bmp != null && data === d && i < strips.size) {
                            strips[i].setImageBitmap(bmp)
                            loaded.add(i)
                        }
                    }
                }
            } else if (far) {
                if (loaded.remove(i)) strips[i].setImageDrawable(null)
                requested.remove(i)
            }
        }
    }
}

// Страница в постраничном режиме: щипок для зума, перетаскивание, двойное касание.
class ZoomImageView(ctx: Context) : AppCompatImageView(ctx) {
    private val m = Matrix()
    private val v = FloatArray(9)
    private var baseScale = 1f
    private var bmpW = 0
    private var bmpH = 0

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale() > baseScale * 1.05f) fit() else zoomBy(2.5f, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale() > baseScale * 1.01f) {
                m.postTranslate(-dx, -dy)
                fixTranslation()
                imageMatrix = m
                return true
            }
            return false
        }
    })

    init { scaleType = ScaleType.MATRIX }

    fun setBitmap(b: Bitmap) {
        setImageBitmap(b)
        bmpW = b.width
        bmpH = b.height
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    private fun scale(): Float {
        m.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    private fun fit() {
        if (bmpW == 0 || width == 0 || height == 0) return
        baseScale = Math.min(width / bmpW.toFloat(), height / bmpH.toFloat())
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((width - bmpW * baseScale) / 2f, (height - bmpH * baseScale) / 2f)
        imageMatrix = m
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val cur = scale()
        val target = Math.min(Math.max(cur * factor, baseScale), baseScale * 5f)
        val s = target / cur
        m.postScale(s, s, fx, fy)
        fixTranslation()
        imageMatrix = m
    }

    private fun fixTranslation() {
        val r = RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        // Пока страница увеличена, свайп двигает картинку, а не листает страницы.
        parent?.requestDisallowInterceptTouchEvent(scale() > baseScale * 1.01f || e.pointerCount > 1)
        return true
    }
}
