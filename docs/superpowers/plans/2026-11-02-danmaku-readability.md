# 弹幕可读性与排布 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把弹幕从「8 秒一刀切 + 全堆在顶部 + 默认设置下必然叠字」改成「固定像素速度（长句自动多给时间）+ 尾随入场的轨道分配（不叠字、铺满可用轨道）+ 密度与字号联动」，并补上描边与超长弹幕的可读性小项。

**Architecture:** 算式全部下沉到 `kotlin-tv/core`（不依赖 Android，可单测）：新增 `DanmakuTiming`（速度/时长/尾巴清场算式），`DanmakuTimeline` 改成「每条弹幕有自己的时长 + 尾随入场 + 轨道轮转 + 可变寿命下的存活判定」。app 层 `DanmakuOverlay` 只做三件事：量文字宽度、截超长文本、把宽度喂给 core。

**Tech Stack:** Kotlin 2.1 / Android View 自绘（`Canvas` + `Paint`）/ Gradle 8（`:core` 是 JVM 模块，`:app` 是 Android 模块）/ JUnit4。

## Global Constraints

- 规格文档：`docs/superpowers/specs/2026-11-02-danmaku-readability-design.md`（已提交，commit `a03db72`）。实现与它不一致时以它为准，不一致处先改文档再改代码。
- **判据用硬指标，不看图**（AGENTS.md）。视觉结论必须来自单测断言或逐帧量像素。
- **core 里不许出现 Android API**（`Paint`/`Canvas`/`Context`）—— core 是 JVM 模块，量宽度的活交给 app。
- **正式交付包不许带任何诊断/探针代码**（AGENTS.md）。临时探针只能活在临时工作区，撤干净后重新出包。
- 覆盖安装要求**同签名 + versionCode 严格大于**已装版本。实验版 `-dbg` 用 versionCode **9080**，正式版用 **9081**（versionName `0.9.80`）—— 正式版必须越过实验版。
- 出包/装包后回读版本：`adb -s 192.168.1.30:5555 shell dumpsys package com.hongguotv.nativeapp | Select-String versionName`。
- 测完**必须退出 App**：`adb -s 192.168.1.30:5555 shell am force-stop com.hongguotv.nativeapp`。
- adb 用 `D:\Programs\android-sdk\platform-tools\adb.exe`（37.0.1）；`C:\Users\server\.local\bin\adb.exe` 是另一份，别混用（会互杀 adb server）。
- 本机录屏量像素的工具链已确认可用：Python 3.14 + PIL 11.3 + numpy 2.4、ffmpeg（在 PATH）。
- 现有测试不许删：`DanmakuTest` / `DanmakuDensityTest` / `DanmakuAnchorTest` / `DanmakuStyleTest` 必须全绿（需要改断言的地方在任务里写明了为什么）。
- **「量不到宽度」时轨道释放退回整段独占**（`clear = life`）—— 2026-11-02 用户拍板：
  按 8% 估出来的清场时刻会低估真实的尾巴离场时刻，宁可这条兼容路径上少飘几条，也不许同轨叠字。
  app 层总会量宽度，所以产品的路径走的是「已量宽度」那条，尾随入场的完整收益不受影响。
- 注释用中文，写清楚「为什么」，不写「是什么」—— 这是本仓库的既有风格。

---

### Task 1: 固定像素速度的算式（core/DanmakuTiming.kt）

**Files:**
- Create: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DanmakuTiming.kt`
- Create: `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuTimingTest.kt`

**Interfaces:**
- Consumes: 无（这是最底层的一块）。
- Produces:
  - `object DanmakuTiming`
  - `const val DanmakuTiming.BASE_SECONDS: Float` = `7.5f`
  - `const val DanmakuTiming.DEFAULT_WIDTH_RATIO: Float` = `0.08f`
  - `fun DanmakuTiming.lifetimeMs(textWidth: Float, bandWidth: Int): Long` —— 固定像素速度下这条弹幕飞完全程要多久（毫秒）
  - `fun DanmakuTiming.tailClearMs(textWidth: Float, bandWidth: Int): Long` —— 入场后多久，这条弹幕的**尾巴**离开屏幕右缘（毫秒）

- [ ] **Step 1: 写失败的测试**

创建 `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuTimingTest.kt`：

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定像素速度。
 *
 * 今天是「所有弹幕一律 8 秒」：20 个字的长句和 4 个字的短句飞得一样快，长句根本读不完。
 * 改成固定像素速度后速度对所有弹幕一致，字越多飘的时间自然越长。
 */
class DanmakuTimingTest {
    private val band=1280

    /** 字越多，飘得越久。 */
    @Test fun aWiderCommentTakesLongerToCross() {
        val short=DanmakuTiming.lifetimeMs(100f,band)
        val long=DanmakuTiming.lifetimeMs(520f,band)
        assertTrue("宽弹幕必须飘得更久",long>short)
    }

    /**
     * 时长与宽度是线性关系：除掉「屏宽」那一项之后必须相等（同一个速度 v）。
     */
    @Test fun theTimeFollowsTheWidthLinearly() {
        val a=DanmakuTiming.lifetimeMs(100f,band)/(1f+100f/band)
        val b=DanmakuTiming.lifetimeMs(520f,band)/(1f+520f/band)
        assertEquals(a,b,1f)
    }

    /**
     * 一屏宽的字要飘 15 秒（基准 7.5 秒 + 文字本身占的那一屏）。
     *
     * 别写成 7.5 秒 —— 「一屏宽」是文字的宽度，飞行距离是「屏宽 + 文字宽」= 两屏。
     */
    @Test fun aScreenWideCommentTakesTwiceTheBaseTime() {
        assertEquals(15_000L,DanmakuTiming.lifetimeMs(band.toFloat(),band))
    }

    /**
     * 尾巴清场：入场后多久，这条弹幕的尾巴离开屏幕右缘。
     *
     * 算式是「基准时间 × 宽/屏宽」：104px 宽的弹幕 586 毫秒就清场，
     * 520px 宽的要 3047 毫秒 —— 长句在自己那条轨道上占得久。
     */
    @Test fun theTailClearsInProportionToTheWidth() {
        assertEquals(586L,DanmakuTiming.tailClearMs(100f,band))
        assertEquals(3_047L,DanmakuTiming.tailClearMs(520f,band))
    }

    /** 尾巴清场一定早于整条走完 —— 否则这条轨道就白占了。 */
    @Test fun theTailAlwaysClearsBeforeTheCommentIsGone() {
        for(w in listOf(50f,100f,300f,520f,900f)) {
            assertTrue("宽 $w 的弹幕",DanmakuTiming.tailClearMs(w,band)<DanmakuTiming.lifetimeMs(w,band))
        }
    }

    /**
     * 量不到宽度时按「普通弹幕约占屏宽 8%」估。
     *
     * core 里没有 `Paint`，量不出宽度 —— 探针和现有单测就走这条退路，
     * 不能让它们的行为跟真机差太远。
     */
    @Test fun anUnknownWidthIsEstimatedAsAnOrdinaryComment() {
        val estimated=DanmakuTiming.DEFAULT_WIDTH_RATIO*band
        assertEquals(DanmakuTiming.lifetimeMs(estimated,band),DanmakuTiming.lifetimeMs(0f,band))
        assertEquals(DanmakuTiming.tailClearMs(estimated,band),DanmakuTiming.tailClearMs(0f,band))
    }

    /** 屏宽还没定下来时（0）只能退回基准值，不能除零。 */
    @Test fun withoutAScreenWidthItFallsBackToTheBaseTime() {
        assertEquals(7_500L,DanmakuTiming.lifetimeMs(100f,0))
        assertEquals(600L,DanmakuTiming.tailClearMs(100f,0))
    }
}
```

