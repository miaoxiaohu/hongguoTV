// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遥控器焦点该落在哪儿。
 *
 * 这里每一条断言都是一次真的「选中不合理」：
 * - 首页从「首页」按下键，焦点跳到屏幕最右边的片库按钮（因为落点是按下标算的）。
 * - 搜索页从「手机输入」按下键，焦点落到最左边的第一张卡。
 * - 网格第一列按左、最后一列按右，方向键指回自己 —— 按了没反应，焦点卡死。
 * - 选集面板第一行按上，第三格去「上一组」、第四格去「下一组」，纯属数下标数出来的。
 * - 从海报按上回到工具栏，落点也在屏幕另一头。
 *
 * 焦点图只有两条规则（左右在本行内环绕、上下按横向位置对齐），规则少才敢说是「合理」，
 * 而且这两条能在这台机器上跑单测 —— 不必把电视搬过来才能验。
 */
class FocusTest {
    private fun rect(key:String,x0:Float,x1:Float,y0:Float,y1:Float)=FocusRect(key,x0,x1,y0,y1)

    /** 一行海报：宽 280，六列一行，间距 30。 */
    private fun posterRow(prefix:String,row:Int,y0:Float,y1:Float): List<FocusRect> =
        (0..5).map { column -> rect("$prefix$row-$column",(column*310).toFloat(),(column*310+280).toFloat(),y0,y1) }

    /** 导航栏六个按钮，挤在屏幕左边。 */
    private fun navRow(): List<FocusRect> = (0..5).map { index ->
        val left=150f+index*150f
        rect("nav$index",left,left+120f,20f,80f)
    }

    /** 宽屏下并进导航栏右端的片库按钮。 */
    private fun typeRow(): List<FocusRect> = listOf(rect("type0",1500f,1620f,20f,80f),rect("type1",1640f,1760f,20f,80f),rect("type2",1780f,1900f,20f,80f))

    /** 搜索页那一行：输入框很宽，右边跟着三个按钮。 */
    private fun searchRow(y0:Float=100f,y1:Float=160f): List<FocusRect> = listOf(
        rect("input",0f,1400f,y0,y1),rect("go",1400f,1500f,y0,y1),
        rect("history",1500f,1660f,y0,y1),rect("phone",1660f,1860f,y0,y1))

    /** 翻页那一行是居中的，不在屏幕边上。 */
    private fun pagingRow(y0:Float=1010f,y1:Float=1060f): List<FocusRect> = listOf(
        rect("prev",700f,820f,y0,y1),rect("jump",820f,1100f,y0,y1),rect("next",1100f,1220f,y0,y1))

    /** 首页：导航 + 两条货架 + 页脚。 */
    private fun homeScreen(): List<FocusRect> = navRow()+listOf(
        rect("resume0",0f,280f,150f,450f),rect("resume1",310f,590f,150f,450f),rect("resume2",620f,900f,150f,450f)
    )+posterRow("hot",0,470f,770f)+listOf(
        rect("refresh",0f,1920f,800f,860f),rect("more",0f,1920f,870f,930f))

    /** 目录页：导航 + 片库 + 三行海报 + 翻页。 */
    private fun catalogScreen(): List<FocusRect> = navRow()+typeRow()+
        posterRow("card",0,180f,480f)+posterRow("card",1,490f,790f)+posterRow("card",2,800f,1000f)+pagingRow()

    /** 搜索页：导航 + 搜索行 + 三行海报 + 翻页。 */
    private fun searchScreen(): List<FocusRect> = navRow()+typeRow()+searchRow()+
        posterRow("card",0,200f,480f)+posterRow("card",1,490f,790f)+posterRow("card",2,800f,1000f)+pagingRow()

    /** 详情页：操作行 + 分组行 + 两行剧集 + 一张季度卡。 */
    private fun detailScreen(): List<FocusRect> = listOf(
        rect("play",0f,200f,0f,50f),rect("fav",200f,380f,0f,50f),rect("synopsis",380f,580f,0f,50f),
        rect("grpPrev",0f,200f,60f,110f),rect("grpLabel",200f,390f,60f,110f),
        rect("grpNext",390f,560f,60f,110f),rect("grpJump",560f,750f,60f,110f)
    )+(0..9).map { rect("ep0-$it",(it*150).toFloat(),(it*150+150).toFloat(),120f,170f) }+
        (0..9).map { rect("ep1-$it",(it*150).toFloat(),(it*150+150).toFloat(),180f,230f) }+
        listOf(rect("season",0f,1920f,260f,340f))

