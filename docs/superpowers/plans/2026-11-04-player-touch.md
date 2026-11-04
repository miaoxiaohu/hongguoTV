# 播放页触控逻辑 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 播放画面支持单击切 HUD、双击播放/暂停、横滑拖进度、纵滑调音量，与遥控器并存互不干扰。

**Architecture:** 手势判定逻辑放 core（纯 Kotlin、可 JVM 单测），app 层新增 `PlayerTouchController` 把 `MotionEvent` 翻译成 core 的意图，`MainActivity.dispatchTouchEvent` 在播放页接管并派发意图。

**Tech Stack:** Kotlin / Android View / Media3 / JUnit4（core 用 `:core:test`）

## Global Constraints

- 回复、文档、注释一律**简体中文**；标识符/路径/命令保持原样。
- core 模块**不得依赖 Android SDK**（纯 Kotlin，JVM 单测可跑）。
- `dispatchKeyEvent` **一行不改** —— 遥控器行为零回归。
- 正式交付包**不含任何诊断代码**：不留读数条、埋点、上报。
- 灵敏度常量集中在 core 的 companion 里，改一处即全生效。
- 本轮版本：**0.9.82 / versionCode 9082**（CI 从 tag `kotlin-v0.9.82` 算出）。
- 出正式包前先删 `kotlin-tv/app/build`（AGP 的 `GenerateBuildConfig` 不跟踪版本号）。
- 实验包 `-dbg` 的 versionCode 必须**严格小于** 9082。
- core 包名 `com.hongguotv.core`，app 包名 `com.hongguotv.nativeapp`。
- adb 路径固定：`D:\Programs\android-sdk\platform-tools\adb.exe`（用 AndroidSideloader 自带的会互杀 adb server）。

## 文件结构

| 文件 | 职责 |
|---|---|
| `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PlaybackGesture.kt`（新建） | 手势判定机 + 意图类型，纯 Kotlin |
| `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/PlaybackGestureTest.kt`（新建） | 手势机单测 |
| `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/PlayerTouchController.kt`（新建） | MotionEvent → 意图；音量调节；预览层/音量条视图 |
| `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt`（修改） | `dispatchTouchEvent` 接管、意图派发、播放页视图生命周期 |

---

### Task 1: core 手势判定机

