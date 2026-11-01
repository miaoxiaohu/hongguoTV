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