    /** 选集面板：两个按钮行 + 每行五格的剧集网格。 */
    private fun episodePanel(): List<FocusRect> = listOf(
        rect("close",0f,200f,0f,60f),rect("jump",200f,420f,0f,60f),
        rect("prev",0f,200f,70f,130f),rect("next",200f,420f,70f,130f)
    )+(0..4).map { rect("cell0-$it",(it*100).toFloat(),(it*100+100).toFloat(),140f,200f) }+
        (0..4).map { rect("cell1-$it",(it*100).toFloat(),(it*100+100).toFloat(),210f,270f) }

    /** 设置页：左右两列，每列四个按钮。 */
    private fun settingsScreen(): List<FocusRect> = navRow()+(0..3).flatMap { index ->
        val top=100f+index*60f
        listOf(rect("left$index",0f,700f,top,top+50f),rect("right$index",800f,1500f,top,top+50f))
    }

    // ---------------------------------------------------------------- 分组

    @Test fun itemsOnTheSameLineFormOneBand() {
        val bands=FocusPlan.bands(navRow())
        assertEquals(1,bands.size)
        assertEquals(6,bands[0].size)
    }

    @Test fun rowsOfPostersFormOneBandEach() {
        val bands=FocusPlan.bands(catalogScreen())
        // 导航+片库一行，三行海报，翻页一行。
        assertEquals(5,bands.size)
        assertEquals(9,bands[0].size)
        assertEquals(6,bands[1].size)
    }

    @Test fun twoColumnsOfSettingsFormFourBandsOfTwo() {
        val bands=FocusPlan.bands(settingsScreen())
        assertEquals(5,bands.size)
        for(index in 1..4) assertEquals(2,bands[index].size)
    }

    @Test fun anItemOfADifferentHeightStillSharesItsRow() {
        // 详情页的操作行里三个按钮一样高，但设置页的标题比按钮高 —— 高矮不一的一排
        // 仍要算作同一行，否则「上」会跳到标题上面去。
        val bands=FocusPlan.bands(listOf(
            rect("short",0f,200f,100f,150f),rect("tall",200f,400f,100f,220f),rect("short2",400f,600f,100f,150f)))
        assertEquals(1,bands.size)
        assertEquals(3,bands[0].size)
    }

    @Test fun theGraphDoesNotDependOnTheOrderOfTheItems() {
        val screen=catalogScreen()
        val straight=FocusPlan.build(screen)
        val shuffled=FocusPlan.build(screen.reversed())
        for(key in straight.keys) {
            assertEquals("$key 的上",straight.target(key,FocusDir.UP),shuffled.target(key,FocusDir.UP))
            assertEquals("$key 的下",straight.target(key,FocusDir.DOWN),shuffled.target(key,FocusDir.DOWN))
            assertEquals("$key 的左",straight.target(key,FocusDir.LEFT),shuffled.target(key,FocusDir.LEFT))
            assertEquals("$key 的右",straight.target(key,FocusDir.RIGHT),shuffled.target(key,FocusDir.RIGHT))
        }
    }

    // ---------------------------------------------------------------- 左右

    @Test fun theLeftEdgeWrapsToTheRightEndInsteadOfSticking() {
        val plan=FocusPlan.build(catalogScreen())
        // 以前第一列按左，方向键指回自己：按了没反应。
        assertEquals("card0-5",plan.target("card0-0",FocusDir.LEFT))
    }

    @Test fun theRightEdgeWrapsToTheLeftEnd() {
        val plan=FocusPlan.build(catalogScreen())
        assertEquals("card0-0",plan.target("card0-5",FocusDir.RIGHT))
    }

    @Test fun movingRightGoesToTheNeighbourNotTheNearest() {
        val plan=FocusPlan.build(catalogScreen())
        assertEquals("card0-1",plan.target("card0-0",FocusDir.RIGHT))
        assertEquals("card0-4",plan.target("card0-5",FocusDir.LEFT))
    }

