// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.*

/** A remote-accessible side panel; selecting a number is the only action that changes episodes. */
class EpisodePanel(
    private val activity: Activity,private val count: Int,private val current: Int,
    private val locate: (Int,View,(Int)->Unit)->Unit,
    private val select: (Int)->Unit,private val closed: ()->Unit
) {
    private val dialog=Dialog(activity)
    /**
     * 面板自己的焦点图。
     *
     * 面板是另一个窗口，坐标是相对那个窗口算的 —— 混进主屏那张图里，落点会指到别处去。
     */
    private val focusRouter=FocusRouter()
    private var group=current/20
    private var focused=current
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(TvStyle.text); setPadding(dp(8),dp(6),dp(8),dp(6)) }
    private fun button(value: String,active: Boolean=false,onFocus: (() -> Unit)?=null,action: ()->Unit)=label(value,16f).apply {
        id=View.generateViewId(); gravity=Gravity.CENTER; minHeight=dp(46); isFocusable=true; isFocusableInTouchMode=true
        fun paint(focus: Boolean) { background=GradientDrawable().apply { setColor(if(focus) TvStyle.accent else if(active) Color.rgb(61,39,34) else TvStyle.surface); cornerRadius=dp(6).toFloat() } }
        paint(false)
        setOnFocusChangeListener { _,hasFocus -> paint(hasFocus); if(hasFocus) onFocus?.invoke() }
        setOnClickListener { action() }
    }
    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        render(); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            attributes=attributes.apply { x=(activity.resources.displayMetrics.widthPixels*.05f).toInt() }
            setLayout(minOf(dp(520),(activity.resources.displayMetrics.widthPixels*.8f).toInt()),(activity.resources.displayMetrics.heightPixels*.9f).toInt())
        }
    }
    fun dismiss()=dialog.dismiss()
    private fun render() {
        focusRouter.reset()
        val content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12)); setBackgroundColor(TvStyle.background) }
        content.addView(label("选集  /  正在看第 ${current+1} 集",20f))
        val actions=LinearLayout(activity)
        val close=button("关闭") { dismiss() }
        lateinit var jump: TextView
        jump=button("跳转集数") { locate(focused,jump) { target -> focused=target; group=target/20; render() } }
        listOf(close,jump).forEach { actions.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }) }
        content.addView(actions)
        val paging=LinearLayout(activity)
        val previous=button("‹ 上一组") { group--; focused=group*20; render() }.apply { isEnabled=group>0; isFocusable=isEnabled; alpha=if(isEnabled) 1f else .4f }
        val next=button("下一组 ›") { group++; focused=group*20; render() }.apply { isEnabled=(group+1)*20<count; isFocusable=isEnabled; alpha=if(isEnabled) 1f else .4f }
        listOf(previous,next).forEach { paging.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }) }
        content.addView(paging)
        content.addView(label(if(count<=0) "没有可播放的分集" else "第 ${group*20+1}—${minOf((group+1)*20,count)} 集 / 共 $count 集",14f))
        val scroll=ScrollView(activity); val grid=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }; scroll.addView(grid)
        content.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val cells=mutableListOf<TextView>()
        (group*20 until minOf((group+1)*20,count)).toList().chunked(5).forEach { indexes ->
            val row=LinearLayout(activity)
            indexes.forEach { index ->
                // Moving the focus onto a cell is what tells the panel which episode the user is
                // on. Without it 「跳转集数」 prefilled the episode the panel was opened on, and
                // confirmed that one instead of the one under the cursor.
                val cell=button("${index+1}",index==current,{ focused=index }) { select(index) }
                cell.contentDescription="第 ${index+1} 集"+(if(index==current) "，当前播放" else "")
                row.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(3),dp(3),dp(3),dp(3)) }); cells+=cell
            }
            repeat(5-indexes.size) { row.addView(Space(activity),LinearLayout.LayoutParams(0,1,1f)) }
            grid.addView(row)
        }
        // 面板里的四个方向由焦点图统一算：同一行左右环绕，上下按位置对齐。
        // 以前这里是手写的 nextFocus*Id，第一行按「上」按格子下标分派（0—2 去「上一组」、
        // 3—4 去「下一组」），纯属数下标数出来的。
        dialog.setContentView(content)
        // 面板每一组重画一次，落点跟着重算一次。
        content.viewTreeObserver.addOnGlobalLayoutListener(object: ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() { focusRouter.apply(content) }
        })
        if(cells.isNotEmpty()) cells[(focused-group*20).coerceIn(0,cells.lastIndex)].requestFocus()
    }
}