**Files:**
- Create: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PlaybackGesture.kt`

**Interfaces:**
- Consumes: 无（不依赖任何已有 core 类型）
- Produces:
  - `sealed class TouchIntent`，嵌套 `object ToggleHud` / `object PlayPause` / `data class Scrub(val targetMs: Long)` / `data class Volume(val steps: Int)` / `object None`
  - `class TouchGestureMachine`：
    - `fun onDown(xDp: Float, yDp: Float, positionMs: Long, durationMs: Long, nowMs: Long)`
    - `fun onMove(xDp: Float, yDp: Float): TouchIntent`
    - `fun onUp(xDp: Float, yDp: Float, nowMs: Long): TouchIntent`
    - `fun onTimeAdvance(nowMs: Long): TouchIntent` —— 推进时钟，超过双击超时就确认挂着的单击
    - `fun reset()`
    - `val pendingScrubTargetMs: Long?` —— 拖动期间的目标位置预览
    - `val isDragging: Boolean`
    - companion: `const val SLOP_DP = 8f`、`const val SECONDS_PER_DP = 0.5f`、`const val VOLUME_STEP_DP = 24f`、`const val DOUBLE_TAP_MS = 300L`

- [ ] **Step 1: 写实现文件**

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 播放页手势的意图。
 *
 * 手势判定与 Android 无关：坐标先换算成 dp，判定完只说「要做什么」，
 * 真正做事（seek、调音量、开关 HUD）留给上层。
 */
sealed class TouchIntent {
    /** 单击画面：显示或隐藏 HUD。 */
    object ToggleHud : TouchIntent()
    /** 双击画面：播放或暂停。 */
    object PlayPause : TouchIntent()
    /** 横向拖动松手：跳到目标位置。 */
    data class Scrub(val targetMs: Long) : TouchIntent()
    /** 纵向拖动：音量增减若干档。 */
    data class Volume(val steps: Int) : TouchIntent()
    /** 什么都不做。 */
    object None : TouchIntent()
}

/**
 * 手势判定机。
 *
 * 三件事必须在它这里做对：
 *
 * 1. **方向锁**：位移过 slop 后一次性定方向，之后不再改判。斜滑只能触发一个动作 ——
 *    否则一次斜滑既拖进度又调音量，用户自己都说不清发生了什么。
 * 2. **单击与双击不能同时成立**：二者的判定时间天然打架。这里取「单击晚发」：
 *    单击要等双击超时才确认（`onTimeAdvance` 里出），双击立刻出。代价是单击有 300ms 延迟，
 *    但不会出现「双击先触发一次 HUD 开关」。
 * 3. **拖动期间不真的 seek**：只在 `onUp` 时给一次目标位置 —— 拖一次 seek 一次会卡成幻灯片。
 *
 * 位置与时长由调用方在按下瞬间给进来，机器自己不持有播放器引用；
 * 拖动期间这两个值固定不变，否则手指停住时目标位置还在飘。
 */
class TouchGestureMachine {
    private enum class Axis { None, Horizontal, Vertical }

    private var axis=Axis.None
    private var downX=0f
    private var downY=0f
    private var basePositionMs=0L
    private var durationMs=0L
    /** 上一根手指抬起的时间；用来判双击。 */
    private var lastUpAt=Long.MIN_VALUE
    /** 音量已经发出的档数，拖动中只发增量。 */
    private var volumeStepsSent=0

    /** 拖动中的目标位置（预览用）；不在拖动时为 null。 */
    var pendingScrubTargetMs: Long?=null
        private set

    /** 现在是不是在拖动。 */
    val isDragging get() = axis!=Axis.None

    /**
     * 按下。`positionMs` 是当前播放位置，`durationMs` 是总时长（未知传 0）。
     * `nowMs` 是单调时钟，用来判双击。
     */
    fun onDown(xDp: Float,yDp: Float,positionMs: Long,durationMs: Long,nowMs: Long) {
        axis=Axis.None
        downX=xDp; downY=yDp
        basePositionMs=positionMs; this.durationMs=durationMs
        pendingScrubTargetMs=null
        volumeStepsSent=0
        // 离上次抬起不到双击超时，这一就是第二下。
        if(lastUpAt!=Long.MIN_VALUE && nowMs-lastUpAt<DOUBLE_TAP_MS) {
            // 双击的第二下算数了，挂着的单击作废。
            lastUpAt=Long.MIN_VALUE
            doubleTap=true
        }
    }

    /**
     * 移动。返回的意图只在纵向拖动时非空 —— 音量要边拖边调，不能等松手。
     */
    fun onMove(xDp: Float,yDp: Float): TouchIntent {
        val dx=xDp-downX; val dy=yDp-downY
        if(axis==Axis.None) {
            if(kotlin.math.abs(dx)<SLOP_DP && kotlin.math.abs(dy)<SLOP_DP) return TouchIntent.None
            // 方向只定一次：斜滑取位移较大的那个方向，此后不再改判。
            axis=if(kotlin.math.abs(dx)>=kotlin.math.abs(dy)) Axis.Horizontal else Axis.Vertical
        }
        return when(axis) {
            Axis.Horizontal -> {
                pendingScrubTargetMs=scrubTarget(dx)
                TouchIntent.None
            }
            Axis.Vertical -> {
                // 上滑为正（dy 是负的），按总位移算，slop 那点位移也一并算进去。
                val steps=kotlin.math.round((-dy/VOLUME_STEP_DP)).toInt()
                val delta=steps-volumeStepsSent
                if(delta==0) TouchIntent.None else { volumeStepsSent=steps; TouchIntent.Volume(delta) }
            }
            Axis.None -> TouchIntent.None
        }
    }

    /**
     * 抬起。横向拖动在这里给出目标位置；单击在这里记时间，不在这里确认。
     */
    fun onUp(xDp: Float,yDp: Float,nowMs: Long): TouchIntent {
        val isTap=axis==Axis.None
        val wasDouble=doubleTap
        doubleTap=false
        val intent: TouchIntent=when(axis) {
            Axis.Horizontal -> TouchIntent.Scrub(pendingScrubTargetMs ?: scrubTarget(xDp-downX))
            Axis.Vertical -> TouchIntent.None
            Axis.None -> if(wasDouble) TouchIntent.PlayPause else TouchIntent.None
        }
        axis=Axis.None
        pendingScrubTargetMs=null
        // 只有「点」才挂一个待确认的单击：拖动不是单击，双击之后也不能再补一个。
        lastUpAt=when {
            wasDouble -> Long.MIN_VALUE
            isTap -> nowMs
            else -> Long.MIN_VALUE
        }
        return intent
    }

    /**
     * 推进时钟。超过双击超时就把挂着的单击确认掉。
     */
    fun onTimeAdvance(nowMs: Long): TouchIntent {
        if(lastUpAt==Long.MIN_VALUE) return TouchIntent.None
        if(nowMs-lastUpAt<DOUBLE_TAP_MS) return TouchIntent.None
        lastUpAt=Long.MIN_VALUE
        return TouchIntent.ToggleHud
    }

    /** 复位：换集、退出播放、面板打开时调用。 */
    fun reset() {
        axis=Axis.None; pendingScrubTargetMs=null; doubleTap=false
        lastUpAt=Long.MIN_VALUE; volumeStepsSent=0
    }

    /**
     * 横向拖动算出来的目标位置。夹在 `[0, duration-500]` 里。
     */
    private fun scrubTarget(totalDxDp: Float): Long {
        if(durationMs<=0) return basePositionMs
        val delta=(totalDxDp*SECONDS_PER_DP*1000).toLong()
        val max=(durationMs-500).coerceAtLeast(0)
        return (basePositionMs+delta).coerceIn(0,max)
    }

    /** 这一下是不是双击的第二下。 */
    private var doubleTap=false

    companion object {
        /** 超过这个位移（dp）才算「拖」，否则算「点」。 */
        const val SLOP_DP=8f
        /** 横向拖动每 dp 对应多少秒。 */
        const val SECONDS_PER_DP=0.5f
        /** 纵向拖动每 dp 对应多少档音量。 */
        const val VOLUME_STEP_DP=24f
        /** 两次点击间隔超过这么久就不算双击。 */
        const val DOUBLE_TAP_MS=300L
    }
}
```

