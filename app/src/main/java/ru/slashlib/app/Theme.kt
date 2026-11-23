package ru.slashlib.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

// Material Design 3. Цвета берутся из темы (тёмная/светлая — автоматически), цвет акцента выбирается в «Аккаунт».
object Theme {
    // Индексы совпадают с Prefs.accent. Последний пункт — «Системные цвета» (Material You, Android 12+).
    val accentNames = listOf("Синий", "Красный", "Оранжевый", "Зелёный", "Бирюзовый", "Фиолетовый", "Розовый", "Системный")
    val accentPreview = listOf(
        0xFF0A84FF.toInt(), 0xFFE53935.toInt(), 0xFFF57C00.toInt(), 0xFF2EAD4B.toInt(),
        0xFF00A3AD.toInt(), 0xFF8C4DD9.toInt(), 0xFFEC4D94.toInt(), 0xFF9E9E9E.toInt()
    )
    private val themes = intArrayOf(
        R.style.Theme_Slashlib_Blue, R.style.Theme_Slashlib_Red, R.style.Theme_Slashlib_Orange,
        R.style.Theme_Slashlib_Green, R.style.Theme_Slashlib_Teal, R.style.Theme_Slashlib_Purple,
        R.style.Theme_Slashlib_Pink, R.style.Theme_Slashlib_Blue
    )

    // Вызывать в onCreate ДО super.onCreate().
    fun apply(a: Activity) {
        val i = Prefs.accent.coerceIn(0, themes.size - 1)
        a.setTheme(themes[i])
        if (i == themes.size - 1) DynamicColors.applyToActivityIfAvailable(a)
    }

    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun c(ctx: Context, attr: Int, fallback: Int): Int = MaterialColors.getColor(ctx, attr, fallback)

    fun surface(ctx: Context) = c(ctx, MR.attr.colorSurface, Color.WHITE)
    fun onSurface(ctx: Context) = c(ctx, MR.attr.colorOnSurface, Color.BLACK)
    fun onSurfaceVariant(ctx: Context) = c(ctx, MR.attr.colorOnSurfaceVariant, Color.GRAY)
    fun primary(ctx: Context) = c(ctx, MR.attr.colorPrimary, Color.BLUE)
    fun onPrimary(ctx: Context) = c(ctx, MR.attr.colorOnPrimary, Color.WHITE)
    fun outline(ctx: Context) = c(ctx, MR.attr.colorOutline, Color.GRAY)
    fun primaryContainer(ctx: Context) = c(ctx, MR.attr.colorPrimaryContainer, Color.LTGRAY)
    fun onPrimaryContainer(ctx: Context) = c(ctx, MR.attr.colorOnPrimaryContainer, Color.BLACK)
    fun secondaryContainer(ctx: Context) = c(ctx, MR.attr.colorSecondaryContainer, Color.LTGRAY)
    fun onSecondaryContainer(ctx: Context) = c(ctx, MR.attr.colorOnSecondaryContainer, Color.BLACK)

    // «Карточка»: поверхность с лёгким тональным оттенком (как приподнятая поверхность в M3).
    fun card(ctx: Context): Int = MaterialColors.layer(surface(ctx), onSurface(ctx), 0.06f)
}
