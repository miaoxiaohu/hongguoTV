// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import java.io.Closeable

/**
 * 手机输入会话的归属。
 *
 * 会话的生命周期应该跟 **App** 走（退出 App 才结束），不该跟界面上的弹窗走。
 * 以前会话和弹窗绑在一起：电视一开始播放 / 换页就收起弹窗，顺手把会话也关了 ——
 * 用户手机里那个二维码当场作废，扫完码还没来得及输剧名，端口就没了，
 * 手机浏览器只能对着一个没人监听的地址转圈到超时。
 *
 * 单独拎出来是为了能写单测：这一条以前没有测试守着，改回去过一次就复发一次。
 */
internal class PhoneSessionPolicy {
    private var session: Closeable?=null

    /** 当前会话的地址；没有会话时是 null。 */
    var url: String?=null
        private set

    /** 手上有没有活着的会话。 */
    val hasSession: Boolean get()=session!=null

    /**
     * 挂上一个新会话。
     *
     * 旧会话**不立刻关**：服务端那边本来就有宽限期（GRACE_MILLIS），
     * 过期之后端口还留着，手机扫到旧码会看到一句中文提示，
     * 而不是对着一个没人监听的地址转圈到超时。
     * 这里要是急着 close()，用户手里刚扫的码立刻就成死地址 —— 正好把宽限期这个设计废掉了。
     */
    fun attach(next: Closeable, nextUrl: String?=null) {
        session=next
        url=nextUrl
    }

    /** 会话结束（提交过一次、App 退出）：彻底关掉，端口不再留着。 */
    fun release() {
        session?.close()
        session=null
        url=null
    }
}