- [ ] **Step 2: 跑测试，确认它失败**

```
cd kotlin-tv
.\gradlew.bat :core:test --tests "com.hongguotv.core.DanmakuTimingTest" --console=plain --no-daemon
```

Expected: 编译失败，`Unresolved reference: DanmakuTiming`。

- [ ] **Step 3: 写实现**

创建 `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DanmakuTiming.kt`：

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import kotlin.math.roundToLong

/**
 * 弹幕在横向上的速度与时长。
 *
 * 之前所有弹幕一律飞 8 秒（`DanmakuOverlay.LIFETIME_MS`）：飞行距离是「屏宽 + 文字宽」，
 * 于是 20 个字的长句和 4 个字的短句**视觉速度一样快** —— 长句根本读不完，
 * 这是用户说的「看不清 / 读不完」的第一来源。
 *
 * 现在改成**固定像素速度**：速度对所有弹幕一致（按屏宽定，约 7.5 秒过一屏），
 * 字越多飘的时间自然越长。20 个字能比 4 个字多拿两秒半。
 *
 * 尾随入场（`tailClearMs`）是同一套算式的副产品：弹幕的尾巴一离开屏幕右缘，
 * 它那条轨道就能接下一条 —— 水平方向错开，不会叠字。
 *
 * 放在 core 里、且不依赖 Android，是为了能测：速度模型这种「读起来对、屏幕上却不对」
 * 的差别，只有把算式拆出来测才抓得住。
 */
object DanmakuTiming {
    /** 一屏宽的字飘过去要多久（秒）。 */
    const val BASE_SECONDS = 7.5f

    /**
     * 量不到宽度时按这个比例估：一条普通弹幕大约占屏宽的 8%。
     *
     * core 里没有 `Paint`，量不出宽度 —— 探针（`Probe.kt`）和现有单测就走这条退路。
     */
    const val DEFAULT_WIDTH_RATIO = 0.08f

    /**
     * 固定像素速度下，一条弹幕飞完全程要多久（毫秒）。
     *
     * @param textWidth 这条文字有多宽（像素）。
     * @param bandWidth 飞行区间的宽度（整屏）。
     */
    fun lifetimeMs(textWidth: Float, bandWidth: Int): Long {
        if (bandWidth <= 0) return (BASE_SECONDS * 1000f).toLong()
        return (BASE_SECONDS * 1000f * (1f + widthRatio(textWidth, bandWidth))).roundToLong()
    }

    /**
     * 尾巴清场：入场后多久，这条弹幕的尾巴离开屏幕右缘（毫秒）。
     *
     * 推导：x(e) = 屏宽 − (e/全程) × (屏宽 + 文字宽)，尾巴越过右缘 ⟺ x + 文字宽 ≤ 屏宽
     * ⟺ e ≥ 全程 × 文字宽 / (屏宽 + 文字宽)；代入 `lifetimeMs` 后化简成
     * 「基准时间 × 文字宽 / 屏宽」。
     *
     * 104px 的普通弹幕 586 毫秒就清场，520px 的长句要 3047 毫秒 ——
     * 长句在自己那条轨道上占得久，这是对的：它也宽，撞上后来的弹幕会更难看。
     */
    fun tailClearMs(textWidth: Float, bandWidth: Int): Long {
        if (bandWidth <= 0) return (BASE_SECONDS * 1000f * DEFAULT_WIDTH_RATIO).roundToLong()
        return (BASE_SECONDS * 1000f * widthRatio(textWidth, bandWidth)).roundToLong()
    }

    /** 文字占屏宽的比例。量不到宽度就按普通弹幕估。 */
    private fun widthRatio(textWidth: Float, bandWidth: Int): Float =
        if (textWidth <= 0f) DEFAULT_WIDTH_RATIO else (textWidth / bandWidth).coerceIn(0f, 1f)
}
```

- [ ] **Step 4: 跑测试，确认它全过**

```
cd kotlin-tv
.\gradlew.bat :core:test --tests "com.hongguotv.core.DanmakuTimingTest" --console=plain --no-daemon
```

Expected: 7 个测试全过。

- [ ] **Step 5: 提交**

```
git add kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DanmakuTiming.kt kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuTimingTest.kt
git commit -m "feat: 弹幕固定像素速度的算式，长句自动多飘两秒"
```

---

### Task 2: DanmakuTimeline 改造（每条自己的时长 + 尾随入场 + 轨道轮转）

**Files:**
- Modify: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/Danmaku.kt`（`DanmakuTimeline` 整个类）
- Create: `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuTrackTest.kt`
- Modify: `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuTest.kt`（改一条断言，见 Step 4）

**Interfaces:**
- Consumes: Task 1 的 `DanmakuTiming.lifetimeMs(textWidth, bandWidth)`、`DanmakuTiming.tailClearMs(textWidth, bandWidth)`、`DanmakuTiming.DEFAULT_WIDTH_RATIO`。
- Produces:
  - `DanmakuTimeline(items, trackCount, lifetimeMs, maxOnScreen, bandWidth, textWidthOf)` —— 后两个参数**新增且可选**
  - `DanmakuTimeline.Placed(danmaku, track, lifetimeMs: Long)` —— 多了一个 `lifetimeMs`
  - `DanmakuTimeline.Visible(danmaku, track, elapsedMs, index, lifetimeMs: Long)` —— 多了一个 `lifetimeMs`
  - `DanmakuTimeline.visibleAt(positionMs, speed)` 返回值里的 `Visible.lifetimeMs` 供 app 层算位置