- [ ] **Step 2: 编译 core**

Run: `cd kotlin-tv && .\gradlew.bat :core:compileKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交**

```bash
git add kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PlaybackGesture.kt
git commit -m "feat: core 手势判定机（方向锁/单击延迟/横滑目标/纵滑音量）"
```

---

### Task 2: core 手势机单测

**Files:**
- Create: `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/PlaybackGestureTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `TouchIntent` / `TouchGestureMachine`
- Produces: 无（测试文件）

- [ ] **Step 1: 写单测**

```kotlin
package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackGestureTest {
    @Test fun 斜滑只锁一个方向不会既拖进度又调音量() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,0L)
        // 先斜着走：横向位移更大，锁横向
        m.onMove(140f,112f)
        // 之后改成纵向走，方向不能改判 —— 不能再发音量
        assertEquals(TouchIntent.None,m.onMove(150f,200f))
        // 抬起仍然是横滑的落点
        assertTrue(m.onUp(150f,200f,10L) is TouchIntent.Scrub)
    }

    @Test fun 横滑按相对位移算目标位置() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,60_000L,600_000L,0L)
        m.onMove(200f,100f)               // +100dp = +50 秒
        val up=m.onUp(200f,100f,10L) as TouchIntent.Scrub
        assertEquals(110_000L,up.targetMs)
    }

    @Test fun 横滑越界夹在零和总时长之间() {
        val forward=TouchGestureMachine().apply {
            onDown(100f,100f,0L,600_000L,0L); onMove(1000f,100f)
        }
        assertEquals(450_000L,(forward.onUp(1000f,100f,10L) as TouchIntent.Scrub).targetMs)
        val back=TouchGestureMachine().apply {
            onDown(100f,100f,10_000L,600_000L,0L); onMove(0f,100f)
        }
        assertEquals(0L,(back.onUp(0f,100f,10L) as TouchIntent.Scrub).targetMs)
    }

    @Test fun 拖动期间不给意图抬起才给一次() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,0L)
        assertEquals(TouchIntent.None,m.onMove(150f,100f))
        assertEquals(TouchIntent.None,m.onMove(200f,100f))
        assertTrue(m.onUp(200f,100f,10L) is TouchIntent.Scrub)
    }

    @Test fun 单击要等双击超时才确认() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,1_000L)
        assertEquals(TouchIntent.None,m.onUp(100f,100f,1_000L))
        assertEquals(TouchIntent.None,m.onTimeAdvance(1_100L))
        assertEquals(TouchIntent.ToggleHud,m.onTimeAdvance(1_300L))
    }

    @Test fun 双击压掉单击只给一次播放暂停() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,0L,600_000L,1_000L)
        m.onUp(100f,100f,1_000L)
        // 第二下：离上次抬起 100ms，不到 300ms
        m.onDown(100f,100f,0L,600_000L,1_100L)
        assertEquals(TouchIntent.PlayPause,m.onUp(100f,100f,1_100L))
        // 超时之后也不该再补一个单击
        assertEquals(TouchIntent.None,m.onTimeAdvance(2_000L))
    }

    @Test fun 纵滑按档位发增量音量() {
        val m=TouchGestureMachine()
        m.onDown(100f,200f,0L,600_000L,0L)
        assertEquals(TouchIntent.Volume(1),m.onMove(100f,176f))   // 上滑 24dp = 一档
        assertEquals(TouchIntent.Volume(1),m.onMove(100f,152f))   // 再一档
        assertEquals(TouchIntent.None,m.onMove(100f,160f))        // 回了一点，不足一档
    }

    @Test fun 总时长未知时不产生目标位置() {
        val m=TouchGestureMachine()
        m.onDown(100f,100f,30_000L,0L,0L)
        m.onMove(200f,100f)
        assertEquals(30_000L,(m.onUp(200f,100f,10L) as TouchIntent.Scrub).targetMs)
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `cd kotlin-tv && .\gradlew.bat :core:test --tests "com.hongguotv.core.PlaybackGestureTest" --console=plain --no-daemon`
Expected: 8 项全部 PASS

- [ ] **Step 3: 跑全量 core 测试确认没打破别的**

Run: `cd kotlin-tv && .\gradlew.bat :core:test --console=plain --no-daemon`
Expected: 全部 PASS（原有 205 项 + 新增 8 项）

- [ ] **Step 4: 提交**

```bash
git add kotlin-tv/core/src/test/kotlin/com/hongguotv/core/PlaybackGestureTest.kt
git commit -m "test: 手势机单测（方向锁/夹取/单击延迟/双击压制/音量档位）"
```

---

### Task 3: app 触控控制器

**Files:**
- Create: `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/PlayerTouchController.kt`

**Interfaces:**
- Consumes: core 的 `TouchIntent` / `TouchGestureMachine`（Task 1）
- Produces:
  - `class PlayerTouchController(activity: Activity, onToggleHud: ()->Unit, onPlayPause: ()->Unit, onScrub: (Long)->Unit)`
  - `fun attach(root: FrameLayout, durationMs: ()->Long, positionMs: ()->Long)` —— 把预览层和音量条挂进播放页根视图
  - `fun detach()`
  - `fun handleTouch(event: MotionEvent): Boolean` —— 返回 true 表示已消费
  - `fun onHudButton(event: MotionEvent): Boolean` —— 落点是不是在 HUD 的可点击按钮上
  - `var hudHost: View?=null` —— HUD 的宿主视图，MainActivity 赋值

- [ ] **Step 1: 写控制器**

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
    private val audio=activity.getSystemService(Activity.AUDIO_SERVICE) as? AudioManager
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
    /** 按下瞬间的进度快照：拖动期间位置和时长都不再变。 */
    private var durationSnapshot=0L
    private var positionSnapshot=0L
    private var durationProvider:()->Long={ 0L }
    private var positionProvider:()->Long={ 0L }
    /** 单击确认：抬起后延迟一拍问一次机器，确认了就切 HUD。 */
    private val confirmSingle=Runnable {
        val intent=machine.onTimeAdvance(android.os.SystemClock.uptimeMillis())
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
        preview?.let { (it.parent as? ViewGroup)?.removeView(it) }
        volumeBox?.let { (it.parent as? ViewGroup)?.removeView(it) }
        preview=null; previewText=null; previewBar=null; volumeBox=null; volumeText=null
        machine.reset()
    }

    /**
     * 吃一个触摸事件。返回 true 表示这件事归我管。
     */
    fun handleTouch(event: MotionEvent): Boolean {
        val density=activity.resources.displayMetrics.density
        val x=event.x/density; val y=event.y/density
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                durationSnapshot=durationProvider()
                positionSnapshot=positionProvider()
                machine.onDown(x,y,positionSnapshot,durationSnapshot,android.os.SystemClock.uptimeMillis())
                hidePreview(); hideVolume()
            }
            MotionEvent.ACTION_MOVE -> {
                handleIntent(machine.onMove(x,y))
                // 拖动中实时更新预览
                machine.pendingScrubTargetMs?.let { showPreview(it) }
            }
            MotionEvent.ACTION_UP -> {
                handleIntent(machine.onUp(x,y,android.os.SystemClock.uptimeMillis()))
                if(machine.isDragging) hidePreview()
                // 挂着的单击要等一拍才确认：延迟一拍问一次机器。
                main.removeCallbacks(confirmSingle)
                main.postDelayed(confirmSingle,TouchGestureMachine.DOUBLE_TAP_MS+40)
            }
            MotionEvent.ACTION_CANCEL -> { machine.reset(); hidePreview(); hideVolume() }
            else -> return false
        }
        return true
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

    private fun formatTime(millis: Long): String {
        val seconds=millis.coerceAtLeast(0)/1000
        return "%02d:%02d".format(seconds/60,seconds%60)
    }
}
```

