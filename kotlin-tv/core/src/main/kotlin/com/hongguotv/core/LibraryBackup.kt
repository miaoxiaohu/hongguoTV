// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class BackupProgress(val series: Series,val episodeId: String,val episodeIndex: Int,val position: Long,val duration: Long,val completed: Boolean,val updatedAt: Long) {
    fun json()=JSONObject().put("series",LibraryBackup.seriesJson(series)).put("episodeId",episodeId).put("episodeIndex",episodeIndex).put("position",position).put("duration",duration).put("completed",completed).put("updatedAt",updatedAt)
}
data class BackupSettings(val quality: Int=1080,val speed: Float=1f,val autoNext: Boolean=true,val type: ContentType=ContentType.COMIC,val frame: VideoFrameMode=VideoFrameMode.FIT,val danmaku: Boolean=true) {
    fun json()=JSONObject().put("quality",quality).put("speed",speed.toDouble()).put("autoNext",autoNext).put("type",type.storedValue).put("frame",frame.name).put("danmaku",danmaku)
}
data class BackupData(val favorites: List<Series>,val history: List<BackupProgress>,val watched: Set<String>,val searches: List<RecentSearch>,val settings: BackupSettings,val later: List<Series> = emptyList(),val hidden: Set<String> = emptySet())

object LibraryBackup {
    const val MAX_BYTES=2*1024*1024
    private val idPattern=Regex("[0-9]{1,30}")