- [ ] **Step 1: 写失败的测试**

创建 `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuTrackTest.kt`：

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨道怎么排。
 *
 * 三件事：
 * 1. 尾随入场 —— 上一条的**尾巴**离开右缘，这条轨道就能接下一条（水平错开，不叠字）。
 * 2. 轨道起点轮转 —— 不再永远从第 0 条轨道开始找，否则弹幕全堆在最上面两行。
 * 3. 每条弹幕有自己的时长 —— 字越宽飘得越久，轨道被占得也越久。
 */
class DanmakuTrackTest {
    private val band=1280
    private val short=100f          // 普通弹幕的宽度
    private val long=520f           // 长句的宽度
    private fun at(ms: Long,text: String="x")=Danmaku(text,ms,0)

    private fun timeline(
        items: List<Danmaku>,
        trackCount: Int = 7,
        maxOnScreen: Int = DanmakuDensity.MEDIUM.maxOnScreen,
        width: (Danmaku) -> Float = { short }
    )=DanmakuTimeline(items,trackCount,lifetimeMs=8_000L,maxOnScreen=maxOnScreen,
        bandWidth=band,textWidthOf=width)

    /**
     * 最要紧的一条：同一条轨道上，后一条不能跟前一条撞上。
     *
     * 撞上的判据是「后一条入场时，前一条的尾巴已经离开右缘」。
     */
    @Test fun twoDanmakuOnOneTrackNeverTouch() {
        val items=(0 until 20).map { at(it*300L,"第 $it 条") }
        val tl=timeline(items)
        val byTrack=tl.placed.groupBy { it.track }
        for((track,rows) in byTrack) {
            val ordered=rows.sortedBy { it.danmaku.enterAtMs }
            for(i in 1 until ordered.size) {
                val prev=ordered[i-1]
                val next=ordered[i]
                val prevClear=prev.danmaku.enterAtMs+DanmakuTiming.tailClearMs(short,band)
                assertTrue("轨道 $track 上第 $i 条撞上了前一条：${prev.danmaku.text} / ${next.danmaku.text}",
                    next.danmaku.enterAtMs>=prevClear)
            }
        }
    }

    /** 轨道起点轮转：弹幕不能全落在第 0 条轨道上。 */
    @Test fun theDanmakuSpreadOverTheTracks() {
        val items=(0 until 7).map { at(it*100L,"第 $it 条") }
        val tl=timeline(items)
        assertEquals("七条轨道都要用上",7,tl.placed.map { it.track }.toSet().size)
    }

    /** 字越宽，飘得越久。 */
    @Test fun aWiderCommentLivesLonger() {
        val tl=timeline(listOf(at(0,"短"),at(20_000,"长")),width={ if (it.text=="长") long else short })
        val shortOne=tl.placed.first { it.danmaku.text=="短" }
        val longOne=tl.placed.first { it.danmaku.text=="长" }
        assertTrue(longOne.lifetimeMs>shortOne.lifetimeMs)
        assertEquals(DanmakuTiming.lifetimeMs(short,band),shortOne.lifetimeMs)
        assertEquals(DanmakuTiming.lifetimeMs(long,band),longOne.lifetimeMs)
    }

    /**
     * 尾随入场之后，同一条轨道上可以同时有两条弹幕 —— 但它们是水平错开的。
     *
     * 这是尾随入场的意义：轨道不再是「一条弹幕飞完才空」，密度档位才真的有用
     * （以前轨道数 7 把同屏条数卡在 7，密度「多」形同虚设）。
     */
    @Test fun aTrackCanHostTwoDanmakuAtOnce() {
        val items=listOf(at(0,"第一条"),at(200,"第二条"))
        val tl=timeline(items,trackCount=1)
        assertEquals(2,tl.placed.size)
        assertEquals("两条得在同一条轨道上",1,tl.placed.map { it.track }.toSet().size)
        // 第二条晚一点入场：它进轨道时第一条的尾巴刚好离开右缘。
        assertEquals(586L,tl.placed[1].danmaku.enterAtMs)
    }

    /** 同屏条数守得住上限：这就是「挡屏幕的是条数」的兜底。 */
    @Test fun theScreenNeverHoldsMoreThanTheLimit() {
        val items=(0 until 40).map { at(0L,"挤进来的 $it") }
        val tl=timeline(items,trackCount=7,maxOnScreen=12)
        for(position in 0L..20_000L step 250L) {
            val visible=tl.visibleAt(position)
            assertTrue("位置 $position 上有 ${visible.size} 条，超了上限",visible.size<=12)
        }
    }

    /**
     * 量不到宽度时退回「整段独占」：轨道要等上一条整条飞完才放下一条。
     *
     * 这条兼容路径只有探针（`Probe.kt`）和不传宽度的既有单测在走，app 层总会量宽度。
     * 宁可这条路径上少飘几条，也不能让它同轨叠字 —— 按 8% 估出来的清场时刻会低估
     * 真实的尾巴离场时刻，轨道提前释放就会追上还在屏上的长句。
     */
    @Test fun withoutWidthsTheTrackIsHeldForTheWholeLife() {
        val items=listOf(at(0,"第一条"),at(200,"第二条"))
        val tl=DanmakuTimeline(items,trackCount=1,lifetimeMs=8_000L,maxOnScreen=12)
        assertEquals("等不到空位就丢（要等八秒，晚了两秒上限）",1,tl.placed.size)
    }

