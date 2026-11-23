package ru.slashlib.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.R as MR

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// Стили текста Material 3.
object Ty {
    val TITLE_LARGE = MR.style.TextAppearance_Material3_TitleLarge
    val TITLE = MR.style.TextAppearance_Material3_TitleMedium
    val BODY = MR.style.TextAppearance_Material3_BodyMedium
    val SMALL = MR.style.TextAppearance_Material3_BodySmall
    val LABEL = MR.style.TextAppearance_Material3_LabelSmall
}

fun label(ctx: Context, s: String?, style: Int, color: Int = 0): TextView {
    val t = TextView(ctx)
    TextViewCompat.setTextAppearance(t, style)
    t.setTextColor(if (color != 0) color else Theme.onSurface(ctx))
    t.text = s
    return t
}

fun selectableBackground(ctx: Context): Int {
    val tv = TypedValue()
    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    return tv.resourceId
}

fun textButton(ctx: Context, s: String, onClick: () -> Unit): MaterialButton {
    val b = MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle)
    b.text = s
    b.setOnClickListener { onClick() }
    return b
}

fun filledButton(ctx: Context, s: String, onClick: () -> Unit): MaterialButton {
    val b = MaterialButton(ctx)
    b.text = s
    b.setOnClickListener { onClick() }
    return b
}

fun outlinedButton(ctx: Context, s: String, onClick: () -> Unit): MaterialButton {
    val b = MaterialButton(ctx, null, MR.attr.materialButtonOutlinedStyle)
    b.text = s
    b.setOnClickListener { onClick() }
    return b
}

// Плашка-тег в прямоугольнике (в духе M3 assist chip).
fun chip(ctx: Context, s: String, accent: Boolean): TextView {
    val t = label(ctx, s, Ty.LABEL, if (accent) Theme.onSecondaryContainer(ctx) else Theme.onSurfaceVariant(ctx))
    t.setPadding(ctx.dp(8), ctx.dp(3), ctx.dp(8), ctx.dp(3))
    t.maxLines = 1
    t.ellipsize = TextUtils.TruncateAt.END
    val d = GradientDrawable()
    d.cornerRadius = ctx.dp(8).toFloat()
    if (accent) d.setColor(Theme.secondaryContainer(ctx)) else {
        d.setColor(0)
        d.setStroke(ctx.dp(1), Theme.outline(ctx))
    }
    t.background = d
    return t
}

fun sectionHeader(ctx: Context, s: String): TextView {
    val t = label(ctx, s, Ty.TITLE, Theme.primary(ctx))
    t.setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(6))
    return t
}

// Верхняя панель M3 (MaterialToolbar) с кнопкой «назад» и действиями справа.
class Action(val label: String, val icon: Int = 0, val onClick: () -> Unit)

fun titleBar(ctx: Context, title: String, onBack: (() -> Unit)?, actions: List<Action> = emptyList()): MaterialToolbar {
    val tb = MaterialToolbar(ctx)
    tb.title = title
    if (onBack != null) {
        tb.setNavigationIcon(R.drawable.ic_back)
        tb.setNavigationOnClickListener { onBack() }
    }
    for ((i, a) in actions.withIndex()) {
        val item = tb.menu.add(0, i, i, a.label)
        if (a.icon != 0) item.setIcon(a.icon)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or (if (a.icon == 0) MenuItem.SHOW_AS_ACTION_WITH_TEXT else 0))
    }
    tb.setOnMenuItemClickListener { item ->
        actions.getOrNull(item.itemId)?.onClick?.invoke()
        true
    }
    return tb
}

// ---------- строки списка ----------

class Row(
    val title: String, val subtitle: String?, val cover: String?, val site: Int?,
    val progress: Float?, val tag: Any?
)

class RowView(ctx: Context) : LinearLayout(ctx) {
    private val cover = ShapeableImageView(ctx)
    private val title = label(ctx, "", Ty.TITLE)
    private val sub = label(ctx, "", Ty.SMALL, Theme.onSurfaceVariant(ctx))
    private val bar = LinearProgressIndicator(ctx)
    private val percent = label(ctx, "", Ty.LABEL, Theme.onSurfaceVariant(ctx))
    private val progressRow = LinearLayout(ctx)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(selectableBackground(ctx))
        setPadding(ctx.dp(16), ctx.dp(10), ctx.dp(16), ctx.dp(10))
        layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
        cover.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        cover.shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCornerSizes(ctx.dp(8).toFloat()).build()
        addView(cover, LayoutParams(ctx.dp(52), ctx.dp(74)))
        val col = LinearLayout(ctx)
        col.orientation = VERTICAL
        col.setPadding(ctx.dp(16), 0, 0, 0)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        sub.maxLines = 2
        sub.ellipsize = TextUtils.TruncateAt.END
        col.addView(title)
        col.addView(sub)
        progressRow.orientation = HORIZONTAL
        progressRow.gravity = Gravity.CENTER_VERTICAL
        bar.max = 100
        bar.trackThickness = ctx.dp(6)
        bar.trackCornerRadius = ctx.dp(3)
        progressRow.addView(bar, LayoutParams(0, WRAP, 1f))
        percent.setPadding(ctx.dp(8), 0, 0, 0)
        progressRow.addView(percent)
        val lp = LayoutParams(MATCH, WRAP)
        lp.topMargin = ctx.dp(8)
        col.addView(progressRow, lp)
        addView(col, LayoutParams(0, WRAP, 1f))
    }

    fun bind(r: Row) {
        title.text = r.title
        sub.text = r.subtitle
        sub.visibility = if (r.subtitle.isNullOrEmpty()) GONE else VISIBLE
        cover.loadCover(r.cover, r.site, Theme.secondaryContainer(context))
        val p = r.progress
        if (p != null) {
            progressRow.visibility = VISIBLE
            bar.setProgressCompat(Math.round(p * 100), false)
            percent.text = if (p >= 0.995f) "прочитано" else "${Math.round(p * 100)}%"
        } else {
            progressRow.visibility = GONE
        }
    }
}

class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onLong: ((Row) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var items: List<Row> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return object : RecyclerView.ViewHolder(RowView(parent.context)) {}
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
        val row = items[pos]
        (h.itemView as RowView).bind(row)
        h.itemView.setOnClickListener { onClick(row) }
        h.itemView.setOnLongClickListener {
            onLong?.invoke(row)
            onLong != null
        }
    }
}

// Тонкие разделители между строками.
class Divider(private val ctx: Context) : RecyclerView.ItemDecoration() {
    private val p = Paint()
    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        p.color = (Theme.outline(ctx) and 0x00FFFFFF) or 0x33000000
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            c.drawRect(v.left + ctx.dp(16).toFloat(), v.bottom.toFloat(), v.right.toFloat(), v.bottom + 1f, p)
        }
    }
}
