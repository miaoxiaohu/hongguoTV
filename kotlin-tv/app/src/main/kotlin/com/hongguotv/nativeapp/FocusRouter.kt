// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.widget.EditText

/**
 * 把焦点图落到真机上。
 *
 * 它只做两件事：把这一屏所有可聚焦的 view 找出来量好位置，按 [FocusPlan] 算出四个方向
 * 该落在哪儿，再写回 `nextFocus*Id`。
 *
 * 原来每个页面都手写了一套 `nextFocus*Id`，落点是按下标算出来的 —— 同一个动作在不同页面
 * 落到不同的地方（首页从「首页」按下键跳到屏幕另一头的片库按钮，搜索页从「手机输入」按
 * 下键落到最左边第一张卡）。
 *
 * 找 view 的方式是**扫整棵树**，不是各处登记：登记过一次就可能漏一处，漏掉的那一处就会
 * 落回系统自己的几何搜索 —— 那正是「跳到屏幕另一头」的来源。扫树则不会漏。
 */
class FocusRouter {
    private var signature: Map<String,String> = emptyMap()

    /** 这一屏重画了：把上一屏的落点忘掉，日志里也好重新记一笔。 */
    fun reset() { signature=emptyMap() }

    /**
     * 量位置、算图、写回。
     *
     * 有一项还没量好（宽或高是 0）就跳过这一项，不整轮不算 —— 半张图比旧图更糟：
     * 落点会指向一个根本量不出来的东西。
     */
    fun apply(root: View) {
        val live=ArrayList<View>()
        collect(root,live)
        if(live.isEmpty()) return
        val location=IntArray(2)
        val rects=ArrayList<FocusRect>(live.size)
        val views=HashMap<Int,View>(live.size)
        for(view in live) {
            if(view.width<=0||view.height<=0) continue
            view.getLocationInWindow(location)
            rects+=FocusRect(view.id.toString(),location[0].toFloat(),(location[0]+view.width).toFloat(),
                location[1].toFloat(),(location[1]+view.height).toFloat())
            views[view.id]=view
        }
        if(rects.isEmpty()) return
        val plan=FocusPlan.build(rects)
        val next=plan.all.mapValues { (_,targets) -> "${targets.up}|${targets.down}|${targets.left}|${targets.right}" }
        if(next!=signature) {
            signature=next
            android.util.Log.i("HongguoTV","Focus graph: ${next.size} focusable items in ${plan.bands.size} rows")
        }
        for(rect in rects) {
            val view=views[rect.key.toInt()] ?: continue
            val targets=plan.targets(rect.key) ?: continue
            view.nextFocusUpId=idOf(targets.up,view)
            view.nextFocusDownId=idOf(targets.down,view)
            // 输入框里按左右是移动光标，不是移动焦点 —— 这两个键得留给它自己。
            if(view is EditText) continue
            view.nextFocusLeftId=idOf(targets.left,view)
            view.nextFocusRightId=idOf(targets.right,view)
        }
    }

    /** 这一屏里所有能拿到焦点的 view。看不见的不算。 */
    private fun collect(view: View,into: MutableList<View>) {
        if(view.visibility!=View.VISIBLE) return
        if(view.isFocusable && view.id!=View.NO_ID) into+=view
        if(view is ViewGroup) for(index in 0 until view.childCount) collect(view.getChildAt(index),into)
    }

    /** 落点为空的时候留在原地，别交给系统的几何搜索去猜。 */
    private fun idOf(key: String?,self: View): Int = key?.toIntOrNull() ?: self.id
}
