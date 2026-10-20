// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

object PlaybackQuality {
    val options=listOf(480,720,1080)
    fun normalize(value: Int)=value.takeIf { it in options } ?: 1080
}

/** One automatic recovery budget per episode; only an explicit user retry resets it. */
class RecoveryBudget {
    var attempts=0; private set
    val remaining get()=attempts<3
    val delayMillis get()=listOf(1000L,2500L,5000L).getOrNull(attempts)
    fun consume(): Boolean { if(!remaining) return false; attempts++; return true }
    fun reset() { attempts=0 }
}

data class ResumeTarget(val index: Int,val position: Long)
object ResumePlayback {
    fun target(episodes: List<String>,episodeId: String?,position: Long,completed: Boolean): ResumeTarget {
        require(episodes.isNotEmpty())
        val index=episodes.indexOf(episodeId)
        if(index<0) return ResumeTarget(0,0)
        return if(completed) ResumeTarget((index+1).coerceAtMost(episodes.lastIndex),0)
            else ResumeTarget(index,position.coerceAtLeast(0))
    }
}

/** Seen count is a high-water mark, so temporary catalog shrinkage is not a new update. */
data class FavoriteUpdate(val total: Int,val seen: Int,val checkedAt: Long) {
    val added get()=(total-seen).coerceAtLeast(0)
    /**
     * A show with no episodes has nothing to compare, so the count is refused and the last known
     * state stands.
     *
     * The old rule was `require(count>0)`, which throws an IllegalArgumentException - and the
     * callers are on the main thread, so one show with no episodes took the whole check down
     * with it. Refusing is the safe answer: an empty episode list is not evidence that the show
     * lost episodes, and overwriting the last known count with a meaningless one would invent
     * an update the user never had.
     */
    fun observe(count: Int,now: Long,acknowledge: Boolean=false): FavoriteUpdate {
        if(count<=0) return this
        if(now<checkedAt) return this
        return FavoriteUpdate(count,if(acknowledge) maxOf(seen,count) else seen,now)
    }
    fun json()=JSONObject().put("total",total).put("seen",seen).put("checkedAt",checkedAt)
    companion object {
        /**
         * The first look at a show.
         *
         * A show with no episodes has no baseline to speak of, so the count is empty rather
         * than an exception. [LibraryIndex.observeFavorite] refuses such a count before it gets
         * here, so this only matters to a caller that asks for a baseline directly.
         */
        fun first(count: Int,now: Long): FavoriteUpdate { val total=count.coerceAtLeast(0); return FavoriteUpdate(total,total,now) }
        fun decode(value: JSONObject?)=runCatching {
            require(value!=null)
            val total=value.getInt("total"); val seen=value.getInt("seen"); val time=value.getLong("checkedAt")
            require(total>0 && seen>0 && time>=0)
            FavoriteUpdate(total,seen,time)
        }.getOrNull()
    }
}
