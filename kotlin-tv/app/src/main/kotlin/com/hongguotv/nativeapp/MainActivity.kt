// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
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
        const val TAB_HOME=0
        const val TAB_SEARCH=1
        const val TAB_RANKING=2
        const val TAB_FAVORITES=3
        const val TAB_HISTORY=4
        const val TAB_SETTINGS=5
        val TAB_LABELS=listOf("首页","搜索","排行榜","收藏","最近观看","设置")
    }
    private val bg=TvStyle.background; private val surface=TvStyle.surface
    private val accent=TvStyle.accent; private val white=TvStyle.text; private val muted=TvStyle.muted
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val repository=ContentRepository()
    private val networkCleanup=Executors.newSingleThreadExecutor()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    private var prefetchHttp: okhttp3.OkHttpClient?=null
    private var playbackHttp: okhttp3.OkHttpClient?=null
    private val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() },PrefetchPolicy.TTL_MS)
    private var prefetchJob: java.util.concurrent.Future<*>?=null
    private var prefetchScope: RequestScope?=null
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
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteStatus: TextView?=null
    private var favoriteCheck: TextView?=null
    private var homeUpdates: TextView?=null
    private val resumeCards=mutableListOf<View>()
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
    /** 当前详情页剧集网格里的按钮，供 [renderSeasons] 接同系列区块的双向焦点。 */
    private var episodeViews: List<View> = emptyList()
    /** 当前详情页里承载「同系列剧」结果的容器；异步回调用它确认自己没画到别的剧上。 */
    private var seasonsHolder: LinearLayout?=null
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: HotRanking?)
    private var ranking: HotRanking?=null
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
        updater=AppUpdater(this) { foreground && library.isLoaded && screen=="catalog" && !tvTools.showing }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
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
        // 一份建完又放了一周的索引该重建了：站方不断上新，旧索引搜不到没见过的剧。
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
            searchMatched=result.matchedCount; searchTotal=result.totalHits; searchFuzzy=result.fuzzy
            val body=catalogBody ?: return@work
            body.removeAllViews(); catalogGrid(body,nav.any { it.hasFocus() },typeButtons.values.any { it.hasFocus() })
            // 失败就留着上一批结果：用户正在看的东西不该被一次网络抖动清空。
        }, { })
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
        episodeViews=emptyList()
        catalogScroll?.let { scrollPositions[catalogPageKey]=it.scrollY }
        catalogBody=null
        homeScreen=null; selectionPreview=null; catalogScroll=null
        nav.clear(); typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear()
        historyManage=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; homeUpdates=null; resumeCards.clear()
        catalogCards.clear(); cardImages.clear()
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
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking); tabFocus[tab]=catalogFocus
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty(); focusColumn=null
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
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; focusColumn=null; homePosition=null
        // query 可能还是上一次搜索留下的。非空不等于现在在搜索页 —— 在首页上切分类，
        // 按 query 非空去发请求会把搜索结果画到首页上。
        showCatalog(load=(tab==TAB_HOME || tab==TAB_RANKING || (tab==TAB_SEARCH && query.isNotBlank())),focusType=true)
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collectionFocus=catalogFocus
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
                    .apply { isSelected=library.contentType==type; nextFocusUpId=nav[tab].id }
            }
            placeCatalogueRow(top,container,types,"长按确认 · 管理片单")
            nav.forEach { it.nextFocusDownId=typeButtons.getValue(library.contentType).id }
        }
        if(tab==TAB_SEARCH) {
            val searchRow=row(); val input=EditText(this).apply { id=View.generateViewId(); hint="输入剧名或关键词（当前片库全量）"; setText(query); textSize=16f; setTextColor(white); setHintTextColor(muted); isSingleLine=true; maxLines=1; filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
            searchRow.addView(input,lp(0,dp(45)).apply { weight=1f; rightMargin=dp(10) })
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { input.requestFocus(); return }; runSearch(next) }
            searchInput=input; searchButton=addButton(searchRow,"搜索") { search() }
            input.nextFocusUpId=nav[tab].id; searchButton?.nextFocusUpId=input.nextFocusUpId
            // 分类按钮进导航栏右上角，和首页完全同一套。它在搜索页是真的有作用：全库索引记着每部
            // 剧属于哪套目录，而同一个题材词两边都命中 —— 搜「都市」1042 部里 670 部是漫剧，
            // 不分开的话大半结果和用户想要的不是一档。摆法和首页一样，是为了让这个开关在两页
            // 里长得一模一样。
            val types=row().apply { setPadding(0,dp(8),0,dp(6)) }
            if(!inlineCatalogueFilters()) types.addView(text("片库",12f,muted),lp(dp(42),-2))
            ContentType.entries.forEach { type ->
                typeButtons[type]=addButton(types,type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type; nextFocusUpId=input.nextFocusUpId }
            }
            placeCatalogueRow(top,container,types)
            // 导航往下先落到分类按钮，再由它落到输入框 —— 分类按钮和导航同处一行，从导航往下跳到
            // 左下角的输入框会显得突兀。
            nav.forEach { it.nextFocusDownId=typeButtons[library.contentType]?.id ?: input.id }
            lateinit var recent: TextView
            recent=addButton(searchRow,"历史") { showSearchHistory(recent) }; recent.nextFocusUpId=input.nextFocusUpId
            lateinit var phone: TextView
            phone=addButton(searchRow,"手机输入") { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); tvTools.phoneInput(phone) { runSearch(it) } }; phone.nextFocusUpId=input.nextFocusUpId
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
                }; rebuild.nextFocusUpId=input.nextFocusUpId
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
                    .apply { isSelected=library.contentType==type; nextFocusUpId=nav[tab].id }
            }
            placeCatalogueRow(top,container,types)
            // 标题、刷新按钮、站方的更新说明，整行都去掉了：这一页已经用分类按钮说明了是哪一档，
            // 榜单自己会更新（一天一次），用户没有任何需要按的东西。
            nav.forEach { it.nextFocusDownId=typeButtons.getValue(library.contentType).id }
        } else if(tab>TAB_RANKING) {
            val heading=row().apply { setPadding(0,dp(14),0,dp(6)) }
            heading.addView(text(if(tab==TAB_FAVORITES) "我的收藏" else "接着上次看",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(tab==TAB_FAVORITES) {
                favoriteCheck=addButton(heading,"检查更新") { favoriteMonitor.check(force=true) }.apply { nextFocusUpId=nav[tab].id }
            }
            if(tab==TAB_HISTORY && library.history().isNotEmpty()) {
                historyManage=addButton(heading,"管理记录") {
                    val rows=library.history()
                    tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
                        tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                    }
                }.apply { nextFocusUpId=nav[tab].id }
            }
            container.addView(heading)
            if(tab==TAB_FAVORITES) {
                favoriteStatus=text(favoriteMonitor.status,13f,muted).apply { setPadding(0,0,0,dp(6)) }; container.addView(favoriteStatus)
            }
        }
        val body=column(); catalogBody=body; container.addView(body,lp(-1,0).apply { weight=1f })
        if(localCatalog()) {
            catalog=localItems(); ranking=null; searchMatched=null; searchTotal=null; searchFuzzy=false; page=page.coerceIn(1,maxOf(1,(catalog.size+pageSizeFor(tab)-1)/pageSizeFor(tab))); hasMore=page*pageSizeFor(tab)<catalog.size
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
                    homeScreen?.refresh?.setOnClickListener { catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; showCatalog() }
                } else {
                    catalog=result.items; hasMore=result.hasMore; ranking=result.ranking
                    searchMatched=result.matchedCount; searchTotal=result.totalHits; searchFuzzy=result.fuzzy
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
                catalog=emptyList(); hasMore=false; ranking=null; searchMatched=null
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                    if(resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
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
        resumeCards.clear(); catalogCards.clear(); cardImages.clear(); collectionBack=null
        if(collection!=null) {
            val heading=row()
            collectionBack=addButton(heading,"‹ 返回首页") { leaveCollection(); showCatalog() }.apply {
                nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[tab].id
                nextFocusDownId=id
            }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "hot" -> "热门发现"; else -> "接着看" },22f))
            body.addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId=collectionBack!!.id }
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
            message(list,if(loading) "正在加载推荐…" else when(tab) { TAB_SEARCH -> if(query.isBlank()) "输入关键词，用遥控器确认搜索" else "没有找到相关剧，换个关键词试试"; TAB_FAVORITES -> "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; TAB_HISTORY -> "播放过的剧集会自动保存在这里"; else -> "本页没有更多内容" })
            // 没结果可跳时，searchInput / searchButton / searchActions 的 ↓ 不能再指 cards.first()，
            // 那是个已经不存在的旧 id，焦点会卡死。分页按钮（如果存在）是最自然的 ↓ 落点；
            // 没有分页按钮就指回 nav，至少不指向过期引用。
            val prevBtn=if((tab<=TAB_RANKING || localCatalog()) && page>1) addButton(list,"上一页") { page--; showCatalog(!localCatalog()) } else null
            val nextBtn=if((tab<=TAB_RANKING || localCatalog()) && hasMore) addButton(list,"下一页") { page++; showCatalog(!localCatalog()) } else null
            val emptyTarget=(nextBtn ?: prevBtn)?.id ?: nav[tab].id
            searchInput?.nextFocusDownId=emptyTarget
            searchButton?.nextFocusDownId=emptyTarget
            searchActions.forEach { it.nextFocusDownId=emptyTarget }
            if(resumeCards.isNotEmpty()) {
                typeButtons.values.forEach { it.nextFocusDownId=resumeCards.first().id }
                resumeCards.forEach { it.nextFocusDownId=it.id }
            }
            val anchorView=focusAnchorView(focusAnchor)
            if(anchorView!=null) anchorView.requestFocus()
            else if(focusInput) searchInput?.requestFocus() else if(focusType) typeButtons[library.contentType]?.requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else if(focusNav || resumeCards.isEmpty()) nav[tab].requestFocus() else resumeCards.first().requestFocus()
            return
        }
        // The site answers every query with ten same-genre rows and never an empty list, so a search
        // that matched nothing still looked like ten confident results — 搜「庆余年」came back as
        // 《剑神归来》. Say so, then keep the rows: 搜「总裁」legitimately reaches shows that merely
        // contain the word, and those are worth showing. A TextView is not focusable, so the D-pad
        // chain below still starts on the first card.
        if(tab==TAB_SEARCH && query.isNotBlank()) {
            if(searchFuzzy) list.addView(text("没有完全匹配《$query》的剧，以下是相近剧名",15f,muted).apply { setPadding(dp(4),dp(2),dp(4),dp(6)) },lp(-1,-2))
            else if(searchMatched==0) list.addView(text("站内没有《$query》，以下是同类推荐",15f,muted).apply { setPadding(dp(4),dp(2),dp(4),dp(6)) },lp(-1,-2))
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
                else -> "全库索引还没建起来 · 现在只能搜到站内的十条结果"
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
        val rows=mutableListOf<List<View>>()
        displayed.chunked(maxColumns).forEach { items ->
            val columns=items.size
            // 每行都左对齐。曾经只把「孤零零一张卡」的那行居中，理由是让它看起来不像没渲染出来 ——
            // 但同一页里别的行全是左对齐，唯独这行飘到中间，左边线在这里断开，反而更像出了错。
            // 统一靠左，海报列才是齐的。
            val line=row().apply { gravity=Gravity.TOP }
            val built=mutableListOf<View>()
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
                line.addView(card,lp(cardWidth,-1).apply { if(columnIndex<columns-1) rightMargin=gap }); built+=card; cards+=card
            }
            list.addView(line,lp(-1,-2).apply { bottomMargin=dp(10) })
            rows+=built
        }
        val paging=row(); paging.gravity=Gravity.CENTER
        var prev: View?=null; var next: View?=null; var pageJump: View?=null
        if(tab<=TAB_RANKING || localCatalog()) {
            prev=addButton(paging,"上一页") { if(page>1) turnPage(page-1) }.apply { isEnabled=page>1; isFocusable=page>1; alpha=if(page>1) 1f else .4f }
            // 页码本身是个按钮：翻到第几页不该一页一页按。
            val jump=button(pagingLabel()) { }.apply { gravity=Gravity.CENTER; textSize=14f; setTextColor(muted) }
            jump.setOnClickListener { showPagePicker(jump) }
            pageJump=jump
            paging.addView(jump,lp(dp(
                when {
                    tab==TAB_RANKING -> 135
                    tab==TAB_SEARCH && searchTotal!=null -> 210
                    else -> 95
                }),dp(44)))
            next=addButton(paging,"下一页") { if(hasMore) turnPage(page+1) }.apply { isEnabled=hasMore; isFocusable=hasMore; alpha=if(hasMore) 1f else .4f }
            // 翻页这一行自己排成一条：上一页 → 页码 → 下一页。
            prev?.let { it.nextFocusRightId=jump.id; jump.nextFocusLeftId=it.id }
            jump.nextFocusRightId=next?.id ?: jump.id
            next?.let { it.nextFocusLeftId=jump.id }
            if(collection=="hot" && !hasMore && collectionReturn?.hasMore==true) {
                next=addButton(paging,"下一批热门") { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) }
            }
            list.addView(paging,lp(-1,dp(50)))
        }
        // 行内左右只在本行的卡片之间走；上下按列号对齐到相邻行，短行的列号越过邻行长度时落到
        // 那行最右一张。卡片宽度虽然统一了，但末行张数不同，用全局下标推邻卡会指错。
        rows.forEachIndexed { rowIndex,rowCards ->
            val above=rows.getOrNull(rowIndex-1); val below=rows.getOrNull(rowIndex+1)
            rowCards.forEachIndexed { columnIndex,card ->
                card.nextFocusLeftId=if(columnIndex==0) card.id else rowCards[columnIndex-1].id
                card.nextFocusRightId=if(columnIndex==rowCards.lastIndex) card.id else rowCards[columnIndex+1].id
                // 第一排海报的上方，贴着它的是这一页的分类按钮所在的那一行：
                // 上下键该是对称的，不该从海报一下跳到导航栏去。
                card.nextFocusUpId=if(above==null) (searchInput?.id ?: collectionBack?.id ?: typeButtons[library.contentType]?.id ?: favoriteCheck?.id ?: historyManage?.id ?: resumeCards.getOrNull(minOf(columnIndex,2))?.id ?: nav[tab].id) else above[columnIndex.coerceAtMost(above.lastIndex)].id
                // 最后一排往下落到翻页那一行：上一页（第一页时不可选）→ 页码 → 下一页。
                card.nextFocusDownId=if(below==null) (prev?.takeIf { it.isFocusable }?.id ?: pageJump?.id ?: next?.takeIf { it.isFocusable }?.id ?: card.id) else below[columnIndex.coerceAtMost(below.lastIndex)].id
            }
        }
        nav.forEach { it.nextFocusDownId=typeButtons[library.contentType]?.id ?: favoriteCheck?.id ?: historyManage?.id ?: cards.first().id }
        historyManage?.nextFocusDownId=cards.first().id
        favoriteCheck?.nextFocusDownId=cards.first().id
        collectionBack?.nextFocusDownId=cards.first().id
        resumeCards.forEachIndexed { index,v -> v.nextFocusDownId=cards[minOf(index,cards.lastIndex)].id }
        val firstRow=cards.take(maxColumns)
        if(resumeCards.isNotEmpty()) firstRow.forEachIndexed { index,v -> v.nextFocusUpId=resumeCards[minOf(index,resumeCards.lastIndex)].id }
        else homeUpdates?.let { updates -> updates.nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[TAB_HOME].id; updates.nextFocusDownId=cards.first().id; firstRow.forEach { it.nextFocusUpId=updates.id } }
        // 分类按钮往下直接轮到海报。
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: collectionBack?.id ?: resumeCards.firstOrNull()?.id ?: homeUpdates?.id ?: cards.first().id }
        // 上一行把导航往下接到了分类按钮，这一行再把最后一排接回海报。
        // 少了它 Android 会按几何位置乱找，遥控器从分类按钮直接落到导航栏，网格就选不中。
        // 搜索页没有分类按钮，searchInput 自己往下走一跳。
        // searchRow 里 input → 搜索 → 历史 → 手机输入 → (续建索引) 从左到右一字排开，
        // 原本都跳 cards[4] 让「搜索」按 ↓ 直接横移到右侧第 5 张，体验上是一跳。改成按 searchRow
        // 内的列号分发，每一列 ↓ 落在最近的那张海报上，几何和焦点一致。
        searchInput?.nextFocusDownId=cards.first().id
        searchButton?.nextFocusDownId=cards.getOrNull(1)?.id ?: cards.first().id
        searchActions.forEachIndexed { i,view -> view.nextFocusDownId=cards.getOrNull(i+2)?.id ?: cards.last().id }
        val anchorView=focusAnchorView(focusAnchor)
        if(anchorView!=null) anchorView.requestFocus()
        else if(focusInput) searchInput?.requestFocus() else if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusNav) nav[tab].requestFocus()
        else if(resumeCards.isNotEmpty() && (catalogFocus.isEmpty() || catalogFocus.startsWith("resume:"))) (resumeCards.firstOrNull { it.tag==catalogFocus } ?: resumeCards.first()).requestFocus()
        else {
            // 还记得是那张卡就还在那张卡上；那张卡这一页没有了（翻页、换词）就退到同一格 ——
            // 那一格是翻页前记下的序号，与页长无关，所以搜索页不会再每翻一页就丢一次焦点。
            val index=catalogFocus.takeIf { it.isNotEmpty() }?.let { id -> displayed.indexOfFirst { it.id==id } }?.takeIf { it>=0 }
                ?: focusColumn?.takeIf { it<displayed.size }
            cards[index ?: 0].requestFocus()
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
        val top=if(inlineCatalogueFilters()) nav[TAB_HOME] else typeButtons.getValue(library.contentType)
        val home=HomeScreen(this,top,::loadCover,{ item,label -> preview.show(item,if(label.startsWith("第 ")) selectionStatus(item) else listOf(label,selectionStatus(item)).filter { it.isNotBlank() }.joinToString(" · ")) },
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        val hot=catalog.filterNot { it.id in library.hidden() }
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType,
            if(hasMore) { { page=2; catalogFocus=""; showCatalog(true) } } else null,homePosition)
        home.refresh.text=if(loading) if(homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        typeButtons.values.forEach { it.nextFocusDownId=home.firstId() }
        if(inlineCatalogueFilters()) nav.forEach { it.nextFocusDownId=home.firstId() }
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
        homeUpdates?.text="收藏更新 ${library.updatedFavorites()} 部"
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
        lateinit var backup: TextView
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
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
        val left=listOf(qualityButton,defaultSpeed,frame,autoNextButton); val right=listOf(backup,hidden,updates,license)
        listOf(left,right).forEach { items -> items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: nav[tab].id
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        } }
        left.forEachIndexed { i,v -> v.nextFocusLeftId=v.id; v.nextFocusRightId=right[i].id }
        right.forEachIndexed { i,v -> v.nextFocusLeftId=left[i].id; v.nextFocusRightId=v.id }
        nav.forEach { it.nextFocusDownId=qualityButton.id }
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
     * 翻页后焦点回到新页的同一格。那一格由 [focusColumn] 记着 —— 翻页前那张卡在它自己那一页
     * 里的序号。原先是「按页长切片再取下标」重算 catalogFocus，而切片用的页长和搜索页真正的
     * 页长不是一个数，下标对第 19 张以后的卡片全是错的，于是每翻一页焦点就跳回第 1 张。
     */
    private fun turnPage(target: Int) {
        page=target
        // 旧页上那张卡这一页没有了，焦点得重新放。放哪一格由 focusColumn 记着 —— 那是翻页前
        // 那张卡在它自己那一页里的序号，与页长无关，所以搜索页不会再每翻一页就丢一次焦点。
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
        searchMatched=result.matchedCount; searchTotal=result.totalHits; searchFuzzy=result.fuzzy
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

    /** 「第 X / Y 页 · 共 N 部」；只有搜索知道命中总数，其它页维持原来的写法。 */
    private fun pagingLabel(): String = when {
        localCatalog() -> "第 $page / ${pageCount()} 页"
        tab==TAB_RANKING && ranking!=null -> "第 $page / ${pageCount()} 页"
        tab==TAB_SEARCH && (searchTotal ?: 0)>0 -> "第 $page / ${pageCount()} 页 · 共 $searchTotal 部"
        else -> "第 $page 页"
    }
    /** 这一页一共有几页。 */
    private fun pageCount(): Int = when {
        localCatalog() -> maxOf(1,(catalog.size+pageSizeFor(tab)-1)/pageSizeFor(tab))
        tab==TAB_RANKING && ranking!=null -> ranking!!.totalPages
        tab==TAB_SEARCH && (searchTotal ?: 0)>0 -> maxOf(1,((searchTotal ?: 0)+pageSizeFor(tab)-1)/pageSizeFor(tab))
        else -> 1
    }
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
        searchMatched=null; searchTotal=null; searchFuzzy=false; showCatalog(true)
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
        // 最后一行不设 nextFocusDownId：写成指向自己会把下键困在网格里，「同系列剧」区块就在
        // 网格下方，那样就永远按不到季度卡了。留空让系统的默认向下搜索落过去。
        episodes.forEachIndexed { i,v -> v.nextFocusLeftId=if(i%10==0) v.id else episodes[i-1].id; v.nextFocusRightId=if(i%10==9 || i==episodes.lastIndex) v.id else episodes[i+1].id; if(i>=10) v.nextFocusUpId=episodes[i-10].id; if(i+10<episodes.size) v.nextFocusDownId=episodes[i+10].id }
        // 「同系列剧」排在网格之后。两个方向的连线都在 renderSeasons 里接：网格最后一行向下
        // 落到第一张季度卡、最后一张季度卡向上回到网格。只靠系统的默认几何搜索，两个方向都
        // 不可靠 —— 之前「下」被 v.id 自锁、「上」直接落空，都是这么来的。
        episodeViews=episodes.toList()
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
        // 纵向列表，焦点只连上下；连左右会让上下键在某些布局下被劫持。
        // 两端都接回剧集网格：写成指向自己会把方向键自锁在区块里（第一张卡按「上」回不去
        // 选集、最后一张按「下」掉不下来）。网格为空时退回自锁，避免连到已销毁的视图。
        val grid=episodeViews
        cards.forEachIndexed { i,v ->
            val upTarget=if(i==0) grid.lastOrNull()?.id else cards[i-1].id
            val downTarget=if(i==cards.lastIndex) grid.firstOrNull()?.id else cards[i+1].id
            v.nextFocusUpId=upTarget ?: v.id
            v.nextFocusDownId=downTarget ?: v.id
        }
        // 网格最后一行向下 → 第一张卡。网格每行只连了行内和同列的上一行，末行没有下行目标，
        // 不显式接的话系统的默认几何搜索会跳过头（之前就是直接落空）。
        grid.lastOrNull()?.let { last -> cards.firstOrNull()?.let { first -> last.nextFocusDownId=first.id } }
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false) {
        updater.interrupt()
        val data=detail ?: return
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val prefetched=if(position==0L && !recovering) preparedNext.take("${data.episodes[selectedIndex]}:${library.maxQuality}") else null
        val claimedHttp=if(prefetched!=null) prefetchHttp.also { prefetchHttp=null } else null
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            player?.stop(); player?.clearMediaItems()
            video?.close(); video=null; closeTransport(playbackHttp); playbackHttp=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        playbackHttp=claimedHttp; video=prefetched
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
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
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
        work({ prefetched ?: RemoteVideo(repository.http,repository.stream(data.series.id,requestedId,maxQuality)).prepare() }, { remote ->
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
        }, { problem -> playerFailure(problem) })
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun cancelPrefetch() {
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(readySince==0L) return
        // 下一集提前准备：本集一播稳就动手，而不是片尾 30 秒才动手 —— 那点时间不够取签名
        // 地址加预热 1MB，下一集开始时往往还没准备好，于是每次自动连播都要先等一下。
        if(!PrefetchPolicy.shouldPrepare(now-readySince,p.bufferedPosition-p.currentPosition)) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
        if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelPrefetch(); prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key)
        val http=okhttp3.OkHttpClient.Builder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
        val source=ContentRepository(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        prefetchJob=prefetchWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val prepared=scope.run {
                    RemoteVideo(http,source.stream(data.series.id,id,quality)).also { remote=it; it.warm() }
                }
                if(preparedNext.complete(ticket,key,prepared)) android.util.Log.i("HongguoTV","Next episode prepared")
            } catch(_: Exception) { remote?.close() }
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
        playbackText.text=if(decoding) "电视无法解码当前视频，可尝试更低清晰度。" else "播放失败，可重试或切换清晰度。"
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
    private fun frameResizeMode()=when(library.frameMode) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
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
            tvTools.choose("播放设置",listOf("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}","清晰度 ${library.maxQuality}P","画面 ${library.frameMode.label}","定时 ${sleepTimer.label()}","从头播放"),more,{ choice ->
                main.post {
                    if(screen=="player" && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> showSleepPicker(more)
                        4 -> { seekTo(0); hidePanel() }
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
        library.save(WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis()))
    }
    private fun releasePlayer() {
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.close(); video=null; playerView?.player=null; playerView=null; player?.release(); player=null; playbackReady=false; transportPlay=null; sleepButton=null
        closeTransport(playbackHttp); playbackHttp=null
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
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
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
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }
    override fun onDestroy() {
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); updater.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
    }
}
