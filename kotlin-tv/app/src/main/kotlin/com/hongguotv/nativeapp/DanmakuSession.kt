// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuAnchors
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
    /** 现在的倍速，决定要提前多少去问下一窗。 */
    private var speed=1f
    /** 现在这一窗已经问到哪里了。 */
    private var loadedUntilMs=0L
    private var loading=false
    private var items=emptyList<Danmaku>()
    private var failed=false
    /**
     * 最近一次采样到的播放位置，和采样它时的真实时刻。
     *
     * 用来推算「数据到手的那一刻，播放到哪儿了」—— 决定哪些弹幕算晚到、要不要重新锚定。
     * 采样是每秒一次，中间这一段按倍速往前推。
     */
    private var positionMs=0L
    private var positionAt=0L
    /**
     * 换了集或关了开关就作废在飞的那次请求。
     *
     * 不用「还在不在跑」来判断：拉完最后一窗时也会把 running 置否，而那一批是要显示的。
     * 用一个只增不减的编号，谁的编号变了谁的结果就扔掉 —— 这跟搜索页作废上一次请求是同一招。
     */
    private var epoch=0

    /** 拿到新一批弹幕时回调（主线程）。 */
    var onItems: ((List<Danmaku>)->Unit)?=null
    /** 拉取失败时回调（主线程）。弹幕是锦上添花，失败不该打断播放。 */
    var onFailure: ((Throwable)->Unit)?=null

    /**
     * 开始一集。
     *
     * @param durationMs 这一集多长。知道了才停得住 —— 不然会一直往后问。
     * @param startMs 从哪儿开始问。续播、或者中途打开弹幕开关时，播放器已经播到中间了 ——
     *  还从第 0 秒问起的话，这一窗的数据会晚到几分钟：那几分钟里的弹幕要么凭空出现在画面中间，
     *  要么干脆对不上画面。直接从当前进度问，弹幕才是这一段的弹幕。
     */
    fun start(seriesId: String,vid: String,durationMs: Long=0L,startMs: Long=0L) {
        val from=startMs.coerceAtLeast(0L)
        synchronized(lock) {
            this.seriesId=seriesId; this.vid=vid; this.durationMs=durationMs
            loadedUntilMs=from; loading=false; items=emptyList<Danmaku>(); failed=false
            positionMs=from; positionAt=SystemClock.elapsedRealtime()
            epoch++
        }
        running.set(true)
        // 换集了：先把上一集的弹幕从画面上撤掉，别让它停在新集的开头。
        main.post { onItems?.invoke(emptyList()) }
        fetch(from)
    }

    /** 播放器拿到了真实时长。 */
    fun setDuration(durationMs: Long) { synchronized(lock) { this.durationMs=durationMs } }

    /**
     * 现在的播放倍速。
     *
     * 2 倍速时视频时间走得快，30 秒的窗口 15 秒真实时间就播完了 —— 提前量要跟着放大，
     * 不然就会断档：上一窗刚播完，下一窗还没到。
     */
    fun setSpeed(value: Float) { synchronized(lock) { speed=if(value>0.05f) value else 1f } }

    /**
     * 播放到了哪儿。
     *
     * 离已加载的末尾还剩不到 [LEAD_MS] 毫秒就去问下一窗：问一次要一秒多，晚了就断档。
     */
    fun onPosition(positionMs: Long) {
        if(!running.get()) return
        synchronized(lock) { this.positionMs=positionMs; this.positionAt=SystemClock.elapsedRealtime() }
        val need=synchronized(lock) {
            if(loading||failed) false
            else if(durationMs>0&&loadedUntilMs>=durationMs) false
            else positionMs+(LEAD_MS*speed).toLong()>=loadedUntilMs
        }
        if(need) fetch(synchronized(lock){ loadedUntilMs })
    }

    /**
     * 现在播放到哪儿了。
     *
     * 采样是每秒一次，数据到手的那一刻正好落在两次采样中间 —— 用中间这一段的真实时间按倍速
     * 往前推。差个一秒不要紧，差一秒就够把「晚到」判成「没晚到」，那一批弹幕就会凭空出现在
     * 画面中间。
     */
    private fun nowPosition(): Long {
        val pos: Long; val at: Long; val sp: Float
        synchronized(lock) { pos=positionMs; at=positionAt; sp=speed }
        val elapsed=SystemClock.elapsedRealtime()-at
        return pos+(elapsed*sp).toLong()
    }

    private fun fetch(startMs: Long) {
        val series: String; val episode: String; val mine: Int
        synchronized(lock) {
            if(loading||!running.get()) return
            loading=true; series=seriesId; episode=vid; mine=epoch
        }
        worker.execute {
            try {
                val window=repository.window(series,episode,startMs,synchronized(lock){ durationMs })
                // 数据到手时，这一窗开头那几秒可能已经过去了。晚到的那几条要重新锚定入场时间，
                // 不然它们一出现就整条摆在画面中间 —— 像凭空冒出来，而不是从右边飞进来。
                val est=nowPosition()
                val arrived=DanmakuAnchors.reanchor(window.items,est)
                val merged=synchronized(lock) {
                    if(mine!=epoch) return@synchronized null      // 换集或关了开关：这一批扔掉
                    val all=(items+arrived).distinctBy { it.offsetMs to it.text }.sortedBy { it.enterAtMs }
                    items=all
                    loadedUntilMs=maxOf(loadedUntilMs,window.nextStartMs)
                    loading=false
                    if(durationMs>0&&loadedUntilMs>=durationMs) running.set(false)
                    all
                }
                // 拉到最后一批时 running 会被置否，但那一批仍要显示 —— 所以看的是编号，不是 running。
                if(merged!=null) main.post { onItems?.invoke(merged) }
            } catch(e: Exception) {
                synchronized(lock) { if(mine==epoch) { loading=false; failed=true } }
                main.post { onFailure?.invoke(e) }
            }
        }
    }

    /** 换集或退出播放时叫停。 */
    fun stop() {
        running.set(false)
        synchronized(lock) { epoch++; loading=false; items=emptyList<Danmaku>(); loadedUntilMs=0L }
    }

    /** App 退出时把线程池收掉。 */
    fun close() {
        stop()
        worker.shutdownNow()
    }

    fun snapshot(): List<Danmaku> = synchronized(lock) { items }

    companion object {
        /**
         * 离末尾还有这么多就提前问下一窗。
         *
         * 六秒，不是两秒：问一次要一秒多（实测 2026-10-31，一次窗口约 1.5 秒），提前量只有两秒的话
         * 数据经常是**这一窗已经开始之后**才到 —— 那几秒里的弹幕就晚了，一晚到就凭空出现在画面
         * 中间。提前六秒问，数据在窗开始前就到手了。
         *
         * 问的频率没变（一窗一次），提前量变的是「什么时候问」，不是「问多频繁」。
         */
        const val LEAD_MS=6_000L
    }
}
