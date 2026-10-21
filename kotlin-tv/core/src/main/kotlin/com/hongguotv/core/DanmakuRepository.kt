// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 红果短剧的弹幕。
 *
 * 走的是平台的评论接口，路径和播放器那条不是一条：
 *
 * ```
 * POST https://api5-normal-sinfonlineb.fqnovel.com/novel/commentapi/comment/list/{vid}/v1/
 * ```
 *
 * 它要 `comment-source` 和 `server-channel` 两个头，`x-ss-stub` 留空。签名**不需要**另外找一套：
 * 上游那个弹幕服务用的是 metasec 风格的签名，本项目这套 drpys 风格的签名（[Signer]）签同一个
 * 接口照样返回 `code=0` —— 2026-10-21 实测，带签名 85 条弹幕，不带签名 `Code 110001`。
 *
 * 一次给 30 秒窗口（[DANMAKU_WINDOW_MS]），要整集就按返回的起点一页页往下问。
 */
class DanmakuRepository(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS).build(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val signer=Signer()

    /**
     * 两次请求之间的最小间隔。
     *
     * 弹幕接口连着问会撞风控 —— 平台会回一个带冷却时间的错误。留出间隔比被拒之后重试便宜。
     */
    private var lastRequestAt=0L

    /**
     * 问一窗弹幕。
     *
     * @param seriesId 剧集 id（协议里叫 book_id）。
     * @param vid 这一集的 video id。
     * @param startMs 这一窗从哪儿开始（毫秒）。
     * @param durationMs 这一集多长（毫秒）。不知道就传 0，平台不靠它定位。
     */
    fun window(seriesId: String, vid: String, startMs: Long, durationMs: Long=0L): DanmakuWindow {
        require(seriesId.isNotBlank() && vid.isNotBlank())
        throttle()
        val base="$API_HOST/novel/commentapi/comment/list/$vid/v1/"
        val body=commentBody(seriesId,vid,startMs,durationMs)
        val signed=signer.sign(base,VendorConstants.newDevice(),body)
        val request=Request.Builder().url(signed.url)
            .header("Content-Type","application/json; charset=UTF-8")
            .header("comment-source",COMMENT_SOURCE)
            .header("server-channel",SERVER_CHANNEL)
            .apply { signed.headers.forEach { (k,v) -> header(k,v) } }
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text=response.body?.string().orEmpty()
            if(!response.isSuccessful) throw IOException("弹幕接口返回 HTTP ${response.code}")
            if(text.isBlank()) throw IOException("弹幕接口返回空响应")
            return parseDanmakuWindow(text,startMs)
        }
    }

    private fun commentBody(seriesId: String,vid: String,startMs: Long,durationMs: Long): ByteArray {
        // 注意：app 模块把 org.json 依赖排除了，运行时用的是 Android 自带的那份 —— 它没有
        // `JSONArray(Collection)` 构造器。core 自己编译和跑单测时用的是 org.json:json，有。
        // 所以这里只用 put 往里塞，编译期看不出来、到电视上才会炸。
        val guide=JSONArray().put(1).put(3).put(4).put(2)
        val business=JSONObject()
            .put("book_id",seriesId)
            .put("start_offset_time",startMs.coerceAtLeast(0))
            .put("playlet_item_duration",durationMs.coerceAtLeast(0))
            .put("need_danmaku_guide_type",guide)
        return JSONObject()
            .put("comment_source",601)
            .put("server_channel",1000)
            .put("group_id",vid)
            .put("group_type",30)
            .put("comment_type",20)
            .put("sort",1)
            .put("business_param",business)
            .put("count",90)
            .put("cursor","")
            .put("aid",8662)
            .put("compliance_status",0)
            .toString().toByteArray()
    }

    private fun throttle() {
        val now=clock()
        val wait=lastRequestAt+MIN_INTERVAL_MS-now
        if(wait>0) try { Thread.sleep(wait) } catch(e: InterruptedException) { Thread.currentThread().interrupt() }
        lastRequestAt=clock()
    }

    companion object {
        private const val API_HOST="https://api5-normal-sinfonlineb.fqnovel.com"
        private const val COMMENT_SOURCE="601"
        private const val SERVER_CHANNEL="1000"
        private const val MIN_INTERVAL_MS=250L
    }
}
