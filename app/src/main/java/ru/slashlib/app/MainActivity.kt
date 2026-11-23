package ru.slashlib.app

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.bottomnavigation.BottomNavigationView

abstract class Screen(val act: MainActivity) {
    abstract val view: View
    open fun onShown() {}
    open fun onBack(): Boolean = false       // true — экран обработал «назад» сам
}

class App : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        applyNightMode()
        Images.init(this)
        Util.bg { Api.loadImageServers() }
        Sync.start()
    }

    companion object {
        // 0 светлая, 1 тёмная, 2 как в системе
        fun applyNightMode() {
            AppCompatDelegate.setDefaultNightMode(
                when (Prefs.themeMode) {
                    0 -> AppCompatDelegate.MODE_NIGHT_NO
                    1 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}

class MainActivity : AppCompatActivity() {
    private val stacks = Array(5) { ArrayList<Screen>() }
    private var tab = 0
    private lateinit var content: FrameLayout
    private lateinit var nav: BottomNavigationView
    private var switching = false

    private val tabIds = intArrayOf(R.id.nav_home, R.id.nav_search, R.id.nav_fav, R.id.nav_marks, R.id.nav_account)

    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.apply(this)
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab", 0) ?: 0

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        nav = BottomNavigationView(this)
        nav.inflateMenu(R.menu.nav)
        nav.setOnItemSelectedListener { item ->
            val i = tabIds.indexOf(item.itemId)
            if (i >= 0 && !switching) selectTab(i)
            true
        }
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)
        selectTab(tab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    private fun root(i: Int): Screen = when (i) {
        0 -> HomeScreen(this)
        1 -> SearchScreen(this)
        2 -> FavoritesScreen(this)
        3 -> BookmarksScreen(this)
        else -> SettingsScreen(this)
    }

    fun selectTab(i: Int) {
        tab = i
        if (stacks[i].isEmpty()) stacks[i].add(root(i))
        switching = true
        nav.selectedItemId = tabIds[i]
        switching = false
        show()
    }

    fun push(s: Screen) {
        stacks[tab].add(s)
        show()
    }

    fun pop() {
        val st = stacks[tab]
        if (st.size > 1) {
            st.removeAt(st.size - 1)
            show()
        }
    }

    private fun show() {
        val s = stacks[tab].last()
        content.removeAllViews()
        content.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
        s.onShown()
    }

    override fun onResume() {
        super.onResume()
        stacks[tab].lastOrNull()?.onShown()
        Sync.syncIfDue()
    }

    override fun onPause() {
        super.onPause()
        Sync.syncNow()      // отправить свежие закладки, пока приложение ещё живо
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val st = stacks[tab]
        if (st.last().onBack()) return
        if (st.size > 1) pop()
        else if (tab != 0) selectTab(0)
        else super.onBackPressed()
    }
}
