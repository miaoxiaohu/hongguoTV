// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一集播着的弹幕。
 *
 * 平台一次只给 30 秒窗口，所以要一窗一窗地问。什么时候问下一窗是这里唯一需要拿主意的地方：
 * 早了是白问（问多了会撞风控），晚了屏幕上会空一段。
 *
 * 所有状态都在锁里换掉：拉取在后台线程，绘制在主线程，两边都看同一份。
 */
class DanmakuSession(
    private val repository: DanmakuRepository = DanmakuRepository(),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r,"hongguotv-danmaku") }
) {
    private val lock=Any()
    private val running=AtomicBoolean(false)
    /** 拉取在后台线程，画画在主线程 —— 回调要自己搬回主线程。 */
    private val main=Handler(Looper.getMainLooper())
    private var seriesId=""
    private var vid=""
    private var durationMs=0L
    /** 已经问到的位置：这一行之前的内容都有了。 */
    private var loadedUntilMs=0L
    private var loading=false
    private var items=emptyList<Danmaku>()
    private var failed=false

    /** 拿到新一批弹幕时回调（主线程）。 */
    var onItems: ((List<Danmaku>)->Unit)?=null
    /** 拉取失败时回调（主线程）。弹幕是锦上添花，失败不该打断播放。 */
    var onFailure: ((Throwable)->Unit)?=null

    /**
     * 开始一集。
     *
     * @param durationMs 这一集多长。知道了才停得住 —— 不然会一直往后问。
     */
    fun start(seriesId: String,vid: String,durationMs: Long=0L) {
        synchronized(lock) {
            this.seriesId=seriesId; this.vid=vid; this.durationMs=durationMs
            loadedUntilMs=0L; loading=false; items=emptyList<Danmaku>(); failed=false
        }
        running.set(true)
        // 换集了：先把上一集的弹幕从画面上撤掉，别让它停在新集的开头。
        main.post { onItems?.invoke(emptyList()) }
        fetch(0L)
    }

    /** 播放器拿到了真实时长。 */
    fun setDuration(durationMs: Long) { synchronized(lock) { this.durationMs=durationMs } }

    /**
     * 播放到了哪儿。
     *
     * 离已加载的末尾还剩不到两秒就去问下一窗：问一次要几百毫秒，晚了就断档。
     */
    fun onPosition(positionMs: Long) {
        if(!running.get()) return
        val need=synchronized(lock) {
            if(loading||failed) false
            else if(durationMs>0&&loadedUntilMs>=durationMs) false
            else positionMs+LEAD_MS>=loadedUntilMs
        }
        if(need) fetch(synchronized(lock){ loadedUntilMs })
    }

    private fun fetch(startMs: Long) {
        val series: String; val episode: String
        synchronized(lock) {
            if(loading||!running.get()) return
            loading=true; series=seriesId; episode=vid
        }
        worker.execute {
            try {
                val window=repository.window(series,episode,startMs,synchronized(lock){ durationMs })
                val merged=synchronized(lock) {
                    val all=(items+window.items).distinctBy { it.offsetMs to it.text }.sortedBy { it.offsetMs }
                    items=all
                    loadedUntilMs=maxOf(loadedUntilMs,window.nextStartMs)
                    loading=false
                    if(durationMs>0&&loadedUntilMs>=durationMs) running.set(false)
                    all
                }
                main.post { onItems?.invoke(merged) }
            } catch(e: Exception) {
                synchronized(lock) { loading=false; failed=true }
                main.post { onFailure?.invoke(e) }
            }
        }
    }

    /** 换集或退出播放时叫停。 */
    fun stop() {
        running.set(false)
        synchronized(lock) { loading=false; items=emptyList<Danmaku>(); loadedUntilMs=0L }
    }

    fun snapshot(): List<Danmaku> = synchronized(lock) { items }

    companion object {
        /** 离末尾还有这么多就提前问下一窗。 */
        const val LEAD_MS=2_000L
    }
}
