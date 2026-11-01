# 弹幕设置面板 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把分散在设置页和播放 HUD 里的 5 项弹幕设置（开关/大小/行数/数量/透明度）合并成一个「弹幕设置」面板。

**Architecture:** 面板的**内容模型**下沉到 core（纯 Kotlin，可单测）：有哪些行、当前值是什么、候选有哪些、摘要怎么拼。
面板**本体**在 app 层，是个独立 `Dialog`，照 `EpisodePanel` 的路子走 —— 自己一套 `FocusRouter`（面板是另一个窗口，
坐标得在面板里算），确认键就地展开候选行，改完不关面板。

**Tech Stack:** Kotlin 2.x / Android SDK 36 / Media3 / JUnit4。core 是纯 JVM 模块（`mainClass=com.hongguotv.core.ProbeKt`），
app 是 Android 模块（有 JVM 单测源集 `app/src/test`）。

## Global Constraints

- 仓库根 `D:\document\project\hongguoTV`，App 工程在 `kotlin-tv/`
- **core 不得依赖 Android**（core 是纯 JVM 模块）
- `core` 单测全绿是硬门槛：`.\gradlew.bat :core:test --console=plain --no-daemon`
- app 编译门槛：`.\gradlew.bat :app:compileReleaseKotlin --console=plain --no-daemon`
- **不改**：`DanmakuSize` / `DanmakuRows` / `DanmakuDensity` / `DanmakuAlpha` 枚举本身、`LibraryBackup`、`LibraryIndex`、
  SharedPreferences 键名（`danmakuEnabled` / `danmakuSize` / `danmakuRows` / `danmakuDensity` / `danmakuAlpha`）
- **不改**弹幕渲染层（`DanmakuOverlay`）
- 现有生效函数照旧复用：`applyDanmakuSetting()`（开关）、`applyDanmakuStyle()`（4 个样式）
- Kotlin 换行续接：**运算符放行尾**；行首 `+` 会被当成一元正号 → `Unresolved reference 'unaryPlus'`
- Edit 工具参数名是 `new_string`（不是 `newString`）
- 交付包**不得带诊断/读数/埋点**（`TvProbe.kt` 等探针只活在 `-dbg` 包）
- 本计划**不单独出包**：本改动随 **0.9.76 正式包**一起交付（探针撤干净后重新出包、重新装）
- git：先只本地提交，不推 tag 不发 Release

---

### Task 1: core 弹幕设置模型

**Files:**
- Create: `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DanmakuSettings.kt`
- Test: `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuSettingsTest.kt`

**Interfaces:**
- Consumes: `DanmakuSize` / `DanmakuRows` / `DanmakuDensity` / `DanmakuAlpha`（`core/DanmakuStyle.kt`，已存在）
- Produces:
  - `data class DanmakuSettingRow(val title: String, val value: String, val options: List<String>, val selectedIndex: Int)`
    （`selectedIndex` 是「当前是第几个候选」——**不能靠 `value` 在 `options` 里找**：
    大小那行的 value 是「标准」，候选串却是「标准 · 100%」，比字符串对不上）
  - `object DanmakuSettings`：
    - `val toggleOptions: List<String>` = `["开启","关闭"]`
    - `val sizeOptions: List<String>`、`val rowOptions: List<String>`、`val densityOptions: List<String>`、`val alphaOptions: List<String>`
    - `fun summary(enabled: Boolean, size: DanmakuSize, rowCount: Int, density: DanmakuDensity, alpha: DanmakuAlpha): String`
    - `fun rows(enabled: Boolean, size: DanmakuSize, rowCount: Int, density: DanmakuDensity, alpha: DanmakuAlpha): List<DanmakuSettingRow>`

- [ ] **Step 1: 写失败的测试**

创建 `kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuSettingsTest.kt`：

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕设置面板上该有哪些行、每行现在是什么值、可以选什么。
 *
 * 面板是电视上唯一改弹幕的地方 —— 改错了用户在屏幕上只能看出「不对」，说不上哪里不对。
 * 所以这里把「面板上长什么样」钉死，不必把电视搬过来才能验。
 */
class DanmakuSettingsTest {
    @Test fun theSummaryReadsAsOneLine() {
        assertEquals("开 · 标准 · 自动 · 中 · 半透明",
            DanmakuSettings.summary(true,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM,DanmakuAlpha.HALF))
    }