    /**
     * The names that only ever mean something inside a network.
     *
     * A cover URL is fetched by the app, and a backup reaches the TV over a QR code - so the URL
     * in it is untrusted input that ends up as an outgoing request. These suffixes are the ones
     * reserved for names that resolve inside a network: `metadata.google.internal` is the
     * metadata service of a cloud host, `home.arpa` is the home network, and the rest are the
     * private-use names a site may use to point at itself.
     */
    private val reservedSuffixes=listOf(".internal",".intranet",".private",".corp",".home",".local",".localhost",".lan",".home.arpa",".test",".example",".invalid")
    /** A host name: two or more labels of letters, digits and dashes, and nothing else. */
    private val hostPattern=Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
    /** An address in any of the notations a URL may use, not only the dotted decimal one. */
    private fun isAddress(host: String): Boolean {
        val labels=host.split('.')
        // A name of nothing but digits is an address in one of its forms: 127.0.0.1, 127.1,
        // 2130706433 - all of them are read as an address by the resolver.
        if(labels.all { it.isNotEmpty() && it.all { c -> c.isDigit() } }) return true
        if(labels.size==4 && labels.all { label ->
            val value=when {
                label.startsWith("0x")->label.removePrefix("0x").toLongOrNull(16)
                label.length>1 && label.startsWith("0")->label.removePrefix("0").toLongOrNull(8)
                else->label.toLongOrNull(10)
            }
            value!=null && value in 0..255
        }) return true
        return labels.size==1 && labels[0].startsWith("0x")
    }
    /**
     * True when the URL may be fetched.
     *
     * The rules keep a request out of the network it sits in: an address in any notation is
     * refused, a name that is reserved for private use is refused, and a name that is not a
     * host name at all - one label, an underscore, an empty label - is refused. What is left is
     * a name that has to be resolved before it can be fetched, and a name that resolves to a
     * private address cannot be recognised without asking DNS - which a decode may not do, and
     * which would be decided once here and once again when the request is made. The fetch path
     * is the place for that check; this one refuses everything that can be refused without it.
     */
    fun isPublicCoverUrl(value: String): Boolean {
        val uri=runCatching { URI(value) }.getOrNull() ?: return false
        val host=uri.host?.lowercase().orEmpty()
        return value.length<=2048 && uri.scheme=="https" && uri.userInfo==null && host.isNotEmpty() &&
            !host.contains(':') && !isAddress(host) && hostPattern.matches(host) &&
            reservedSuffixes.none { host==it.removePrefix(".") || host.endsWith(it) } && value.none(Char::isISOControl)
    }
    private fun cleanCover(value: String): String=if(isPublicCoverUrl(value)) value else ""
    private fun id(value: String)=value.also { require(idPattern.matches(it)) { "剧集标识无效" } }
    private fun cleanText(value: String,max: Int)=value.map { if(it.isISOControl()) ' ' else it }.joinToString("").take(max)
    fun seriesJson(series: Series)=series.copy(title=cleanText(series.title,200),cover=cleanCover(series.cover),description="",badge=cleanText(series.badge,100),tags=cleanText(series.tags,200)).toJson()
    fun encode(data: BackupData): String {
        val result=JSONObject().put("format","hongguotv.library").put("version",1).put("createdAt",System.currentTimeMillis())
            .put("later",JSONArray(data.later.map(::seriesJson))).put("hidden",JSONArray(data.hidden.toList()))
            .put("favorites",JSONArray(data.favorites.map(::seriesJson))).put("history",JSONArray(data.history.map { it.json() }))
            .put("watched",JSONArray(data.watched.intersect((data.favorites.map { it.id }+data.history.map { it.series.id }).toSet()).take(500)))
            .put("searches",JSONArray(SearchHistory.encode(data.searches.map { it.copy(query=cleanText(it.query,80)) }))).put("settings",data.settings.json()).toString()
        require(result.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份超过 2 MB 限制" }
        return result
    }
    private fun JSONObject.number(key: String,max: Long): Long {
        val value=get(key); require(value is Number) { "数值字段无效" }
        val decimal=value.toDouble(); val integer=value.toLong()
        require(decimal.isFinite() && decimal==integer.toDouble() && integer in 0..max) { "数值范围无效" }; return integer
    }
    private fun JSONObject.string(key: String,max: Int): String {
        val value=get(key); require(value is String && value.length<=max && value.none(Char::isISOControl)) { "文字字段无效" }; return value
    }
    private fun JSONObject.rows(key: String,max: Int): List<JSONObject> {
        val array=getJSONArray(key); require(array.length()<=max) { "记录数量超过上限" }
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun series(o: JSONObject): Series = Series(id(o.string("id",30)),o.string("title",200).also { require(it.isNotBlank()) },cleanCover(o.string("cover",2048)),"",o.string("badge",100),o.string("tags",200))
    fun decode(value: String): BackupData {
        require(value.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份超过 2 MB 限制" }
        var depth=0; var quoted=false; var escaped=false
        value.forEach { c ->
            if(escaped) escaped=false
            else if(quoted && c=='\\') escaped=true
            else if(c=='"') quoted=!quoted
            else if(!quoted) { if(c=='{' || c=='[') { depth++; require(depth<=12) { "备份结构过深" } }; if(c=='}' || c==']') depth-- }
        }
        val o=JSONObject(value)
        require(o.getString("format")=="hongguotv.library" && o.number("version",1)==1L) { "不支持的备份格式或版本" }
        val later=if(o.has("later")) o.rows("later",100).map(::series) else emptyList()
        require(later.distinctBy { it.id }.size==later.size) { "稍后看包含重复剧集" }
        val hidden=if(o.has("hidden")) o.getJSONArray("hidden") else JSONArray()
        require(hidden.length()<=500)
        val hiddenIds=(0 until hidden.length()).map { id(hidden.getString(it)) }.toSet()
        val favorites=o.rows("favorites",500).map(::series)
        val history=o.rows("history",200).map { row ->
            val duration=row.number("duration",604_800_000)
            BackupProgress(series(row.getJSONObject("series")),id(row.string("episodeId",30)),row.number("episodeIndex",100_000).toInt(),row.number("position",604_800_000).let { if(duration>0) minOf(it,duration) else it },duration,row.get("completed").also { require(it is Boolean) } as Boolean,row.number("updatedAt",4_102_444_800_000))
        }
        require(favorites.distinctBy { it.id }.size==favorites.size && history.distinctBy { it.series.id }.size==history.size) { "备份包含重复剧集" }
        val watched=o.getJSONArray("watched"); require(watched.length()<=500)
        val watchedIds=(0 until watched.length()).map { id(watched.getString(it)) }.toSet()
        val searches=o.rows("searches",20).map { row -> RecentSearch(row.string("query",80).also { require(it.isNotBlank()) },ContentType.fromStored(row.getString("type"))) }
        val settings=o.getJSONObject("settings")
        val quality=settings.number("quality",1080).toInt(); require(quality in PlaybackQuality.options)
        val speed=settings.get("speed"); require(speed is Number && speed.toFloat() in PlaybackSpeed.options)
        val autoNext=settings.get("autoNext"); require(autoNext is Boolean)
        val type=ContentType.fromStored(settings.getString("type"))
        val frame=VideoFrameMode.entries.firstOrNull { it.name==settings.getString("frame") } ?: error("画面模式无效")
        // 旧备份里没有这个字段：当成开着。备份是给人拿去用的，不是让人丢设置的。
        val danmaku=settings.opt("danmaku") as? Boolean ?: true
        return BackupData(favorites,history,watchedIds,searches,BackupSettings(quality,speed.toFloat(),autoNext,type,frame,danmaku),later,hiddenIds)
    }
    /** Existing favorites stay first; only newer imported progress replaces a local record. */
    fun merge(local: BackupData,incoming: BackupData,restoreSettings: Boolean): BackupData {
        val favorites=(local.favorites+incoming.favorites).distinctBy { it.id }.take(500)
        val records=local.history.associateBy { it.series.id }.toMutableMap()
        val watched=local.watched.toMutableSet()
        incoming.history.forEach { candidate ->
            val id=candidate.series.id; val prior=records[id]
            if(prior==null || candidate.updatedAt>prior.updatedAt) {
                records[id]=candidate
                if(id in incoming.watched) watched.add(id) else watched.remove(id)
            }
        }
        incoming.watched.filter { id -> id !in records && local.favorites.none { it.id==id } }.forEach { watched.add(it) }
        val history=records.values.sortedByDescending { it.updatedAt }.take(200)
        val retained=(favorites.map { it.id }+history.map { it.series.id }).toSet()
        return BackupData(favorites,history,watched.intersect(retained).take(500).toSet(),(local.searches+incoming.searches).distinct().take(20),if(restoreSettings) incoming.settings else local.settings,(local.later+incoming.later).distinctBy { it.id }.take(100),(local.hidden+incoming.hidden).take(500).toSet())
    }
}
