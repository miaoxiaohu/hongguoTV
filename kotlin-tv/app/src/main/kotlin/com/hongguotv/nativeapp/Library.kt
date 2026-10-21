// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The backup and native library use the same immutable progress shape.
typealias WatchProgress = BackupProgress

/** Memory-only UI access; disk reads, JSON serialization and commits run on workers. */
class Library(context: Context) {
    private companion object {
        // A replacement Activity must not load before its predecessor's final checkpoint commits.
        val retiredWrites = mutableListOf<LatestWriteQueue<String, () -> Any?>>()
        /** Shows between disk checkpoints. Re-encoding 2400 rows costs a J1900 real time. */
        const val CHECKPOINT_EVERY = 200
    }
    private data class Loaded(
        val index: LibraryIndex,
        val homes: Map<ContentType, CatalogPage>,
        val stamps: Map<ContentType, Long>,
        val catalogue: CatalogueSnapshot?,
        val ranking: HotRank.Cache
    )
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    private val cataloguePrefs by lazy { app.getSharedPreferences("native-catalogue-index-v1", Context.MODE_PRIVATE) }
    private val rankingPrefs by lazy { app.getSharedPreferences("native-ranking-cache-v1", Context.MODE_PRIVATE) }
    private val writes = LatestWriteQueue<String, () -> Any?>("hongguotv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HongguoTV", "Local library commit failed; pending changes retained for retry") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HongguoTV", "Home cache commit failed") })
    private val catalogueWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-catalogue-write", write = { batch -> commit(cataloguePrefs, batch) },
        failed = { Log.w("HongguoTV", "Catalogue index commit failed; the index is rebuilt next launch") })
    private val rankingWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-ranking-write", write = { batch -> commit(rankingPrefs, batch) },
        failed = { Log.w("HongguoTV", "Ranking cache commit failed; the list is fetched again next launch") })
    private val catalogueLoader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-catalogue-build") }
    private var index: LibraryIndex? = null
    private val homes = mutableMapOf<ContentType, CatalogPage>()
    private val homeSavedAt = mutableMapOf<ContentType, Long>()
    private var catalogue: CatalogueSnapshot? = null
    /**
     * The hot lists, one per shelf, kept on disk so the next launch has something to show.
     *
     * It is replaced when the library is loaded, on the main thread, and read from a worker
     * thread while a list is being fetched - so the reference itself has to be visible to the
     * reader, and the cache it points at is safe to read and write at the same time.
     */
    @Volatile private var rankingCache = HotRank.Cache()
    private var catalogueBuilding = false
    private var loading = false
    private var closed = false
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    /** Call before showing the first page. Successful/error callbacks run on the main thread. */
    fun loadAsync(onFailure: (Throwable) -> Unit = { Log.e("HongguoTV", "Local library failed to load", it) }, onReady: () -> Unit) {
        check(!closed)
        if (isLoaded) { onReady(); return }
        readyCallbacks += onReady
        errorCallbacks += onFailure
        if (loading) return
        loading = true
        loader.execute {
            val result = runCatching {
                val retired = synchronized(retiredWrites) { retiredWrites.toList() }
                retired.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) { "上次观看记录仍在保存，请稍后重试" } }
                synchronized(retiredWrites) { retiredWrites.removeAll(retired.toSet()) }
                val values = prefs.all
                val loaded = LibraryDisk.decode(values)
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<ContentType, CatalogPage>()
                val stamps = mutableMapOf<ContentType, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                ContentType.entries.forEach { type ->
                    val key = "home-${type.storedValue}"
                    val legacy = values[key] as? String
                    val raw = homePrefs.getString(key, null) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[type] = page
                        stamps[type] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homePrefs.contains(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                // Only remove legacy cache after the separate cache has been committed.
                if (oldKeys.isNotEmpty() && migration.commit()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                // The full-catalogue index is independent of the user's library: a corrupt or
                // missing one costs search breadth, not favorites, so it is read separately and
                // simply leaves null behind.
                val builtCatalogue = cataloguePrefs.getString("index", null)?.let { CatalogueIndexDisk.decode(it, now) }
                // 榜单也是上一份就能先看的东西：它不是用户的记录，坏了也只影响「热播榜」这一页。
                // 两档各存各的：真人剧一个榜，漫剧两个榜合成一张。把两档塞进同一份里，
                // 切档时就会拿另一档的榜来画，而这一档自己的榜再也取不回来。
                val builtRanking=HotRank.Cache()
                ContentType.entries.forEach { type ->
                    HotRank.decode(rankingPrefs.getString(HotRank.Cache.key(type),null) ?: "")?.let { builtRanking.put(it) }
                }
                Loaded(loaded, cached, stamps, builtCatalogue, builtRanking)
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { loaded ->
                    index = loaded.index; homes.putAll(loaded.homes); homeSavedAt.putAll(loaded.stamps)
                    catalogue = loaded.catalogue; rankingCache = loaded.ranking
                    ready.forEach { it() }
                }.onFailure { failure -> errors.forEach { it(failure) } }
            }
        }
    }
    private fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun put(key: String, value: Any?) { writes.submit(mapOf(key to { value })) }

    /**
     * The whole-catalogue search index, or null while there is none worth searching.
     *
     * Search on its own can only see the ten rows the site renders. This is what makes the other
     * 2390 reachable - see [CatalogueIndex].
     */
    fun catalogue() = catalogue?.index
    /** How many shows the index currently holds; 0 until a build lands its first pages. */
    fun catalogueSize() = catalogue?.size ?: 0
    fun isBuildingCatalogue() = catalogueBuilding
    /**
     * True when the walk reached the end of every catalogue and skipped no page.
     *
     * This is what stops a completed index from being rebuilt on every launch: without it, each
     * start would spend another 102 serial requests re-collecting shows it already has on disk.
     */
    fun isCatalogueComplete() = catalogue?.complete == true
    /** Whether every indexed show records the catalogue it came from; until then 分开搜 cannot split. */
    fun isCatalogueLabelled() = catalogue?.labelled == true
    /**
     * The hot list of one shelf, or null when there is none yet.
     *
     * It is kept on disk so the ranking page has something to show before the network answers -
     * the page then updates itself in the background instead of making the user wait and press
     * anything.
     *
     * The shelf is part of the question, not of the answer: [type] asks for one shelf's list and
     * gets that shelf's list or nothing. A list belonging to the other shelf is not a fallback
     * here - it is the wrong board, and showing it under this shelf's heading is worse than
     * showing an empty page that fills in a moment later.
     */
    fun cachedRanking(type: ContentType) = rankingCache.get(type)
    /**
     * The list of one shelf, but only while it is still young enough to show.
     *
     * "Young enough" is one rule, so it is asked in one place: the page draws whatever it has and
     * refreshes in the background, while the request itself only reuses a list that has not gone
     * stale. Two copies of that rule drift apart, and the one that drifts is the one that decides
     * whether the site's rate is worth spending.
     */
    fun freshRanking(type: ContentType) = rankingCache.fresh(type, System.currentTimeMillis())
    /** Keeps a freshly fetched hot list, in memory and on disk, under the shelf it was fetched for. */
    fun saveRanking(list: HotRank.HotList) {
        rankingCache.put(list)
        rankingWrites.submit(mapOf(HotRank.Cache.key(list.type) to { HotRank.encode(list) }))
    }
    /**
     * True when the index is old enough that a rebuild is due.
     *
     * Old is not the same as broken. The index keeps serving - it is still thousands of shows the
     * site has listed - and the rebuild runs behind it, so the number on screen never drops to
     * zero on the way to a fresher number.
     */
    fun isCatalogueStale() = catalogue?.isStale(System.currentTimeMillis()) == true

    /**
     * Walks both catalogues in the background and publishes the result as it arrives.
     *
     * Returns false when a build is already running or the library is closed. [onProgress] runs on
     * the main thread with the running total, so the search tab can show 「已收录 N 部」 while the
     * walk is still going and be useful long before it finishes.
     *
     * The walk resumes: it starts after the page the last one stopped at and appends to what is
     * already on disk. A partial index is therefore kept rather than discarded. The earlier rule -
     * store nothing below 200 shows - looked cautious and was the reason this never worked at all:
     * on a slow box the walk cannot finish inside one session, so every launch restarted at page
     * one, died at the same place, and fell short of the floor again. An index that is half built
     * and says so, and that continues where it stopped, beats a complete absence of one.
     */
    fun buildCatalogue(repository: ContentRepository, force: Boolean = false, onProgress: (Int) -> Unit = {}): Boolean {
        if (catalogueBuilding || closed) return false
        catalogueBuilding = true
        val carried = catalogue
        // 走完了却没记下每部属于哪条货架的索引，必须整趟重走一遍才能补上标签：
        // 标签只有抓的时候才知道，而游标记着「都到头了」，接着走一步都不会走。
        // 已收录的剧仍然留着 —— 丢掉两千多部重新抓，正是这个索引当初建不成的原因。
        val relabel = carried != null && carried.walkedToEnd && !carried.labelled
        // 过期不等于作废。旧的索引照样能用，重建在它背后跑：数字不会掉到 0 再爬回来，
        // 搜索也不会退化成站方那十条。重建出来的会跟旧的一路按 id 合并，数字只增不减。
        val stale = carried != null && carried.isStale(System.currentTimeMillis())
        // Touched only on the build thread; the main thread ever sees the immutable snapshots.
        //
        // 按 id 合并，不是追加：走查器每趟都不记得上一趟，所以重走一遍会把同样的两千多部再返回
        // 一次。追加的话 2400 部会变成 4800 条 —— 屏幕上的数字跟着翻倍，存下去的行数又超过
        // 读的时候允许的上限，下一次启动索引被当成损坏的文件整个扔掉。合并之后，重走只补上
        // 站上新剧和补齐标签，数字始终是这部剧的个数。
        val collected = CatalogueEntries(carried?.entries ?: emptyList(), carried?.routes ?: emptyList())
        // Forcing forgets the bookmark and rewalks from page one. The shows already held are kept
        // and merged by id, so the second pass only adds what is new and repairs the pages that
        // failed the first time - which is what "retry the walk" has to mean once a pass has
        // finished. A stale index is rewalked the same way, and keeps serving throughout.
        val resume = if (force || relabel || stale) emptyMap() else carried?.cursor ?: emptyMap()
        val done = if (force || relabel || stale) emptySet() else carried?.done ?: emptySet()
        var checkpointed = collected.size
        catalogueLoader.execute {
            val walk = CatalogueBuilder(repository).build(
                resume = resume,
                done = done,
                isCancelled = { closed },
                onPage = { step ->
                    collected.add(step.fresh, step.route)
                    val snapshot = collected.entries
                    val snapshotKinds = collected.routes
                    val total = snapshot.size
                    // WalkStep.total counts this pass only; a resumed build carries a thousand shows
                    // it never re-fetched, and the number the user reads has to include them.
                    main.post { if (!closed) { catalogue = CatalogueSnapshot(snapshot, snapshotKinds, step.cursor, step.done, System.currentTimeMillis()); onProgress(total) } }
                    // Checkpointing on every page would re-encode the whole library 102 times on a
                    // box that can barely parse one of them.
                    if (total - checkpointed >= CHECKPOINT_EVERY) {
                        checkpointed = total
                        checkpoint(snapshot, snapshotKinds, step.cursor, step.done)
                    }
                }
            )
            checkpoint(collected.entries, collected.routes, walk.cursor, walk.done)
            main.post { if (!closed) catalogueBuilding = false }
            Log.i(
                "HongguoTV",
                "全库走查：本轮 +${walk.shows.size} 部，合计 ${collected.size} 部，断点 ${walk.cursor}，已完成 ${walk.done.map { it.storedValue }}" +
                    if (walk.gaps.isEmpty()) "" else "，未取到 ${walk.gaps.joinToString("；")}"
            )
        }
        return true
    }
    /** Hands an immutable snapshot to the write thread; encode() runs there, not here. */
    private fun checkpoint(collected: List<Series>, kinds: List<Route?>, cursor: Map<Route, Int>, done: Set<Route>) {
        val snapshot = collected.toList()
        val snapshotKinds = kinds.toList()
        val now = System.currentTimeMillis()
        catalogueWrites.submit(mapOf("index" to { CatalogueIndexDisk.encode(snapshot, snapshotKinds, cursor, done, now) }))
    }
    /** Throws the index away so the next build starts from an empty file rather than a merged one. */
    fun clearCatalogue() { catalogue = null; catalogueWrites.submit(mapOf("index" to { null })) }
    private fun persist(vararg keys: String) {
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            // Each lambda captures an immutable snapshot; JSON is built by the serial writer.
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                "progress" -> state.history().let { rows -> { LibraryDisk.history(rows) } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                else -> error("Unknown library partition")
            }
        }
        writes.submit(batch)
    }
    fun favorites() = state().favorites()
    fun favorite(id: String) = state().favorite(id)
    fun later() = state().later()
    fun queued(id: String) = state().queued(id)
    fun history(): List<WatchProgress> = state().history()
    fun progress(id: String): WatchProgress? = state().progress(id)
    fun searches() = state().searches()
    fun watched(id: String) = state().watched(id)
    fun hidden() = state().hidden()
    fun favoriteUpdate(id: String) = state().favoriteUpdate(id)
    fun updatedFavorites() = state().updatedFavorites()
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        if (it.added > 0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
    } ?: "尚未检查更新"
    fun toggle(series: Series) = state().toggle(series).also { persist("favorites", "favoriteUpdates") }
    fun toggleLater(series: Series) = state().toggleLater(series).also { persist("later") }
    fun save(progress: WatchProgress) { if (state().save(progress)) persist("progress") }
    fun setWatched(id: String, value: Boolean) { if (state().setWatched(id, value)) persist("watched") }
    fun hide(id: String) { state().hide(id); persist("hidden") }
    fun unhideAll() { state().unhideAll(); persist("hidden") }
    fun rememberSearch(query: String, type: ContentType) { state().rememberSearch(query, type); persist("searches") }
    fun clearSearches() { state().clearSearches(); persist("searches") }
    fun removeHistory(id: String) { state().removeHistory(id); persist("progress", "watched") }
    fun clearHistory() { state().clearHistory(); persist("progress", "watched") }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false) {
        if (state().observeFavorite(id, count, now, acknowledge)) persist("favoriteUpdates")
    }
    fun cachedHome(type: ContentType): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (homeSavedAt[type] ?: return null)
        return homes[type].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }
    fun cacheHome(type: ContentType, items: List<Series>, hasMore: Boolean) {
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        homes[type] = CatalogPage(retained, hasMore)
        homeSavedAt[type] = now
        homeWrites.submit(mapOf("home-${type.storedValue}" to { CatalogSnapshot.encode(retained, hasMore, now) }))
    }
    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    var autoNext: Boolean
        get() = state().settings.autoNext
        set(value) { if (value != autoNext) { state().settings = state().settings.copy(autoNext = value); put("autoNext", value) } }
    /** 画面上的弹幕。关掉时既不拉也不画 —— 连请求都不该发出去。 */
    var danmakuEnabled: Boolean
        get() = state().settings.danmaku
        set(value) { if (value != danmakuEnabled) { state().settings = state().settings.copy(danmaku = value); put("danmaku", value) } }
    var contentType: ContentType
        get() = state().settings.type
        set(value) { if (value != contentType) { state().settings = state().settings.copy(type = value); put("contentType", value.storedValue) } }
    var playbackSpeed: Float
        get() = state().settings.speed
        set(value) { val next = PlaybackSpeed.normalize(value); if (next != playbackSpeed) { state().settings = state().settings.copy(speed = next); put("playbackSpeed", next) } }
    var frameMode: VideoFrameMode
        get() = state().settings.frame
        set(value) { if (value != frameMode) { state().settings = state().settings.copy(frame = value); put("frameMode", value.name) } }
    fun snapshot() = state().snapshot()
    fun restore(incoming: BackupData, restoreSettings: Boolean) {
        val merged = LibraryBackup.merge(snapshot(), incoming, restoreSettings)
        index = LibraryIndex(merged, state().updates())
        persist("favorites", "later", "progress", "watched", "hidden", "searches", "favoriteUpdates")
        writes.submit(mapOf("quality" to { merged.settings.quality }, "playbackSpeed" to { merged.settings.speed },
            "autoNext" to { merged.settings.autoNext }, "contentType" to { merged.settings.type.storedValue }, "frameMode" to { merged.settings.frame.name }))
        flush()
    }
    /** Requests prompt background commit, without lifecycle-thread disk waits. */
    fun flush() { writes.flush(); homeWrites.flush(); catalogueWrites.flush(); rankingWrites.flush() }
    /** Drain pending commits after Activity destruction; abrupt process death is still a boundary. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown(); catalogueLoader.shutdownNow()
        synchronized(retiredWrites) {
            // 榜单这一份也要在这里收尾：漏掉它，取到的榜就停在写队列里没落盘，
            // 下次开应用时手上没有，当天取榜的那十个请求白花了。
            writes.close(); homeWrites.close(); catalogueWrites.close(); rankingWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites; retiredWrites += catalogueWrites; retiredWrites += rankingWrites
        }
    }
    private fun commit(target: SharedPreferences, values: Map<String, () -> Any?>): Boolean {
        val edit = target.edit()
        values.forEach { (key, encode) ->
            when (val value = encode()) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported library value")
            }
        }
        return edit.commit()
    }
}