    @Test fun aClosedDanmakuShowsUpInTheSummary() {
        assertEquals("关 · 标准 · 自动 · 中 · 半透明",
            DanmakuSettings.summary(false,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM,DanmakuAlpha.HALF))
    }

    @Test fun theSummaryFollowsEveryKnob() {
        assertEquals("开 · 特大 · 8 行 · 多 · 不透明",
            DanmakuSettings.summary(true,DanmakuSize.HUGE,8,DanmakuDensity.HIGH,DanmakuAlpha.SOLID))
    }

    @Test fun thePanelHasFiveRowsInOrder() {
        val rows=DanmakuSettings.rows(true,DanmakuSize.NORMAL,DanmakuRows.AUTO,DanmakuDensity.MEDIUM,DanmakuAlpha.HALF)
        assertEquals(listOf("弹幕开关","弹幕大小","弹幕行数","弹幕数量","弹幕透明度"),rows.map { it.title })
    }

    @Test fun everyRowShowsItsCurrentValue() {
        val rows=DanmakuSettings.rows(false,DanmakuSize.SMALL,5,DanmakuDensity.LOW,DanmakuAlpha.LIGHT)
        assertEquals(listOf("关闭","小","5 行","少","淡"),rows.map { it.value })
    }

    @Test fun theToggleOffersOnAndOff() {
        assertEquals(listOf("开启","关闭"),DanmakuSettings.toggleOptions)
    }

    @Test fun theSizeOptionShowsTheScale() {
        assertEquals("标准 · 100%",DanmakuSettings.sizeOptions[DanmakuSize.NORMAL.ordinal])
        assertEquals("小 · 75%",DanmakuSettings.sizeOptions[DanmakuSize.SMALL.ordinal])
    }

