package ru.slashlib.app

import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MR

// Вкладка «Аккаунт»: вход, синхронизация, внешний вид, источники, кэш.
class SettingsScreen(act: MainActivity) : Screen(act) {
    private val siteStatus = label(act, "", Ty.BODY)
    private val syncStatus = label(act, "", Ty.BODY)
    private val cacheLabel = label(act, "", Ty.SMALL, Theme.onSurfaceVariant(act))
    private lateinit var siteToken: TextInputEditText
    private lateinit var syncToken: TextInputEditText

    override val view: View = build()

    private fun field(hint: String): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(act, null, MR.attr.textInputOutlinedStyle)
        til.hint = hint
        val e = TextInputEditText(til.context)
        e.minLines = 2
        e.setHorizontallyScrolling(false)
        e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        til.addView(e, LinearLayout.LayoutParams(MATCH, WRAP))
        return Pair(til, e)
    }

    private fun small(s: String): TextView {
        val t = label(act, s, Ty.SMALL, Theme.onSurfaceVariant(act))
        t.setPadding(0, act.dp(4), 0, act.dp(8))
        return t
    }

    private fun switchRow(text: String, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(act)
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.addView(label(act, text, Ty.BODY), LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = MaterialSwitch(act)
        sw.isChecked = on
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        row.addView(sw)
        return row
    }

    private fun buttons(vararg b: MaterialButton): LinearLayout {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        for (x in b) {
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = act.dp(8)
            row.addView(x, lp)
        }
        return row
    }

    private fun clipboard(): String {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: ""
    }

    private fun build(): View {
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.addView(titleBar(act, "Аккаунт", null))

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(act.dp(16), 0, act.dp(16), act.dp(24))

        fun section(s: String) {
            val h = sectionHeader(act, s)
            h.setPadding(0, act.dp(20), 0, act.dp(4))
            col.addView(h)
        }

        // --- вход ---
        section("Вход на сайт")
        col.addView(siteStatus)
        col.addView(small("Вставьте токен (Bearer) из вашего аккаунта на сайте: в браузере на ПК DevTools → Network → любой запрос к api.lib.social → заголовок Authorization."))
        val sf = field("Токен сайта")
        siteToken = sf.second
        col.addView(sf.first)
        col.addView(buttons(
            textButton(act, "Вставить") { siteToken.setText(clipboard()) },
            filledButton(act, "Сохранить") { saveSiteToken() },
            outlinedButton(act, "Выйти") { Prefs.token = null; refreshSite() }
        ))

        // --- синхронизация ---
        section("Синхронизация между устройствами")
        col.addView(syncStatus)
        col.addView(small("Закладки, избранное, история и прочитанные главы синхронизируются через приватный Gist в вашем аккаунте GitHub — в том числе между Android и iOS. 1) github.com/settings/tokens → Generate new token (classic) → отметьте только «gist», срок — No expiration (тогда продлевать не нужно). 2) Вставьте токен ниже и нажмите «Включить». На втором устройстве введите тот же токен."))
        val gf = field("Токен GitHub")
        syncToken = gf.second
        col.addView(gf.first)
        col.addView(buttons(
            textButton(act, "Вставить") { syncToken.setText(clipboard()) },
            filledButton(act, "Включить") { enableSync() },
            outlinedButton(act, "Сейчас") { if (Sync.isEnabled) Sync.syncNow() }
        ))
        col.addView(buttons(textButton(act, "Отключить синхронизацию") { Sync.disable(); refreshSync() }))

        // --- внешний вид ---
        section("Внешний вид")
        val theme = MaterialButtonToggleGroup(act)
        theme.isSingleSelection = true
        theme.isSelectionRequired = true
        val names = listOf("Светлая", "Тёмная", "Система")
        val ids = ArrayList<Int>()
        for (n in names) {
            val b = MaterialButton(act, null, MR.attr.materialButtonOutlinedStyle)
            b.text = n
            b.id = View.generateViewId()
            ids.add(b.id)
            theme.addView(b)
        }
        theme.check(ids[Prefs.themeMode.coerceIn(0, 2)])
        theme.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                val m = ids.indexOf(id)
                if (m != Prefs.themeMode) {
                    Prefs.themeMode = m
                    App.applyNightMode()       // AppCompat сам пересоздаст экраны
                }
            }
        }
        col.addView(theme)

        val cap = label(act, "Цвет акцента", Ty.SMALL, Theme.onSurfaceVariant(act))
        cap.setPadding(0, act.dp(12), 0, act.dp(6))
        col.addView(cap)
        val swatches = LinearLayout(act)
        for ((i, color) in Theme.accentPreview.withIndex()) {
            val v = View(act)
            val d = GradientDrawable()
            d.shape = GradientDrawable.OVAL
            d.setColor(color)
            if (i == Prefs.accent) d.setStroke(act.dp(3), Theme.onSurface(act))
            v.background = d
            v.contentDescription = Theme.accentNames[i]
            v.setOnClickListener {
                if (Prefs.accent != i) {
                    Prefs.accent = i
                    act.recreate()
                }
            }
            val lp = LinearLayout.LayoutParams(act.dp(34), act.dp(34))
            lp.rightMargin = act.dp(8)
            swatches.addView(v, lp)
        }
        col.addView(swatches)
        col.addView(small("Последний кружок — системные цвета Material You (Android 12+)."))

        // --- источники ---
        section("Источники")
        col.addView(switchRow("Тайтлы MangaLib", Prefs.mangalib) { Prefs.mangalib = it })
        col.addView(small("Добавляет каталог MangaLib в поиск и подборки (тот же API, другой сайт сети). Работает по моим данным о API и на живом сервере не проверялось; для 18+ нужен токен."))

        // --- кэш ---
        section("Кэш")
        col.addView(cacheLabel)
        col.addView(buttons(outlinedButton(act, "Очистить кэш картинок") { Images.clearAll(); refreshCache() }))

        section("О приложении")
        col.addView(small("Slashlib для Android 5.0+ (armv7 и arm64). Неофициальный клиент; адреса и поля API в Config.kt / Api.kt помечены «ПРОВЕРИТЬ»."))

        val sv = ScrollView(act)
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    override fun onShown() {
        refreshSite()
        refreshSync()
        refreshCache()
        Sync.listener = { refreshSync() }
    }

    private fun refreshSite() {
        siteStatus.text = if (Prefs.token.isNullOrEmpty()) "Не выполнен вход" else "Токен сохранён"
    }

    private fun refreshSync() {
        syncStatus.text = Sync.statusText
    }

    private fun refreshCache() {
        Util.bg {
            val mb = Images.cacheBytes() / 1048576
            Util.ui { cacheLabel.text = "Кэш картинок на диске: $mb МБ (старые файлы удаляются сами)" }
        }
    }

    private fun saveSiteToken() {
        val t = (siteToken.text?.toString() ?: "").trim().removePrefix("Bearer ").trim()
        if (t.isEmpty()) { siteStatus.text = "Токен пустой"; return }
        Prefs.token = t
        siteToken.setText("")
        siteStatus.text = "Проверяю токен…"
        Util.bg {
            val msg = try {
                val name = Api.me()
                "Вы вошли как ${name ?: "пользователь"}"
            } catch (e: Exception) {
                "Токен сохранён, но проверить его не удалось (возможно, неверный адрес проверки)."
            }
            Util.ui { siteStatus.text = msg }
        }
    }

    private fun enableSync() {
        val raw = syncToken.text?.toString() ?: ""
        syncStatus.text = "Проверяю токен…"
        Sync.enable(raw) { err ->
            if (err != null) syncStatus.text = "Ошибка: $err" else { syncToken.setText(""); refreshSync() }
        }
    }
}