    /** 延迟飘进来的弹幕是从右边飞进来的，不是一露面就摆在画面中间。 */
    @Test fun aDelayedOneStillEntersFromTheRightEdge() {
        val items=(0 until 40).map { at(0L,"挤进来的 $it") }
        val tl=timeline(items,trackCount=7,maxOnScreen=12)
        val delayed=tl.placed.first { it.danmaku.enterAtMs>0L }
        assertTrue("被延后的要从右边飞进来",tl.visibleAt(delayed.danmaku.enterAtMs)
            .first { it.danmaku==delayed.danmaku }.elapsedMs==0L)
    }
}
```

- [ ] **Step 2: 跑测试，确认它失败**

```
cd kotlin-tv
.\gradlew.bat :core:test --tests "com.hongguotv.core.DanmakuTrackTest" --console=plain --no-daemon
```

Expected: 编译失败（`DanmakuTimeline` 没有 `bandWidth` / `textWidthOf` 参数，`Placed` 没有 `lifetimeMs`）。

- [ ] **Step 3: 改 DanmakuTimeline**

改 `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/Danmaku.kt` 里的 `DanmakuTimeline` 整个类，替换成下面这版（`Danmaku` / `DanmakuWindow` / `parseDanmakuWindow` / `DanmakuAnchors` 都不动）：

```kotlin
class DanmakuTimeline(
    items: List<Danmaku>,
    val trackCount: Int,
    /**
     * 所有弹幕共用一个时长。
     *
     * 这是老接口：传了 [bandWidth] 与 [textWidthOf] 就按每条自己的算，没传就用这个。
     * 探针（`Probe.kt`）和现有单测走的是老接口。
     */
    val lifetimeMs: Long,
    /**
     * 同屏最多几条。
     *
     * 挡屏幕的不是「叠字」，是**条数**（实测一集同屏最高 36 条）。这个上限是用户选的固定档位，
     * 与轨道数是两个独立的旋钮。
     */
    val maxOnScreen: Int = DanmakuDensity.MEDIUM.maxOnScreen,
    /** 飞行区间的宽度（整屏）。0 = 退回统一时长。 */
    private val bandWidth: Int = 0,
    /** 每条弹幕有多宽（像素）。null = 退回统一时长。 */
    private val textWidthOf: ((Danmaku) -> Float)? = null
) {
    /** 一条弹幕占的位置。 */
    data class Placed(val danmaku: Danmaku, val track: Int, val lifetimeMs: Long)

    /** 某一刻还看得见的弹幕，以及它已经走了多久。 */
    data class Visible(val danmaku: Danmaku, val track: Int, val elapsedMs: Long, val index: Int, val lifetimeMs: Long)

    /** 这条弹幕飞完全程要多久。 */
    private fun lifeOf(item: Danmaku): Long {
        if (bandWidth <= 0 || textWidthOf == null) return lifetimeMs
        return DanmakuTiming.lifetimeMs(textWidthOf.invoke(item), bandWidth)
    }

    /**
     * 这条弹幕的尾巴离开屏幕右缘要多久（从入场算起）。
     *
     * 量不到宽度时退回「**整段独占**」（clear = life）：按 8% 估出来的清场时刻会**低估**真实的
     * 尾巴离场时刻（尾巴真正离开右缘是 `life×w/(band+w)`），轨道提前释放，长句的尾巴还在屏上
     * 就会被后一条追上 —— 同轨叠字，那正是这次要治的毛病。
     *
     * 代价是这条兼容路径上的同屏条数由轨道数决定（app 层总会量宽度，所以产品的路径不受影响）。
     * 2026-11-02 用户拍板：宁可少飘几条，也不能叠字。
     */
    private fun clearOf(item: Danmaku, life: Long): Long {
        if (bandWidth <= 0 || textWidthOf == null) return life
        return DanmakuTiming.tailClearMs(textWidthOf.invoke(item), bandWidth)
    }

    /**
     * 每条弹幕走哪条轨道。
     *
     * 两条规矩：
     *
     * 1. **尾随入场**：一条轨道等上一条的**尾巴**离开右缘就能接下一条（水平方向错开，
     *    不叠字）。所有弹幕速度相同，尾巴清场之后两条就再也碰不上了。
     *    以前是「整条飞完才放下一条」，轨道数（7）直接成了同屏条数的硬顶，
     *    密度「多」（24）在默认行数下形同虚设。
     * 2. **轨道起点轮转**：从上次用的轨道往后找空的。不轮转的话弹幕全堆在最上面两行，
     *    下面 58% 的屏幕永远空着 —— 用户说的「摆得乱」。
     *
     * 排布还管**同屏最多几条**（[maxOnScreen]）：满员的时候后来的弹幕不是硬塞进来，
     * 而是**晚点飘进来** —— 等一条旧的走完、位置空出来；等不到（晚太多）就丢掉。
     */
    val placed: List<Placed> = buildList {
        val tracks = maxOf(1, trackCount)
        // 每条轨道下一次能接人的时刻：上一条的尾巴离开右缘的时刻。
        val freeAt = LongArray(tracks)
        // 已放行弹幕的入场时间（升序）与离场时间（= 入场 + 自己的时长）。
        val entered = ArrayList<Long>()
        val leaving = ArrayList<Long>()
        // 已放行弹幕里最长的那个时长：用来框定「可能还活着」的窗口。
        var longest = 0L
        // 轨道起点轮转用的游标。
        var nextTrack = 0
        for (item in items.sortedBy { it.enterAtMs }) {
            val want = item.enterAtMs
            val life = lifeOf(item)
            val clear = clearOf(item, life)
            var at = want
            var admitted = false
            var chosen = -1
            while (at - want <= MAX_DELAY_MS) {
                // 一、有轨道能接吗？从上次那条往后轮转着找。
                chosen = -1
                for (k in 0 until tracks) {
                    val t = (nextTrack + k) % tracks
                    if (freeAt[t] <= at) { chosen = t; break }
                }
                if (chosen < 0) {
                    // 全都占着：等最早有轨道清场。
                    at = freeAt.min()
                    continue
                }
                // 二、同屏塞得下吗？
                if (aliveCount(entered, leaving, at, longest) < maxOnScreen) { admitted = true; break }
                // 满了：等最早离场的那条腾出位置。
                at = earliestLeaving(entered, leaving, at, longest)
            }
            // 等不到位置就丢。
            //
            // 晚太多还飘出来的弹幕，跟眼前画面对不上 —— 观众看到的是一句跟画面无关的话，
            // 比没有弹幕更糟（跟「错过太久的弹幕不往回捞」是同一个道理）。
            if (!admitted || chosen < 0) continue
            nextTrack = (chosen + 1) % tracks
            freeAt[chosen] = at + clear
            if (life > longest) longest = life
            insertEntered(entered, leaving, at, at + life)
            // 入场时间改成真正入场的时刻：晚飘的弹幕必须从右边飞进来。不改的话它一露面就摆在
            // 画面中间 —— 那正是「凭空冒出来」那个毛病。
            add(Placed(item.copy(enterAtMs = at), chosen, life))
        }
    }.sortedBy { it.danmaku.enterAtMs }

    /** 每条弹幕的起点，单独存一份是为了每帧能二分，而不是每帧扫全表。 */
    private val offsets = LongArray(placed.size) { placed[it].danmaku.enterAtMs }

    /** 已放行弹幕里最长的那个时长。 */
    private val longestMs: Long = placed.maxOfOrNull { it.lifetimeMs } ?: 0L

    /** 第一个起点大于 [value] 的下标（没有就是表长）。 */
    private fun firstAfter(value: Long): Int = lowerBound(offsets, value)

    /**
     * 某一刻看得见的弹幕。
     *
     * 位置用播放器的播放位置算，不用墙钟：暂停时弹幕跟着停，跳转后按新位置显示，倍速播放时
     * 弹幕也跟着快 —— 这几件事只要用墙钟就一定做不对。
     *
     * 看得见的弹幕在时间轴上是一段连续的区间（入场时间升序），所以这里二分出那段区间，
     * 再逐条按自己的时长判断是不是已经走完 —— 时长不再一样，不能只看区间。
     *
     * @param positionMs 播放位置（**视频时间**轴）。
     * @param speed 播放倍速。弹幕在画面上飘过去要多久，按的是**真实时间**：2 倍速时画面内容
     *  走得快，弹幕也跟着飞快的话，一句话还没看完就出屏了 —— 倍速下就白看了。
     *  出现的时间点仍按视频时间轴算，所以弹幕和画面的内容对得上。
     */
    fun visibleAt(positionMs: Long, speed: Float = 1f): List<Visible> {
        val factor = if (speed > 0.05f) speed else 1f
        val end = firstAfter(positionMs)                             // 第一个起点 > 位置
        // 存活窗口按倍速放大：8 秒真实时间，在 2 倍速下是 16 秒的**视频**时间。
        val begin = firstAfter(positionMs - (longestMs * factor).toLong())
        if (end <= begin) return emptyList()
        val out = ArrayList<Visible>(end - begin)
        for (i in begin until end) {
            val p = placed[i]
            val elapsed = ((positionMs - p.danmaku.enterAtMs) / factor).toLong()
            // 自己的时长走完了就是走了 —— 时长各不相同，不能只靠区间。
            if (elapsed >= p.lifetimeMs) continue
            out += Visible(p.danmaku, p.track, elapsed, i, p.lifetimeMs)
        }
        return out
    }

    /**
     * 已经放行的弹幕里，[at] 这一刻**还活着**的是几条。
     *
     * 活着的条件是「已经入场、但还没走完」：入场时间 <= at 且 入场时间 + 自己的时长 > at。
     * 时长各不相同，存活的那一批不再是一个连续区间，所以只能在这个小区间里逐条数
     * （区间长度受同屏上限约束，很小）。
     */
    private fun aliveCount(entered: List<Long>, leaving: List<Long>, at: Long, longest: Long): Int {
        var count = 0
        val begin = lowerBound(entered, at - longest)
        for (i in begin until entered.size) {
            if (entered[i] > at) break
            if (leaving[i] > at) count++
        }
        return count
    }

    /**
     * 最早离场的那条什么时候离场 —— 满员时等它腾出位置。
     *
     * @return 一个**大于** [at] 的时刻（活着的东西离场时刻必然在后面）。
     */
    private fun earliestLeaving(entered: List<Long>, leaving: List<Long>, at: Long, longest: Long): Long {
        var best = Long.MAX_VALUE
        val begin = lowerBound(entered, at - longest)
        for (i in begin until entered.size) {
            if (entered[i] > at) break
            if (leaving[i] > at && leaving[i] < best) best = leaving[i]
        }
        return best
    }

    /** 第一个大于 [value] 的下标。 */
    private fun lowerBound(values: List<Long>, value: Long): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) / 2
            if (values[mid] <= value) low = mid + 1 else high = mid
        }
        return low
    }

    /** 把入场时间插进已放行的那一串里，保持升序 —— 后面的二分靠的就是这个顺序。 */
    private fun insertEntered(entered: ArrayList<Long>, leaving: ArrayList<Long>, at: Long, end: Long) {
        val low = lowerBound(entered, at)
        entered.add(low, at)
        leaving.add(low, end)
    }

    companion object {
        /**
         * 同屏满了的时候，后来的弹幕最多能晚多少才飘进来。
         *
         * 两秒。晚一点是「网络慢了点」，观众还等得起；晚太多飘出来的弹幕跟眼前画面对不上，
         * 那就不如不飘。
         */
        const val MAX_DELAY_MS = 2_000L
    }
}
```

**注意**：老类里 `aliveRange` / `insertEntered`（旧签名）/ `firstAfter` 都要删掉（被新实现取代），
`DanmakuAnchors` 一个字都不动。

- [ ] **Step 4: 跑测试**

```
cd kotlin-tv
.\gradlew.bat :core:test --console=plain --no-daemon
```

Expected: `DanmakuTrackTest` 6 条全过；**旧的 `DanmakuTest.theVisibleRowsAreOnlyTheOnesInTheWindow` 会挂**（它断言「30 秒那一处看得见的最后一条正好是 offset 30000 的那条」—— 那是「挤轨道」时代的产物：老实现把挤不下的弹幕硬塞进轨道，所以一条不落。现在轨道排满了就丢（宁可丢不要叠字），最后一条的 offset 会小于 30000）。

改这一条断言，改成测「看得见的都落在窗口里」这个真正的性质：

```kotlin
    /** 弹幕很多的时候，可见的那一段不能把整集都扫一遍。 */
    @Test fun theVisibleRowsAreOnlyTheOnesInTheWindow() {
        val many=(0 until 500).map { at(it*100L) }
        // 密度上限放开：这条测的是「看得见的只是窗口内那些」，同屏几条是另一回事
        // （见 [DanmakuDensityTest]）。
        val timeline=DanmakuTimeline(many,trackCount=4,lifetimeMs=8_000L,maxOnScreen=Int.MAX_VALUE)
        val visible=timeline.visibleAt(30_000L)
        assertTrue("30 秒那一处最多八十来条",visible.size<=90)
        // 看得见的都得在窗口里：入场时间不超过播放位置，差值不超过全程。
        // 2026-11-02 起轨道排满了就丢（不再「挤轨道」），所以不能再断言「最后一条正好是
        // 30 秒那条」—— 那条可能已经被丢了。
        for(row in visible) {
            assertTrue(row.danmaku.offsetMs<=30_000L)
            assertTrue(row.danmaku.offsetMs>30_000L-8_000L)
        }
    }