    @Test fun aSingleItemBandHasNoHorizontalTarget() {
        val plan=FocusPlan.build(listOf(rect("only",0f,100f,0f,50f)))
        assertNull(plan.target("only",FocusDir.LEFT))
        assertNull(plan.target("only",FocusDir.RIGHT))
    }

    // ---------------------------------------------------------------- 上下

    @Test fun theNavButtonDoesNotJumpToTheFarSideOfTheScreen() {
        val plan=FocusPlan.build(catalogScreen())
        // 以前「首页」按下键落到屏幕最右边的片库按钮 —— 按下标算的，不是按位置算的。
        assertEquals("card0-0",plan.target("nav0",FocusDir.DOWN))
    }

    @Test fun theTypeButtonLandsOnTheCardBelowIt() {
        val plan=FocusPlan.build(catalogScreen())
        assertEquals("card0-5",plan.target("type0",FocusDir.DOWN))
    }

    @Test fun upFromTheLastCardLandsOnTheControlAboveIt() {
        val plan=FocusPlan.build(catalogScreen())
        // 以前从海报按上会跳到屏幕另一头的工具栏。
        assertEquals("type1",plan.target("card0-5",FocusDir.UP))
    }

    @Test fun upFromTheFirstCardLandsOnTheNavButton() {
        val plan=FocusPlan.build(catalogScreen())
        assertEquals("nav0",plan.target("card0-0",FocusDir.UP))
    }

    @Test fun downKeepsTheColumn() {
        val plan=FocusPlan.build(catalogScreen())
        assertEquals("card1-3",plan.target("card0-3",FocusDir.DOWN))
        assertEquals("card0-3",plan.target("card1-3",FocusDir.UP))
    }

    @Test fun theLastRowFallsOntoThePagingRow() {
        val plan=FocusPlan.build(catalogScreen())
        // 翻页那一行是居中的，所以从中间那列按下落在离它最近的按钮上，从最右那列落在「下一页」。
        assertEquals("prev",plan.target("card2-2",FocusDir.DOWN))
        assertEquals("next",plan.target("card2-5",FocusDir.DOWN))
    }

    // ---------------------------------------------------------------- 搜索页

    @Test fun thePhoneButtonLandsUnderItselfNotOnTheFirstCard() {
        val plan=FocusPlan.build(searchScreen())
        // 以前「手机输入」按下键落到最左边的第一张卡。
        assertEquals("card0-5",plan.target("phone",FocusDir.DOWN))
    }

    @Test fun theHistoryButtonLandsUnderItself() {
        val plan=FocusPlan.build(searchScreen())
        assertEquals("card0-5",plan.target("history",FocusDir.DOWN))
    }

    @Test fun theSearchButtonLandsUnderItself() {
        val plan=FocusPlan.build(searchScreen())
        assertEquals("card0-4",plan.target("go",FocusDir.DOWN))
    }

    @Test fun upFromACardLandsOnTheSearchRowNotOnTheFarLeftInput() {
        val plan=FocusPlan.build(searchScreen())
        assertEquals("phone",plan.target("card0-5",FocusDir.UP))
    }

    @Test fun theSearchRowIsReachableFromTheNav() {
        val plan=FocusPlan.build(searchScreen())
        assertEquals("input",plan.target("nav0",FocusDir.DOWN))
    }

    // ---------------------------------------------------------------- 首页

    @Test fun shelvesLineUpByColumn() {
        val plan=FocusPlan.build(homeScreen())
        assertEquals("hot0-0",plan.target("resume0",FocusDir.DOWN))
        assertEquals("resume0",plan.target("hot0-0",FocusDir.UP))
    }

    @Test fun theFooterIsReachableFromTheLastShelf() {
        val plan=FocusPlan.build(homeScreen())
        assertEquals("refresh",plan.target("hot0-5",FocusDir.DOWN))
        assertEquals("more",plan.target("refresh",FocusDir.DOWN))
        assertEquals("refresh",plan.target("more",FocusDir.UP))
    }

    // ---------------------------------------------------------------- 详情页

    @Test fun theEpisodeGridLinesUpByColumn() {
        val plan=FocusPlan.build(detailScreen())
        assertEquals("ep1-7",plan.target("ep0-7",FocusDir.DOWN))
    }

