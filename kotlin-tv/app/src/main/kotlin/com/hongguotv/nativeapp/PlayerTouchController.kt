// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.hongguotv.core.TouchGestureMachine
import com.hongguotv.core.TouchIntent

/**
 * 播放页的触控通道。
 *
 * 遥控器照旧走 `dispatchKeyEvent`，这里只管手指 —— 两边互不干涉。
 *
 * 音量走系统的媒体流（`STREAM_MUSIC`），跟电视上按音量键是同一条路，
 * 用户不会觉得「手机上能调、电视上不能」。
 */
class PlayerTouchController(
    private val activity: Activity,
    private val onToggleHud: ()->Unit,
    private val onPlayPause: ()->Unit,
    private val onScrub: (Long)->Unit
) {
    private val machine=TouchGestureMachine()
    private val main=Handler(Looper.getMainLooper())
    private val audio=activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    /** 预览层：拖动时显示目标时间。 */
    private var preview: LinearLayout?=null
    private var previewText: TextView?=null
    private var previewBar: ProgressBar?=null
    /** 音量条：纵滑时出现。 */
    private var volumeBox: LinearLayout?=null
    private var volumeText: TextView?=null
    private var volumeHide: Runnable?=null
    /** HUD 的宿主视图：用来判断落点是不是在按钮上。由 MainActivity 赋值。 */
    var hudHost: View?=null
    /** 这一轮手势是不是已经判给 HUD 按钮了。 */
    private var hudBlocked=false
    /** 按下瞬间的进度快照：拖动期间位置和时长都不再变。 */
    private var durationSnapshot=0L
    private var positionSnapshot=0L
    private var durationProvider:()->Long={ 0L }
    private var positionProvider:()->Long={ 0L }
    /** 手势是否已经收尾（主手指先抬起、别的手指还按着）。 */
    private var gestureFinished=false
    /**
     * 单击确认：抬起后延迟一拍问一次机器，确认了就切 HUD。
     *
     * 单击必须等双击超时才能确定，否则一次双击会先弹一次 HUD 再切播放状态。
     */
    private val confirmSingle=Runnable {
        val intent=machine.onTimeAdvance(SystemClock.uptimeMillis())
        if(intent is TouchIntent.ToggleHud) onToggleHud()
    }

    fun attach(root: FrameLayout,durationMs: ()->Long,positionMs: ()->Long) {
        detach()
        val density=activity.resources.displayMetrics.density
        val label=TextView(activity).apply {
            textSize=22f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
            setPadding((20*density).toInt(),(12*density).toInt(),(20*density).toInt(),(12*density).toInt())
        }
        previewText=label
        val bar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=1000
            progressTintList=android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.argb(80,255,255,255))
        }
        previewBar=bar
        val box=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
            background=GradientDrawable().apply { setColor(Color.argb(190,11,15,21)); cornerRadius=(12*density).toFloat() }
            setPadding((24*density).toInt(),(16*density).toInt(),(24*density).toInt(),(16*density).toInt())
            addView(label,LinearLayout.LayoutParams(-2,-2))
            addView(bar,LinearLayout.LayoutParams((220*density).toInt(),(6*density).toInt()).apply { topMargin=(10*density).toInt() })
            visibility=View.GONE
        }
        preview=box
        // 预览层挂在画面正中，不挡底部的 HUD 按钮。
        root.addView(box,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        val vol=TextView(activity).apply {
            textSize=18f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
            background=GradientDrawable().apply { setColor(Color.argb(190,11,15,21)); cornerRadius=(10*density).toFloat() }
            setPadding((18*density).toInt(),(10*density).toInt(),(18*density).toInt(),(10*density).toInt())
            visibility=View.GONE
        }
        volumeText=vol
        val vbox=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL
            addView(vol,LinearLayout.LayoutParams(-2,-2))
            visibility=View.GONE
        }
        volumeBox=vbox
        root.addView(vbox,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL).apply {
            leftMargin=(activity.resources.displayMetrics.widthPixels*0.08f).toInt()
        })
        durationProvider=durationMs
        positionProvider=positionMs
    }

    fun detach() {
        main.removeCallbacks(confirmSingle)
        volumeHide?.let { main.removeCallbacks(it) }
        preview?.let { (it.parent as? ViewGroup)?.removeView(it) }
        volumeBox?.let { (it.parent as? ViewGroup)?.removeView(it) }
        preview=null; previewText=null; previewBar=null; volumeBox=null; volumeText=null; volumeHide=null
        machine.reset()
        gestureFinished=false
    }

    /**
     * 吃一个触摸事件。返回 true 表示这件事归我管。
     */
    fun handleTouch(event: MotionEvent): Boolean {
        val action=event.actionMasked
        // 这一轮点在了 HUD 按钮上：整轮都不插手，交给按钮自己处理。
        // 手指按下后移出按钮范围也不改判 —— 半路抢走会让按钮的点击变得不可预测。
        if(hudBlocked && action!=MotionEvent.ACTION_DOWN) return false
        // 第二根手指按下：不喂给手势机。
        // core 的 onDown 会把进行中的拖动状态清掉，多指进来用户的进度拖动会无声丢失。
        if(action==MotionEvent.ACTION_POINTER_DOWN) return false
        val density=activity.resources.displayMetrics.density
        val (px,py)=primaryXY(event)
        val x=px/density; val y=py/density
        when(action) {
            MotionEvent.ACTION_DOWN -> {
                // 落在 HUD 的可点击按钮上就整轮放行：按钮自己有 onClick，
                // 点在它们身上时手势不该抢，否则点「播放」会先变成一次单击把 HUD 关了，按钮根本没被点到。
                if(onHudButton(event)) { hudBlocked=true; return false }
                hudBlocked=false
                gestureFinished=false
                durationSnapshot=durationProvider()
                positionSnapshot=positionProvider()
                // onDown 会把「已经过期还没确认」的挂起单击补发出来，返回值别丢。
                handleIntent(machine.onDown(x,y,positionSnapshot,durationSnapshot,SystemClock.uptimeMillis()))
                hidePreview(); hideVolume()
            }
            MotionEvent.ACTION_MOVE -> {
                handleIntent(machine.onMove(x,y))
                // 拖动中实时更新预览
                machine.pendingScrubTargetMs?.let { showPreview(it) }
            }
            MotionEvent.ACTION_UP -> {
                // 主手指在多指场景下已经抬起过了：这里只收尾，别再喂一次 onUp。
                if(gestureFinished) { gestureFinished=false; hidePreview(); return true }
                finishGesture(x,y)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 别的（次要）手指抬起：不碰手势机 —— 主手指还在屏幕上，拖动继续。
                // 只有主手指（pointer id 0）抬起才收尾。
                if(event.getPointerId(event.actionIndex)!=0) return false
                finishGesture(x,y)
                gestureFinished=true
            }
            MotionEvent.ACTION_CANCEL -> {
                // 取消必须收尾，不能直接丢：core 里会残留双击标志，
                // 把之后的普通单击误判成双击而凭空开始/暂停播放。
                main.removeCallbacks(confirmSingle)
                machine.reset()
                gestureFinished=false
                hidePreview(); hideVolume()
            }
            else -> return false
        }
        return true
    }

    /**
     * 收尾一次手势：抬手 = 可能出目标位置，也可能挂一个待确认的单击。
     */
    private fun finishGesture(xDp: Float,yDp: Float) {
        handleIntent(machine.onUp(xDp,yDp,SystemClock.uptimeMillis()))
        hidePreview()
        // 挂着的单击要等一拍才确认：延迟一拍问一次机器。
        main.removeCallbacks(confirmSingle)
        main.postDelayed(confirmSingle,TouchGestureMachine.DOUBLE_TAP_MS+40)
    }

    /**
     * 取主指针（pointer id 0）的坐标。
     *
     * 多指在 app 层就挡掉：core 的 onDown 没有 pointer id 参数，
     * 第二根手指按下会让进行中的拖动状态整个被清掉。
     */
    private fun primaryXY(event: MotionEvent): Pair<Float,Float> {
        val idx=event.findPointerIndex(0)
        if(idx<0) return event.x to event.y
        return event.getX(idx) to event.getY(idx)
    }

    private fun handleIntent(intent: TouchIntent) {
        when(intent) {
            is TouchIntent.ToggleHud -> onToggleHud()
            is TouchIntent.PlayPause -> onPlayPause()
            is TouchIntent.Scrub -> { onScrub(intent.targetMs); hidePreview() }
            is TouchIntent.Volume -> adjustVolume(intent.steps)
            is TouchIntent.None -> Unit
        }
    }

    /**
     * 调音量。取不到音频服务就什么都不做 —— 不能因为一个没插着的耳机把播放搞崩。
     */
    private fun adjustVolume(steps: Int) {
        val am=audio ?: return
        try {
            val max=am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val current=am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val next=(current+steps).coerceIn(0,max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC,next,0)
            showVolume(next,max)
        } catch(problem: Throwable) {
            // 音量调不了不是播放的事，别往上传。
        }
    }

    private fun showPreview(targetMs: Long) {
        val box=preview ?: return
        box.visibility=View.VISIBLE
        previewText?.text=formatTime(targetMs)
        val duration=durationSnapshot
        previewBar?.progress=if(duration>0) (targetMs*1000/duration).toInt().coerceIn(0,1000) else 0
    }

    private fun hidePreview() { preview?.visibility=View.GONE }

    private fun showVolume(current: Int,max: Int) {
        val box=volumeBox ?: return
        box.visibility=View.VISIBLE
        volumeText?.text="音量 $current / $max"
        volumeHide?.let { main.removeCallbacks(it) }
        val hide=Runnable { volumeBox?.visibility=View.GONE }
        volumeHide=hide
        main.postDelayed(hide,1200)
    }

    private fun hideVolume() { volumeBox?.visibility=View.GONE }

    /**
     * 落点是不是在 HUD 的可点击按钮上。
     *
     * HUD 里的按钮（播放/选集/播放设置…）自己有 onClick，点在它们身上时手势不该抢 ——
     * 否则点「播放」会先变成一次单击，把 HUD 关了，按钮根本没被点到。
     *
     * 坐标：用 `event.rawX/rawY`（屏幕绝对坐标），跟 `getLocationOnScreen` 是同一个坐标系。
     */
    fun onHudButton(event: MotionEvent): Boolean {
        val host=hudHost ?: return false
        if(host.visibility!=View.VISIBLE) return false
        val clickable=mutableListOf<View>()
        collectClickable(host,clickable)
        for(v in clickable) {
            val loc=IntArray(2); v.getLocationOnScreen(loc)
            // 用 rawX/rawY：它们本来就是屏幕绝对坐标，跟 getLocationOnScreen 是同一个坐标系，
            // 不用去猜窗口偏移。event.x/y 是相对窗口内容区的，换算最容易写错。
            if(event.rawX>=loc[0] && event.rawX<loc[0]+v.width &&
                event.rawY>=loc[1] && event.rawY<loc[1]+v.height) return true
        }
        return false
    }

    private fun collectClickable(view: View,into: MutableList<View>) {
        if(view.visibility!=View.VISIBLE) return
        if(view.isClickable && view.isEnabled) into+=view
        if(view is ViewGroup) for(i in 0 until view.childCount) collectClickable(view.getChildAt(i),into)
    }

    private fun formatTime(millis: Long): String {
        val seconds=millis.coerceAtLeast(0)/1000
        return "%02d:%02d".format(seconds/60,seconds%60)
    }
}