- [ ] **Step 2: 编译 app**

Run: `cd kotlin-tv && .\gradlew.bat :app:compileDebugKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 提交**

```bash
git add kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/PlayerTouchController.kt
git commit -m "feat: 播放页触控控制器（预览层/音量条/手势派发）"
```

---

### Task 4: MainActivity 接入

**Files:**
- Modify: `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt`

**Interfaces:**
- Consumes: Task 3 的 `PlayerTouchController`
- Produces: 无（对外行为变化）

改动点（**`dispatchKeyEvent` 一行不动**）：

1. 新增字段（放在 `private var panel=false` 附近）：
   ```kotlin
   private var touchController: PlayerTouchController?=null
   ```

2. `onCreate` 里（`root=FrameLayout(this).apply{...}` 之后）创建控制器：
   ```kotlin
   touchController=PlayerTouchController(this,
       onToggleHud={ if(this::hud.isInitialized && hud.visibility==View.VISIBLE) hidePlaybackOverlay() else showHud() },
       onPlayPause={ togglePlayback() },
       onScrub={ target -> seekTo(target) })
   ```
   ⚠ `hud` 是 `lateinit`，`onCreate` 里不能直接引用 —— 闭包是延迟执行的，
   真正引用发生在点击时，那时 `hud` 早已初始化。用 `this::hud.isInitialized` 兜住。

3. `playEpisode` 里，`if(!reuse){...}` 整块之后挂预览层并交出 HUD 宿主：
   ```kotlin
   touchController?.hudHost=hud
   touchController?.attach(root,{ player?.duration ?: 0L },{ currentPosition() })
   ```
   ⚠ 必须放在整块之后：`attach` 内部先 `detach`（会把旧预览层从父视图摘掉）再挂新的，
   所以换集复用播放器时不会留下两个预览层。放在块内则复用路径不挂、首次路径漏挂。

4. `releasePlayer()` 里释放：
   ```kotlin
   touchController?.detach()
   ```

5. 新增 `dispatchTouchEvent` 覆写：
   ```kotlin
   override fun dispatchTouchEvent(event: MotionEvent): Boolean {
       // 只有播放页、且没有错误面板/选集面板时才接管。
       if(screen=="player" && !playError && episodePanel==null) {
           val controller=touchController
           if(controller!=null && !controller.onHudButton(event)) {
               if(controller.handleTouch(event)) return true
           }
       }
       return super.dispatchTouchEvent(event)
   }
   ```

6. `PlayerTouchController.onHudButton` 的实现（坐标换算是接入里最容易写错的地方）：
   ```kotlin
   /**
    * 落点是不是在 HUD 的可点击按钮上。
    *
    * HUD 里的按钮（播放/选集/播放设置…）自己有 onClick，点在它们身上时手势不该抢 ——
    * 否则点「播放」会先变成一次单击，把 HUD 关了，按钮根本没被点到。
    *
    * 坐标：`event.x/y` 是相对窗口内容区的，`getLocationOnScreen` 给的是屏幕坐标，
    * 两者差一个窗口偏移，所以拿 `root` 的屏幕坐标当基准换算。
    */
   fun onHudButton(event: MotionEvent): Boolean {
       val host=hudHost ?: return false
       if(host.visibility!=View.VISIBLE) return false
       val base=IntArray(2); host.getLocationOnScreen(base)
       val clickable=mutableListOf<View>()
       collectClickable(host,clickable)
       for(v in clickable) {
           val loc=IntArray(2); v.getLocationOnScreen(loc)
           val left=loc[0]-base[0]; val top=loc[1]-base[1]
           if(event.x>=left && event.x<left+v.width && event.y>=top && event.y<top+v.height) return true
       }
       return false
   }

   private fun collectClickable(view: View,into: MutableList<View>) {
       if(view.visibility!=View.VISIBLE) return
       if(view.isClickable && view.isEnabled) into+=view
       if(view is ViewGroup) for(i in 0 until view.childCount) collectClickable(view.getChildAt(i),into)
   }
   ```
   ⚠ `hudHost` 字段 Task 3 已建好，这里只补 `onHudButton` 的实现。

- [ ] **Step 1: 按上面 6 点改 MainActivity**

改动后核对：
- `dispatchKeyEvent`（原第 2000 行起）**逐字未变**。
- `PlayerTouchController` 的 `var hudHost: View?=null` 已在 Task 3 建好，本任务只补 `onHudButton` 实现。

- [ ] **Step 2: 编译**

Run: `cd kotlin-tv && .\gradlew.bat :app:compileDebugKotlin --console=plain --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: 跑全量测试 + lint**

