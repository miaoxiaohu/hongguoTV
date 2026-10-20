// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 一个编号，回答「我还是被要的那件事吗」。
 *
 * 存在的理由是一件具体的事：请求被取消**不是**失败。
 *
 * 取消一次请求之后，它会以「请求已取消」这个异常结束。如果被取消的那次还拿着和下一次
 * 一样的编号，回调就会把取消当成失败端上去 —— 搜索页于是被清空、换成一张「暂时无法
 * 加载」的错误页，再被下一次请求换回来。用户在索引增长时看着自己的搜索结果闪掉一次。
 *
 * 所以取消要作废编号，而「被要的那件事」要每次换一个。两者都归这里管：
 *
 * - [next] 发一次新活，返回它自己的编号；
 * - [cancel] 作废当前所有在飞的活；
 * - [isCurrent] 回答某个编号还是不是当前那件事。
 *
 * 取消不打断正在跑的活，它只是让那次活的回调到点时发现自己过期了。
 */
class WorkTicket {
    private var current=0L

    /** 发一次新活，作废之前所有在飞的活，返回这一次的编号。 */
    @Synchronized fun next(): Long {
        current++
        return current
    }

    /** 作废当前所有在飞的活。它们完成时会发现自己过期，回调不会被执行。 */
    @Synchronized fun cancel() {
        current++
    }

    /** 这个编号还是不是当前那件事。 */
    @Synchronized fun isCurrent(ticket: Long)=ticket==current
}
