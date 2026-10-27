// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    /**
     * 导航标签的编号。常量编号 = 在 [TAB_LABELS] 里的下标，两边必须一起改。
     *
     * 早先这些编号是散在二十多处判断里的裸数字，换个顺序要逐处 +1，漏一处就是某个标签页悄悄
     * 长出另一个标签页的行为 —— 排行榜长出剧集进度、收藏页开始显示热度，都是这么来的。
     */
    companion object {
        const val TAB_HOME=Paging.HOME
        const val TAB_SEARCH=Paging.SEARCH
        const val TAB_RANKING=Paging.RANKING
        const val TAB_FAVORITES=Paging.FAVORITES
        const val TAB_HISTORY=Paging.HISTORY
        const val TAB_SETTINGS=Paging.SETTINGS
        val TAB_LABELS=listOf("首页","搜索","排行榜","收藏","最近观看","设置")
    }
    private val bg=TvStyle.background; private val surface=TvStyle.surface
    private val accent=TvStyle.accent; private val white=TvStyle.text; private val muted=TvStyle.muted
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val repository=ContentRepository()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    private val pageWorker=Executors.newSingleThreadExecutor()
    /**
     * 一次备集的活：自己的期限、自己的句柄、自己的取消。
     *
     * 备集有两种：用户马上要看的那一集，和本集播着的时候备的下一集。**两种不能互相取消** ——
     * 用户按下播放的时候，下一集那份还早得很；本集播完要换下一集的时候，页面那份早就没人要了。
     */
    private inner class Preparation {
        var scope: RequestScope?=null
        var job: java.util.concurrent.Future<*>?=null
        var watchdog: Runnable?=null
        /**
         * 这一份在备哪一集，以及它已经问到的地址。
         *
         * 备集真正省下的是取地址那几波并发请求 —— 而用户常常就在这一份还在预热那一兆的时候
         * 按下播放。那时最不该做的是把它停掉、再从头问一遍地址：地址它已经问到了，取走就是。
         *
         * 地址和「它属于哪一集」锁在同一个对象里、换一集就换一个对象，所以取的人不可能拿到
         * 上一集的地址：备第二集的那份覆盖不了第一集刚问到的地址，只会把它换掉。
         */
        @Volatile var resolved: Resolved?=null
        fun cancel() {
            watchdog?.let { main.removeCallbacks(it) }; watchdog=null
            scope?.cancel(); scope=null
            job?.cancel(true); job=null
            resolved=null
        }
    }
    /** 一集备到一半的进展：哪一集，以及问到的地址。 */
    private class Resolved(val key: String) { @Volatile var address: StreamInfo?=null }
    /** 用户马上要看的那一集的备集（首页「接着看」、详情页）。 */
    private val pagePreparation=Preparation()
    /** 本集播着的时候备下一集。 */
    private val nextPreparation=Preparation()
    /**
     * 本集播着的时候备下的下一集。
     *
     * 备好的是流本身：它带着地址、首包和解好的索引，播的时候不用再问一遍地址。
     */
    private val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() },PrefetchPolicy.TTL_MS)
    /**
     * 用户马上要看的那一集：首页「接着看」或者详情页刚打开的时候备下的。
     *
     * 它和 [preparedNext] 分开放，因为两件事会同时发生：详情页在备着用户要按的那一集，
     * 本集播着的时候又在备下一集。
     */
    private val preparedPage=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() },PrefetchPolicy.TTL_MS)
    private var readySince=0L
    private lateinit var artwork: ArtworkLoader
    private data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    private var deferredWork: DeferredUi?=null
    private var initialCatalogPending=true
    private var restoreStoppedPage=false
    private var pendingDetail: Series?=null
    private var detailAutoplay=true
    private var libraryLoadError: Throwable?=null
    private var workScope: RequestScope?=null
    private var workJob: java.util.concurrent.Future<*>?=null
    private var playerListener: Player.Listener?=null
    private lateinit var playbackTitle: TextView
    private var directPlayback=false
    private var collection: String?=null
    private var collectionReturn: CatalogState?=null
    private var collectionFocus=""
    private var collectionBack: View?=null
    /**
     * 一行几张。首页、搜索、榜单、收藏共用，页大小按它对齐。
     *
     * 声明在 [pageSize] 之前是有原因的：属性按源码顺序初始化，反过来写编译器会拒绝。
     */
    private val GRID_COLUMNS=6
    /**
     * 每页 18 部，不是 20。
     *
     * 网格一行摆 [GRID_COLUMNS] 张，页大小必须是它的整数倍，否则每一页都会收尾在半排上 —— 20 除以 6
     * 余 2，于是每一页都是「三整行加两张」，右边的空档比偶尔出现的还频繁。18 = 6 x 3，三行整。
     */
    private val pageSize=GRID_COLUMNS*3
    /**
     * 这一页有多少张。
     *
     * 搜索页的页长是 core 给的 —— [ContentRepository.SEARCH_PAGE_SIZE]，一页 30 条；别的页是
     * 网格自己定的 18。之前页数器、翻页时还原焦点的那段、还有「下一页」的门各按各的算，搜索页
     * 于是翻到第 14 页以后就再也翻不动，而页码选择器还能跳到 35 页 —— 跳过去是一张空白页。
     */
    private fun pageSizeFor(which: Int)=if(which==TAB_SEARCH) ContentRepository.SEARCH_PAGE_SIZE else pageSize
    /**
     * 榜单一页 36 部：六行六列。
     *
     * 榜单是一屏一屏地看的，比浏览列表的三行多 —— 之前一页 40 张（站方一页给多少就画多少），
     * 18 张显得太少。而且 36 是 6 的整数倍，网格不会收尾在半排上。
     */
    private val rankingPageSize=GRID_COLUMNS*6
    private var catalogScroll: ScrollView?=null
    private var catalogBody: LinearLayout?=null
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private val catalogCards=linkedMapOf<String,View>()
    private val cardImages=linkedMapOf<String,Pair<ImageView,String>>()
    private var prefetchAttemptAt=0L
    private var prefetchAttempts=0
    private var homeScreen: HomeScreen?=null
    private var selectionPreview: SeriesPreview?=null
    private var homeFromCache=false
    private var homeUpdateCount=0
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var tvTools: TvTools
    private lateinit var updater: AppUpdater
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    /** 画在画面上的弹幕；没有它播放器就只是一段视频。 */
    private var danmakuOverlay: DanmakuOverlay?=null
    private val danmakuSession=DanmakuSession()
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteStatus: TextView?=null
    private var favoriteCheck: TextView?=null
    private var episodePanel: EpisodePanel?=null
    private val recovery=RecoveryBudget()
    private var retryPending=false
    private var autoRecovery=false
    private var startPosition=0L
    private var retryPosition=0L
    private var requestedAutoplay=true
    private var retryAutoplay=true
    private var foreground=false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        private fun changed() { main.post { if(connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch(); if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    private val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var generation=0
    /**
     * 让 work 的回调作废的凭据。
     *
     * work() 原来拿 generation 当「我还是当前那个吗」的凭据，可 cancelWork() 并不动 generation ——
     * 于是被取消的那次请求照样把「请求已取消」当成失败报上来：搜索页被清空、换成一张
     * 「暂时无法加载」的错误页，再被下一次请求换回来。取消是 work 自己的事，用自己的编号；
     * generation 留给播放器和选集面板，那两处的 ticket 是在 invalidatePage() 之后取的。
     */
    private val workTicket=WorkTicket()
    /**
     * 这一屏的焦点图。
     *
     * 遥控器往哪儿落由它算：收齐这一屏所有可聚焦的 view，量出各自的位置，再按位置决定
     * 上下左右落到哪儿。落点不再由下标算 —— 下标算出来的落点常常在屏幕另一头。
     */
    private val focusRouter=FocusRouter()
    private var screen="catalog"
    private var tab=0
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var hasMore=false
    /**
     * 搜索结果里真正被关键词命中的条数；null 表示还没搜、或当前不是搜索结果页。
     *
     * 服务端对任何关键词都回十条同类热门剧，从不回空，所以「站内没有这部剧」只能在这里判。
     */
    private var searchMatched:Int?=null
    /** 本次搜索一共命中多少条，用来显示「第 X/Y 页 · 共 N 部」；null 表示还没搜。 */
    private var searchTotal:Int?=null
    /** 结果来自错字容错而非字面匹配，搜索页据此说明「按相近剧名找到」。 */
    private var searchFuzzy=false
    /**
     * 站方那一路（补位用的十条）取不到了。
     *
     * 搜索的主体是本机索引，所以这不影响能不能搜到 —— 但界面得说清楚结果是从哪来的，
     * 更不能在这种时候说「站内没有《X》**：我们根本没问到站方，不能替它下结论。
     *
     * 「取不到」分两种，说的话也不一样：等一等就好的，和等不来的 —— 见 [ServerSearchProblem]。
     */
    private var searchServerProblem=ServerSearchProblem.NONE
    /** 站方那十行是不是没拿到。 */
    private val searchServerFailed get() = searchServerProblem != ServerSearchProblem.NONE
    /** 上次重绘索引进度时已收录的部数，用于把 68 次回调压成每 400 部一次。 */
    private var cataloguePaintMark=0
    private var catalogFocus=""
    /**
     * 当前这一页里焦点卡片的序号（第几张，从 0 起）。
     *
     * 翻页之后卡片 id 会变，但人的眼睛停在同一格。原来靠「按页长切片再取下标」来还原，而
     * 搜索页的页长是 30、这里算的是 18，切出来的下标对第 19 张以后的卡片全是错的 —— 翻页必
     * 丢焦点，而且算出来的新页也是旧页里的。直接记序号就跟页长无关了。
     */
    private var focusColumn:Int?=null
    /**
     * 异步加载期间用户可能用 D-pad 把焦点从 nav/typeButtons 移到 searchRow 里的搜索/历史/手机输入/
     * 续建索引这些按钮。releaseCatalogViews 之后这些 view 引用会清，加载回调拿不回当时焦点，
     * 所以在 showCatalog 入口先记一个 anchor 字符串，加载回来时由 catalogGrid 按它还原焦点。
     * 字符串而不是 view id，是因为 view id 是 View.generateViewId 生成的，新一轮渲染的 id 不同。
     */
    private var keptFocusAnchor: String? = null
    /** 当前详情页里承载「同系列剧」结果的容器；异步回调用它确认自己没画到别的剧上。 */
    private var seasonsHolder: LinearLayout?=null
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: HotRanking?,val totalPages: Int?=null)
    private var ranking: HotRanking?=null
    /**
     * 这一页所在的清单一共有几页，站方说的那个数。
     *
     * 只有首页用得上（搜索的总数在 searchTotal 里，榜单的在 ranking 里，本机清单的自己数）。
     * 首页的页数只有站方知道：它一页给 24 条，34 页，而 App 把这些条画在自己的网格里 ——
     * 从屏幕上的条数去数页数，数出来的是 App 的页数，不是站方的页数。
     */
    private var catalogTotalPages:Int?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    private val typeButtons=mutableMapOf<ContentType,View>()
    private var searchInput: View?=null
    private var searchButton: View?=null
    private val searchActions=mutableListOf<View>()
    private var historyManage: View?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    private var group=0
    private var synopsisExpanded=false
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel=false
    private var transportPlay: TextView?=null
    private var sleepButton: TextView?=null
    private var speedDialog: AlertDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var quality=""
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen=="player") {
                if(sleepTimer.poll()) stopForSleep()
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                maybePrefetch()
                val p=player
                if(p!=null) {
                    // 弹幕：把这一集的时长告诉它（它靠这个知道问到哪里为止），再报一下进度，
                    // 离已加载的末尾近了它就去问下一窗。倍速也要告诉它：2 倍速时视频时间走得快，
                    // 提前量得跟着放大，不然上一窗播完了下一窗还没到。
                    val duration=p.duration
                    if(duration>0) danmakuSession.setDuration(duration)
                    danmakuSession.setSpeed(p.playbackParameters.speed)
                    danmakuSession.onPosition(p.currentPosition)
                }
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    private val hideHud=Runnable { if(screen=="player" && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    private val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) { view.background=rounded(if(focused) TvStyle.raised else if(selected) Color.rgb(61,39,34) else surface,if(focused) accent else Color.TRANSPARENT) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this); artwork=ArtworkLoader(this); tvTools=TvTools(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root)
        // 焦点图跟着布局走：每一次布局落定都重算一次落点。
        // 布局变了（异步内容到了、封面高度改了）落点就得跟着变 —— 只算一次的话，
        // 换页之后按方向键还是会落到上一屏的位置上。
        root.viewTreeObserver.addOnGlobalLayoutListener(object: ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() { if(!isDestroyed) focusRouter.apply(root) }
        })
        updater=AppUpdater(this) { foreground && library.isLoaded && screen=="catalog" && !tvTools.showing }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
        // 弹幕是锦上添花：拉不到就拉不到，绝不能打断播放，也不该在屏幕上留一条错误。
        danmakuSession.onItems={ items -> danmakuOverlay?.setItems(items); if(items.isNotEmpty()) danmakuOverlay?.start() }
        danmakuSession.onFailure={ problem -> android.util.Log.w("HongguoTV","Danmaku unavailable: ${problem.message}") }
        message(base(),"正在读取本机记录…")
        loadLibrary()
    }
    private fun showLibraryError(problem: Throwable) {
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }
    private fun loadLibrary() {
        libraryLoadError=null
        library.loadAsync(onFailure={ problem ->
            if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            if(!isDestroyed && foreground) { showCatalog(load=true); favoriteMonitor.check() }
            startCatalogueBuild()
            // 今天的热播榜先在后台取回来，等用户翻到那一页时不用等。
            refreshRankingInBackground()
        }
    }
    /**
     * Kick off (or skip) the walk that turns the site's ten search rows into the whole library.
     *
     * Runs once per process: the index is cached for a week, and a build already in flight is
     * refused by [Library.buildCatalogue]. It is deliberately not tied to the visible screen, since
     * the first search usually happens long before the walk finishes and should still find it.
     */
    private fun startCatalogueBuild(force: Boolean=false) {
        // A finished index is already on disk. Re-walking 102 serial pages on every launch would buy
        // nothing and keep hammering an endpoint that is already sensitive to call rate.
        //
        // 一份建完又放了一天的索引该重建了：站方天天上新，旧索引搜不到没见过的剧。
        // 但过期不等于作废 —— 重建期间旧的那份照常用着，数字不会掉到 0 再爬回来。
        if(library.isBuildingCatalogue()) return
        if(!force && library.isCatalogueComplete() && !library.isCatalogueStale()) return
        library.buildCatalogue(repository,force) { total ->
            // The walk reports ~68 times. Repainting on every one would rebuild the whole grid on
            // a low-end box, so the notice steps in every 400 shows instead.
            if(total-cataloguePaintMark<400 || isDestroyed) return@buildCatalogue
            cataloguePaintMark=total
            // 索引在长大，搜索结果跟着重画一次，不闪「正在加载」。
            if(tab==TAB_SEARCH && query.isNotBlank()) refreshSearchQuietly() else if(tab==TAB_SEARCH) showCatalog()
        }
    }
    /**
     * 索引构建期间静默重搜当前查询。
     *
     * 用户在索引只有几百部时就搜了，看到的「已收录 N 部」会一路涨到全库；结果却停在半路上，
     * 除非他自己再搜一次。这里后台重查并直接换掉列表 —— 不走 showCatalog(true)，那会先把整页
     * 换成「正在加载…」再跳回来，比不刷新还糟。
     */
    private fun refreshSearchQuietly() {
        val at=tab; val q=query; val p=page; val type=library.contentType
        work({ repository.search(q,p,type,library.catalogue()) }, { result ->
            // 回调可能落在别的页或别的查询上，回来时先确认自己没被顶掉。
            if(isDestroyed || at!=tab || q!=query || p!=page) return@work
            catalog=result.items; hasMore=result.hasMore
            searchMatched=result.matchedCount; searchTotal=result.totalHits; searchFuzzy=result.fuzzy; searchServerProblem=result.serverProblem
            val body=catalogBody ?: return@work
            body.removeAllViews(); catalogGrid(body,nav.any { it.hasFocus() },typeButtons.values.any { it.hasFocus() })
            // 失败就留着上一批结果：用户正在看的东西不该被一次网络抖动清空。
        }, { })
    }
    /**
     * 站方那一路到底怎么了，说法要分开。
     *
     * 「暂时不可用」是等一等就好：网络抖了，站方回了 502，同一个问题过会儿再问就通了。
     * 「用不了」是等不来的：站方拒了这个查询，或者答了一个这个客户端读不懂的形状 ——
     * 等下去站方也不会变回原来的样子。把这两种都说成「暂时」，用户就会一直等一个不会来的结果。
     */
    private fun serverSearchPhrase(): String = when(searchServerProblem) {
        ServerSearchProblem.UNAVAILABLE -> "站方搜索暂时不可用"
        ServerSearchProblem.REJECTED -> "站方搜索用不了"
        ServerSearchProblem.NONE -> ""
    }
    private fun base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column(); val horizontal=dp(widthDp()*.05f); val vertical=(resources.displayMetrics.heightPixels*.05f).toInt()
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        if(title!=null) { val heading=row(); addButton(heading,"‹ 返回") { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD) },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        return container
    }
    private fun loadCover(view: ImageView,url: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
    }
    private fun cancelWork() {
        // 作废上一次 work 的回调：被取消的请求不是失败，不该把「请求已取消」端到页面上。
        workTicket.cancel()
        deferredWork?.discard?.invoke(); deferredWork=null
        workScope?.cancel(); workScope=null
        workJob?.cancel(true); workJob=null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun invalidatePage() {
        generation++; cancelWork(); artwork.cancelPage()
    }
    private fun releaseCatalogViews() {
        homeScreen?.let { homePosition=it.capturePosition() }
        catalogScroll?.let { scrollPositions[catalogPageKey]=it.scrollY }
        catalogBody=null
        homeScreen=null; selectionPreview=null; catalogScroll=null
        nav.clear(); typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear()
        historyManage=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null
        catalogCards.clear(); cardImages.clear()
        // 上一屏的 view 都不在了，焦点图也该重画 —— 否则新旧两屏的落点会串在一起。
        focusRouter.reset()
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        cancelWork()
        val ticket=workTicket.next(); val scope=RequestScope(); workScope=scope
        workJob=io.submit {
            try {
                val result=scope.run(action)
                main.post {
                    val discard={ if(result is RemoteVideo) result.close(); Unit }
                    if(isDestroyed || !workTicket.isCurrent(ticket)) discard()
                    else if(foreground) done(result)
                    else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
                }
            } catch(e: Exception) {
                main.post {
                    if(!isDestroyed && workTicket.isCurrent(ticket)) {
                        if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                    }
                }
            }
        }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }
    /**
     * 哪些标签页的内容来自网络。首页、搜索、排行榜要发请求；收藏和最近观看读本机记录，设置页
     * 什么都不读 —— 对它们调 showCatalog(true) 会白等一次「正在加载…」。
     */
    private fun fetchesRemote(which: Int)=which==TAB_HOME || which==TAB_SEARCH || which==TAB_RANKING
    private fun switchTab(next: Int) {
        leaveCollection()
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking,catalogTotalPages); tabFocus[tab]=catalogFocus
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogTotalPages=saved?.totalPages; catalogFocus=tabFocus[next].orEmpty(); focusColumn=null
        // 搜索页只有真的放过一个词才去请求；空着的搜索框配一张空网格，什么也没说。
        // 从导航按「搜索」进来时焦点交给输入框：那才是这一页要做的事，跳到结果卡片等于让人先
        // 看结果再想办法回去改词。返回详情页时不走这里，焦点仍旧回到上次看的那张卡。
        showCatalog(load=(fetchesRemote(next) && catalog.isEmpty() && (next!=TAB_SEARCH || query.isNotBlank())),focusInput=(next==TAB_SEARCH))
    }
    /**
     * 换档之后，那几页缓存的页码和列表就都是另一套片库的了，全部丢掉。
     *
     * 首页、搜索页和排行榜页都跟着档走 —— 榜单是站方按货架分的榜，所以三页都要清。
     * 这段逻辑原来在「按分类按钮」和「点搜索历史」两处各写了一份，其中一份只清了两页：
     * 症状是排行榜还停着上一个档的榜，而页面上看不出任何异常（榜不会自己标出是哪一档）。
     * 合成一处就不会再漂。
     */
    private fun dropShelfCachedPages() {
        listOf(TAB_HOME,TAB_SEARCH,TAB_RANKING).forEach { tabState.remove(it); tabFocus.remove(it) }
    }
    private fun switchContentType(type: ContentType) {
        if(library.contentType==type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query=it.text.toString().trim() }
        library.contentType=type
        dropShelfCachedPages()
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogTotalPages=null; catalogFocus=""; focusColumn=null; homePosition=null
        // query 可能还是上一次搜索留下的。非空不等于现在在搜索页 —— 在首页上切分类，
        // 按 query 非空去发请求会把搜索结果画到首页上。
        showCatalog(load=(tab==TAB_HOME || tab==TAB_RANKING || (tab==TAB_SEARCH && query.isNotBlank())),focusType=true)
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking; catalogTotalPages=it.totalPages }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking,catalogTotalPages); collectionFocus=catalogFocus
        collection=kind; page=1; catalogFocus=""; focusColumn=null; showCatalog()
    }
    /**
     * 片库按钮能不能塞进导航栏那一行的右上角。
     *
     * 条件写在首页这一处，是为了让「看真人剧还是漫剧」这个开关在首页和搜索页长得一模一样 ——
     * 它是导航的一部分，不是页内的一个控件。
     */
    private fun inlineCatalogueFilters()=(tab==TAB_HOME || tab==TAB_SEARCH || tab==TAB_RANKING) && resources.configuration.fontScale<=1.2f && widthDp()>=900
    /**
     * 把「片库」那一行摆到该去的地方，宽屏就并进导航栏右端，窄屏就自己占一行。
     *
     * [hint] 只在摆不下、需要自己占一行时才跟着写出来 —— 挤进导航栏时导航已经很满，再加一句
     * 说明就是把它撑破。
     */
    private fun placeCatalogueRow(top: LinearLayout,container: LinearLayout,types: LinearLayout,hint: String?=null) {
        if(inlineCatalogueFilters()) {
            top.addView(Space(this),lp(0,1).apply { weight=1f })
            typeButtons.values.forEach { view -> (view as TextView).apply { textSize=12f; minHeight=dp(34); setPadding(dp(12),dp(7),dp(12),dp(7)) } }
            types.setPadding(0,0,0,0); top.addView(types)
        } else {
            hint?.let { types.addView(text(it,12f,muted).apply { setPadding(dp(14),0,0,0) }) }
            container.addView(types)
        }
    }
    private fun localCatalog()=tab==TAB_FAVORITES || tab==TAB_HISTORY || collection!=null
    private fun localItems(): List<Series> = when(collection) {
        "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
        "later" -> library.later()
        "updates" -> library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0)>0 }
        "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
        else -> if(tab==TAB_FAVORITES) library.favorites() else library.history().map { it.series }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false,focusInput: Boolean=false) {
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
        // 从详情页回到目录页：用户没按播放，那一兆不该接着拉。翻页、换标签、刷新热门不走这
        // 条路（那时 screen 已经是 catalog），首页备着的「接着看」那一集因此不会白备。
        if(screen=="detail") cancelPagePrefetch()
        // 异步加载期间用户可能把焦点挪到 searchRow 里的「搜索/历史/手机输入/续建索引」上。
        // releaseCatalogViews 之后这些 view 引用被清，加载回调里再读 hasFocus() 都是 false。
        // 在清引用前先记一个 anchor，加载完成时 catalogGrid 按它把焦点还原到同位置的 view。
        keptFocusAnchor=if(load) {
            when {
                searchInput?.hasFocus()==true -> "searchInput"
                searchButton?.hasFocus()==true -> "searchButton"
                else -> searchActions.indexOfFirst { it.hasFocus() }.takeIf { it>=0 }?.let { "searchAction:$it" }
            }
        } else null
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            val cached=if(tab==TAB_HOME && page==1) library.cachedHome(library.contentType) else null
            catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=null; homeFromCache=cached!=null
        }
        invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        val top=row().apply { setPadding(0,0,0,dp(8)) }
        val brand=row()
        brand.addView(ImageView(this).apply { setImageResource(com.hongguotv.nativeapp.R.mipmap.ic_launcher) },lp(dp(28),dp(28)).apply { rightMargin=dp(9) })
        brand.addView(text("红果",22f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        top.addView(brand,lp(dp(120),dp(44)))
        // 顺序只有 TAB_LABELS 一处说了算；编号是这个列表的下标，见类头的 companion object。
        TAB_LABELS.forEachIndexed { index,label ->
            val item=addButton(top,label,index==tab) { switchTab(index) }
            fun paint(focused: Boolean) {
                item.setTextColor(if(focused) bg else if(index==tab) accent else muted)
                item.background=rounded(if(focused) white else if(index==tab) Color.rgb(48,32,29) else Color.TRANSPARENT)
                item.setTypeface(null,if(focused || index==tab) Typeface.BOLD else Typeface.NORMAL)
            }
            paint(false); item.setOnFocusChangeListener { _,focused -> paint(focused) }; nav+=item
        }
        container.addView(top)
        container.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { bottomMargin=dp(6) })
        if(tab==TAB_SETTINGS) {
            val scroll=ScrollView(this); val body=column(); scroll.addView(body); container.addView(scroll,lp(-1,0).apply { weight=1f })
            settings(body); if(focusNav) nav[tab].requestFocus(); return
        }
        // 分类按钮画在首页、搜索页和排行榜页 —— 三页都是「看哪一档」有意义的地方：
        // 首页的热门、搜索的结果、榜单的内容都跟着档走。收藏和历史读的是本机记录，分不分档
        // 没有意义，所以那两页不画。
        if(tab==TAB_HOME) {
            val types=row().apply { setPadding(0,dp(8),0,dp(6)) }
            if(!inlineCatalogueFilters()) types.addView(text("片库",12f,muted),lp(dp(42),-2))
            ContentType.entries.forEach { type ->
                typeButtons[type]=addButton(types,type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type }
            }
            placeCatalogueRow(top,container,types,"长按确认 · 管理片单")
        }
        if(tab==TAB_SEARCH) {
            val searchRow=row(); val input=EditText(this).apply { id=View.generateViewId(); hint="输入剧名或关键词（当前片库全量）"; setText(query); textSize=16f; setTextColor(white); setHintTextColor(muted); isSingleLine=true; maxLines=1; filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
            searchRow.addView(input,lp(0,dp(45)).apply { weight=1f; rightMargin=dp(10) })
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { input.requestFocus(); return }; runSearch(next) }
            searchInput=input; searchButton=addButton(searchRow,"搜索") { search() }
            // 分类按钮进导航栏右上角，和首页完全同一套。它在搜索页是真的有作用：全库索引记着每部
            // 剧属于哪套目录，而同一个题材词两边都命中 —— 搜「都市」1042 部里 670 部是漫剧，
            // 不分开的话大半结果和用户想要的不是一档。摆法和首页一样，是为了让这个开关在两页
            // 里长得一模一样。
            val types=row().apply { setPadding(0,dp(8),0,dp(6)) }
            if(!inlineCatalogueFilters()) types.addView(text("片库",12f,muted),lp(dp(42),-2))
            ContentType.entries.forEach { type ->
                typeButtons[type]=addButton(types,type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type }
            }
            placeCatalogueRow(top,container,types)
            lateinit var recent: TextView
            recent=addButton(searchRow,"历史") { showSearchHistory(recent) }
            lateinit var phone: TextView
            phone=addButton(searchRow,"手机输入") { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); tvTools.phoneInput(phone) { runSearch(it) } }
            searchActions.addAll(listOf(recent,phone))
            // The walk is normally invisible and automatic, so this only appears once it has fallen
            // behind. Without a lever here the only thing a user can do is restart the app, which is
            // exactly what they did, repeatedly, with no way to tell anything from it.
            if(!library.isBuildingCatalogue() && (!library.isCatalogueComplete() || library.isCatalogueStale())) {
                lateinit var rebuild: TextView
                // 索引没建完是「接着上次」，建完但过期是「重新抓一遍」—— 两件事的代价差很多，
                // 说清楚用户才知道按下去会发生什么。
                val unfinished=!library.isCatalogueComplete()
                rebuild=addButton(searchRow,if(unfinished) "续建索引" else "更新索引") {
                    tvTools.info(if(unfinished) "续建全库索引" else "更新全库索引",
                        if(unfinished) "将从上次中断的页面继续抓取，途中可以照常使用应用。"
                        else "将重新抓一遍全部目录，补上站上新剧。途中可以照常使用应用，搜索照旧可用。",
                        rebuild)
                    startCatalogueBuild(true)
                }
                searchActions.add(rebuild)
            }
            input.setOnEditorActionListener { _,_,_-> search(); true }; container.addView(searchRow)
        } else if(tab==TAB_RANKING) {
            // 榜单跟着片库分档。站方有真人剧榜、漫剧榜和 AI 剧榜，混在一起给等于没分：
            // 用户在真人剧档里翻到的却是漫剧的榜，说的还是「热播榜」三个字。
            val types=row().apply { setPadding(0,dp(8),0,dp(6)) }
            if(!inlineCatalogueFilters()) types.addView(text("片库",12f,muted),lp(dp(42),-2))
            ContentType.entries.forEach { type ->
                typeButtons[type]=addButton(types,type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type }
            }
            placeCatalogueRow(top,container,types)
            // 标题、刷新按钮、站方的更新说明，整行都去掉了：这一页已经用分类按钮说明了是哪一档，
            // 榜单自己会更新（一天一次），用户没有任何需要按的东西。
        } else if(tab>TAB_RANKING) {
            val heading=row().apply { setPadding(0,dp(14),0,dp(6)) }
            heading.addView(text(if(tab==TAB_FAVORITES) "我的收藏" else "接着上次看",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(tab==TAB_FAVORITES) {
                favoriteCheck=addButton(heading,"检查更新") { favoriteMonitor.check(force=true) }
            }
            if(tab==TAB_HISTORY && library.history().isNotEmpty()) {
                historyManage=addButton(heading,"管理记录") {
                    val rows=library.history()
                    tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
                        tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                    }
                }
            }
            container.addView(heading)
            if(tab==TAB_FAVORITES) {
                favoriteStatus=text(favoriteMonitor.status,13f,muted).apply { setPadding(0,0,0,dp(6)) }; container.addView(favoriteStatus)
            }
        }
        val body=column(); catalogBody=body; container.addView(body,lp(-1,0).apply { weight=1f })
        if(localCatalog()) {
            catalog=localItems(); ranking=null; searchMatched=null; searchTotal=null; searchFuzzy=false; searchServerProblem=ServerSearchProblem.NONE; page=page.coerceIn(1,maxOf(1,(catalog.size+pageSizeFor(tab)-1)/pageSizeFor(tab))); hasMore=page*pageSizeFor(tab)<catalog.size
        }
        if(load) {
            if(tab==TAB_RANKING) {
                // 榜单永远先显示手上有的那份 —— 上次取到的，或者刚在后台取到的。新的在后台取，
                // 到货再换上去：用户不用等，也不用按什么。站方的榜一天只更新一次，所以一天取一次
                // 就够了（HotRank.FRESH_MS）。
                // 手上的榜必须是**这一档**的榜：真人剧档和漫剧档是两个榜，画错就是别人的榜。
                val requestType=library.contentType
                val cached=library.cachedRanking(requestType)
                if(cached!=null && cached.items.isNotEmpty()) showRankingPage(cached,body,focusNav,focusType)
                else { message(body,"正在读取最新榜单…"); if(focusType) typeButtons[library.contentType]?.requestFocus() else nav[tab].requestFocus() }
                work({ rankingList(requestType) }, { list ->
                    library.saveRanking(list)
                    showRankingPage(list,body,focusNav,focusType)
                }, { problem ->
                    // 取不到就继续显示手上那份，不把用户正在看的东西清空。
                    if(catalog.isEmpty()) { body.removeAllViews(); error(body,problem) { showCatalog(true) } }
                })
            }
            else {
                // 首页和搜索页共用这一个请求。首页**必须**走这里 —— 它画完「正在加载推荐…」的
                // 网格之后，就靠这次请求把内容换上去；请求不发，这一页会永远停在那句话上。
                if(tab==TAB_HOME) catalogGrid(body,focusNav,focusType,loading=true)
                else { message(body,"正在加载…"); if(focusInput) searchInput?.requestFocus() else if(focusType) typeButtons[library.contentType]?.requestFocus() else nav[tab].requestFocus() }
                val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=library.contentType
                work({
                    val result=when(requestTab) { TAB_HOME -> repository.home(requestPage,requestType); else -> repository.search(requestQuery,requestPage,requestType,library.catalogue()) }
                    result
                }, { result ->
                if(requestTab==TAB_HOME && requestPage==1) library.cacheHome(requestType,result.items,result.hasMore)
                if(requestTab==TAB_HOME && requestPage==1 && (homeFromCache || tvTools.showing || updater.showing)) {
                    homeScreen?.refresh?.text="热门已更新 · 按确认查看"
                    homeScreen?.refresh?.setOnClickListener { catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; catalogTotalPages=result.totalPages; showCatalog() }
                } else {
                    catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; catalogTotalPages=result.totalPages
                    searchMatched=result.matchedCount; searchTotal=result.totalHits; searchFuzzy=result.fuzzy; searchServerProblem=result.serverProblem
                    val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                    body.removeAllViews()
                    // keptFocusAnchor 优先：用户在加载期间用 D-pad 把焦点挪到「历史/手机输入/续建索引」
                    // 上时，加载回来不能被 focusInput/focusNav 等默认值挤掉，否则焦点会跳走。
                    val focusAnchor=keptFocusAnchor ?: if(keepNav) "nav" else if(keepType) "type" else null
                    catalogGrid(body,keepNav || focusNav,keepType || focusType,focusInput=focusInput,focusAnchor=focusAnchor)
                }
            }, { problem ->
                if(requestTab==TAB_HOME && requestPage==1) {
                    homeScreen?.refresh?.text=if(catalog.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                    homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null; catalogTotalPages=null; searchMatched=null; searchServerProblem=ServerSearchProblem.NONE
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                }
            })
            }
        } else catalogGrid(body,focusNav,focusType,focusInput=focusInput)
    }
    /**
     * The hot list the app should be showing, fetched only when there is none worth showing.
     *
     * The site's board changes once a day, so a list fetched today is good for today. That is what
     * makes the refresh invisible: the page always has something to show, and the day's list is
     * already there when the user asks for it.
     */
    private fun rankingList(type: ContentType): HotRank.HotList {
        // 手上那份只在这一档自己身上算数：另一档的榜再新也不是这一档的榜。
        library.freshRanking(type)?.let { return it }
        return repository.rankingList(type)
    }

    /**
     * Draws the ranking page from a hot list - one fetched now, or one from an earlier launch.
     *
     * The focus is not taken from the user: a list that refreshes by itself must not move the
     * card they were looking at out from under the remote.
     */
    private fun showRankingPage(list: HotRank.HotList, body: LinearLayout, focusNav: Boolean, focusType: Boolean) {
        val shown=HotRank.page(list,page,rankingPageSize)
        catalog=shown.items; hasMore=shown.hasMore; ranking=shown.ranking
        val anchor=when {
            nav.any { it.hasFocus() } -> "nav"
            typeButtons.values.any { it.hasFocus() } -> "type"
            else -> null
        }
        body.removeAllViews()
        catalogGrid(body,focusNav,focusType,focusAnchor=anchor)
    }

    /**
     * Fetches the day's hot list in the background, so the ranking page has it before it is asked
     * for.
     *
     * It runs on the shared pool rather than through [work]: work() cancels whatever was running,
     * and this must not cancel the page the user is on.
     */
    private fun refreshRankingInBackground() {
        if(library.freshRanking(library.contentType)!=null) return
        io.submit {
            runCatching { repository.rankingList(library.contentType) }
                .onSuccess { list -> main.post { if(!isDestroyed) library.saveRanking(list) } }
        }
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false,focusInput: Boolean=false,focusAnchor: String?=null) {        if(tab==TAB_HOME && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading); return }
        catalogCards.clear(); cardImages.clear(); collectionBack=null
        if(collection!=null) {
            val heading=row()
            collectionBack=addButton(heading,"‹ 返回首页") { leaveCollection(); showCatalog() }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "hot" -> "热门发现"; else -> "接着看" },22f))
            body.addView(heading)
        }
        catalogPageKey="$tab:${collection.orEmpty()}:$page:$query"
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val scroll=ScrollView(this).apply { isFillViewport=false; isVerticalScrollBarEnabled=false; clipToPadding=false }; catalogScroll=scroll
        val list=column(); scroll.addView(list); body.addView(scroll,lp(-1,0).apply { weight=1f })
        var restoringScroll=true
        // 榜单的一页有多少行就画多少行：真人剧档 20 行，漫剧档是两张榜合成后的 40 行。
        // 那个 take(30) 是搜索页的一页（30 条搜索结果），套到榜单上会把第 31 到 40 行永远
        // 藏起来 —— 名次是连续的，看得见的和看不见的之间会凭空少掉一截。
        val displayed=if(localCatalog()) catalog.drop((page-1)*pageSizeFor(tab)).take(pageSizeFor(tab))
            else if(tab==TAB_HOME) catalog.filterNot { it.id in library.hidden() }
            else if(tab==TAB_RANKING) catalog
            else catalog.take(ContentRepository.SEARCH_PAGE_SIZE)
        if(displayed.isEmpty()) {
            message(list,if(loading) "正在加载推荐…" else when(tab) { TAB_SEARCH -> when {
                query.isBlank() -> "输入关键词，用遥控器确认搜索"
                // 站方那一路取不到的时候不能说「没有找到」：我们没问到站方，只能说本机索引里也没有。
                // 说成「没有找到」是拿索引的结论冒充站方的结论 —— 那部剧可能真的有。
                searchServerFailed -> {
                    val indexed=library.catalogueSize()
                    val phrase=serverSearchPhrase()
                    // 索引还没建起来的时候，这一句才是要紧的：搜索不是没得搜，是还在建。
                    if(library.isBuildingCatalogue()||indexed==0) "$phrase · 全库索引还在建立（已收录 $indexed 部）"
                    else "$phrase · 本机索引里也没有《$query》"
                }
                else -> "没有找到相关剧，换个关键词试试"
            }; TAB_FAVORITES -> "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; TAB_HISTORY -> "播放过的剧集会自动保存在这里"; else -> "本页没有更多内容" })
            // 没结果可跳时，searchInput / searchButton / searchActions 的 ↓ 不能再指 cards.first()，
            // 那是个已经不存在的旧 id，焦点会卡死。分页按钮（如果存在）是最自然的 ↓ 落点；
            // 没有分页按钮就指回 nav，至少不指向过期引用。
            if((tab<=TAB_RANKING || localCatalog()) && page>1) addButton(list,"上一页") { page--; showCatalog(!localCatalog()) }
            if((tab<=TAB_RANKING || localCatalog()) && hasMore) addButton(list,"下一页") { page++; showCatalog(!localCatalog()) }
            val anchorView=focusAnchorView(focusAnchor)
            if(anchorView!=null) anchorView.requestFocus()
            else if(focusInput) searchInput?.requestFocus() else if(focusType) typeButtons[library.contentType]?.requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else nav[tab].requestFocus()
            return
        }
        // The site answers every query with ten same-genre rows and never an empty list, so a search
        // that matched nothing still looked like ten confident results — 搜「庆余年」came back as
        // 《剑神归来》. Say so, then keep the rows: 搜「总裁」legitimately reaches shows that merely
        // contain the word, and those are worth showing. A TextView is not focusable, so the D-pad
        // chain below still starts on the first card.
        if(tab==TAB_SEARCH && query.isNotBlank()) {
            if(searchFuzzy) list.addView(text("没有完全匹配《$query》的剧，以下是相近剧名",15f,muted).apply { setPadding(dp(4),dp(2),dp(4),dp(6)) },lp(-1,-2))
            // 「站内没有《X》」这句话只有问过站方才敢说。站方这一路取不到的时候说这句，是
            // 拿本机索引的结论冒充站方的结论 —— 用户会以为那部剧真的不存在。
            else if(searchMatched==0 && !searchServerFailed) list.addView(text("站内没有《$query》，以下是同类推荐",15f,muted).apply { setPadding(dp(4),dp(2),dp(4),dp(6)) },lp(-1,-2))
            // 说清楚结果是从哪来的：站方那十行取不到时，剩下的都是本机索引里的。
            if(searchServerFailed) list.addView(text("${serverSearchPhrase()} · 以下结果来自本机索引",15f,muted).apply { setPadding(dp(4),dp(2),dp(4),dp(6)) },lp(-1,-2))
            // 关键词太长，站方那边只收了前 30 个字 —— 这件事得说出来。用户打了四十个字，
            // 拿到的却是按三十个字问出来的结果；不说明白，他就以为搜的是那四十个字。
            // 只在第一页说：翻页的时候根本没问站方，这一句就不成立了。
            if(page==1 && query.length>ContentRepository.SITE_QUERY_LIMIT)
                list.addView(text("关键词超过 ${ContentRepository.SITE_QUERY_LIMIT} 字 · 已按前 ${ContentRepository.SITE_QUERY_LIMIT} 字搜索",15f,muted).apply { setPadding(dp(4),dp(2),dp(4),dp(6)) },lp(-1,-2))
            // Saying how much the index holds is the difference between "搜不出" and "还没建完".
            // Every state gets a line, including the one that used to say nothing at all - a silent
            // status is what left a user restarting the app with no idea whether it was working.
            val indexed=library.catalogueSize()
            val building=library.isBuildingCatalogue()
            val complete=library.isCatalogueComplete()
            val status=when {
                // 索引里已经有东西的时候，重建是在更新它，不是在从零建：数字不会掉到 0 再爬回来
                // —— 这正是「无感」要保住的东西。空的那次才叫「建立」。
                building && indexed>0 -> "正在更新全库索引 · 已收录 $indexed 部"
                building -> "正在建立全库索引 · 已收录 $indexed 部"
                complete -> "已搜索全库 $indexed 部"
                // 走完了但还没记下每部属于哪套目录：分类按钮此刻按下去是不分档的。与其让用户以为
                // 自己按错了，不如把差在哪一步说出来 —— 「没建完」和「建完了但缺这一步」是两回事。
                indexed>0 && !library.isCatalogueLabelled() -> "正在给每部剧记下属于哪套目录 · 已收录 $indexed 部，分开搜要等这一步做完"
                indexed>0 -> "全库索引还没建完 · 已收录 $indexed 部，下次打开自动继续"
                // 索引是空的，站方那一路又取不到 —— 这时候「只能搜到站内的十条结果」是句假话。
                else -> if(searchServerFailed) "全库索引还没建起来 · ${serverSearchPhrase()}" else "全库索引还没建起来 · 现在只能搜到站内的十条结果"
            }
            list.addView(text(status,13f,muted).apply { setPadding(dp(4),0,dp(4),dp(12)) },lp(-1,-2))
        }
        // 空的搜索框下面什么都不摆。以前这里会铺一片剧再写一段「这不是搜索结果」，可那是绕远路：
        // 说清楚来源的做法是给浏览一个自己的标签页，用户想翻就去那一页，搜索页只负责搜索。
        val cards=mutableListOf<View>(); val maxColumns=GRID_COLUMNS; val gap=dp(14)
        // One width for every card on every screen. Two earlier attempts at a full last row both
        // broke this: widening those cards gave the grid two poster sizes, and stretching the
        // spacing left the same visible gap. The row width is now a multiple of the page size
        // instead, so a page never ends short to begin with.
        val cardWidth=((resources.displayMetrics.widthPixels*.9f-gap*(maxColumns-1))/maxColumns).toInt()
        displayed.chunked(maxColumns).forEach { items ->
            val columns=items.size
            // 每行都左对齐。曾经只把「孤零零一张卡」的那行居中，理由是让它看起来不像没渲染出来 ——
            // 但同一页里别的行全是左对齐，唯独这行飘到中间，左边线在这里断开，反而更像出了错。
            // 统一靠左，海报列才是齐的。
            val line=row().apply { gravity=Gravity.TOP }
            items.forEachIndexed { columnIndex,item ->
                // 这一页里的序号。翻页后靠它把焦点放回同一格 —— 与页长无关，所以搜索页
                // （core 给 30 条一页）和别的页（18）用的是同一套算法。
                val pageIndex=cards.size
                val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card); card.contentDescription=item.title; card.tag=item.id; catalogCards[item.id]=card
                val position=if(tab==TAB_RANKING) ranking?.positions?.get(item.id) else null
                val artwork=FrameLayout(this); card.addView(artwork,lp(-1,((cardWidth-dp(8))/TvStyle.POSTER_ASPECT).toInt()))
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }; artwork.addView(image,FrameLayout.LayoutParams(-1,-1)); loadCover(image,item.cover); cardImages[item.id]=image to item.cover
                if(tab==TAB_RANKING) {
                    val rankLabel=position?.rank?.let { "第 $it 名" } ?: "名次暂无"
                    artwork.addView(text(rankLabel,15f).apply { setTypeface(null,Typeface.BOLD); setPadding(dp(8),dp(5),dp(8),dp(5)); background=rounded(if((position?.rank ?: Int.MAX_VALUE)<=3) accent else Color.rgb(28,31,39)) },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
                    card.contentDescription="$rankLabel，${item.title}，${position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"}"
                }
                card.addView(text(item.title,14f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) },lp(-1,-2).apply { weight=1f })
                val progress=if(tab==TAB_HISTORY || collection=="resume") library.progress(item.id) else null
                val badge=text(if(tab==TAB_FAVORITES) library.favoriteLabel(item.id) else if(tab==TAB_RANKING) position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无" else if(progress!=null) if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}" else item.badge,12f,if(tab==TAB_RANKING || tab==TAB_FAVORITES) accent else muted).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
                card.addView(badge,lp(-1,-2)); if(tab==TAB_FAVORITES) favoriteBadges[item.id]=badge
                card.setOnClickListener { catalogFocus=item.id; openDetail(item,tab==TAB_HISTORY || collection=="resume" || collection=="updates") }
                card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
                card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
                card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this,focused); if(focused) { catalogFocus=item.id; focusColumn=pageIndex; loadCover(image,item.cover); preview?.show(item,selectionStatus(item)); if(!restoringScroll) scroll.post { scroll.smoothScrollTo(0,when { line.top<scroll.scrollY -> line.top; line.bottom>scroll.scrollY+scroll.height -> (line.bottom-scroll.height).coerceAtLeast(0); else -> scroll.scrollY }) } } }
                // The row measures its tallest card, then stretches siblings to keep badges aligned.
                line.addView(card,lp(cardWidth,-1).apply { if(columnIndex<columns-1) rightMargin=gap }); cards+=card
            }
            list.addView(line,lp(-1,-2).apply { bottomMargin=dp(10) })
        }
        val paging=row(); paging.gravity=Gravity.CENTER
        var next: View?=null
        if(tab<=TAB_RANKING || localCatalog()) {
            addButton(paging,"上一页") { if(page>1) turnPage(page-1) }.apply { isEnabled=page>1; isFocusable=page>1; alpha=if(page>1) 1f else .4f }
            // 页码本身是个按钮：翻到第几页不该一页一页按。
            val jump=button(pagingLabel()) { }.apply { gravity=Gravity.CENTER; textSize=14f; setTextColor(muted) }
            jump.setOnClickListener { showPagePicker(jump) }
            paging.addView(jump,lp(dp(
                when {
                    tab==TAB_RANKING -> 135
                    // 首页的页数是两位数（「第 34 / 34 页」），比榜单那一位数宽。
                    tab==TAB_HOME -> 150
                    tab==TAB_SEARCH && searchTotal!=null -> 210
                    else -> 95
                }),dp(44)))
            next=addButton(paging,"下一页") { if(hasMore) turnPage(page+1) }.apply { isEnabled=hasMore; isFocusable=hasMore; alpha=if(hasMore) 1f else .4f }
            if(collection=="hot" && !hasMore && collectionReturn?.hasMore==true) {
                next=addButton(paging,"下一批热门") { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) }
            }
            list.addView(paging,lp(-1,dp(50)))
        }
        // 落点不在这里算：整屏的焦点图按每个控件在屏幕上的实际位置统一算，写回 nextFocus*Id。
        // 这一段原来手写下一整套：行内左右、上下对齐、导航和片库按钮的连线、翻页那一行的
        // 连线。落点是按下标算出来的，于是「首页」按下键会跳到屏幕另一头的片库按钮，
        // 第一列按左会把自己锁死。现在这些都由 [FocusRouter] 接手。
        val anchorView=focusAnchorView(focusAnchor)
        if(anchorView!=null) anchorView.requestFocus()
        else if(focusInput) searchInput?.requestFocus() else if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusNav) nav[tab].requestFocus()
        else {
            // 新页的焦点落在哪一格，由 [PageTurn] 说了算：
            // - 用户正在看的那张卡还在新页里，就让它留在原地（页面自己换的时候：榜单每天刷新
            //   一次、收藏有更新 —— 别把它从用户眼皮底下挪走）。
            // - 搜索页回到同一格：结果是一份连续的清单，用户拿两页对着看，位置跳了就没法比。
            // - 其余各页从新页的第一张开始：翻页那一行（上一页 / 页码 / 下一页）画在整页的最
            //   下面，它不在网格里，「回到翻页前的那一格」照搬过来就是把用户丢到新页的最后一排，
            //   而新页的头几行还在屏幕上面看不见。实测（2026-10-21，排行榜一页 36 张、六列六行）：
            //   焦点从左上角一路按到底停在第六行第一列，按下「下一页」之后新页的焦点也在第六行
            //   第一列 —— 看着就像「跳到了角落里」。
            val sameCard=catalogFocus.takeIf { it.isNotEmpty() }?.let { id -> displayed.indexOfFirst { it.id==id } }
                ?: PageTurn.NOT_THERE
            val index=PageTurn.focusCell(tab,focusColumn,sameCard,displayed.size)
            cards[index].requestFocus()
        }
        val savedScroll=scrollPositions[catalogPageKey]
        scroll.post {
            if(catalogScroll!==scroll) return@post
            // 卡片宽度统一了，但可见高度还要压到一屏能放下整行：远小于卡片宽的那些海报按比例放大会
            // 高过屏幕，所以取宽高比和剩余高度里的小者。
            cards.forEach { view ->
                val card=view as LinearLayout
                val image=card.getChildAt(0)
                val own=card.layoutParams.width
                if(own<=0) return@forEach
                val textHeight=card.paddingTop+card.paddingBottom+(1 until card.childCount).sumOf { card.getChildAt(it).measuredHeight }
                val target=minOf(((own-dp(8))/TvStyle.POSTER_ASPECT).toInt(),(scroll.height-textHeight-dp(4)).coerceAtLeast(dp(48)))
                if(image.layoutParams.height!=target) image.layoutParams=image.layoutParams.apply { height=target }
            }
            scroll.post settle@{
                if(catalogScroll!==scroll) return@settle
                savedScroll?.let { scroll.scrollTo(0,it) }
                (cards.firstOrNull { it.hasFocus() }?.parent as? View)?.let { line ->
                    val y=when {
                        line.top<scroll.scrollY -> line.top
                        line.bottom>scroll.scrollY+scroll.height -> if(line.height>=scroll.height) line.top else line.bottom-scroll.height
                        else -> scroll.scrollY
                    }
                    scroll.scrollTo(0,y.coerceAtLeast(0))
                }
                restoringScroll=false
            }
        }
    }
    private fun selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        return listOf(if(library.favorite(series.id)) "已收藏" else "",if(library.queued(series.id)) "稍后看" else "",
            if(library.watched(series.id)) "整剧已看完" else progress?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }
    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
        homeUpdateCount=library.updatedFavorites()
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val home=HomeScreen(this,::loadCover,{ item,label -> preview.show(item,if(label.startsWith("第 ")) selectionStatus(item) else listOf(label,selectionStatus(item)).filter { it.isNotBlank() }.joinToString(" · ")) },
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        val hot=catalog.filterNot { it.id in library.hidden() }
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType,
            if(hasMore) { { page=2; catalogFocus=""; showCatalog(true) } } else null,homePosition)
        prepareResumeEpisode()
        home.refresh.text=if(loading) if(homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        if(focusNav) nav[TAB_HOME].requestFocus() else if(focusType) typeButtons.getValue(library.contentType).requestFocus()
        if(hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    /**
     * 备下首页「接着看」的那一集。
     *
     * 它是用户最可能按下去的那一集，而首页摆出来到用户按下去之间总有一两秒 —— 那一刻画面
     * 还没开始播，网络是空的。按下去就不用再问一遍地址、等一遍首包。
     *
     * 看完一整部的不备：下一集的地址得先有分集表才知道，而分集表要等进了详情页才有。
     * 计费网络也不备：那一兆是白花的。
     */
    private fun prepareResumeEpisode() {
        if(connectivity.isActiveNetworkMetered) return
        val progress=library.history().firstOrNull { !library.watched(it.series.id) } ?: return
        if(progress.completed) return
        prepareEpisode(progress.series.id,progress.episodeId,library.maxQuality,preparedPage,pagePreparation)
    }
    private fun homeShelves(): List<HomeScreen.Shelf> {
        val recent=library.history().filterNot { library.watched(it.series.id) }
        val updates=library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0)>0 }
        val later=library.later(); val hidden=library.hidden(); val hot=catalog.filterNot { it.id in hidden }
        return listOf(
            HomeScreen.Shelf("resume","接着看",recent.take(8).map { p -> HomeScreen.Entry(p.series,
                "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",true,
                if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null) },
                if(recent.size>8) { { showCollection("resume") } } else null),
            HomeScreen.Shelf("updates","收藏有更新",updates.take(8).map { HomeScreen.Entry(it,library.favoriteLabel(it.id),true) },
                if(updates.size>8) { { showCollection("updates") } } else null),
            HomeScreen.Shelf("later","稍后看",later.take(10).map { HomeScreen.Entry(it,"稍后看 · 长按管理") },
                if(later.size>10) { { showCollection("later") } } else null),
            HomeScreen.Shelf("hot","热门发现 · ${library.contentType.label}",hot.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                if(hot.size>10) { { showCollection("hot") } } else null))
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!="catalog") return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); home.refreshSelection(); return }
        if(removed && (localCatalog() || tab==TAB_HOME)) {
            val oldIds=catalogCards.keys.toList(); val index=oldIds.indexOf(changedId).coerceAtLeast(0)
            if(localCatalog()) {
                catalog=localItems(); page=page.coerceIn(1,maxOf(1,(catalog.size+pageSizeFor(tab)-1)/pageSizeFor(tab))); hasMore=page*pageSizeFor(tab)<catalog.size
            }
            val remaining=if(localCatalog()) catalog.drop((page-1)*pageSizeFor(tab)).take(pageSizeFor(tab)) else catalog.filterNot { it.id in library.hidden() }
            catalogFocus=remaining.getOrNull(index.coerceAtMost((remaining.size-1).coerceAtLeast(0)))?.id.orEmpty()
            val y=catalogScroll?.scrollY ?: 0; scrollPositions[catalogPageKey]=y
            artwork.cancelPage(); favoriteBadges.clear()
            catalogBody?.let { body -> body.removeAllViews(); catalogGrid(body,false) }
        } else {
            catalog.firstOrNull { it.id==changedId }?.let { selectionPreview?.show(it,selectionStatus(it)) }
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
    private fun quickActions(series: Series,anchor: View) {
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(library.favorite(series.id)) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看","查看剧集详情")
        if(tab==TAB_HOME) labels+="不在热门推荐中显示"
        if(tab==TAB_HISTORY) labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && (tab==TAB_FAVORITES || collection=="updates")); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> openDetail(series)
                4 -> if(tab==TAB_HISTORY) manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        favoriteStatus?.text=favoriteMonitor.status
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        val home=homeScreen
        if(screen=="catalog" && tab==TAB_HOME && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            // A stable resume key can be restored without moving the user's selection.
            if(!tvTools.showing && !updater.showing && currentFocus?.tag?.toString()?.startsWith("resume:")==true) {
                homeUpdateCount=library.updatedFavorites(); home.updateShelves(homeShelves())
            } else {
                home.refresh.text="收藏更新 ${library.updatedFavorites()} 部 · 按确认查看"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }
    private fun settings(parent: LinearLayout) {
        parent.addView(text("按你的习惯播放",25f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(15),0,dp(7)) })
        parent.addView(text("偏好自动保存，下一部剧继续使用。",13f,muted).apply { setPadding(0,0,0,dp(18)) })
        val groups=row().apply { gravity=Gravity.TOP }
        val playback=column(); val device=column()
        listOf(playback,device).forEachIndexed { i,group ->
            group.setPadding(dp(16),dp(16),dp(16),dp(16)); group.background=rounded(surface)
            groups.addView(group,lp(0,-2).apply { weight=1f; if(i==0) rightMargin=dp(16) })
        }
        parent.addView(groups)
        fun heading(group: LinearLayout,title: String,subtitle: String) {
            group.addView(text(title,18f).apply { setTypeface(null,Typeface.BOLD) })
            group.addView(text(subtitle,12f,muted).apply { setPadding(0,dp(5),0,dp(15)) })
        }
        fun setting(group: LinearLayout,label: String,action: ()->Unit): TextView {
            val view=button(label,onClick=action).apply {
                gravity=Gravity.CENTER_VERTICAL; setPadding(dp(13),dp(11),dp(13),dp(11))
                background=rounded(bg)
                setOnFocusChangeListener { _,focused -> background=rounded(if(focused) TvStyle.raised else bg,if(focused) accent else Color.TRANSPARENT) }
            }
            group.addView(view,lp(-1,-2).apply { bottomMargin=dp(8) })
            return view
        }
        heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
        heading(device,"片单与设备","保管记录，保持应用更新")
        lateinit var qualityButton: TextView
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
        lateinit var defaultSpeed: TextView
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        lateinit var frame: TextView
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        lateinit var autoNextButton: TextView
        autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
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
        lateinit var backup: TextView
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
                    applyDanmakuSetting(); applyDanmakuStyle()
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    Toast.makeText(this,"记录已合并恢复",Toast.LENGTH_LONG).show()
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        lateinit var hidden: TextView
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; Toast.makeText(this,"热门推荐已恢复",Toast.LENGTH_SHORT).show()
        }
        lateinit var updates: TextView
        updates=setting(device,"版本与更新  ·  ${BuildConfig.VERSION_NAME}") { updater.show(updates) }
        val license=setting(device,"开源许可") {
            AlertDialog.Builder(this).setTitle("开源许可").setMessage("本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：${AppUpdate.REPOSITORY}。").setPositiveButton("关闭",null).show()
        }
        // 两列之间的左右、以及每一列里的上下，都由焦点图按实际位置算 —— 设置页的按钮是
        // 左边一列、右边一列，手写的连线只照顾了其中几个方向。
        parent.addView(text("红果TV+  /  ${BuildConfig.VERSION_NAME}    ·    收藏与进度保存在本机",12f,muted).apply { setPadding(dp(2),dp(18),0,dp(10)) })
        nav[tab].requestFocus()
    }
    /**
     * 翻到 [target] 页。
     *
     * 搜索页在有本地索引时走同步路径：翻页只查内存里的索引（毫秒级），走异步会先闪一次
     * 「正在加载…」再立刻跳走，那一下闪烁比等待本身更让人以为卡住了。回到第 1 页仍然要发请求，
     * 因为只有那一页会用服务端那十行补位，所以那一次仍旧走异步。
     *
     * 翻页后焦点落在新页的哪一格，由 [PageTurn] 说了算：搜索页回到同一格（那一格由
     * [focusColumn] 记着 —— 翻页前那张卡在它自己那一页里的序号，与页长无关；原先是「按页长
     * 切片再取下标」重算 catalogFocus，而切片用的页长和搜索页真正的页长不是一个数，下标对
     * 第 19 张以后的卡片全是错的，于是每翻一页焦点就跳回第 1 张），其余各页从新页的第一张开始。
     */
    private fun turnPage(target: Int) {
        page=target
        // 旧页上那张卡这一页没有了，焦点得重新放。放哪一格由 [PageTurn] 说了算。
        // catalogFocus 先清掉：旧页的 id 在新页里找不到时，不能再被当成「还记得的卡」而挡住
        // 同一格的还原。
        //
        // 原来这段是「按页长切片再取下标」，而切片用的页长（18）和搜索页真正的页长（30）不是
        // 一个数，于是第 19 张以后的卡片下标全是错的，新页也是从旧页里切出来的。
        catalogFocus=""
        if(localCatalog()) { showCatalog(false); return }
        val local=library.catalogue()
        if(tab!=TAB_SEARCH || local==null) { showCatalog(true); return }
        if(page==1) { showCatalog(true); return }
        val result=repository.search(query,page,library.contentType,local)
        catalog=result.items; hasMore=result.hasMore
        searchMatched=result.matchedCount; searchTotal=result.totalHits; searchFuzzy=result.fuzzy; searchServerProblem=result.serverProblem
        showCatalog()
    }

    /**
     * 把 keptFocusAnchor 字符串还原成当前 view 树里的对应 view。anchor 是字符串不是 view id：
     * view id 是 View.generateViewId 生成的，新一轮渲染的 id 完全不同；anchor 是位置标签
     * （"searchInput" / "searchAction:2" 等），catalogGrid 重新画了 view 之后只要对应位置上
     * 还有 view，焦点就能还原回去。
     */
    private fun focusAnchorView(anchor: String?): View? = when(anchor) {
        "searchInput" -> searchInput
        "searchButton" -> searchButton
        "nav" -> nav.getOrNull(tab)
        "type" -> typeButtons[library.contentType]
        else -> {
            val actionIdx=anchor?.removePrefix("searchAction:")?.toIntOrNull()
            if(actionIdx!=null) searchActions.getOrNull(actionIdx) else null
        }
    }

    /**
     * 这一页的账：几页、页码那行字怎么写。
     *
     * 算法在 [Paging] 里，因为它得有一个能写测试的地方 —— 这里的私有方法测不到，而
     * 「哪一页一共有几页」正是那种读代码看不出错、只有数一遍才发现的逻辑。
     */
    private fun pagingAccount() = Paging.of(
        tab=tab,
        localItems=if(localCatalog()) catalog.size else null,
        pageSize=pageSizeFor(tab),
        sitePages=catalogTotalPages,
        rankingPages=ranking?.totalPages,
        searchHits=searchTotal
    )
    /** 「第 X / Y 页 · 共 N 部」。 */
    private fun pagingLabel(): String = pagingAccount().label(page)
    /** 这一页一共有几页。 */
    private fun pageCount(): Int = pagingAccount().total
    /**
     * 跳到第几页。
     *
     * 页数一多，一页一页按就是折磨，而页码就写在屏幕上 —— 它本来就该是能点的。
     */
    private fun showPagePicker(anchor: View?) {
        val total=pageCount()
        if(total<=1) { tvTools.info("翻页","只有一页，没有可跳的。",anchor); return }
        tvTools.choose("跳到第几页",(1..total).map { "第 $it 页" },anchor,{ index -> turnPage(index+1) })
    }
    private fun runSearch(value: String,type: ContentType=library.contentType) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        if(library.contentType!=type) { library.contentType=type; dropShelfCachedPages() }
        library.rememberSearch(clean,type); query=clean
        // 切到搜索页之前先把当前 tab 的 page/catalog/catalogFocus 存下来，
        // 否则在详情页或其他 tab 触发的搜索会让 switchTab 回来时回到第 1 页。
        if(tab!=TAB_SEARCH) {
            tabState[tab]=CatalogState(page,catalog,hasMore,ranking); tabFocus[tab]=catalogFocus
            tab=TAB_SEARCH
            val saved=tabState[TAB_SEARCH]
            page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking
            catalogFocus=tabFocus[TAB_SEARCH].orEmpty()
        }
        page=1; catalogFocus=""; focusColumn=null
        searchMatched=null; searchTotal=null; searchFuzzy=false; searchServerProblem=ServerSearchProblem.NONE; showCatalog(true)
    }
    private fun showSearchHistory(anchor: View) {
        val history=library.searches()
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { "${it.type.label} · ${it.query}" },anchor,{ index -> runSearch(history[index].query,history[index].type) },"清空历史") {
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        }
    }
    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true) {
        pendingDetail=series; detailAutoplay=autoplay
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null; synopsisExpanded=false; seasonsHolder=null
        val body=base(series.title); message(body,"正在加载剧集…")
        work({ repository.detail(series.id) }, { result ->
            detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val progress=library.progress(series.id)
            val target=ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            episodeIndex=target.index; group=episodeIndex/20
            // 详情页一打开就把用户马上要看的那一集备上：这一刻网络是空的，画面还没开始播。
            // 按播放的时候地址和首包都已经在手上了，不必再从头问一遍地址、等一遍首包。
            // 计费网络不备：那一兆是白花的，用户还没说要播。
            if(!resume&&result.episodes.isNotEmpty()&&!connectivity.isActiveNetworkMetered) prepareEpisode(result.series.id,result.episodes[target.index],library.maxQuality,preparedPage,pagePreparation)
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
        }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay) } })
    }
    private fun showDetail(focusEpisode: Boolean) {
        invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
        val body=base("剧集详情")
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row(); hero.gravity=Gravity.TOP; hero.setPadding(0,dp(12),0,dp(12))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }; hero.addView(image,lp(dp(123),dp(174)).apply { rightMargin=dp(24) }); loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        // 顶部那行大剧名去掉了：它占的是整块屏幕，海报上本来就写着剧名，屏幕该留给简介和按钮。
        // 名字挪到下面那行小字里 —— 全删掉的话，这一页就认不出是哪部剧了。
        info.addView(text(data.series.title,13f,muted).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(2),0,dp(2)) })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}",13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        val description=data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis=text(description,15f,muted).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
        info.addView(synopsis)
        val actions=row(); actions.setPadding(0,dp(12),0,0); info.addView(actions)
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        val resumePosition=progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0
        val watched=library.watched(data.series.id)
        val play=addButton(actions,if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeIndex+1} 集" else "开始观看") { playEpisode(if(watched) 0 else if(progress?.completed==true) (resumeIndex+1).coerceAtMost(data.episodes.lastIndex) else resumeIndex,if(watched) 0 else resumePosition) }
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏") {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        lateinit var synopsisButton: TextView
        synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介") {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        content.addView(hero)
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(dp(190),dp(42)))
        addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        lateinit var jump: TextView
        jump=addButton(groupRow,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; showDetail(true) } }
        content.addView(groupRow)
        val episodes=mutableListOf<View>()
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(10).forEach { numbers ->
            val line=row()
            numbers.forEach { index -> val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) }; item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item }
            repeat(10-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(49)))
        }
        // 剧集网格的四个方向由焦点图统一算：同一行左右环绕，上下按列对齐，末行往下落到
        // 「同系列剧」区块的第一张卡。以前这里是手写的 nextFocus*Id：末行不写下键，于是
        // 「下」只能靠系统的几何搜索去猜，猜不中就永远按不到季度卡。
        seasonsHolder=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(seasonsHolder)
        loadSeasons(data)
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
    }

    /**
     * 查询同系列其它季度并渲染。
     *
     * **不缓存**：每次都重新查，避免进别的剧再回来时看到的是上次那份快照。详情页每次重建
     * 都会调它，所以缓存命中时也不写「正在查找…」—— 那一行每闪一次都是一次视觉噪音。
     */
    private fun loadSeasons(data: Detail) {
        val holder=seasonsHolder ?: return
        val currentId=data.series.id
        // 不缓存：同系列结果每次都重新查，避免进别的剧再回来时看到的是上次那份快照。
        holder.addView(text("正在查找同系列剧…",13f,muted).apply { setPadding(0,dp(14),0,dp(8)) })
        work({ repository.seasons(data.series) }, { rows ->
            // 回调可能落在别的剧上，回来时别把结果画到当前页面。
            if(screen=="detail" && detail?.series?.id==currentId && seasonsHolder===holder) renderSeasons(holder,rows,currentId)
        }, {
            if(screen=="detail" && detail?.series?.id==currentId && seasonsHolder===holder) {
                holder.removeAllViews(); holder.addView(text("暂未找到同系列剧",13f,muted).apply { setPadding(0,dp(14),0,dp(8)) })
            }
        })
    }
    /**
     * 「同系列剧」结果区块，渲染到剧集网格下方的 [seasonsHolder]。
     *
     * 纵向列表、左侧海报右侧文字，和详情页本身「一屏一部剧」的节奏一致；
     * 之前用密集网格时，季度卡比封面还抢眼，看着像另一个页面的内容。
     *
     * 不设入口按钮，详情页每次渲染都直接查，结果不缓存 —— 页面本身在一个 ScrollView 里，
     * 季度卡排在网格下方，需要时往下滚即可。
     */
    private fun renderSeasons(holder: LinearLayout, rows: List<SeasonEntry>, currentId: String) {
        holder.removeAllViews()
        val others=rows.filter { it.series.id!=currentId }
        if(others.isEmpty()) { holder.addView(text("暂未找到其它季度",14f,muted)); return }
        holder.addView(text("同系列剧 · 共 ${others.size} 部其它季度",15f).apply { setTypeface(null,Typeface.BOLD); setPadding(0,dp(16),0,dp(8)) })
        val cards=mutableListOf<View>()
        val poster=dp(74)
        others.forEach { entry ->
            val item=row(); item.gravity=Gravity.CENTER_VERTICAL
            item.setPadding(dp(8),dp(8),dp(8),dp(8)); focusStyle(item)
            item.contentDescription="第 ${entry.season} 季，${entry.series.title}，${entry.series.badge}"
            val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            item.addView(image,lp(poster,(poster/TvStyle.POSTER_ASPECT).toInt()))
            // 先按「可见」优先级加载一次，否则列表滚进来时是空图，只有聚焦后才出现 —— 和目录页同一套做法。
            loadCover(image,entry.series.cover)
            val info=column(); info.gravity=Gravity.CENTER_VERTICAL
            item.addView(info,lp(0,-2).apply { weight=1f; leftMargin=dp(14) })
            info.addView(text(entry.series.title,16f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END })
            // badge 是「全N集」这类现成文案，有就接在季号后面，没有就只显示季号。
            val episodes=entry.series.badge
            val meta=if(episodes.isBlank()) "第 ${entry.season} 季" else "第 ${entry.season} 季 · $episodes"
            info.addView(text(meta,13f,muted).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(0,dp(5),0,0) })
            item.setOnClickListener { openDetail(entry.series) }
            item.setOnFocusChangeListener { _,focused -> item.background=TvStyle.card(this,focused); if(focused) loadCover(image,entry.series.cover) }
            holder.addView(item,lp(-1,-2).apply { bottomMargin=dp(8) })
            cards+=item
        }
        // 纵向列表：季度卡自己排成上下一条，焦点图再把这一串和上面的剧集网格接起来 ——
        // 网格最后一行往下落到第一张季度卡、第一张季度卡往上回到网格最后一行。
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false) {
        updater.interrupt()
        // 上一集的弹幕先撤下来 —— 换集到新地址取到之前，画面上不该还飘着旧集的弹幕。
        danmakuSession.stop(); danmakuOverlay?.setItems(emptyList())
        val data=detail ?: return
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        // 备好的那一集直接拿走：详情页备下的那一集，或者本集播着的时候备下的下一集。
        // 位置不为零也能用 —— 备好的流从头到尾都在，播放器要从哪里读，它就从哪里读。
        val preparedKey="${data.episodes[selectedIndex]}:${library.maxQuality}"
        val prepared=if(!recovering) (preparedPage.take(preparedKey) ?: preparedNext.take(preparedKey)) else null
        // 备着的正是这一集、只是还没备完：把它已经问到的地址取走，别再从头问一遍。
        // 取地址是几波并发请求，那几百毫秒正是备集要替用户省掉的东西；那一兆留给播放器按
        // 自己的节奏拉 —— 两边同时拉同一集的开头，正是备集本想避免的那种抢带宽。
        val adopted=if(prepared==null && !recovering) adoptAddress("${data.series.id}:${data.episodes[selectedIndex]}:${library.maxQuality}") else null
        if(adopted!=null) android.util.Log.i("HongguoTV","Episode address adopted episode=${selectedIndex+1}")
        val prefetched=prepared
        // 画面开始了，页面那份备集就停掉：它再拉那一兆是在跟画面抢带宽。
        pagePreparation.cancel()
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            player?.stop(); player?.clearMediaItems()
            video?.close(); video=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        video=prefetched
        var firstReady=true; var firstFrame=true
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        if(autoplay) sleepStopped=false
        screen="player"; episodeIndex=selectedIndex; group=episodeIndex/20
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        player?.playWhenReady=requestedAutoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            val view=PlayerView(this).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.TRANSPARENT); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
            // 弹幕画在画面上：加在播放器之上、HUD 之下。位置取自播放进度，所以暂停、跳转、
            // 倍速都对得上 —— 用墙钟算位移这三件事一定做不对其中一件。
            danmakuOverlay=DanmakuOverlay(this).apply {
                setPositionSource { player?.currentPosition ?: 0L }
                setSpeedSource { player?.playbackParameters?.speed ?: 1f }
                setVideoRectProvider { videoRect() }
                // 字号和行数先记着：画面尺寸这时候还没定，等 onSizeChanged 再排版。
                setStyle(library.danmakuSize,library.danmakuRows)
                // 屏幕放不下用户要的那么多行时说一声：界面上写着 12 行而屏幕上只有 7 行，
                // 用户只会以为这个设置是坏的。
                onClamped={ rows -> Toast.makeText(this@MainActivity,"屏幕放不下这么多行，实际显示 $rows 行",Toast.LENGTH_LONG).show() }
            }
            root.addView(danmakuOverlay,FrameLayout.LayoutParams(-1,-1))
            hud=column().apply { setPadding(dp(widthDp()*.05f),dp(20),dp(widthDp()*.05f),(resources.displayMetrics.heightPixels*.05f).toInt()); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(240,11,15,21))) }
            playbackTitle=text("",23f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }; hud.addView(playbackTitle)
            playbackText=text("",15f,muted).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
            progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface) }; hud.addView(progressBar,lp(-1,dp(4)))
            hud.addView(text("确认 暂停/播放    左右 快退/快进    ↓ 选集    ↑ 更多    返回 收起 / 退出",13f,muted).apply { setPadding(0,dp(12),0,0) })
            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
        controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation
        work({ prefetched ?: RemoteVideo(repository.http,adopted ?: repository.stream(data.series.id,requestedId,maxQuality)).prepare() }, { remote ->
            video=remote; quality=remote.info.quality
            val p=player ?: run {
                val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,1000,2000).setTargetBufferBytes(12*1024*1024).build()
                val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                    player=it; playerView?.player=it
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private fun active()=player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state==Player.STATE_READY) {
                        if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}") }
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else { requestedAutoplay=false; p.pause(); playbackText.text="本集已结束"; showPanel() }
                    }
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { if(hud.visibility==View.VISIBLE) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) { if(active()) syncMediaSession() }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hongguotv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
            p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
            // 这一集的弹幕。切集时也走这里：session 一开始就会把上一集的弹幕撤掉。
            if(library.danmakuEnabled) danmakuSession.start(data.series.id,requestedId)
        }, { problem -> playerFailure(problem) })
    }
    private fun cancelPrefetch() {
        // 只停下一集那份：用户马上要看的那一集还在备着，按播放时还要用它。
        nextPreparation.cancel()
        preparedNext.clear()
    }
    /**
     * 停掉页面那份备集。
     *
     * 它只在「用户马上要按播放」的时候才有意义：人已经从详情页走开、或者应用已经退到后台，
     * 那一兆就是在跟目录页和封面图抢带宽，而真正按下播放时它多半已经被槽判成过期了。
     */
    private fun cancelPagePrefetch() { pagePreparation.cancel() }
    /**
     * 取走正在备着的那一集的地址。
     *
     * 备着的那一份正好是用户刚按下的这一集时才有的取：地址它已经问到了，取走就不用再问一遍；
     * 不是这一集就什么也没有 —— 别把上一集的地址当成这一集的。
     */
    private fun adoptAddress(key: String): StreamInfo? {
        val fromPage=pagePreparation.resolved
        if(fromPage!=null && fromPage.key==key) return fromPage.address
        val fromNext=nextPreparation.resolved
        if(fromNext!=null && fromNext.key==key) return fromNext.address
        return null
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(readySince==0L) return
        // 下一集提前准备：本集一播稳、而且本集自己的缓冲已经填到位，就动手，而不是片尾 30 秒
        // 才动手 —— 那点时间不够取签名地址加预热 1MB，下一集开始时往往还没准备好，于是每次
        // 自动连播都要先等一下。缓冲这条门槛对齐的是播放器自己的缓冲目标：预取那 1MB 走的是
        // 同一条上行，别在播放器自己还没填满缓冲的时候插队（见 PrefetchPolicy.BUFFER_AHEAD_MS）。
        if(!PrefetchPolicy.shouldPrepare(now-readySince,p.bufferedPosition-p.currentPosition)) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
        if(preparedNext.isFresh(key) || nextPreparation.job?.isDone==false) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelPrefetch(); prefetchAttemptAt=now; prefetchAttempts++
        prepareEpisode(data.series.id,id,quality,preparedNext,nextPreparation,giveUpOnTimeout=true)
    }
    /**
     * 提前备好一集：地址、首包、1MB 预热，三样都齐了才算备好。
     *
     * 两个地方叫它：用户还在页面上的时候备下他马上要看的那一集 —— 那一刻网络是空的，画面
     * 还没开始播，按播放的时候地址和首包都已经在手上了；以及本集播着的时候备下下一集。
     *
     * 备好的东西放在槽里：一次只备一集，备下一集的时候上一集已经被取走了。
     */
    private fun prepareEpisode(seriesId: String,id: String,quality: Int,slot: PreparedSlot<RemoteVideo>,preparation: Preparation,giveUpOnTimeout: Boolean=false) {
        val key="$id:$quality"
        if(isDestroyed||slot.isFresh(key)) return
        // 同一件事只备一集：已经在备的那份停掉，用户马上要按的这一集更重要。
        preparation.cancel()
        val scope=RequestScope(); preparation.scope=scope
        // 地址一到手就挂出去：这一份还没预热完，用户也可能已经按了播放，那时取走地址就够了。
        val resolved=Resolved("$seriesId:$id:$quality"); preparation.resolved=resolved
        val started=android.os.SystemClock.elapsedRealtime()
        val ticket=slot.begin(key)
        // 准备有自己的期限。不能拿客户端的超时来当这个期限 —— 那套超时之后要陪着整集播放。
        val watchdog=Runnable {
            // 超了预算就是这条链路掏不出这 1MB，别再拿本集的带宽试第二次第三次。
            if(giveUpOnTimeout) prefetchAttempts=3
            scope.cancel()
        }
        preparation.watchdog=watchdog
        main.postDelayed(watchdog,PrefetchPolicy.BUDGET_MS)
        val worker=if(preparation===pagePreparation) pageWorker else prefetchWorker
        preparation.job=worker.submit {
            var remote: RemoteVideo?=null
            try {
                val info=scope.run { repository.stream(seriesId,id,quality) }
                resolved.address=info
                // 首包和预热都走仓库那个客户端：它的连接池是热的，握手不用重来一遍。
                // 每集另起一个客户端，看着干净，实则每集都要重新握手一次 CDN。
                val prepared=scope.run { RemoteVideo(repository.http,info).also { remote=it; it.warm() } }
                if(slot.complete(ticket,key,prepared)) android.util.Log.i("HongguoTV","Episode prepared key=$key elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}")
                else { remote?.close(); android.util.Log.i("HongguoTV","Episode preparation dropped key=$key elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
            } catch(e: Exception) {
                remote?.close()
                if(e !is CancelledException) android.util.Log.i("HongguoTV","Episode preparation failed key=$key elapsedMs=${android.os.SystemClock.elapsedRealtime()-started} ${e.javaClass.simpleName}")
            }
            finally {
                main.removeCallbacks(watchdog)
                if(preparation.watchdog===watchdog) preparation.watchdog=null
            }
        }
    }
    private fun networkAvailable(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
        if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    private fun playerFailure(problem: Throwable) {
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch()
        playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException
        // 「播放失败」和「网络断了」是两回事：前者要换清晰度或换剧，后者等一会儿就好。
        // 说错了，用户就会去按「尝试 720P」——那一下跟网络一点关系都没有。
        val offline=isNetworkFailure(problem)
        playbackText.text=when {
            decoding -> "电视无法解码当前视频，可尝试更低清晰度。"
            offline -> "网络连接失败，可重试或切换清晰度。"
            else -> "播放失败，可重试或切换清晰度。"
        }
        val actions=row(); controls.addView(actions)
        addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
        addButton(actions,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,retryPosition,retryAutoplay) }
        addButton(actions,"返回选集") { returnToDetail(true) }
        if(retryable && !sleepStopped) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
        android.util.Log.w("HongguoTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""))
    }
    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel=false; controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
    }
    private fun showHud() {
        if(screen!="player") return
        hud.visibility=View.VISIBLE; updatePlaybackText(); main.removeCallbacks(hideHud)
        if(!panel && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    private fun updatePlaybackText() {
        transportPlay?.text=if(player?.playWhenReady==true) "暂停" else "播放"
        sleepButton?.text="定时 ${sleepTimer.label()}"
        if(screen!="player" || playError) return
        if(sleepStopped) { playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"; return }
        if(!playbackReady) { playbackText.text=(if(!requestedAutoplay) "准备后保持暂停 · " else "")+if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }
    private fun skipEpisode(direction: Int) {
        if(!foreground || screen!="player") return
        val count=detail?.episodes?.size ?: return
        val target=episodeIndex+direction
        if(target in 0 until count) playEpisode(target)
    }
    private fun syncMediaSession() {
        if(!foreground || screen!="player") { mediaSession.deactivate(); return }
        val data=detail ?: return; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
    }
    private fun stopForSleep() {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        Toast.makeText(this,"定时停止已生效",Toast.LENGTH_LONG).show()
    }
    private fun showSleepPicker(anchor: TextView) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            if(anchor.tag!="playback-settings") anchor.text="定时 ${sleepTimer.label()}"; updatePlaybackText(); showHud()
        })
    }
    /**
     * 弹幕开关生效。
     *
     * 关掉时连请求都不该发出去 —— 开着开关却什么都看不到，比没有这个功能还让人以为坏了。
     * 开着时正在播的那一集要立刻把弹幕拉起来，不用等下一集。
     */
    private fun applyDanmakuSetting() {
        if(!library.danmakuEnabled) {
            danmakuSession.stop(); danmakuOverlay?.setItems(emptyList()); danmakuOverlay?.stop()
            return
        }
        val data=detail ?: return
        if(screen!="player") return
        val episode=data.episodes.getOrNull(episodeIndex) ?: return
        danmakuSession.start(data.series.id,episode)
    }
    /**
     * 弹幕的字号和行数生效。
     *
     * 改了要立刻重排，不用退出播放再进来 —— 正在看的时候觉得字小，正是最想调它的时候。
     * 屏幕放不下用户要的那么多行时，overlay 会自己喊一声（见 [DanmakuOverlay.onClamped]）。
     */
    private fun applyDanmakuStyle() {
        danmakuOverlay?.setStyle(library.danmakuSize,library.danmakuRows)
    }
    private fun frameResizeMode()=when(library.frameMode) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    /**
     * 视频画面在窗口里的矩形。
     *
     * 竖屏短剧在 16:9 的电视上默认是「完整画面」：画面撑满高度，左右各留一条黑边。弹幕要按
     * 画面的真实宽度走，不然就飘在那两条黑边上 —— 看着像飘在画面外面。
     *
     * 找的是 surface 本身：播放器外面那层框永远是全屏的，只有 surface 才是画面真正的范围。
     */
    private fun videoRect(): Rect? {
        val pv=playerView ?: return null
        val surface=findSurface(pv) ?: return null
        if(surface.width<=0||surface.height<=0) return null
        val at=IntArray(2); surface.getLocationInWindow(at)
        return Rect(at[0],at[1],at[0]+surface.width,at[1]+surface.height)
    }
    private fun findSurface(view: View): View? {
        if(view is SurfaceView||view is TextureView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
        })
    }
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
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else 10000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun showPanel() {
        if(playError) return
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val transport=row(); controls.addView(transport)
        val play=addButton(transport,if(player?.playWhenReady==true) "暂停" else "播放") { togglePlayback() }.also { transportPlay=it }
        addButton(transport,"上一集") { if(episodeIndex>0) playEpisode(episodeIndex-1) }.apply { isEnabled=episodeIndex>0; isFocusable=episodeIndex>0; alpha=if(episodeIndex>0) 1f else .4f }
        addButton(transport,"下一集") { if(episodeIndex<(detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex+1) }.apply { val enabled=episodeIndex<(detail?.episodes?.lastIndex ?: 0); isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .4f }
        val options=row().apply { setPadding(0,dp(8),0,0) }; controls.addView(options)
        lateinit var episodes: TextView
        episodes=addButton(options,"选集") { showEpisodePanel(episodes) }
        lateinit var more: TextView
        more=addButton(options,"播放设置") {
            tvTools.choose("播放设置",listOf("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}","清晰度 ${library.maxQuality}P","画面 ${library.frameMode.label}","定时 ${sleepTimer.label()}","从头播放","弹幕 ${if(library.danmakuEnabled) "开" else "关"}","弹幕大小 ${library.danmakuSize.label}","弹幕行数 ${DanmakuRows.label(library.danmakuRows)}"),more,{ choice ->
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
                    }
                }
            })
        }.apply { tag="playback-settings" }
        // 「剧集详情」：回到这部剧的详情页接着挑集。forceDetail 是因为直接从「接着看」进播放的话
        // 本来就没有详情页可回，但既然是用户明确点的，就该把详情页画出来。
        addButton(options,"剧集详情") { returnToDetail(true) }
        play.requestFocus()
    }
    private fun showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val selected=speeds.indexOf(library.playbackSpeed)
        val dialog=AlertDialog.Builder(this).setTitle("全局默认倍速")
            .setSingleChoiceItems(speeds.map(PlaybackSpeed::label).toTypedArray(),selected) { popup,index ->
                library.playbackSpeed=speeds[index]
                if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
                if(inPlayer) updatePlaybackText()
                popup.dismiss()
            }.setNegativeButton("取消",null).create()
        speedDialog=dialog
        dialog.setOnDismissListener {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
        dialog.show(); dialog.listView.setSelection(selected); dialog.listView.requestFocus()
    }
    private fun showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val dialog=AlertDialog.Builder(this).setTitle("全局默认清晰度")
            .setSingleChoiceItems(options.map { "${it}P" }.toTypedArray(),options.indexOf(library.maxQuality)) { popup,index ->
                val target=options[index]; val changed=library.maxQuality!=target
                library.maxQuality=target; popup.dismiss()
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P"
                if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            }.setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
    }
    private fun showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        val next=EpisodePanel(this,data.episodes.size,episodeIndex,
            { current,view,located -> tvTools.episodePicker(data.episodes.size,current,view,located) },
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected) }
            },
            {
                episodePanel=null
                if(resumeOnClose && foreground && generation==ticket && screen=="player") {
                    if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                    if(anchor.isAttachedToWindow) anchor.requestFocus()
                    if(overlayWasVisible) showHud() else hidePlaybackOverlay()
                } else if(foreground && generation==ticket && screen=="player") {
                    // 换了集数：showPanel 留下的控制条是上一集的快照，必须丢掉再重建，否则
                    // 「上一集/下一集」的可用状态和播放/暂停文字都停在旧集数上。
                    hidePanel(); showPanel()
                }
            })
        episodePanel=next; next.show()
    }
    private fun hidePanel() { if(playError) return; panel=false; controls.visibility=View.GONE; controls.clearFocus(); showHud() }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        // 用 core 里那个真名，不用同模块的 typealias：跨文件引用 typealias 在增量编译时
        // 会偶发解析不到（报「Unresolved reference 'WatchProgress'」，而那个符号就在眼前）。
        library.save(BackupProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis()))
    }
    private fun releasePlayer() {
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.close(); video=null; playerView?.player=null; playerView=null; player?.release(); player=null; playbackReady=false; transportPlay=null; sleepButton=null
        // 弹幕也要停：不然它会接着按上一集的位置往下画，而画面已经不是那一集了。
        danmakuSession.stop(); danmakuOverlay?.stop(); danmakuOverlay=null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun returnToDetail(forceDetail: Boolean=false) {
        sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
    }
    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        when(screen) {
            "player" -> if(hud.visibility==View.VISIBLE && !playError) hidePlaybackOverlay() else returnToDetail()
            "detail" -> showCatalog()
            else -> if(collection!=null) { leaveCollection(); showCatalog() } else if(nav.any { it.hasFocus() }) { if(tab!=TAB_HOME) { switchTab(TAB_HOME); nav[TAB_HOME].requestFocus() } else finish() } else nav.getOrNull(tab)?.requestFocus()
        }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU)) {
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> seek(1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) showEpisodePanel(hud)
                        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_MENU -> if(event.repeatCount==0) showPanel()
                        else -> showHud()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            "player" -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "detail" -> if(detail!=null) showDetail(false)
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        updater.resume()
        if(library.isLoaded) {
            if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                    else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && fetchesRemote(tab) && (tab!=TAB_SEARCH || query.isNotBlank()))
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
    }
    override fun onPause() {
        updater.pause()
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); cancelPagePrefetch(); mediaSession.deactivate()
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); saveProgress(); library.flush(); super.onPause()
    }
    override fun onStop() {
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        if(screen=="player") { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(::artwork.isInitialized) artwork.trimMemory(level)
        // 内存吃紧：备着的那两集是猜的，画面和目录页是真的，先放掉猜的那一两兆。
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) { cancelPrefetch(); cancelPagePrefetch(); preparedPage.clear() }
    }
    override fun onDestroy() {
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); updater.destroy(); saveProgress(); releasePlayer(); library.close()
        // 备着的那两集放掉：活动没了，它们也没人要了。
        pagePreparation.cancel(); nextPreparation.cancel()
        preparedPage.clear(); preparedNext.clear()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); pageWorker.shutdownNow()
        danmakuSession.close()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
    }
}
