package ru.slashlib.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

object Util {
    val main = Handler(Looper.getMainLooper())
    val io = Executors.newFixedThreadPool(4)

    fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    fun bg(block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (e: Throwable) {
                android.util.Log.e("Slashlib", "bg task failed", e)
            }
        }
    }

    // Выполнить блок на главном потоке и дождаться результата (вызывать НЕ с главного потока).
    fun <T> onMainSync(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask<T> { block() }
        main.post(task)
        return task.get()
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