Run: `cd kotlin-tv && .\gradlew.bat :core:test :app:lintDebug --console=plain --no-daemon`
Expected: 全部 PASS，lint 无新增 error

- [ ] **Step 4: 提交**

```bash
git add kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/PlayerTouchController.kt
git commit -m "feat: 播放页接入触控（遥控器路径不变）"
```

---

### Task 5: Palm 实机验收

**Files:**
- 产出：`docs/PLAYER_TOUCH_VERIFICATION.md`

**Interfaces:**
- Consumes: Task 1–4 的产物
- Produces: `docs/PLAYER_TOUCH_VERIFICATION.md`

- [ ] **Step 1: 出开发包并装到 Palm**

```powershell
cd kotlin-tv
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon
& 'D:\Programs\android-sdk\platform-tools\adb.exe' -s 192.168.1.30 install -r app\build\outputs\apk\debug\app-debug.apk
```

装完回读版本，确认装的是新包：
```powershell
& 'D:\Programs\android-sdk\platform-tools\adb.exe' -s 192.168.1.30 shell "dumpsys package com.hongguotv.nativeapp | Select-String versionName"
```

- [ ] **Step 2: 起一集播放**

```powershell
$adb='D:\Programs\android-sdk\platform-tools\adb.exe'
& $adb -s 192.168.1.30 shell "uiautomator dump /sdcard/u.xml"
& $adb -s 192.168.1.30 shell "cat /sdcard/u.xml"
```
按 dump 出来的剧卡位置，用 `input tap` 点进去，再 `input keyevent 23` 起播。

