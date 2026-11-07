// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A one-use, short-lived LAN form. No cookies, platform credentials or external resources. */
class PhoneSearchServer(
    address: String,
    private val onQuery: (String)->Unit,
    private val onExpired: ()->Unit = {},
    ttlMillis: Long = DEFAULT_TTL_MILLIS
): Closeable {
    companion object {
        /**
         * 会话时长 30 分钟。5 分钟太短：用户扫完码、掏手机、开浏览器、输完剧名，
         * 常常正好踩在过期点上 —— 屏幕上只剩一个转圈的图标，什么线索都没有。
         */
        const val DEFAULT_TTL_MILLIS=1_800_000L
        /**
         * 会话过期（或已经提交过一次）之后，端口还留着这么久。
         * 端口一关，手机浏览器只能对着一个没人监听的地址转圈到超时；
         * 留着这段时间，它就能回一句中文提示（「二维码已失效，请在电视上重新打开」）。
         */
        const val GRACE_MILLIS=600_000L
    }
    private val stopped=AtomicBoolean(false)
    private val consumed=AtomicBoolean(false)
    private val expiredNotified=AtomicBoolean(false)
    private val localAddress=InetAddress.getByName(address).also { require(it.isLoopbackAddress || it.isSiteLocalAddress) }.hostAddress
    private val listener=ServerSocket(0,4).apply { soTimeout=500 }
    private val deadline=System.nanoTime()+ttlMillis.coerceIn(1,DEFAULT_TTL_MILLIS)*1_000_000
    // 端口活得比会话久：会话过期之后还要再撑一段，好让手机收到一句提示而不是超时。
    private val hardDeadline=deadline+GRACE_MILLIS*1_000_000
    private val token=ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val host="$localAddress:${listener.localPort}"
    val url="http://$host/$token"
    private val thread=Thread({ serve() },"hongguotv-phone-input").apply { isDaemon=true }
    init {
        thread.start()
    }
    override fun close() { stopped.set(true); runCatching { listener.close() } }
    private fun serve() {
        try {
            while(!stopped.get() && System.nanoTime()<hardDeadline) {
                // 会话到期就通知一次（电视那边换一张新二维码），但端口继续留着回提示。
                if(!consumed.get() && System.nanoTime()>=deadline) notifyExpired()
                val client=try { listener.accept() } catch(_: SocketTimeoutException) { continue }
                client.use { socket ->
                    socket.soTimeout=2000
                    try { handle(socket) } catch(_: Exception) { /* Discard malformed or disconnected requests. */ }
                }
            }
        } catch(_: Exception) { /* Closing the dialog interrupts accept(). */ }
        finally {
            val expired=!stopped.get() && !consumed.get()
            close()
            if(expired) notifyExpired()
        }
    }
    private fun notifyExpired() { if(expiredNotified.compareAndSet(false,true)) onExpired() }
    private fun line(input: InputStream, max: Int): String {
        val out=ByteArrayOutputStream()
        while(out.size()<max) {
            val next=input.read()
            if(next<0) throw java.io.EOFException()
            if(next==10) return out.toString("US-ASCII").trimEnd('\r')
            out.write(next)
        }
        throw java.io.IOException("Header too large")
    }
    private fun handle(socket: Socket) {
        if(!socket.inetAddress.let { it.isLoopbackAddress || it.isSiteLocalAddress }) return
        val input=socket.getInputStream().buffered()
        val request=line(input,1024).split(' ')
        if(request.size!=3) return
        val headers=mutableMapOf<String,String>()
        var bytes=0
        while(true) {
            val next=line(input,4096); bytes+=next.length+2
            if(bytes>8192) return respond(socket,431,"请求过大")
            if(next.isEmpty()) break
            val at=next.indexOf(':'); if(at<=0) return respond(socket,400,"请求格式错误")
            val key=next.substring(0,at).lowercase(Locale.ROOT)
            if(headers.put(key,next.substring(at+1).trim())!=null) return respond(socket,400,"重复请求头")
        }
        if(stopped.get()) return
        // 会话过期了，或者这个二维码已经提交过一次：回一句中文提示。
        // 端口在宽限期里还开着，所以手机看到的是这句话，而不是「一直转圈直到超时」。
        if(consumed.get()) return respond(socket,410,"这个二维码已经用过了，请在电视上重新打开「手机输入」。")
        if(System.nanoTime()>=deadline) return respond(socket,410,"二维码已失效，请在电视上重新打开「手机输入」。")
        if(headers["host"]!=host || request[1] !in listOf("/$token","/$token/submit")) return respond(socket,404,"页面不存在")
        if(request[0]=="GET" && request[1]=="/$token") {
            // 手机上打字的几处讲究：inputmode/enterkeyhint 让浏览器弹文字键盘、回车键显示「发送」；
            // 不写 required —— 原生校验的气泡会挡在提交前面，用户只看到「请填写此字段」，
            // 却不知道自己打的字跑哪儿去了；空提交交给服务器回一句中文提示更清楚。
            return respond(socket,200,"""<h1>在电视上搜索</h1><p>点下面的输入框，用手机键盘输入剧名，再点「发送到电视」。</p><form method="post" action="/$token/submit"><input type="text" name="query" maxlength="80" autofocus inputmode="text" enterkeyhint="send" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="输入剧名或关键词" autocomplete="off"><button type="submit">发送到电视</button></form><p>电视会按当前选择的漫剧或短剧分类搜索。页面仅在电视的手机输入窗口打开期间有效，最长 30 分钟。</p>""")
        }
        if(request[0]!="POST" || request[1]!="/$token/submit") return respond(socket,405,"不支持的操作")
        // A browser that cannot name the origin says "null". Chromium does exactly that for a
        // form POST under "Referrer-Policy: no-referrer" - the header hides the origin along with
        // the referrer - and in-app browsers do it for their own reasons. That is not by itself
        // evidence of a cross-site request: the one-shot token in the URL is the secret, and a
        // page that cannot read the token cannot post to it. A concrete origin that is not ours
        // is refused, and so is a browser that states the request is cross-site, even when it
        // could not name an origin.
        val origin=headers["origin"]
        if(origin!=null && origin!="null" && origin!="http://$host") return respond(socket,403,"来源不匹配")
        if(headers["sec-fetch-site"]=="cross-site") return respond(socket,403,"来源不匹配")
        if(headers.containsKey("transfer-encoding") || headers["content-type"]?.substringBefore(';')!="application/x-www-form-urlencoded") return respond(socket,400,"请求格式错误")
        val length=headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..4096 } ?: return respond(socket,413,"输入内容过长")
        val body=ByteArray(length); var offset=0
        while(offset<body.size) { val n=input.read(body,offset,body.size-offset); if(n<0) return; offset+=n }
        val fields=String(body,Charsets.UTF_8).split('&').map { it.split('=',limit=2) }
        if(fields.size!=1 || fields[0].size!=2 || fields[0][0]!="query") return respond(socket,400,"请填写剧名")
        val query=runCatching { URLDecoder.decode(fields[0][1],"UTF-8").trim() }.getOrNull()
        if(query.isNullOrBlank() || query.length>80 || query.any { it.isISOControl() }) return respond(socket,400,"请输入 1—80 个字符的剧名")
        if(stopped.get() || System.nanoTime()>=deadline || !consumed.compareAndSet(false,true)) return respond(socket,410,"二维码已失效")
        try { respond(socket,200,"<h1>已发送到电视</h1><p>请回到电视查看搜索结果。</p>") }
        finally { close(); onQuery(query) }
    }
    private fun respond(socket: Socket, code: Int, message: String) {
        val body="""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果TV+ · 手机输入</title><style>body{font:18px sans-serif;background:#10131b;color:#f4f5f8;max-width:500px;margin:40px auto;padding:20px}input,button{box-sizing:border-box;width:100%;font:inherit;padding:16px;margin:10px 0;border-radius:8px}button{background:#ff634c;color:white;border:0}p{line-height:1.6}</style></head><body>$message</body></html>""".toByteArray(Charsets.UTF_8)
        val header="HTTP/1.1 $code Response\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: same-origin\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n"
        socket.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }
}
