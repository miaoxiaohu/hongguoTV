// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import kotlin.math.abs

/** 遥控器方向键的四个方向。 */
enum class FocusDir { UP, DOWN, LEFT, RIGHT }

/**
 * 一个可聚焦项在屏幕上的位置（像素，原点为窗口左上角）。
 *
 * 「遥控器选中不合理」的根子就在这里：焦点往哪儿落，原来每个页面各手写一套
 * `nextFocus*Id`，靠数下标算出来。于是同一个动作在不同页面落到不同的地方 ——
 * 首页从「首页」按下键跳到屏幕最右边的片库按钮，搜索页从「手机输入」按下键却落到
 * 最左边的第一张卡。数字算的是「第几个」，眼睛看的是「在哪儿」。
 *
 * 这里只记事实（一个项在哪儿），判断交给 [FocusPlan]。
 */
data class FocusRect(
    val key: String,
    val left: Float, val right: Float,
    val top: Float, val bottom: Float
) {
    val centerX get() = (left+right)/2f
    val centerY get() = (top+bottom)/2f
    val height get() = bottom-top

    /** 与另一项在横向上重叠了多长（不重叠时是 0 或负数）。 */
    fun overlapX(other: FocusRect): Float = minOf(right, other.right)-maxOf(left, other.left)
}

/**
 * 一屏的焦点图：每个可聚焦项的四个方向分别落到哪儿。
 *
 * 规则只有两条，和页面无关：
 * - **左右**：在同一「视觉行」里找最近的一个；到头了在本行内环绕（左边尽头绕到最右，
 *   右边尽头绕到最左）。以前到头是把方向键指向自己 —— 焦点卡死，按了没反应。
 * - **上下**：去相邻的视觉行，在那一行里挑与当前位置横向重叠最多的那个；没有重叠就挑
 *   中心离得最近的。这样「在屏幕右边的东西往下掉」会落在右边，而不是屏幕中间。
 */
