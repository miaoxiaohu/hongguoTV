// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.EpisodeTarget
import com.hongguotv.core.PhoneSearchServer
import com.hongguotv.core.LibraryTransferServer
import com.hongguotv.core.BackupData
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Native modal controls kept separate from catalog/player navigation. */
class TvTools(private val activity: Activity) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog?=null
    val showing: Boolean get()=dialog?.isShowing==true
    private var phone: java.io.Closeable?=null
    // 手机输入会话要用到的几样东西。退到后台时弹窗要收起来，会话得留着 —— 手机那边的提交
    // 还要能送到电视；以前这里把会话一起关了，手机一点「发送到电视」就是「连接被拒绝」。
    private var phoneUrl: String?=null
    private var phoneDialog: AlertDialog?=null
    private var phoneContent: LinearLayout?=null
    private var phoneAnchor: View?=null
    private var phoneSubmit: ((String)->Unit)?=null
    // 这些弹窗被收起时，不许把会话一起关掉。
    private val keepOnDismiss=mutableSetOf<AlertDialog>()
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(Color.WHITE); setPadding(dp(8),dp(4),dp(8),dp(4)) }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(4),dp(16),dp(8)) }
    private fun open(next: AlertDialog,anchor: View?) {
        dialog?.dismiss(); dialog=next
        next.setOnDismissListener {
            val keep=keepOnDismiss.remove(next)
            if(dialog===next) dialog=null
            if(!keep) closeSession()
            if(anchor?.isAttachedToWindow==true) anchor.requestFocus()
        }
        next.show()
    }
    fun close() { keepOnDismiss.clear(); dialog?.dismiss(); closeSession() }
    fun destroy() { close(); worker.shutdownNow() }
    private fun closeSession() {
        phone?.close(); phone=null
        phoneUrl=null; phoneDialog=null; phoneContent=null; phoneAnchor=null; phoneSubmit=null
    }
    /** 退到后台：把弹窗收起来，但手机输入的会话要留着。 */
    fun background() {
        if(phone!=null) dialog?.let { keepOnDismiss.add(it) }
        dialog?.dismiss()
    }
    /** 回到前台：会话还在就把二维码重新摆出来，用户不用再扫一次。 */
    fun foreground() {
        val url=phoneUrl ?: return
        if(dialog!=null && dialog!!.isShowing) return
        if(phoneSubmit==null) return
        val content=column()
        val next=AlertDialog.Builder(activity).setTitle("手机输入剧名").setView(content).setNegativeButton("关闭",null).create()
        phoneDialog=next; phoneContent=content
        open(next,anchor=phoneAnchor)
        renderPhoneCode(content,url)
    }
    private fun deliverPhoneQuery(query: String) {
        val submit=phoneSubmit
        val current=phoneDialog
        closeSession()
        current?.dismiss()
        submit?.invoke(query)
    }
    /**
     * 会话到期：旧的二维码不能再用了，但**不能让用户自己重新去找按钮** ——
     * 屏幕上立刻换一张新的，手机随时扫都有效。
     * （服务端那边还会多留一段宽限期：手机扫到过期的码会看到一句中文提示，
     *   而不是对着一个已经关掉的端口一直转圈。）
     */
    private fun expirePhoneSession() {
        val anchor=phoneAnchor
        val submit=phoneSubmit
        val content=phoneContent
        val current=phoneDialog
        val address=phoneAddress()
        closeSession()
        if(content!=null) { content.removeAllViews(); content.addView(label("二维码已过期，正在生成新的…")) }
        if(anchor==null || submit==null || address==null) return
        if(current!=null && current.isShowing) {
            // 旧弹窗被 dismiss 时不能再去 closeSession —— 那会把刚设好的 phoneSubmit/phoneAnchor 清掉。
            dialog?.let { keepOnDismiss.add(it) }
            phoneInput(anchor,submit)
        } else {
            // 电视在后台：不弹新窗口（免得在后台弹一个看不见的对话框），只把服务重建好，
            // 等回到前台 foreground() 会把新的二维码摆出来。
            phoneAnchor=anchor; phoneSubmit=submit
            startPhoneSession(null,null,address)
        }
    }
    fun info(title: String,message: String,anchor: View?) = open(AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("知道了",null).create(),anchor)
    fun confirm(title: String,message: String,anchor: View?,action: ()->Unit) {
        val next=AlertDialog.Builder(activity).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确认") { _,_-> action() }.create()
        open(next,anchor); next.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }
    fun choose(title: String,labels: List<String>,anchor: View?,onChoice: (Int)->Unit,extra: String?=null,onExtra: ()->Unit={}) {
        val builder=AlertDialog.Builder(activity).setTitle(title).setItems(labels.toTypedArray()) { _,index-> onChoice(index) }.setNegativeButton("取消",null)
        if(extra!=null) builder.setNeutralButton(extra) { _,_-> onExtra() }
        val next=builder.create(); open(next,anchor); next.listView.requestFocus()
    }
    fun episodePicker(count: Int,current: Int,anchor: View?,select: (Int)->Unit) {
        val content=column(); var digits=(current+1).toString(); var replace=true
        val value=label(digits,28f).apply { gravity=Gravity.CENTER }; content.addView(value)
        val error=label("定位到该集，再按确认播放",14f); content.addView(error)
        fun digit(key: String) { digits=if(replace) key else (digits+key).take(6); replace=false; value.text=digits; error.text="定位到该集，再按确认播放" }
        fun erase() { digits=if(replace) "" else digits.dropLast(1); replace=false; value.text=digits.ifEmpty { "—" } }
        val buttons=mutableListOf<Button>()
        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("清空","0","退格")).forEach { keys ->
            val row=LinearLayout(activity)
            keys.forEach { key ->
                val button=Button(activity).apply {
                    id=View.generateViewId(); text=key; minHeight=dp(42)
                    backgroundTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(Color.rgb(255,99,76),Color.rgb(31,36,47)))
                    setOnClickListener { when(key) { "清空"->{ digits="";replace=false;value.text="—" }; "退格"->erase(); else->digit(key) } }
                }
                buttons+=button
                row.addView(button,LinearLayout.LayoutParams(0,-2,1f))
            }
            content.addView(row)
        }
        val next=AlertDialog.Builder(activity).setTitle("跳转集数 · 1—$count").setView(ScrollView(activity).apply { addView(content) }).setNegativeButton("取消",null).setPositiveButton("定位",null).create()
        next.setOnKeyListener { _,key,event ->
            when {
                key in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { if(event.action==KeyEvent.ACTION_DOWN) digit((key-KeyEvent.KEYCODE_0).toString()); true }
                key==KeyEvent.KEYCODE_DEL -> { if(event.action==KeyEvent.ACTION_DOWN) erase(); true }
                else -> false
            }
        }
        open(next,anchor)
        val locate=next.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel=next.getButton(AlertDialog.BUTTON_NEGATIVE)
        buttons.forEachIndexed { i,button ->
            button.nextFocusLeftId=buttons[if(i%3==0) i else i-1].id
            button.nextFocusRightId=buttons[if(i%3==2) i else i+1].id
            button.nextFocusUpId=buttons[if(i<3) i else i-3].id
            button.nextFocusDownId=if(i<9) buttons[i+3].id else locate.id
        }
        locate.nextFocusUpId=buttons[10].id; cancel.nextFocusUpId=buttons[9].id
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val target=EpisodeTarget.parse(digits,count)
            if(target==null) error.text="请输入 1—$count 之间的集数"
            else { next.dismiss(); select(target) }
        }
        buttons.first().requestFocus()
    }
    private fun qr(url: String)=ImageView(activity).apply {
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,360,360)
        val pixels=IntArray(360*360) { i->if(bits[i%360,i/360]) Color.BLACK else Color.WHITE }
        setImageBitmap(Bitmap.createBitmap(pixels,360,360,Bitmap.Config.ARGB_8888))
        contentDescription="二维码"
    }
    fun phoneInput(anchor: View?,submit: (String)->Unit) {
        val address=phoneAddress()
        if(address==null) {
            info("手机输入","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return
        }
        phoneAnchor=anchor; phoneSubmit=submit
        val content=column(); val status=label("正在生成二维码…"); content.addView(status)
        val next=AlertDialog.Builder(activity).setTitle("手机输入剧名").setView(content).setNegativeButton("关闭",null).create()
        phoneDialog=next; phoneContent=content
        open(next,anchor)
        startPhoneSession(next,content,address)
    }
    /** 电视在用的局域网地址；拿不到就是没连上 Wi-Fi 或有线。 */
    private fun phoneAddress(): String? {
        val connectivity=activity.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork
        val capabilities=connectivity.getNetworkCapabilities(network) ?: return null
        if(!(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
        return connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }
    /**
     * 开一个手机输入会话。owner 是这个会话所属的弹窗：弹窗已经被换掉（用户关了、
     * 或者过期重开），这个会话就没用了，直接关掉。
     */
    private fun startPhoneSession(owner: AlertDialog?,content: LinearLayout?,address: String) {
        worker.execute {
            try {
                val session=PhoneSearchServer(address,{ query -> main.post { deliverPhoneQuery(query) } },{ main.post { expirePhoneSession() } })
                main.post {
                    if(owner!=null && owner!==this@TvTools.dialog) { session.close(); return@post }
                    phone=session; phoneUrl=session.url
                    if(content!=null) renderPhoneCode(content,session.url)
                }
            } catch(_: Exception) {
                main.post { if(content!=null) { content.removeAllViews(); content.addView(label("无法开启手机输入，请检查网络后重试。")) } }
            }
        }
    }
    private fun renderPhoneCode(content: LinearLayout,url: String) {
        content.removeAllViews()
        val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        row.addView(qr(url),LinearLayout.LayoutParams(dp(185),dp(185)))
        val instructions=column()
        instructions.addView(label("手机扫码后用浏览器打开，输入剧名发送到电视。\n需连接同一局域网；会话最长 30 分钟，过期会自动换一张新码。",16f))
        instructions.addView(label(url,12f))
        row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
    }
    fun libraryTransfer(backup: String,anchor: View?,restore: (BackupData,Boolean)->Unit) {
        val connectivity=activity.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork; val capabilities=connectivity.getNetworkCapabilities(network)
        val address=connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        if(address==null || capabilities==null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
            info("备份与恢复","请让电视和手机连接同一局域网后重试。",anchor); return
        }
        val content=column(); val status=label("正在生成二维码…"); content.addView(status)
        val next=AlertDialog.Builder(activity).setTitle("手机备份与恢复").setView(ScrollView(activity).apply { addView(content) }).setNegativeButton("关闭",null).create()
        open(next,anchor)
        worker.execute {
            try {
                val session=LibraryTransferServer(address,backup,{ data -> main.post {
                    if(dialog!==next) return@post
                    next.dismiss()
                    val preview=column()
                    preview.addView(label("备份含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.searches.size} 条搜索、${data.later.size} 部稍后看、${data.hidden.size} 部已隐藏推荐。\n\n收藏合并，观看位置以较新的记录为准；已有收藏优先保留（最多 500 部），历史保留最近 200 条。",16f))
                    val settings=CheckBox(activity).apply { text="同时恢复播放设置和内容分类"; isChecked=false }; preview.addView(settings)
                    val confirm=AlertDialog.Builder(activity).setTitle("确认恢复本机记录")
                        .setView(ScrollView(activity).apply { addView(preview) }).setNegativeButton("取消",null)
                        .setPositiveButton("合并恢复") { _,_-> restore(data,settings.isChecked) }.create()
                    open(confirm,anchor); confirm.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
                } },{ main.post { if(dialog===next) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(qr(session.url),LinearLayout.LayoutParams(dp(185),dp(185)))
                    val instructions=column()
                    instructions.addView(label("手机扫码，用浏览器下载备份或选择备份文件恢复。\n仅限同一局域网；关闭即失效，最长 5 分钟。恢复需在电视确认。",16f))
                    instructions.addView(label(session.url,12f)); row.addView(instructions,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启备份传输，请检查网络后重试。" } }
        }
    }
}