```

改完再跑一次 `:core:test`，Expected: 全绿。

**另外还要改 `DanmakuDensityTest`**（不改会挂）：它的 `DanmakuTimeline(...)` 构造没传宽度，
走的是「量不到宽度」那条路径 —— 而那条路径现在退回**整段独占**（`clear = life`），
7 条轨道各自要占满 8 秒，于是「三十六条挤进来、上限十二」只能放下 **7** 条，
`theScreenNeverHoldsMoreThanTheLimit` 与 `theDefaultLimitIsTheMediumOne` 断言的 `12` 会失效。

改法：**给 `DanmakuDensityTest` 里每一处 `DanmakuTimeline(...)` 构造加上 `bandWidth = 1280` 与
`textWidthOf = { 100f }`**，让它走 app 层真正走的那条路径（已量宽度 → 尾随入场 → 密度档位守得住）。
**断言一个字都不用改** —— 改完它们仍然是 12。

为什么这么改：密度上限本来就是给用户选的旋钮，产品路径（app 层总会量宽度）下它必须守得住；
「量不到宽度」的那条兼容路径只有探针和旧单测在走，在那儿同屏条数由轨道数决定是已知取舍
（见规格「量不到宽度时」一节）。

- [ ] **Step 5: 提交**

```
git add kotlin-tv/core/src/main/kotlin/com/hongguotv/core/Danmaku.kt kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuTrackTest.kt kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuTest.kt
git commit -m "feat: 弹幕尾随入场 + 轨道轮转 + 每条自己的时长"
```

---

### Task 3: 密度与字号联动 + 「淡」档描边抬实

**Files:**
- Modify: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DanmakuStyle.kt`（`DanmakuLayouts.resolve` + `DanmakuAlpha`）
- Modify: `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuStyleTest.kt`