- [ ] **Step 3: 验单击（切 HUD）**

```powershell
& $adb -s 192.168.1.30 shell "input tap 640 360"
Start-Sleep -Milliseconds 700
& $adb -s 192.168.1.30 shell "uiautomator dump /sdcard/u1.xml"
& $adb -s 192.168.1.30 shell "cat /sdcard/u1.xml" | Select-String "暂停|播放"
```
Expected: 能读到 HUD 上的「暂停 / 播放」文字（HUD 出现）。
再点一次，同样命令，Expected: HUD 消失（读不到那行字）。

- [ ] **Step 4: 验双击（播放/暂停）**

先确认在播：
```powershell
& $adb -s 192.168.1.30 shell "dumpsys media_session | Select-String 'state='"
```
Expected: `state=PLAYING(3)`

再双击（两次 tap 之间必须 < 300ms）：
```powershell
& $adb -s 192.168.1.30 shell "input tap 640 360; input tap 640 360"
Start-Sleep -Seconds 2
& $adb -s 192.168.1.30 shell "dumpsys media_session | Select-String 'state='"
```
Expected: `state=PAUSED(2)`
⚠ `input tap; input tap` 是两次独立命令，间隔可能超过 300ms。若判不出双击，
把 `DOUBLE_TAP_MS` 临时放到 500ms 复测，测完改回 300ms。

