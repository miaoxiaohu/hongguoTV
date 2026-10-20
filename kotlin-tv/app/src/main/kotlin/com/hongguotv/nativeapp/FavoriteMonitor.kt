// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.RequestScope
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Serial foreground checks; cancellation owns only calls made by this monitor. */
class FavoriteMonitor(private val library: Library, source: ContentRepository, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var epoch = 0
    private var job: Future<*>? = null
    private var scope: RequestScope? = null
    private var destroyed = false
    private val http = source.http.newBuilder().callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val repository = ContentRepository(http)
    var running = false; private set
    var status = "进入收藏可手动检查更新"; private set
    var lastChangedId: String? = null; private set

    fun check(force: Boolean = false) {
        if (running || destroyed || !library.isLoaded) return
        val now = System.currentTimeMillis()
        val favorites = library.favorites()
        val items = favorites.filter { force || library.favoriteUpdate(it.id)?.let { now - it.checkedAt !in 0 until 3_600_000L } != false }
        lastChangedId = null
        if (items.isEmpty()) {
            status = if (favorites.isEmpty()) "收藏后可检查新集" else "最近一小时已检查，可手动刷新"
            changed(); return
        }
        val ticket = ++epoch
        val owned = RequestScope()
        scope = owned
        running = true
        var completed = 0; var failures = 0; var consecutiveFailures = 0; var lastError: Throwable? = null
        fun next() {
            if (ticket != epoch) return
            if (completed == items.size || consecutiveFailures >= 3) {
                running = false
                // The reason the run ended is part of what the user is told: a network that is
                // down and a detail page that changed shape need different answers, and a run
                // that stopped early says so.
                val reason=lastError?.let { describe(it) }.orEmpty()
                status = when {
                    failures == 0 -> "已检查 $completed/${items.size} 部 · 检查完成"
                    consecutiveFailures >= 3 -> "已检查 $completed/${items.size} 部 · 连续 3 次失败已停止（$reason）"
                    else -> "已检查 $completed/${items.size} 部 · $failures 部失败（$reason），稍后可重试"
                }
                changed(); return
            }
            val item = items[completed]
            val started = System.currentTimeMillis()
            status = "检查更新 ${completed + 1}/${items.size}…"
            changed()
            lastChangedId = null
            job = worker.submit {
                if (ticket != epoch) return@submit
                val result = runCatching { owned.run { repository.detail(item.id).episodes.size } }
                main.post {
                    if (ticket != epoch) return@post
                    job = null
                    result.onSuccess { count ->
                        library.observeFavorite(item.id, count, started)
                        lastChangedId = item.id
                        consecutiveFailures = 0
                    }.onFailure { error ->
                        failures++; consecutiveFailures++
                        // The exception used to be dropped here: it went into no status and into
                        // no log, so a check that failed for every show looked the same as one
                        // that failed for one show, and there was nothing to look at afterwards.
                        lastError=error
                        Log.w("HongguoTV", "收藏更新检查失败：${item.id}", error)
                    }
                    completed++
                    next()
                }
            }
        }
        next()
    }
    fun stop() {
        epoch++
        if (running) status = "更新检查已暂停，返回目录后继续"
        running = false
        scope?.cancel(); scope = null
        job?.cancel(true); job = null
    }
    fun destroy() { destroyed = true; stop(); worker.shutdownNow() }
    /**
     * What went wrong, in words the user can read.
     *
     * The type says which kind of failure it was - a timeout, a refused connection, a page
     * that no longer parses - and the message says which host or which field. The full
     * exception is in the log; this is the short form that fits on a line of the status.
     */
    private fun describe(error: Throwable): String {
        val name=error.javaClass.simpleName
        val message=error.message?.replace(Regex("\\s+")," ")?.trim()?.take(60).orEmpty()
        return if(message.isEmpty()) name else "$name: $message"
    }
}