**Interfaces:**
- Consumes: 无（只依赖 core 里的 `DanmakuDensity`）。
- Produces:
  - `fun DanmakuLayouts.densityScale(density: DanmakuDensity): Float` —— `HIGH` → `0.85f`，其余 `1f`
  - `DanmakuLayouts.resolve(heightPx, size, requestedRows, density = DanmakuDensity.MEDIUM)` —— 新增**带默认值**的第四个参数，老调用点不用改
  - `DanmakuAlpha.LIGHT` 的描边 alpha：`160` → `180`

- [ ] **Step 1: 写失败的测试**

在 `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuStyleTest.kt` 末尾追加：

```kotlin
    /**
     * 密度调高时字号降一档。
     *
     * 字多了还按大字号飘，屏幕上就是一片白字 —— 用户说的「挡屏幕」。
     */
    @Test fun aDenserScreenUsesSmallerText() {
        val height=720
        val medium=DanmakuLayouts.resolve(height,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM)
        val high=DanmakuLayouts.resolve(height,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.HIGH)
        assertTrue("密度高时字号要小",high.textSize<medium.textSize)
        assertEquals(medium.textSize*0.85f,high.textSize,0.01f)
    }

    /** 密度低的时候不放大 —— 放大了可能溢出屏幕。 */
    @Test fun aQuieterScreenDoesNotEnlargeTheText() {
        val height=720
        val normal=DanmakuLayouts.resolve(height,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM)
        val low=DanmakuLayouts.resolve(height,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.LOW)
        assertEquals(normal.textSize,low.textSize,0.01f)
    }

    /** 不传密度就是「中」—— 老调用点（app 之外）行为不变。 */
    @Test fun withoutADensityTheTextIsTheMediumOne() {
        val height=720
        assertEquals(
            DanmakuLayouts.resolve(height,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM).textSize,
            DanmakuLayouts.resolve(height,DanmakuSize.NORMAL,DanmakuRows.AUTO).textSize,
            0.01f
        )
    }

    /** 「淡」那档的描边必须够实：白字全靠那道黑边才看得清。 */
    @Test fun theFaintestTierStillHasASolidEdge() {
        assertTrue(DanmakuAlpha.LIGHT.edge>=180)
    }
```

- [ ] **Step 2: 跑测试，确认它失败**

```
cd kotlin-tv
.\gradlew.bat :core:test --tests "com.hongguotv.core.DanmakuStyleTest" --console=plain --no-daemon
```

Expected: 编译失败（`resolve` 没有第四个参数）。

- [ ] **Step 3: 改实现**

改 `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DanmakuStyle.kt`：

1. `DanmakuAlpha` 的 `LIGHT(105,160,"淡")` 改成 `LIGHT(105,180,"淡")`，并在注释里补一句：
   「淡档的描边从 160 抬到 180：白字在亮画面上全靠这道黑边撑住，边淡了字就糊在画面里了。」

2. `DanmakuLayouts` 里加：

```kotlin
    /**
     * 密度对字号的影响。
     *
     * 密度调高时字号降一档：同屏的条数多了还按大字号飘，屏幕上就是一片白字。
     * 密度低的时候**不放大** —— 放大了可能溢出屏幕。
     */
    fun densityScale(density: DanmakuDensity): Float = when (density) {
        DanmakuDensity.LOW -> 1f
        DanmakuDensity.MEDIUM -> 1f
        DanmakuDensity.HIGH -> 0.85f
    }
```

3. `resolve` 的签名与第一行改成：

```kotlin
    fun resolve(
        heightPx: Int,
        size: DanmakuSize,
        requestedRows: Int,
        density: DanmakuDensity = DanmakuDensity.MEDIUM
    ): DanmakuLayout {
        val textSize = (baseTextSize(heightPx) * size.scale * densityScale(density)).coerceIn(14f, 88f)
```

（其余几行不动。）

- [ ] **Step 4: 跑测试，确认全绿**

```
cd kotlin-tv
.\gradlew.bat :core:test --console=plain --no-daemon
```

Expected: 全绿（`DanmakuStyleTest` 的老断言一条都不许动挂）。

- [ ] **Step 5: 提交**

```
git add kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DanmakuStyle.kt kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuStyleTest.kt
git commit -m "feat: 密度与字号联动，淡档描边抬实"
```

---

### Task 4: app 层接线（量宽度、逐条时长、超长截断、描边加粗）

**Files:**
- Modify: `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuOverlay.kt`

**Interfaces:**
- Consumes: Task 1 的 `DanmakuTiming`；Task 2 的 `DanmakuTimeline(items, trackCount, lifetimeMs, maxOnScreen, bandWidth, textWidthOf)`、`Visible.lifetimeMs`。
- Produces: `DanmakuOverlay` 对外接口**不变**（`setItems` / `setStyle` / `setPositionSource` / `setSpeedSource` / `start` / `stop`），`MainActivity` 不用改。

- [ ] **Step 1: 改 `setItems`（量宽度 + 截断 + 喂给 core）**