- [ ] **Step 5: 验横滑（拖进度）**

```powershell
& $adb -s 192.168.1.30 shell "dumpsys media_session | Select-String 'state=PlaybackState'"
& $adb -s 192.168.1.30 shell "input swipe 400 360 900 360 400"
Start-Sleep -Seconds 2
& $adb -s 192.168.1.30 shell "dumpsys media_session | Select-String 'state=PlaybackState'"
```
Expected: 位置从 ~0 跳到目标附近（+500dp ≈ +250 秒）。
拖动进行中还要能看到预览层：`input swipe` 期间另开一条命令抓 dump。

- [ ] **Step 6: 验纵滑（音量）**

```powershell
& $adb -s 192.168.1.30 shell "dumpsys audio | Select-String -Pattern 'STREAM_MUSIC' -Context 0,3"
& $adb -s 192.168.1.30 shell "input swipe 640 500 640 200 400"
Start-Sleep -Milliseconds 500
& $adb -s 192.168.1.30 shell "dumpsys audio | Select-String -Pattern 'STREAM_MUSIC' -Context 0,3"
```
Expected: STREAM_MUSIC 的音量变大。

- [ ] **Step 7: 验遥控器没退化**

```powershell
& $adb -s 192.168.1.30 shell "input keyevent 23"   # 确认 = 暂停/播放
& $adb -s 192.168.1.30 shell "input keyevent 22"   # 右 = 快进
& $adb -s 192.168.1.30 shell "input keyevent 20"   # 下 = 选集
& $adb -s 192.168.1.30 shell "input keyevent 4"    # 返回
```
Expected: 暂停/播放能切、快进位置会跳、下键打开选集、返回收起面板。
⚠ 播放中按 `23` 就是暂停 —— 这是遥控器的既有语义，别在验收时把它当故障。

