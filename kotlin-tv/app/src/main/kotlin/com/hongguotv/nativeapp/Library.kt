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
        /** Shows between disk checkpoints. Re-encoding 1600 rows costs a J1900 real time. */
        const val CHECKPOINT_EVERY = 200
    }
    private data class Loaded(
        val index: LibraryIndex,
        val homes: Map<ContentType, CatalogPage>,
        val stamps: Map<ContentType, Long>,
        val catalogue: CatalogueSnapshot?
    )
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    private val cataloguePrefs by lazy { app.getSharedPreferences("native-catalogue-index-v1", Context.MODE_PRIVATE) }
    private val writes = LatestWriteQueue<String, () -> Any?>("hongguotv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HongguoTV", "Local library commit failed; pending changes retained for retry") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HongguoTV", "Home cache commit failed") })
    private val catalogueWrites = LatestWriteQueue<String, () -> Any?>("hongguotv-catalogue-write", write = { batch -> commit(cataloguePrefs, batch) },
        failed = { Log.w("HongguoTV", "Catalogue index commit failed; the index is rebuilt next launch") })
    private val catalogueLoader = Executors.newSingleThreadExecutor { task -> Thread(task, "hongguotv-catalogue-build") }
    private var index: LibraryIndex? = null
    private val homes = mutableMapOf<ContentType, CatalogPage>()
    private val homeSavedAt = mutableMapOf<ContentType, Long>()
    private var catalogue: CatalogueSnapshot? = null
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
                Loaded(loaded, cached, stamps, builtCatalogue)
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { loaded ->
                    index = loaded.index; homes.putAll(loaded.homes); homeSavedAt.putAll(loaded.stamps)
                    catalogue = loaded.catalogue
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
     * 1590 reachable - see [CatalogueIndex].
     */
    fun catalogue() = catalogue?.index
    /** How many shows the index currently holds; 0 until a build lands its first pages. */
    fun catalogueSize() = catalogue?.size ?: 0
    fun isBuildingCatalogue() = catalogueBuilding
    /**
     * True when the walk reached the end of every catalogue and skipped no page.
     *
     * This is what stops a completed index from being rebuilt on every launch: without it, each
     * start would spend another 68 serial requests re-collecting shows it already has on disk.
     */
    fun isCatalogueComplete() = catalogue?.complete == true

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
        // Touched only on the build thread; the main thread ever sees the immutable snapshots.
        val collected = (carried?.entries ?: emptyList()).toMutableList()
        // Forcing forgets the bookmark and rewalks from page one. The shows already held are kept
        // and deduped by id, so the second pass adds nothing and only repairs the pages that failed
        // the first time - which is what "retry the walk" has to mean once a pass has finished.
        val resume = if (force) emptyMap() else carried?.cursor ?: emptyMap()
        val done = if (force) emptySet() else carried?.done ?: emptySet()
        var checkpointed = collected.size
        catalogueLoader.execute {
            val walk = CatalogueBuilder(repository).build(
                resume = resume,
                done = done,
                isCancelled = { closed },
                onPage = { step ->
                    collected += step.fresh
                    val snapshot = collected.toList()
                    val total = snapshot.size
                    // WalkStep.total counts this pass only; a resumed build carries a thousand shows
                    // it never re-fetched, and the number the user reads has to include them.
                    main.post { if (!closed) { catalogue = CatalogueSnapshot(snapshot, step.cursor, step.done); onProgress(total) } }
                    // Checkpointing on every page would re-encode the whole library 68 times on a
                    // box that can barely parse one of them.
                    if (total - checkpointed >= CHECKPOINT_EVERY) {
                        checkpointed = total
                        checkpoint(snapshot, step.cursor, step.done)
                    }
                }
            )
            checkpoint(collected, walk.cursor, walk.done)
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
    private fun checkpoint(collected: List<Series>, cursor: Map<ContentType, Int>, done: Set<ContentType>) {
        val snapshot = collected.toList()
        val now = System.currentTimeMillis()
        catalogueWrites.submit(mapOf("index" to { CatalogueIndexDisk.encode(snapshot, cursor, done, now) }))
    }
    /** Drops a stale index so the next build starts from a clean file rather than a merged one. */
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
    fun flush() { writes.flush(); homeWrites.flush(); catalogueWrites.flush() }
    /** Drain pending commits after Activity destruction; abrupt process death is still a boundary. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown(); catalogueLoader.shutdownNow()
        synchronized(retiredWrites) {
            writes.close(); homeWrites.close(); catalogueWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites; retiredWrites += catalogueWrites
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