把 `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuOverlay.kt` 里的 `setItems` 换成：

```kotlin
    /**
     * 换一集的弹幕。
     *
     * 轨道数是按屏幕高度算的，所以宽度和高度定下来之前先记着，等尺寸到了再排。
     */
    fun setItems(items: List<Danmaku>) {
        source=items
        if(items.isEmpty()) { timeline=null; widths=FloatArray(0); pending=null; invalidate(); return }
        if(textSizePx<=0||width<=0) { pending=items; invalidate(); return }
        pending=null
        // 量宽度要用当前字号，所以画笔先设好，再去量、去截。
        fill.textSize=textSizePx
        edge.textSize=textSizePx
        // 描边粗一点：白字在电视上那种五花八门的画面里，全靠这道黑边才看得清。
        edge.strokeWidth=(textSizePx*.14f).coerceAtLeast(1f)
        // 超长的先截短：一条比屏还宽的弹幕要飘两屏宽才走完，看着就是一大坨。
        val fitted=items.map { it.copy(text=fit(it.text)) }
        // 每条有多宽，core 要靠它算飞行时长和尾巴清场的时间。量一次就够 —— 每帧 measureText
        // 在这台机器上是白白的开销。
        val measured=HashMap<String,Float>()
        fun widthOf(text:String):Float = measured.getOrPut(text) { fill.measureText(text) }
        val laid=DanmakuTimeline(fitted,trackCount,lifetimeMs(),density.maxOnScreen,
            bandWidth=width,textWidthOf={ widthOf(it.text) })
        timeline=laid
        widths=FloatArray(laid.placed.size) { widthOf(laid.placed[it].danmaku.text) }
        invalidate()
    }

    /**
     * 太宽的文字截短。
     *
     * 超过屏宽九成的文字要飘两屏宽才走完，挡着画面看不清 —— 截掉加省略号。
     */
    private fun fit(text:String):String {
        val limit=width*.9f
        if(limit<=0f) return text
        if(fill.measureText(text)<=limit) return text
        var low=0
        var high=text.length
        while(low<high) {
            val mid=(low+high+1)/2
            if(fill.measureText(text.substring(0,mid)+"…")<=limit) low=mid else high=mid-1
        }
        return text.substring(0,low)+"…"
    }
```

**注意**：`source` 存的是**没截断**的原始弹幕 —— 截断依赖屏宽，屏幕尺寸变了要重新截，所以原样留着。

- [ ] **Step 2: 改 `onDraw`（用每条自己的时长）**

把 `onDraw` 里的两行位置计算改成用 `v.lifetimeMs`：

```kotlin
        val bandWidth=width
        val baseline0=textSizePx
        for(v in visible) {
            val w=if(v.index<widths.size) widths[v.index] else 0f
            val x=DanmakuMotion.xAt(v.elapsedMs,v.lifetimeMs,bandWidth,w)
            // 整条都在屏外就不画：一个字都不露的时候画了也没用。
            if(DanmakuMotion.visibleTextRange(v.elapsedMs,v.lifetimeMs,bandWidth,w)==null) continue
            val baseline=baseline0+v.track*trackHeightPx
            canvas.drawText(v.danmaku.text,x,baseline,edge)
            canvas.drawText(v.danmaku.text,x,baseline,fill)
        }
```

（`DanmakuMotion` 的公式一个字都不用改 —— `x = 屏宽 − (已飞/全程) × (屏宽 + 文字宽)` 在固定速度下正好是匀速，
只是「全程」从全局 8 秒换成了每条自己的时长。）

- [ ] **Step 3: 改 `applyLayout`（把密度喂给排版算式）**

`applyLayout()` 里这一行：

```kotlin
        val laid=DanmakuLayouts.resolve(viewHeight,size,requestedRows)
```

改成：

```kotlin
        val laid=DanmakuLayouts.resolve(viewHeight,size,requestedRows,density)
```

- [ ] **Step 4: 编译 + 跑全部单测**

```
cd kotlin-tv
.\gradlew.bat :core:test :app:testDebugUnitTest --console=plain --no-daemon
```

Expected: 全绿，`:app:assembleDebug` 也能编过（顺手跑一下 `.\gradlew.bat :app:assembleDebug --console=plain --no-daemon`）。

- [ ] **Step 5: 提交**

```
git add kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuOverlay.kt
git commit -m "feat: 弹幕层按每条文字宽度算时长，超长截断，描边加粗"
```

---

### Task 5: 实机验收（像素判据）+ 正式包 0.9.80

**Files:**
- 临时：`kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuOverlay.kt`（探针，**不提交**）
- Create: `kotlin-tv/RELEASE_0.9.80.md`
- Modify: `kotlin-tv/README.md`（加 0.9.80 段落）、`kotlin-tv/CHANGELOG.md`（加一条）

**Interfaces:**
- Consumes: Task 1~4 的全部成果。
- Produces: 0.9.80 正式包（versionCode **9081**，versionName **0.9.80**，同发布签名）。

- [ ] **Step 1: 临时探针（只在工作区，绝不提交）**

在 `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuOverlay.kt` 里加一个临时的合成弹幕入口
（**这一段最后必须删掉**）：

```kotlin
    // ==== 临时探针：2026-11-02 实机量像素用，交付前必须删掉 ====
    // 合成弹幕的时钟用探针自己的 SystemClock.elapsedRealtime()，不跟播放进度走 ——
    // 播放器位置一动，弹幕位置就没法按像素量了。
    fun debugSynthetic(texts: List<String>) {
        val start=SystemClock.elapsedRealtime()
        setPositionSource { SystemClock.elapsedRealtime()-start }
        setItems(texts.map { Danmaku(it,0L,0) })
        start()
    }
```

（`DanmakuOverlay.kt` 顶部要补一行 `import android.os.SystemClock`。）再在
`kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt` 里加两处（**同样必须删掉**）：

1. 加一个字段（放在 `danmakuOverlay` 字段旁边）：

```kotlin
    /** ==== 临时探针：2026-11-02 实机量像素用，交付前必须删掉 ==== */
    private var debugDanmaku=false
```

2. 播放页里 `danmakuOverlay` 加进 `root` 之后（`MainActivity.kt` 约 1508 行
   `root.addView(danmakuOverlay,FrameLayout.LayoutParams(-1,-1))` 的下面）加：