class FocusPlan internal constructor(
    val bands: List<List<FocusRect>>,
    private val links: Map<String, Targets>
) {
    /** 一个可聚焦项的四个方向落点。null 表示这一侧没有目标（交回系统默认）。 */
    data class Targets(val up: String?, val down: String?, val left: String?, val right: String?)

    val keys: Set<String> get() = links.keys
    /** 全部项的落点。 */
    val all: Map<String,Targets> get() = links
    fun targets(key: String): Targets? = links[key]
    fun target(key: String, dir: FocusDir): String? = when(dir) {
        FocusDir.UP -> links[key]?.up
        FocusDir.DOWN -> links[key]?.down
        FocusDir.LEFT -> links[key]?.left
        FocusDir.RIGHT -> links[key]?.right
    }

    /**
     * 指着自己的方向键。
     *
     * 只允许出现在两个地方：最上面一行按「上」、最下面一行按「下」—— 那两个方向本来就没有
     * 别的去处，留在原地是唯一说得通的行为。别处的自锁是 bug，不是设计。
     */
    fun selfLoops(): List<Pair<String,FocusDir>> = links.flatMap { (key,targets) ->
        listOf(FocusDir.UP to targets.up, FocusDir.DOWN to targets.down,
            FocusDir.LEFT to targets.left, FocusDir.RIGHT to targets.right)
            .filter { it.second==key }.map { key to it.first }
    }

    /** 指向了不存在的项。 */
    fun dangling(): List<Pair<String,FocusDir>> = links.flatMap { (key,targets) ->
        listOf(FocusDir.UP to targets.up, FocusDir.DOWN to targets.down,
            FocusDir.LEFT to targets.left, FocusDir.RIGHT to targets.right)
            .filter { it.second!=null && it.second !in links }.map { key to it.first }
    }

    /** 从 [start] 出发、按四个方向走能到达的全部项。 */
    fun reachable(start: String): Set<String> {
        val seen=mutableSetOf(start); val queue=ArrayDeque<String>(); queue.add(start)
        while(queue.isNotEmpty()) {
            val key=queue.removeFirst()
            for(dir in FocusDir.entries) {
                val next=target(key,dir) ?: continue
                if(seen.add(next)) queue.add(next)
            }
        }
        return seen
    }

    companion object {
        /**
         * 把可聚焦项按「视觉行」分组：纵向范围大致重叠的算同一行。
         *
         * 行是算出来的，不是声明的 —— 每一页的行数都不一样（首页是若干条货架，详情页是
         * 剧集网格加季度列表），声明就得在五个地方各写一遍，而写错一处不会报错、只会让
         * 焦点跳到奇怪的地方。
         */
        fun bands(rects: List<FocusRect>): List<List<FocusRect>> {
            if(rects.isEmpty()) return emptyList()
            val sorted=rects.sortedBy { it.centerY }
            val out=mutableListOf<MutableList<FocusRect>>()
            var current=mutableListOf<FocusRect>()
            var top=Float.MAX_VALUE; var bottom=-Float.MAX_VALUE
            for(rect in sorted) {
                val overlap=minOf(bottom,rect.bottom)-maxOf(top,rect.top)
                val shorter=minOf(bottom-top,rect.height)
                val same=current.isNotEmpty() && overlap>0 && overlap>=shorter*0.5f
                if(same) { current+=rect; top=minOf(top,rect.top); bottom=maxOf(bottom,rect.bottom) }
                else { if(current.isNotEmpty()) out+=current; current=mutableListOf(rect); top=rect.top; bottom=rect.bottom }
            }
            if(current.isNotEmpty()) out+=current
            return out
        }

        /** 算一屏的焦点图。 */
        fun build(rects: List<FocusRect>): FocusPlan {
            val rows=bands(rects)
            val links=linkedMapOf<String,Targets>()
            rows.forEachIndexed { index,row ->
                row.forEach { rect ->
                    // 上下两端没有去处，就留在原地 —— 明确写出来，别交给系统的几何搜索去猜。
                    val up=verticalTarget(rows,index,rect,FocusDir.UP) ?: rect.key
                    val down=verticalTarget(rows,index,rect,FocusDir.DOWN) ?: rect.key
                    val left=horizontalTarget(row,rect,FocusDir.LEFT)
                    val right=horizontalTarget(row,rect,FocusDir.RIGHT)
                    links[rect.key]=Targets(up,down,left,right)
                }
            }
            return FocusPlan(rows,links)
        }

        /**
         * 上下：去相邻的视觉行，在那一行里挑横向重叠最多的那个。
         *
         * 相邻是按行的顺序定的，不是按几何距离：网格的三行海报高度一样，按几何挑和按顺序
         * 挑是一回事；但详情页的「开始观看」那一行比剧集网格高得多，按几何挑会跳错行。
         */
        private fun verticalTarget(rows: List<List<FocusRect>>, index: Int, current: FocusRect, dir: FocusDir): String? {
            val candidateIndex=when(dir) {
                FocusDir.UP -> (index-1 downTo 0).firstOrNull { at -> rows[at].any { it.centerY<current.centerY } }
                FocusDir.DOWN -> (index+1 until rows.size).firstOrNull { at -> rows[at].any { it.centerY>current.centerY } }
                else -> null
            } ?: return null
            val row=rows[candidateIndex]
            val overlapping=row.filter { it.overlapX(current)>0 }
            val pool=if(overlapping.isNotEmpty()) overlapping.sortedWith(
                compareByDescending<FocusRect>{ it.overlapX(current) }.thenBy { abs(it.centerX-current.centerX) })
            else row.sortedBy { abs(it.centerX-current.centerX) }
            return pool.firstOrNull()?.key
        }

        /**
         * 左右：同一行里最近的一个；到头了在本行内环绕。
         *
         * 环绕是为了消掉「按了没反应」：以前第一列按左、最后一列按右，方向键被指回自己，
         * 焦点卡死在原地，用户只看到屏幕一动不动。
         */
        private fun horizontalTarget(row: List<FocusRect>, current: FocusRect, dir: FocusDir): String? {
            if(row.size<=1) return null
            val others=row.filter { it.key!=current.key }
            if(others.isEmpty()) return null
            return when(dir) {
                FocusDir.RIGHT -> {
                    val next=others.filter { it.left>=current.right-0.5f }.minByOrNull { it.left }
                    // 绕回本行最左边的那一个；绝不绕回自己。
                    next?.key ?: others.minByOrNull { it.left }?.key
                }
                FocusDir.LEFT -> {
                    val previous=others.filter { it.right<=current.left+0.5f }.maxByOrNull { it.right }
                    previous?.key ?: others.maxByOrNull { it.right }?.key
                }
                else -> null
            }
        }
    }
}
