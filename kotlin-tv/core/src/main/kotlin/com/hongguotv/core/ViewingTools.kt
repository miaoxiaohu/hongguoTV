// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

data class RecentSearch(val query: String, val type: ContentType)

object SearchHistory {
    fun remember(existing: List<RecentSearch>, query: String, type: ContentType): List<RecentSearch> {
        val clean=query.trim()
        if(clean.isEmpty() || clean.length>80) return existing
        val entry=RecentSearch(clean,type)
        return (listOf(entry)+existing.filterNot { it==entry }).take(20)
    }
    fun decode(value: String): List<RecentSearch> = runCatching {
        val rows=JSONArray(value)
        (0 until rows.length()).mapNotNull { i ->
            val row=rows.optJSONObject(i) ?: return@mapNotNull null
            val query=row.optString("query").trim()
            // 「ai」是 0.9.14 那个 AI 档的落盘值。那一档的剧现在都是漫剧，所以它落到漫剧 ——
            // 而不是让这些搜索记录悄悄消失。备份恢复那边也是这么认的（见 ContentType.fromStored），
            // 同一个值两处认法不一样，症状是升级之后搜索历史少了几条，却没有任何提示。
            // 认不出来的值仍然丢掉：那不是旧档，是坏数据。
            val stored=row.optString("type")
            val type=ContentType.entries.firstOrNull { it.storedValue==stored }
                ?: if(stored in ContentType.RETIRED_STORED_VALUES) ContentType.COMIC else null
            if(query.isEmpty() || query.length>80 || type==null) null else RecentSearch(query,type)
        }.distinct().take(20)
    }.getOrDefault(emptyList())
    fun encode(rows: List<RecentSearch>) = JSONArray(rows.map { JSONObject().put("query",it.query).put("type",it.type.storedValue) }).toString()
}

object EpisodeTarget {
    /** One-based user input to a zero-based episode index; never silently clamp it. */
    fun parse(input: String, count: Int): Int? {
        val clean=input.trim()
        if(!clean.matches(Regex("[0-9]{1,6}"))) return null
        return clean.toIntOrNull()?.takeIf { it in 1..count }?.minus(1)
    }
}