```kotlin
            // ==== 临时探针：合成弹幕，八条（轨道七条 → 必有一条轨道上挤两条，用来验「不叠字」）====
            debugDanmaku=true
            danmakuSession.stop()
            danmakuOverlay?.debugSynthetic(listOf(
                "短","短一些","短短短短","短短短短短短短短",
                "短短短短短短短短短短短短","短短短短短短短短短短短短短短短短",
                "短短短短短短短短短短短短短短短短短短","短短短短短短短短短短短短短短短短短短短短"
            ))
```

3. 把播放页里 `danmakuSession.onItems={ items -> ... }` 改成探针期间不生效（否则真实弹幕会盖掉合成的）：

```kotlin
        danmakuSession.onItems={ items -> if(!debugDanmaku) { danmakuOverlay?.setItems(items); if(items.isNotEmpty()) danmakuOverlay?.start() } }
```

- [ ] **Step 2: 出 -dbg 包并装到 Palm**

```
cd kotlin-tv
$env:HONGGUOTV_VERSION_CODE="9080"
$env:HONGGUOTV_VERSION_NAME="0.9.80-dbg"
.\gradlew.bat :app:assembleRelease --console=plain --no-daemon
```

用 `D:\Programs\android-sdk\platform-tools\adb.exe` 安装到 `192.168.1.30:5555`，装前先回读已装版本
（versionCode 必须严格大于它，否则 `INSTALL_FAILED_VERSION_DOWNGRADE`），装完再回读一次确认。

- [ ] **Step 3: 录屏 + 量像素（判据在硬指标上，不在观感上）**

1. Palm 息屏时先 `input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard` 再操作。
2. 进播放页（合成弹幕已注入），用 `adb shell screenrecord --time-limit 20 /sdcard/danmu.mp4` 录 20 秒。
3. 拉回本机：`adb pull /sdcard/danmu.mp4 D:\tmp\danmu.mp4`。
4. 用 ffmpeg 抽帧：`ffmpeg -i D:\tmp\danmu.mp4 -vf fps=10 D:\tmp\danmu\%04d.png`。
5. 用 Python + numpy 逐帧量亮像素的 x 范围与 y 分布，判据：

   - **速度一致**：短弹幕与长弹幕在相邻两帧的 x 位移之比 ≈ 1（两者像素速度相同）。
   - **不叠字**：同一帧里，两条弹幕的亮像素 x 区间**不重叠**（尾随入场的效果）。
   - **铺开**：整段录屏里，弹幕出现的 y 位置覆盖到**所有可用轨道**，而不是只在最上面两行。

   脚本放在 `D:\tmp\measure_danmu.py`（临时目录，不进仓库）。量完把结果抄进发布说明。

- [ ] **Step 4: 撤掉探针，重新出正式包**

```
git checkout -- kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuOverlay.kt kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt
git status --short
```

Expected: `git status --short` 里**没有**任何 `M`（探针改动全撤干净；Task 4 已提交的内容不受影响）。

然后出正式包（versionCode 必须**越过**实验版的 9080）：

```
cd kotlin-tv
$env:HONGGUOTV_VERSION_CODE="9081"
$env:HONGGUOTV_VERSION_NAME="0.9.80"
.\gradlew.bat :app:assembleRelease --console=plain --no-daemon
```

- [ ] **Step 5: 装正式包并实机复核**

1. 装到 Palm，回读版本，确认是 `0.9.80` / `9081`。
2. 走设置页：首页按两下 `keyevent 19`（↑）到导航行，再按 5 下 `keyevent 22`（→）到「设置」，`keyevent 23` 确认。
3. 左列找到「弹幕设置」那一行，进去把「数量」切到「多」，确认摘要文案里的字号档位跟着变小（密度 HIGH → 字号 ×0.85）。
4. 回读落盘：
   ```
   adb -s 192.168.1.30:5555 shell "su -c 'cat /data/data/com.hongguotv.nativeapp/shared_prefs/native-library-v1.xml'" | Select-String danmaku
   ```
5. 播一集，肉眼确认：长句飘得比旧版久、弹幕铺得开、同轨不叠字。
6. **测完退出 App**：`adb -s 192.168.1.30:5555 shell am force-stop com.hongguotv.nativeapp`。
7. 删掉临时录屏/帧：`adb shell rm /sdcard/danmu.mp4`，删掉 `D:\tmp\danmu` 与 `D:\tmp\measure_danmu.py`。

- [ ] **Step 6: 写发布说明**

创建 `kotlin-tv/RELEASE_0.9.80.md`，照 `RELEASE_0.9.79.md` 的格式（标题 + `<!-- notes -->` 摘要块 + 逐条说明 +
「最低 Android 8.0 / API 26，versionCode 9081」），内容写清：

- 弹幕改成固定像素速度：长句自动多飘两秒半。
- 轨道改成尾随入场：不再叠字，同屏上限回到密度档位本身。
- 弹幕铺满可用轨道，不再全堆在最上面。
- 密度「多」时字号自动降一档。
- 描边加粗、「淡」档描边抬实、超长弹幕截断。
- 把 Step 3 量出来的像素数据写进去（实测数字，不是形容）。

并在 `kotlin-tv/README.md` 顶部段落加 0.9.80 一节、`kotlin-tv/CHANGELOG.md` 加一条。

- [ ] **Step 7: 提交并推送**

```
git add kotlin-tv/RELEASE_0.9.80.md kotlin-tv/README.md kotlin-tv/CHANGELOG.md
git commit -m "docs: 0.9.80 弹幕可读性与排布"
git push origin main
```

---

## 自查

- **规格覆盖**：规格里的每一条都有任务对应 —— 固定像素速度（Task 1+2）、铺满轨道（Task 2）、
  尾随入场（Task 2）、密度↔字号联动（Task 3）、描边加粗与「淡」档抬实（Task 3+4）、
  超长截断（Task 4）、逐条时长（Task 2）、删掉「挤轨道」（Task 2）、存活判定不再用统一二分的二值假设（Task 2）、
  实机像素判据与「正式包不带探针」（Task 5）。规格里明确「不做」的（人脸避让、彩色弹幕、
  顶部/底部弹幕、反向弹幕、字体选择）本计划一项都没碰。
- **占位符**：无 TBD/TODO；每个代码步骤都给了实际代码。
- **类型一致**：`DanmakuTiming.lifetimeMs` / `tailClearMs` 的签名在 Task 1 定义，Task 2、4 调用一致；
  `Placed.lifetimeMs` / `Visible.lifetimeMs` 在 Task 2 定义，Task 4 使用一致；
  `DanmakuLayouts.resolve` 的第四个参数在 Task 3 定义，Task 4 调用一致。