- [ ] **Step 8: 写实测记录**

```markdown
# 播放页触控 实测记录

日期：2026-11-04
设备：Palm PVG100（192.168.1.30，LineageOS 24 / Android 17，density 360）
包：debug（开发包）

| 手势 | 判据 | 结果 |
|---|---|---|
| 单击 | HUD 出现/消失（uiautomator dump 读「暂停/播放」） | |
| 双击 | media_session state=PLAYING ↔ PAUSED | |
| 横滑 | media_session 位置跳到目标附近 | |
| 纵滑 | dumpsys audio 的 STREAM_MUSIC 音量变化 | |
| 遥控器 | 确认/左右/下/返回 全部照旧 | |

## 参数实测
- 横滑灵敏度：`SECONDS_PER_DP` = 0.5 秒/dp（待定稿）
- 单击延迟：`DOUBLE_TAP_MS` = 300ms（待定稿）
```

- [ ] **Step 9: 提交**

```bash
git add docs/PLAYER_TOUCH_VERIFICATION.md
git commit -m "docs: 播放页触控实机验收记录（Palm）"
```

---

### Task 6: 出正式版 0.9.82

**Files:**
- Modify: `kotlin-tv/CHANGELOG.md`、`kotlin-tv/README.md`（各加一段）

**Interfaces:**
- Consumes: Task 1–5 的产物
- Produces: 正式签名 APK

- [ ] **Step 1: 确认版本号没被占**

```powershell
gh release list --repo miaoxiaohu/hongguoTV
```
Expected: 没有比 9082 新的 `kotlin-v` 版本（9082 要新于所有已发布的）。

- [ ] **Step 2: 撤干净调试代码**

```bash
git diff --stat HEAD~6..HEAD
```
逐条确认没有残留的读数条、埋点、上报。正式包只留功能。

- [ ] **Step 3: 出包**

```powershell
Remove-Item -Recurse -Force kotlin-tv\app\build -ErrorAction SilentlyContinue
$env:HONGGUOTV_VERSION_CODE='9082'
$env:HONGGUOTV_VERSION_NAME='0.9.82'
.\scripts\build-kotlin.sh
```
Expected: `out/hongguotv-kotlin-0.9.82-android8.apk`

- [ ] **Step 4: 回读 APK 里的版本号**

```powershell
$aapt=Get-ChildItem "$env:ANDROID_HOME\build-tools" -Recurse -Filter aapt2.exe | Select-Object -First 1
& $aapt.FullName dump badging out\hongguotv-kotlin-0.9.82-android8.apk | Select-String "versionCode|versionName"
```
Expected: `versionCode='9082'`、`versionName='0.9.82'` —— 文档写的和 CI 算的必须一致。

- [ ] **Step 5: 写文档**

`kotlin-tv/CHANGELOG.md` 与 `kotlin-tv/README.md` 各加一节「0.9.82 新增」，
写清四类手势，并注明遥控器行为不变。

- [ ] **Step 6: 提交**

```bash
git add kotlin-tv/CHANGELOG.md kotlin-tv/README.md
git commit -m "docs: 0.9.82 播放页触控"
```

- [ ] **Step 7: 推 tag 触发 CI**

```bash
git tag kotlin-v0.9.82
git push origin main --tags
```
