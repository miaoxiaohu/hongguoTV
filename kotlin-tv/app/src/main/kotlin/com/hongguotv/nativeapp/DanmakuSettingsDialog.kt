// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.core.DanmakuSettingRow

/**
 * 弹幕设置面板。
 *
 * 一个对话框里竖着摆五行：开关、大小、行数、数量、透明度。每行显示当前值，
 * 确认键就地展开这一行的候选值；选完立刻生效、行上的文字当场更新 —— 面板不关，
 * 这样五项的当前值一直都在眼前，不必反复进出。
 *
 * 面板高度封顶：总高不超过屏高的 90%，超出的部分在面板内部滚。320dpi 的 720p 电视上，
 * 展开「弹幕行数」那一行（5 个候选）时内容比屏还高，不滚就会被裁掉 ——
 * 而焦点图是按布局后的真实位置算的，被裁掉的候选仍留在图里，焦点能走到屏幕外，
 * 用户只看到「高亮凭空消失」。
 *
 * 焦点走 [FocusRouter]：面板是另一个窗口，坐标得在这个窗口里算，混进主屏那张图里落点会指到别处去。
 *
 * 契约：[apply] 必须返回与传入 rows **等长、同序** 的新列表 —— 面板按下标定位主行和候选，
 * 顺序变了就会张冠李戴。面板只在下标上兜底，不校验长度和顺序。
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
    /** 面板内容根。整个生命周期只有这一个，重画只换里面的行。 */
    private val box=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(dp(16),dp(16),dp(16),dp(16))
        setBackgroundColor(TvStyle.background)
    }
    /** 标题留在滚动区外面：面板再高，标题也一直看得见。 */
    private val title=TextView(activity).apply {
        text="弹幕设置"; textSize=22f; setTextColor(TvStyle.text); setPadding(dp(6),dp(2),dp(6),dp(12))
    }
    /** 主行和候选都在这一列里，超出就靠外面的 ScrollView 滚。 */
    private val list=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private val scroll=ScrollView(activity).apply { isVerticalScrollBarEnabled=false }
    /** 主行（不含候选）。重画时重建。 */
    private var mains: List<TextView> = emptyList()
    /** 上次定下的滚动区高度。同值不重设，免得布局来回抖。 */
    private var scrollHeight=-1
    /** 布局完成后要把焦点放到哪儿。取不到就留着，下一轮布局再试。 */
    private var pendingFocus: View?=null
    /** closed 只报一次。 */
    private var closedSent=false
    /** 全局布局监听：只挂一个，挂在窗口的 decor 上。 */
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener?=null
    private var layoutObserver: ViewTreeObserver?=null
    /** 宿主生命周期监听挂上了没有。 */
    private var lifecycleRegistered=false
    private val mainHandler=Handler(Looper.getMainLooper())

    /**
     * 宿主 Activity 停了就把面板收掉。
     *
     * 面板是独立的 Dialog 窗口，不走 TvTools —— MainActivity.onStop() 里的 tvTools.close() 关不掉它。
     * 面板开着时按 HOME / 切应用 / 熄屏，Dialog 的窗口会留着，可能报 WindowLeaked。
     *
     * 只在 onActivityStopped 收：onActivityPaused 时（半透明覆盖层、拉通知栏）宿主还看得见，
     * 这时候把面板关了莫名其妙。Activity 销毁前一定先 stop，所以这一条就够。
     */
    private val lifecycle=object: Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(host: Activity,savedInstanceState: Bundle?)=Unit
        override fun onActivityStarted(host: Activity)=Unit
        override fun onActivityResumed(host: Activity)=Unit
        override fun onActivityPaused(host: Activity)=Unit
        override fun onActivityStopped(host: Activity) { stopWith(host) }
        override fun onActivitySaveInstanceState(host: Activity,outState: Bundle)=Unit
        override fun onActivityDestroyed(host: Activity) { stopWith(host) }
    }

    /**
     * 宿主停了 → 收面板。
     *
     * 收的动作挪到下一轮消息循环：反注册要动 Application 的回调列表，
     * 而此刻正是在遍历那个列表 —— 遍历中删元素会越界。
     */
    private fun stopWith(host: Activity) {
        if(host!==activity) return
        mainHandler.post { dismiss() }
    }

    init {
        box.addView(title)
        scroll.addView(list)
        box.addView(scroll,LinearLayout.LayoutParams(-1,0))
        // closed 在构造时就挂上：调用方先 dismiss 后 show、或者构造完没 show 就 dismiss，
        // 系统都不会走 onDismiss，closed() 得自己补一次。
        dialog.setOnDismissListener { notifyClosed() }
    }

    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()

    /** 面板宽度。窗口和内容测量用的是同一个值。 */
    private fun panelWidth()=minOf(dp(560),(activity.resources.displayMetrics.widthPixels*.72f).toInt())

    /** 面板总高的上限：屏高的 90%。 */
    private fun maxPanelHeight()=(activity.resources.displayMetrics.heightPixels*.9f).toInt()

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
        // 行高给死一个数：密度不同行高就不同，没有准数就估不出面板该有多高。
        minHeight=dp(44)
        isFocusable=true; isFocusableInTouchMode=true
        paint(this,false,active)
        setOnFocusChangeListener { _,hasFocus -> paint(this,hasFocus,active) }
        setOnClickListener { action() }
    }

    /** 画一遍面板。展开状态变了就整体重画 —— 比手动增删 view 干净。 */
    private fun render() {
        focusRouter.reset()
        list.removeAllViews()
        val mainRows=mutableListOf<TextView>()
        val optionViews=mutableListOf<TextView>()
        rows.forEachIndexed { index,row ->
            val line=item("${row.title}  ·  ${row.value}",false) {
                // 再按一次同一行 = 收起。
                if(expanded==index) { expanded=null; focusRow=index; focusOption=null }
                else { expanded=index; focusRow=index; focusOption=0 }
                render()
            }
            mainRows+=line
            list.addView(line,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(4) })
            if(expanded==index) {
                row.options.forEachIndexed { optionIndex,option ->
                    // 当前项用 selectedIndex 认，不用「文案相等」认：大小那行的当前值是
                    // 「标准」，候选串却是「标准 · 100%」，比字符串对不上。
                    val choice=item(option,optionIndex==row.selectedIndex) {
                        // apply 要落盘、要全量重排弹幕，是最容易抛异常的地方：
                        // 抛了就当这个候选没选成，保留旧值，别把整个面板带崩。
                        rows=runCatching { apply(index,optionIndex) }.getOrElse { rows }
                        expanded=null
                        focusRow=index
                        focusOption=null
                        render()
                    }
                    optionViews+=choice
                    list.addView(choice,LinearLayout.LayoutParams(-1,-2).apply {
                        leftMargin=dp(34); bottomMargin=dp(2)
                    })
                }
            }
        }
        mains=mainRows
        // apply 回来的列表可能比原来短：焦点行先夹到合法范围，再取不到就退到第一行 ——
        // 面板里一个能接焦点的 view 都没有的话，用户按什么键都像没反应。
        focusRow=focusRow.coerceIn(0,(rows.size-1).coerceAtLeast(0))
        pendingFocus=focusOption?.let { optionViews.getOrNull(it) } ?: mainRows.getOrNull(focusRow) ?: mainRows.firstOrNull()
        applyScrollHeight()
    }

    /**
     * 定下滚动区的高度：内容装得下就照内容高，装不下就截到上限，剩下的在面板里滚。
     *
     * 高度先手动量一遍再交给布局：等第一次布局完再改高度，中间那一帧面板是空的。
     */
    private fun applyScrollHeight() {
        val inner=(panelWidth()-box.paddingLeft-box.paddingRight).coerceAtLeast(0)
        list.measure(View.MeasureSpec.makeMeasureSpec(inner,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        title.measure(View.MeasureSpec.makeMeasureSpec(inner,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        setScrollHeight(scrollHeightFor(list.measuredHeight,title.measuredHeight))
    }

    /** 滚动区能有多高：上限减去标题和内边距，剩下的才是行和候选的地方。 */
    private fun scrollHeightFor(contentHeight: Int,titleHeight: Int)=
        contentHeight.coerceAtMost((maxPanelHeight()-box.paddingTop-box.paddingBottom-titleHeight).coerceAtLeast(0))

    /** 设滚动区高度。同值不重设 —— 改了高度就会再触发一次布局，来回改就抖了。 */
    private fun setScrollHeight(height: Int) {
        if(height==scrollHeight) return
        scrollHeight=height
        val lp=scroll.layoutParams
        if(lp==null) scroll.layoutParams=LinearLayout.LayoutParams(-1,height)
        else { lp.height=height; scroll.requestLayout() }
    }

    /** 布局完成：按实测高度纠正滚动区、重算焦点图、把焦点还给该落的那一行。 */
    private fun onPanelLayout() {
        // 面板已经关掉就别再动它的 view：布局回调可能比 dismiss 晚到一步。
        if(!dialog.isShowing) return
        // 手动量的时候宽度是按面板宽算的，真布局差一点就以实测为准。
        setScrollHeight(scrollHeightFor(list.measuredHeight,title.measuredHeight))
        focusRouter.apply(box)
        // 焦点得等布局完再给：刚 setContentView 的那一瞬间还没有宽高，requestFocus 不作数。
        val want=pendingFocus
        if(want!=null) {
            // 拿到了就销账；拿不到就留着，下一轮布局再试，同时先退到第一行 ——
            // 面板里总得有一行接得住焦点。
            if(want.requestFocus()) pendingFocus=null
            else mains.firstOrNull()?.requestFocus()
        } else if(box.findFocus()==null) mains.firstOrNull()?.requestFocus()
    }

    /**
     * 全局布局监听只挂一个，挂在窗口的 decor 上，指向当前的 [box]。
     *
     * 挂在每一层内容上会越攒越多：AOSP 里 view 已 attach 时 getViewTreeObserver() 返回的是
     * 根观察者，而 dispatchDetachedFromWindow 不做 unmerge —— 每开一次面板就多一个常驻监听，
     * 每次窗口任意 layout 都拿已经 detach 的旧树再算一遍焦点图。
     */
    private fun hookLayout() {
        val decor=dialog.window?.decorView ?: return
        val observer=decor.viewTreeObserver
        layoutListener?.let { layoutObserver?.removeOnGlobalLayoutListener(it) }
        val listener=object: ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() { onPanelLayout() }
        }
        layoutObserver=observer
        layoutListener=listener
        observer.addOnGlobalLayoutListener(listener)
    }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // 面板外点一下不该把整个面板关掉 —— 连展开的候选一起丢。
        dialog.setCanceledOnTouchOutside(false)
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
        dialog.setContentView(box)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            setLayout(panelWidth(),LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        hookLayout()
        // 宿主一停就收面板（见 lifecycle）：挂上就得记得摘，摘在 notifyClosed 里。
        if(!lifecycleRegistered) {
            activity.application.registerActivityLifecycleCallbacks(lifecycle)
            lifecycleRegistered=true
        }
    }

    fun dismiss() {
        val showing=dialog.isShowing
        dialog.dismiss()
        // 没 show 就 dismiss 的话系统不走 onDismiss，closed() 得自己补一次。
        if(!showing) notifyClosed()
    }

    private fun notifyClosed() {
        if(closedSent) return
        closedSent=true
        // 面板没了就别再听它的布局：晚到的回调只会去动已经 detach 的 view。
        layoutListener?.let { layoutObserver?.removeOnGlobalLayoutListener(it) }
        layoutListener=null
        layoutObserver=null
        // 生命周期监听也得摘：Application 一直活着，留着就把面板和 Activity 一起拖住了。
        if(lifecycleRegistered) {
            activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
            lifecycleRegistered=false
        }
        closed()
    }
}