    @Test fun theOptionOrderMatchesTheEnums() {
        // 面板把「第几个候选」原样交给调用方，调用方再按下标取枚举 —— 两者必须对齐，
        // 否则在面板上选了「特大」，落进枚举里的却是「大」。
        DanmakuSize.entries.forEachIndexed { index,size ->
            assertTrue(DanmakuSettings.sizeOptions[index].startsWith(size.label))
        }
        DanmakuDensity.entries.forEachIndexed { index,density ->
            assertTrue(DanmakuSettings.densityOptions[index].startsWith(density.label))
            assertTrue(DanmakuSettings.densityOptions[index].contains("同屏 ${density.maxOnScreen} 条"))
        }
        DanmakuAlpha.entries.forEachIndexed { index,alpha ->
            assertEquals(alpha.label,DanmakuSettings.alphaOptions[index])
        }
        DanmakuRows.options.forEachIndexed { index,count ->
            assertEquals(DanmakuRows.label(count),DanmakuSettings.rowOptions[index])
        }
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

```
cd D:\document\project\hongguoTV\kotlin-tv
.\gradlew.bat :core:test --console=plain --no-daemon --tests "com.hongguotv.core.DanmakuSettingsTest"
```

Expected: 编译失败，`Unresolved reference 'DanmakuSettings'`。

- [ ] **Step 3: 写实现**

创建 `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DanmakuSettings.kt`：

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 弹幕设置面板里的一行。
 *
 * 面板（app 里那个对话框）只负责画和收按键；有哪些行、当前是什么值、可以选什么，都由 core 说了算 ——
 * 这样「面板上该长什么样」是能测的，不必开电视看一眼。
 */
data class DanmakuSettingRow(val title: String, val value: String, val options: List<String>)

/**
 * 弹幕设置的条目、当前值和候选值。
 *
 * 放在 core 里、不依赖 Android，是为了能测：面板是电视上唯一改弹幕的地方，
 * 改错了用户在屏幕上只能看出「不对」，说不上哪里不对。
 *
 * 各组候选的**顺序**跟对应枚举的顺序一致 —— 面板把「第几个候选」原样交给调用方，
 * 调用方再按下标取枚举，两者必须对齐。
 */
object DanmakuSettings {
    /** 开关的候选。第 0 个是「开启」。 */
    val toggleOptions=listOf("开启","关闭")

    /** 字号档位。顺序与 [DanmakuSize.entries] 一致。 */
    val sizeOptions=DanmakuSize.entries.map { "${it.label} · ${(it.scale*100).toInt()}%" }

    /** 行数候选。顺序与 [DanmakuRows.options] 一致。 */
    val rowOptions=DanmakuRows.options.map { DanmakuRows.label(it) }

    /** 同屏条数候选。顺序与 [DanmakuDensity.entries] 一致。 */
    val densityOptions=DanmakuDensity.entries.map { "${it.label} · 同屏 ${it.maxOnScreen} 条" }

    /** 透明度候选。顺序与 [DanmakuAlpha.entries] 一致。 */
    val alphaOptions=DanmakuAlpha.entries.map { it.label }

    /** 入口按钮上的一行摘要。 */
    fun summary(enabled: Boolean,size: DanmakuSize,rowCount: Int,density: DanmakuDensity,alpha: DanmakuAlpha): String =
        listOf(if(enabled) "开" else "关",size.label,DanmakuRows.label(rowCount),density.label,alpha.label).joinToString(" · ")

    /** 面板里的五行。顺序就是面板从上到下的顺序。 */
    fun rows(enabled: Boolean,size: DanmakuSize,rowCount: Int,density: DanmakuDensity,alpha: DanmakuAlpha): List<DanmakuSettingRow> = listOf(
        DanmakuSettingRow("弹幕开关",if(enabled) "开启" else "关闭",toggleOptions),
        DanmakuSettingRow("弹幕大小",size.label,sizeOptions),
        DanmakuSettingRow("弹幕行数",DanmakuRows.label(rowCount),rowOptions),
        DanmakuSettingRow("弹幕数量",density.label,densityOptions),
        DanmakuSettingRow("弹幕透明度",alpha.label,alphaOptions))
}
```

- [ ] **Step 4: 跑测试，确认通过**

```
cd D:\document\project\hongguoTV\kotlin-tv
.\gradlew.bat :core:test --console=plain --no-daemon --tests "com.hongguotv.core.DanmakuSettingsTest"
```

Expected: `DanmakuSettingsTest` 全过。

- [ ] **Step 5: 跑全量 core 单测，确认没有回归**

```
cd D:\document\project\hongguoTV\kotlin-tv
.\gradlew.bat :core:test --console=plain --no-daemon
```

Expected: `BUILD SUCCESSFUL`（改动前基线 325 条，加这 8 条 → 333 条，0 failures）。

- [ ] **Step 6: 提交**

```
cd D:\document\project\hongguoTV
git add kotlin-tv/core/src/main/kotlin/com/hongguotv/core/DanmakuSettings.kt kotlin-tv/core/src/test/kotlin/com/hongguotv/core/DanmakuSettingsTest.kt
git commit -m "feat: 弹幕设置面板的内容模型下沉到 core，可单测"
```

---

### Task 2: 面板本体（app 层）

**Files:**
- Create: `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuSettingsDialog.kt`

**Interfaces:**
- Consumes: `com.hongguotv.core.DanmakuSettingRow`（Task 1）、`FocusRouter`（`app/FocusRouter.kt`，已存在）、
  `TvStyle`（`app/TvStyle.kt`，已存在）
- Produces:
  ```kotlin
  class DanmakuSettingsDialog(
      activity: Activity,
      initialRows: List<DanmakuSettingRow>,
      apply: (Int, Int) -> List<DanmakuSettingRow>,   // 行号、候选号 → 应用后的新行
      closed: () -> Unit
  ) {
      fun show()
      fun dismiss()
  }
  ```

- [ ] **Step 1: 写 `DanmakuSettingsDialog.kt`**

创建 `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuSettingsDialog.kt`：

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.DanmakuSettingRow

/**
 * 弹幕设置面板。
 *
 * 一个对话框里竖着摆五行：开关、大小、行数、数量、透明度。每行显示当前值，
 * 确认键就地展开这一行的候选值；选完立刻生效、行上的文字当场更新 —— 面板不关，
 * 这样五项的当前值一直都在眼前，不必反复进出。
 *
 * 焦点走 [FocusRouter]：面板是另一个窗口，坐标得在这个窗口里算，混进主屏那张图里落点会指到别处去。
 */
class DanmakuSettingsDialog(
    private val activity: Activity,
    initialRows: List<DanmakuSettingRow>,
    private val apply: (Int,Int) -> List<DanmakuSettingRow>,
    private val closed: ()->Unit
) {
    private val dialog=Dialog(activity)
    /** 面板自己的焦点图。 */
    private val focusRouter=FocusRouter()
    private var rows=initialRows
    /** 哪一行展开了候选。null 表示都没展开。 */
    private var expanded: Int?=null
    /** 焦点该落在哪一行（渲染完要还回去）。 */
    private var focusRow=0
    /** 焦点该落在这一行的第几个候选。null 表示焦点在主行上。 */
    private var focusOption: Int?=null

    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()

    private fun paint(view: TextView,hasFocus: Boolean,active: Boolean=false) {
        view.background=GradientDrawable().apply {
            setColor(if(hasFocus) TvStyle.accent else if(active) TvStyle.raised else TvStyle.surface)
            cornerRadius=dp(8).toFloat()
        }
    }

    private fun item(value: String,active: Boolean,action: ()->Unit): TextView = TextView(activity).apply {
        id=View.generateViewId()
        text=value
        textSize=19f
        setTextColor(TvStyle.text)
        gravity=Gravity.CENTER_VERTICAL
        isFocusable=true; isFocusableInTouchMode=true
        paint(this,false,active)
        setOnFocusChangeListener { _,hasFocus -> paint(this,hasFocus,active) }
        setOnClickListener { action() }
    }

    /** 画一遍面板。展开状态变了就整体重画 —— 比手动增删 view 干净。 */
    private fun render() {
        focusRouter.reset()
        val box=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(16),dp(16),dp(16))
            setBackgroundColor(TvStyle.background)
        }
        box.addView(TextView(activity).apply {
            text="弹幕设置"; textSize=22f; setTextColor(TvStyle.text); setPadding(dp(6),dp(2),dp(6),dp(12))
        })
        val mains=mutableListOf<TextView>()
        val options=mutableListOf<TextView>()
        rows.forEachIndexed { index,row ->
            val line=item("${row.title}  ·  ${row.value}",false) {
                // 再按一次同一行 = 收起。
                if(expanded==index) { expanded=null; focusRow=index; focusOption=null }
                else { expanded=index; focusRow=index; focusOption=0 }
                render()
            }
            mains+=line
            box.addView(line,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(4) })
            if(expanded==index) {
                row.options.forEachIndexed { optionIndex,option ->
                    // 当前项用 selectedIndex 认，不用「文案相等」认：大小那行的当前值是
                    // 「标准」，候选串却是「标准 · 100%」，比字符串对不上。
                    val choice=item(option,optionIndex==row.selectedIndex) {
                        rows=apply(index,optionIndex)
                        expanded=null
                        focusRow=index
                        focusOption=null
                        render()
                    }
                    options+=choice
                    box.addView(choice,LinearLayout.LayoutParams(-1,-2).apply {
                        leftMargin=dp(34); bottomMargin=dp(2)
                    })
                }
            }
        }
        dialog.setContentView(box)
        // 面板里每一行都是自己一「行」，焦点图按位置算：上下走到相邻行，左右没有去处就留在原地。
        // 候选是竖着排的，上下走；左右在这行里没有别的项，就留在原地。
        box.viewTreeObserver.addOnGlobalLayoutListener(object: ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                focusRouter.apply(box)
                // 焦点得等布局完再给：刚 setContentView 的那一瞬间还没有宽高，requestFocus 不作数。
                pendingFocus?.requestFocus()
                pendingFocus=null
            }
        })
        pendingFocus=focusOption?.let { options.getOrNull(it) } ?: mains.getOrNull(focusRow)
    }

    /** 布局完成后要把焦点放到哪儿。 */
    private var pendingFocus: View?=null

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        // 返回键：候选展开时先只收候选，再按才关面板。
        dialog.setOnKeyListener { _,keyCode,event ->
            if(keyCode==KeyEvent.KEYCODE_BACK && event.action==KeyEvent.ACTION_UP && expanded!=null) {
                val row=expanded!!
                expanded=null; focusRow=row; focusOption=null
                render()
                true
            } else false
        }
        render()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            setLayout(minOf(dp(560),(activity.resources.displayMetrics.widthPixels*.72f).toInt()),LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    fun dismiss()=dialog.dismiss()
}
```

要点：

- `active` 表示「这一项就是当前值」：聚焦时用 accent 底色，没聚焦但正是当前值时用 `raised` 底色。
- **候选竖着排**：电视上一列一列读最顺。上下在候选之间走，左右在这行里没有别的项，留在原地。
- 焦点在 `onGlobalLayout` 里给，不在 `setContentView` 之后立刻给 —— 那时候还没有宽高，`requestFocus` 不作数。

- [ ] **Step 2: 编译 app**

```
cd D:\document\project\hongguoTV\kotlin-tv
.\gradlew.bat :app:compileReleaseKotlin --console=plain --no-daemon
```

Expected: `BUILD SUCCESSFUL`。若报 `Unresolved reference 'DanmakuSettingRow'`，说明 Task 1 的 core 没被 app 依赖到 ——
`app/build.gradle.kts` 里已经有 `implementation(project(":core"))`，重新 `:core:build` 一次即可。

- [ ] **Step 3: 提交**

```
cd D:\document\project\hongguoTV
git add kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/DanmakuSettingsDialog.kt
git commit -m "feat: 弹幕设置面板对话框，确认键就地展开候选"
```

---

### Task 3: 接上两个入口，删掉旧的四个 picker

**Files:**
- Modify: `kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt`

**Interfaces:**
- Consumes: `DanmakuSettingsDialog`（Task 2）、`DanmakuSettings`（Task 1）
- Produces: `MainActivity` 里的两个私有方法
  - `private fun danmakuSummary(): String`
  - `private fun showDanmakuSettings(anchor: TextView, onClosed: () -> Unit = {})`

- [ ] **Step 1: 设置页：5 个按钮换 1 个**

把 `MainActivity.kt` 里这段（`settings()` 方法内，现 1164~1177 行）：

```kotlin
        lateinit var danmakuButton: TextView
        danmakuButton=setting(playback,"弹幕  ·  ${if(library.danmakuEnabled) "开启" else "关闭"}") {
            library.danmakuEnabled=!library.danmakuEnabled
            danmakuButton.text="弹幕  ·  ${if(library.danmakuEnabled) "开启" else "关闭"}"
            applyDanmakuSetting()
        }
        lateinit var danmakuSizeButton: TextView
        danmakuSizeButton=setting(playback,"弹幕大小  ·  ${library.danmakuSize.label}") { showDanmakuSizePicker(danmakuSizeButton,false) }
        lateinit var danmakuRowsButton: TextView
        danmakuRowsButton=setting(playback,"弹幕行数  ·  ${DanmakuRows.label(library.danmakuRows)}") { showDanmakuRowsPicker(danmakuRowsButton,false) }
        lateinit var danmakuDensityButton: TextView
        danmakuDensityButton=setting(playback,"弹幕数量  ·  ${library.danmakuDensity.label}") { showDanmakuDensityPicker(danmakuDensityButton,false) }
        lateinit var danmakuAlphaButton: TextView
        danmakuAlphaButton=setting(playback,"弹幕透明度  ·  ${library.danmakuAlpha.label}") { showDanmakuAlphaPicker(danmakuAlphaButton,false) }
```

换成：

```kotlin
        lateinit var danmakuButton: TextView
        danmakuButton=setting(playback,"弹幕设置  ·  ${danmakuSummary()}") {
            showDanmakuSettings(danmakuButton) { danmakuButton.text="弹幕设置  ·  ${danmakuSummary()}" }
        }
```

- [ ] **Step 2: 新增 `danmakuSummary()` 和 `showDanmakuSettings()`**

在 `applyDanmakuStyle()` 方法后面（现 1837 行 `}` 之后）插入：

```kotlin
    /**
     * 弹幕设置入口上的一行摘要。
     *
     * 开关 + 4 个样式的当前值，`·` 分隔 —— 一行放得进设置页左列，也一眼看得出现在是什么状态。
     */
    private fun danmakuSummary()=DanmakuSettings.summary(library.danmakuEnabled,library.danmakuSize,library.danmakuRows,library.danmakuDensity,library.danmakuAlpha)

    /**
     * 弹幕设置面板。
     *
     * 五项（开关、大小、行数、数量、透明度）都在这一个面板里：改完不关面板，五项的当前值一直都在眼前。
     * 选中的值立刻生效 —— 正在播的时候觉得字小，正是最想调它的时候。
     *
     * 面板里给的是「第几个候选」，这里按下标取枚举；两边的顺序由 core 的单测钉着
     * （见 DanmakuSettingsTest.theOptionOrderMatchesTheEnums）。
     */
    private fun showDanmakuSettings(anchor: TextView,onClosed: ()->Unit={}) {
        DanmakuSettingsDialog(this,
            DanmakuSettings.rows(library.danmakuEnabled,library.danmakuSize,library.danmakuRows,library.danmakuDensity,library.danmakuAlpha),
            { row,option ->
                when(row) {
                    0 -> { library.danmakuEnabled=option==0; applyDanmakuSetting() }
                    1 -> { library.danmakuSize=DanmakuSize.entries[option]; applyDanmakuStyle() }
                    2 -> { library.danmakuRows=DanmakuRows.options[option]; applyDanmakuStyle() }
                    3 -> { library.danmakuDensity=DanmakuDensity.entries[option]; applyDanmakuStyle() }
                    else -> { library.danmakuAlpha=DanmakuAlpha.entries[option]; applyDanmakuStyle() }
                }
                DanmakuSettings.rows(library.danmakuEnabled,library.danmakuSize,library.danmakuRows,library.danmakuDensity,library.danmakuAlpha)
            },
            { onClosed(); if(anchor.isAttachedToWindow) anchor.requestFocus() }).show()
    }
```

- [ ] **Step 3: HUD「播放设置」菜单：10 项变 6 项**

把 `MainActivity.kt` 里 `showPanel()` 内的这段（现 1942~1959 行）：

```kotlin
        more=addButton(options,"播放设置") {
            tvTools.choose("播放设置",listOf("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}","清晰度 ${library.maxQuality}P","画面 ${library.frameMode.label}","定时 ${sleepTimer.label()}","从头播放","弹幕 ${if(library.danmakuEnabled) "开" else "关"}","弹幕大小 ${library.danmakuSize.label}","弹幕行数 ${DanmakuRows.label(library.danmakuRows)}","弹幕数量 ${library.danmakuDensity.label}","弹幕透明度 ${library.danmakuAlpha.label}"),more,{ choice ->
                main.post {
                    if(screen=="player" && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> showSleepPicker(more)
                        4 -> { seekTo(0); hidePanel() }
                        5 -> { library.danmakuEnabled=!library.danmakuEnabled; applyDanmakuSetting() }
                        6 -> showDanmakuSizePicker(more,true)
                        7 -> showDanmakuRowsPicker(more,true)
                        8 -> showDanmakuDensityPicker(more,true)
                        9 -> showDanmakuAlphaPicker(more,true)
                    }
                }
            })
        }.apply { tag="playback-settings" }
```

换成：

```kotlin
        more=addButton(options,"播放设置") {
            tvTools.choose("播放设置",listOf("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}","清晰度 ${library.maxQuality}P","画面 ${library.frameMode.label}","定时 ${sleepTimer.label()}","从头播放","弹幕设置 ${danmakuSummary()}"),more,{ choice ->
                main.post {
                    if(screen=="player" && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> showSleepPicker(more)
                        4 -> { seekTo(0); hidePanel() }
                        5 -> showDanmakuSettings(more)
                    }
                }
            })
        }.apply { tag="playback-settings" }
```

- [ ] **Step 4: 删掉四个旧 picker**

把 `MainActivity.kt` 里这四个方法**连同它们上方的注释块**整个删掉（现 1869~1923 行）：

```kotlin
    /**
     * 弹幕的字号。
     *
     * 档位而不是倍数：倍数是相对「按屏高算出来的基准字号」而言的，换台电视同一个倍数
     * 看起来完全不是一回事。存的是档位，跟着机器走。
     */
    private fun showDanmakuSizePicker(anchor: TextView,inPlayer: Boolean) {
        val options=DanmakuSize.entries
        tvTools.choose("弹幕大小",options.map { "${it.label} · ${(it.scale*100).toInt()}%" },anchor,{ index ->
            val target=options[index]
            library.danmakuSize=target; applyDanmakuStyle()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "弹幕大小 " else "弹幕大小  ·  ")+target.label
        })
    }
    /**
     * 弹幕占几行。
     *
     * 「自动」是按屏幕高度算，填满画面上半；写死行数就照他说的排 —— 画面放不下时少几行，
     * 并且告诉用户实际是几行，不能让人对着屏幕数出来的行数找不着北。
     */
    private fun showDanmakuRowsPicker(anchor: TextView,inPlayer: Boolean) {
        val options=DanmakuRows.options
        tvTools.choose("弹幕行数",options.map { DanmakuRows.label(it) },anchor,{ index ->
            val target=options[index]
            library.danmakuRows=target; applyDanmakuStyle()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "弹幕行数 " else "弹幕行数  ·  ")+DanmakuRows.label(target)
        })
    }
    /**
     * 同屏最多几条弹幕。
     *
     * 弹幕一多，屏幕上就是一片白字（实测同屏最高 36 条，铺在 7 行轨道上）。这个旋钮压的是**条数**，
     * 不是行数 —— 行数是画面上留几行给弹幕，这个是这几行里同时最多飘几条。
     */
    private fun showDanmakuDensityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=DanmakuDensity.entries
        tvTools.choose("弹幕数量",options.map { "${it.label} · 同屏 ${it.maxOnScreen} 条" },anchor,{ index ->
            val target=options[index]
            library.danmakuDensity=target; applyDanmakuStyle()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "弹幕数量 " else "弹幕数量  ·  ")+target.label
        })
    }
    /**
     * 弹幕有多透明。
     *
     * 描边是黑边、填充是白字，两者不一起降：亮画面上白字全靠那道黑边才看得清，边淡了字就糊了。
     */
    private fun showDanmakuAlphaPicker(anchor: TextView,inPlayer: Boolean) {
        val options=DanmakuAlpha.entries
        tvTools.choose("弹幕透明度",options.map { it.label },anchor,{ index ->
            val target=options[index]
            library.danmakuAlpha=target; applyDanmakuStyle()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "弹幕透明度 " else "弹幕透明度  ·  ")+target.label
        })
    }
```

删干净之后，`MainActivity.kt` 里 `showDanmakuSizePicker` / `showDanmakuRowsPicker` /
`showDanmakuDensityPicker` / `showDanmakuAlphaPicker` 这四个名字都不该再出现。

- [ ] **Step 5: 编译**

```
cd D:\document\project\hongguoTV\kotlin-tv
.\gradlew.bat :app:compileReleaseKotlin :app:testDebugUnitTest --console=plain --no-daemon
```

Expected: `BUILD SUCCESSFUL`。
若报 `Unresolved reference 'showDanmakuSizePicker'` 之类，说明第 4 步没删干净（还有调用点）；
若报 `Unresolved reference 'DanmakuSettings'`，core 没编进去，跑一次 `.\gradlew.bat :core:build --console=plain --no-daemon`。

- [ ] **Step 6: 确认旧调用点已清空**

```
cd D:\document\project\hongguoTV
```

用 grep 工具搜 `showDanmaku(Size|Rows|Density|Alpha)Picker`，Expected: 0 命中。

- [ ] **Step 7: 提交**

```
cd D:\document\project\hongguoTV
git add kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt
git commit -m "feat: 设置页与播放 HUD 的弹幕设置合并到一个面板"
```

---

### Task 4: 实机验证（Palm 替身机）

**Files:** 不改代码（除非验证发现毛病，改了另提一次提交）

**Interfaces:**
- Consumes: Task 3 的 app
- Produces: 验收结论

- [ ] **Step 1: 看 Palm 上现在装的是哪个版本**

```
$adb='D:\Programs\android-sdk\platform-tools\adb.exe'
& $adb connect 192.168.1.30:5555
& $adb -s 192.168.1.30:5555 shell dumpsys package com.hongguotv.nativeapp | Select-String 'versionName|versionCode'
```

记下 versionCode。**新包的 versionCode 必须严格大于它**（否则 `INSTALL_FAILED_VERSION_DOWNGRADE`）。

- [ ] **Step 2: 出一个装到 Palm 的调试包**

版本号取 **9076 / 0.9.76-dbg**：必须严格大于 Palm 上现装的 9074（电视上也是 9074），
又必须小于将来的正式包 9077 —— 更新提示按 versionCode 严格大于比较，正式包要越过实验版。

```
cd D:\document\project\hongguoTV\kotlin-tv
$env:HONGGUOTV_VERSION_CODE='9076'; $env:HONGGUOTV_VERSION_NAME='0.9.76-dbg'
.\gradlew.bat :app:assembleRelease --console=plain --no-daemon
```

产物：`kotlin-tv/app/build/outputs/apk/release/app-release.apk`

- [ ] **Step 3: 装到 Palm**

```
& $adb -s 192.168.1.30:5555 install -r "D:\document\project\hongguoTV\kotlin-tv\app\build\outputs\apk\release\app-release.apk"
```

报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（签名不一致）就先卸载再装：
```
& $adb -s 192.168.1.30:5555 uninstall com.hongguotv.nativeapp
& $adb -s 192.168.1.30:5555 install "D:\document\project\hongguoTV\kotlin-tv\app\build\outputs\apk\release\app-release.apk"
```

- [ ] **Step 4: 打开 App，跑到设置页**

```
& $adb -s 192.168.1.30:5555 shell am start -n com.hongguotv.nativeapp/.MainActivity
```

Palm 息屏时按键不生效，先唤醒：
```
& $adb -s 192.168.1.30:5555 shell input keyevent KEYCODE_WAKEUP
& $adb -s 192.168.1.30:5555 shell wm dismiss-keyguard
```

从首页按两下 `19`（↑）走到顶部导航行，再按 5 下 `22`（→）到「设置」，按 `23`（确认）。

- [ ] **Step 5: 验收清单（每条都要有证据）**

用 `uiautomator` dump 读控件文字（比截图可靠，模型读不了图）：

```
& $adb -s 192.168.1.30:5555 shell uiautomator dump /sdcard/ui.xml
& $adb -s 192.168.1.30:5555 shell cat /sdcard/ui.xml
```

| # | 操作 | 期望 |
|---|---|---|
| 1 | 设置页左列 | 只有 5 项：`默认清晰度` / `默认倍速` / `画面模式` / `自动连播` / `弹幕设置 · 开 · 标准 · 自动 · 中 · 半透明`；**没有**独立的「弹幕大小/行数/数量/透明度」 |
| 2 | 按 `23` 打开「弹幕设置」 | 面板出现，标题 `弹幕设置`，五行：`弹幕开关 · 开启`、`弹幕大小 · 标准`、`弹幕行数 · 自动`、`弹幕数量 · 中`、`弹幕透明度 · 半透明` |
| 3 | 按 `20`（↓）两次 | 焦点依次落到「弹幕行数」「弹幕数量」（dump 里带 `focused="true"` 的那个） |
| 4 | 在「弹幕数量」按 `23` | 候选就地展开：同屏 6 条 / 同屏 12 条 / 同屏 24 条 三行 |
| 5 | 焦点落在「同屏 24 条」按 `23` | 该行文字变成 `弹幕数量 · 多`；**面板没关**；其它四行文字不变 |
| 6 | 按 `4`（返回） | 面板关闭，焦点回到设置页的「弹幕设置」按钮 |
| 7 | 再次打开面板 | 「弹幕数量」显示 `多`（落盘了） |
| 8 | 在「弹幕大小」选「特大」，然后重进 App | 仍是「特大」 |
| 9 | 播一集，在 HUD 里 `↑` → 「播放设置」 | 菜单里是 `弹幕设置 开 · 特大 · 自动 · 多 · 半透明`；选它 → 同一个面板，改完立刻反映到画面上 |

- [ ] **Step 6: 记录问题并修**

任何一条不符：改代码 → 重新 `:app:assembleRelease` → 重装 → 重跑 Step 5。改完单独提交一次。

- [ ] **Step 7: 收尾**

```
& $adb -s 192.168.1.30:5555 shell am force-stop com.hongguotv.nativeapp
```

（测完必须退出 App：它会一直拉流、一直写本机索引，还占着用户的机器。）

- [ ] **Step 8: 提交（如果 Step 6 改过代码）**

```
cd D:\document\project\hongguoTV
git add -A kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp
git commit -m "fix: 弹幕设置面板实机验收发现的问题"
```

---

## 交付说明

本计划**不单独出正式包**。弹幕设置面板随正式包一起交付 —— 那个包还要一并收掉
「播放总是重试」修复的临时探针（`TvProbe.kt`、`ContentRepository.probeDoors()` 等），撤干净后重新出包、重新装。

版本号链路（更新提示按 versionCode 严格大于比较，官方包必须越过实验版）：

| versionCode | versionName | 用途 |
|---|---|---|
| 9074 | 0.9.74 | 电视与 Palm 上现装的版本 |
| 9075 | 0.9.75-dbg | 「播放总是重试」修复的探针包（不装） |
| 9076 | 0.9.76-dbg | 本次实机验收用（装 Palm） |
| **9077** | **0.9.77** | **正式交付包**（弹幕面板 + 播放重试修复，探针撤干净） |

## 验收标准

- `:core:test` 全绿（基线 325 + 新增 8 = 333 条）
- `:app:compileReleaseKotlin` 通过
- Palm 实机走完 Task 4 的 9 条清单
- 交付包内**不含**任何探针/读数/埋点代码