    @Test fun upFromTheFirstEpisodeLandsOnTheGroupRow() {
        val plan=FocusPlan.build(detailScreen())
        assertEquals("grpPrev",plan.target("ep0-0",FocusDir.UP))
    }

    @Test fun theLastEpisodeRowFallsOntoTheSeasonCard() {
        val plan=FocusPlan.build(detailScreen())
        assertEquals("season",plan.target("ep1-3",FocusDir.DOWN))
    }

    @Test fun theGroupRowFallsBackOntoTheActionRow() {
        val plan=FocusPlan.build(detailScreen())
        assertEquals("play",plan.target("grpPrev",FocusDir.UP))
    }

    // ---------------------------------------------------------------- 选集面板

    @Test fun theEpisodePanelTopRowGoesUpToThePagingRow() {
        val plan=FocusPlan.build(episodePanel())
        assertEquals("prev",plan.target("cell0-0",FocusDir.UP))
    }

    @Test fun theEpisodePanelDoesNotSendTheThirdCellToThePreviousGroup() {
        val plan=FocusPlan.build(episodePanel())
        // 以前是数下标：0—2 去「上一组」，3—4 去「下一组」，纯属胡来。
        assertEquals("next",plan.target("cell0-2",FocusDir.UP))
        assertEquals("next",plan.target("cell0-4",FocusDir.UP))
    }

    @Test fun theEpisodePanelRowsLineUpByColumn() {
        val plan=FocusPlan.build(episodePanel())
        assertEquals("cell1-3",plan.target("cell0-3",FocusDir.DOWN))
    }

    // ---------------------------------------------------------------- 不变量

    @Test fun nothingLocksItselfUnlessThereIsNowhereElseToGo() {
        val screens=listOf(
            "首页" to homeScreen(),"目录" to catalogScreen(),"搜索" to searchScreen(),
            "详情" to detailScreen(),"选集面板" to episodePanel(),"设置" to settingsScreen())
        for((name,screen) in screens) {
            val plan=FocusPlan.build(screen)
            plan.selfLoops().forEach { (key,dir) ->
                val current=screen.first { it.key==key }
                val elsewhere=screen.filter { it.key!=key }
                val somewhereToGo=when(dir) {
                    FocusDir.UP -> elsewhere.any { it.centerY<current.centerY }
                    FocusDir.DOWN -> elsewhere.any { it.centerY>current.centerY }
                    else -> true
                }
                assertTrue("$name 里 $key 的 $dir 把自己锁死了，而别处明明有可去的项",!somewhereToGo)
            }
        }
    }

    @Test fun theTopAndBottomRowsStayPutWhenThereIsNowhereElseToGo() {
        val plan=FocusPlan.build(catalogScreen())
        // 最上面一行按上、最下面一行按下：留在原地，而且要写明是自锁（不是悬空）。
        assertEquals("nav0",plan.target("nav0",FocusDir.UP))
        assertEquals("next",plan.target("next",FocusDir.DOWN))
    }

    @Test fun everyTargetPointsAtSomethingThatExists() {
        val screens=listOf(homeScreen(),catalogScreen(),searchScreen(),detailScreen(),episodePanel(),settingsScreen())
        for(screen in screens) {
            val plan=FocusPlan.build(screen)
            assertTrue("有指向空处的落点：${plan.dangling()}",plan.dangling().isEmpty())
        }
    }

    @Test fun everyItemCanBeReachedFromTheTop() {
        val screens=listOf(homeScreen(),catalogScreen(),searchScreen(),detailScreen(),episodePanel(),settingsScreen())
        for(screen in screens) {
            val plan=FocusPlan.build(screen)
            val start=plan.bands.first().first().key
            val reached=plan.reachable(start)
            assertTrue("从 $start 走不到全部项，差：${plan.keys-reached}",reached.containsAll(plan.keys))
        }
    }

    @Test fun anEmptyScreenHasNoPlan() {
        val plan=FocusPlan.build(emptyList())
        assertTrue(plan.keys.isEmpty())
        assertTrue(plan.bands.isEmpty())
    }

    @Test fun aSingleItemScreenKeepsItselfInReach() {
        val plan=FocusPlan.build(listOf(rect("only",0f,100f,0f,50f)))
        assertNotNull(plan.targets("only"))
        assertEquals(setOf("only"),plan.reachable("only"))
    }
}
