// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import android.text.TextUtils
import android.util.LruCache
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import okhttp3.Request
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    private val bg=Color.rgb(16,19,27); private val surface=Color.rgb(31,36,47)
    private val accent=Color.rgb(255,99,76); private val white=Color.rgb(244,245,248); private val muted=Color.rgb(161,172,190)
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val images=Executors.newFixedThreadPool(2)
    private val repository=ContentRepository()
    private lateinit var library: Library
    private lateinit var root: FrameLayout
    private lateinit var pageBody: LinearLayout
    private var generation=0
    private var screen="catalog"
    private var tab=0
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var hasMore=false
    private var catalogFocus=""
    private val tabState=mutableMapOf<Int,Triple<Int,List<Series>,Boolean>>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    private var searchInput: View?=null
    private var searchButton: View?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    private var group=0
    private var synopsisExpanded=false
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel=false
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var quality=""
    private val coverCache=object: LruCache<String,Bitmap>(12*1024*1024) { override fun sizeOf(key: String,value: Bitmap)=value.byteCount }
    private val tick=object: Runnable { override fun run() { if(screen=="player") { updatePlaybackText(); val now=System.currentTimeMillis(); if(now-lastSaved>5000) { saveProgress(); lastSaved=now } }; main.postDelayed(this,1000) } }
    private val hideHud=Runnable { if(screen=="player" && !panel && player?.isPlaying==true && !playError) hud.visibility=View.GONE }
    private val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=GradientDrawable().apply { setColor(color); cornerRadius=dp(9).toFloat(); setStroke(dp(2),border) }
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) { view.background=rounded(if(focused) Color.rgb(66,43,43) else if(selected) Color.rgb(55,39,40) else surface,if(focused) accent else Color.TRANSPARENT) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root)
        showCatalog(load=true); main.post(tick)
    }
    private fun base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column(); val horizontal=dp(widthDp()*.05f); val vertical=(resources.displayMetrics.heightPixels*.05f).toInt()
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        if(title!=null) { val heading=row(); addButton(heading,"‹ 返回") { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD) },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        pageBody=container; return container
    }
    private fun loadCover(view: ImageView,url: String) {
        view.tag=url; view.setBackgroundColor(surface)
        if(url.isEmpty()) return
        coverCache.get(url)?.let { view.setImageBitmap(it); return }
        val ticket=generation
        images.execute {
            if(generation!=ticket || isDestroyed) return@execute
            try {
                val request=Request.Builder().url(url).build()
                repository.http.newCall(request).execute().use { response ->
                    if(!response.isSuccessful || (response.body?.contentLength() ?: 0)>4*1024*1024) return@execute
                    val bytes=response.body?.source()?.let { source -> val out=java.io.ByteArrayOutputStream(); val buf=ByteArray(8192); val input=source.inputStream(); while(out.size()<=4*1024*1024) { val n=input.read(buf); if(n<0) break; out.write(buf,0,n) }; out.toByteArray() } ?: return@execute
                    if(bytes.size>4*1024*1024) return@execute
                    val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    if(options.outWidth<=0 || options.outHeight<=0) return@execute
                    options.inSampleSize=1; while(options.outWidth/options.inSampleSize>640 || options.outHeight/options.inSampleSize>640) options.inSampleSize*=2
                    options.inJustDecodeBounds=false
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: return@execute
                    coverCache.put(url,bitmap)
                    main.post { if(!isDestroyed && generation==ticket && view.tag==url) view.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) { /* Text remains usable when artwork is unavailable. */ }
        }
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        val ticket=generation
        io.execute { try { val result=action(); main.post { if(!isDestroyed && generation==ticket) done(result) else if(result is RemoteVideo) result.close() } } catch(e: Exception) { main.post { if(!isDestroyed && generation==ticket) failed(e) } } }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }
    private fun switchTab(next: Int) {
        tabState[tab]=Triple(page,catalog,hasMore); tabFocus[tab]=catalogFocus
        tab=next; val saved=tabState[next]; page=saved?.first ?: 1; catalog=saved?.second ?: emptyList(); hasMore=saved?.third ?: false; catalogFocus=tabFocus[next].orEmpty()
        showCatalog(load=(next<=1 && catalog.isEmpty() && (next==0 || query.isNotBlank())))
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false) {
        generation++; screen="catalog"; val container=base(); nav.clear(); searchInput=null; searchButton=null
        val top=row(); top.addView(text("红果 TV",24f).apply { setTypeface(null,Typeface.BOLD) },lp(dp(135),dp(48)))
        listOf("推荐","搜索","收藏","最近观看","设置").forEachIndexed { index,label -> nav+=addButton(top,label,index==tab) { switchTab(index) } }
        container.addView(top)
        if(tab==4) { settings(container); if(focusNav) nav[tab].requestFocus(); return }
        if(tab==1) {
            val searchRow=row(); val input=EditText(this).apply { id=View.generateViewId(); hint="输入短剧名称或关键词"; setText(query); textSize=16f; setTextColor(white); setHintTextColor(muted); isSingleLine=true; maxLines=1; filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
            searchRow.addView(input,lp(0,dp(45)).apply { weight=1f; rightMargin=dp(10) })
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { input.requestFocus(); return }; query=next; page=1; catalogFocus=""; (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); showCatalog(true) }
            searchInput=input; searchButton=addButton(searchRow,"搜索") { search() }; input.nextFocusUpId=nav[tab].id; input.setOnEditorActionListener { _,_,_-> search(); true }; container.addView(searchRow)
        } else container.addView(text(if(tab==0) "发现好故事" else if(tab==2) "我的收藏" else "接着上次看",25f).apply { setTypeface(null,Typeface.BOLD); setPadding(0,dp(14),0,dp(6)) })
        val body=column(); container.addView(body,lp(-1,0).apply { weight=1f })
        if(tab==2) { catalog=library.favorites(); hasMore=false }
        if(tab==3) { catalog=library.history().map { it.series }; hasMore=false }
        if(load) {
            message(body,"正在加载…"); nav[tab].requestFocus()
            val requestTab=tab; val requestPage=page; val requestQuery=query
            work({ if(requestTab==0) repository.home(requestPage) else repository.search(requestQuery,requestPage) }, { result -> catalog=result.items; hasMore=result.hasMore; body.removeAllViews(); catalogGrid(body,focusNav) }, { problem -> body.removeAllViews(); error(body,problem) { showCatalog(true) } })
        } else catalogGrid(body,focusNav)
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean) {
        if(catalog.isEmpty()) { message(body,when(tab) { 1 -> if(query.isBlank()) "输入关键词，用遥控器确认搜索" else "没有找到相关短剧，换个关键词试试"; 2 -> "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; 3 -> "播放过的剧集会自动保存在这里"; else -> "本页没有更多内容" }); if(tab<=1 && page>1) addButton(body,"上一页") { page--; showCatalog(true) }; nav[tab].requestFocus(); return }
        val scroll=ScrollView(this).apply { isFillViewport=false; isVerticalScrollBarEnabled=false; clipToPadding=false }
        val list=column(); scroll.addView(list); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val cards=mutableListOf<View>(); val count=5; val gap=dp(10); val width=((resources.displayMetrics.widthPixels*.9f-gap*(count-1))/count).toInt()
        catalog.chunked(count).forEachIndexed { rowIndex,items ->
            val line=row(); line.gravity=Gravity.TOP
            items.forEachIndexed { columnIndex,item ->
                val card=column(); card.setPadding(dp(5),dp(5),dp(5),dp(7)); focusStyle(card); card.contentDescription=item.title
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }; card.addView(image,lp(-1,dp(142))); loadCover(image,item.cover)
                card.addView(text(item.title,15f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) },lp(-1,dp(46)))
                val progress=if(tab==3) library.progress(item.id) else null
                card.addView(text(if(progress!=null) "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}" else item.badge,12f,muted).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) },lp(-1,dp(19)))
                card.setOnClickListener { catalogFocus=item.id; openDetail(item) }
                card.setOnFocusChangeListener { _,focused -> card.background=rounded(if(focused) Color.rgb(66,43,43) else surface,if(focused) accent else Color.TRANSPARENT); if(focused) { catalogFocus=item.id; scroll.post { scroll.smoothScrollTo(0,line.top) } } }
                line.addView(card,lp(width,dp(221)).apply { if(columnIndex<count-1) rightMargin=gap }); cards+=card
            }
            list.addView(line,lp(-1,dp(231)))
        }
        val paging=row(); paging.gravity=Gravity.CENTER
        var prev: View?=null; var next: View?=null
        if(tab<=1) {
            prev=addButton(paging,"上一页") { if(page>1) { page--; catalogFocus=""; showCatalog(true) } }.apply { isEnabled=page>1; isFocusable=page>1; alpha=if(page>1) 1f else .4f }
            paging.addView(text("第 $page 页",14f,muted).apply { gravity=Gravity.CENTER },lp(dp(95),dp(44)))
            next=addButton(paging,"下一页") { if(hasMore) { page++; catalogFocus=""; showCatalog(true) } }.apply { isEnabled=hasMore; isFocusable=hasMore; alpha=if(hasMore) 1f else .4f }
            list.addView(paging,lp(-1,dp(50)))
        }
        cards.forEachIndexed { index,card ->
            card.nextFocusLeftId=if(index%count==0) card.id else cards[index-1].id
            card.nextFocusRightId=if(index%count==count-1 || index==cards.lastIndex) card.id else cards[index+1].id
            card.nextFocusUpId=if(index<count) (searchInput?.id ?: nav[tab].id) else cards[index-count].id
            card.nextFocusDownId=if(index+count<cards.size) cards[index+count].id else if(index/count<cards.lastIndex/count) cards.last().id else next?.takeIf { it.isFocusable }?.id ?: prev?.takeIf { it.isFocusable }?.id ?: card.id
        }
        nav.forEach { it.nextFocusDownId=searchInput?.id ?: cards.first().id }
        searchInput?.nextFocusDownId=cards.first().id
        searchButton?.nextFocusDownId=cards[minOf(4,cards.lastIndex)].id
        if(focusNav) nav[tab].requestFocus() else cards[catalog.indexOfFirst { it.id==catalogFocus }.coerceAtLeast(0)].requestFocus()
    }
    private fun settings(parent: LinearLayout) {
        message(parent,"原生独立版 · 0.2.0")
        parent.addView(text("安装后联网即可使用，无需服务器地址或 Docker。",18f).apply { setPadding(0,0,0,dp(18)) })
        addButton(parent,"清晰度上限：${library.maxQuality}P") { library.maxQuality=if(library.maxQuality==1080) 720 else 1080; showCatalog() }
        addButton(parent,"自动播放下一集：${if(library.autoNext) "开启" else "关闭"}") { library.autoNext=!library.autoNext; showCatalog() }
        message(parent,"遥控器：方向键移动焦点，确认键选择；播放时左右快退/快进，确认暂停，向下打开选集菜单。")
        parent.addView(text("最低 Android 8.0 · Kotlin / Media3\n内容通过互联网读取。收藏与观看记录保存在本机。\n旧版的收藏和记录不自动迁移。",14f,muted))
        addButton(parent,"开源许可") { AlertDialog.Builder(this).setTitle("开源许可").setMessage("本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：N3urda/hongguoTV，codex/kotlin-standalone 分支。").setPositiveButton("关闭",null).show() }
        nav[tab].requestFocus()
    }
    private fun openDetail(series: Series) {
        generation++; screen="detail"; detail=null; synopsisExpanded=false
        val body=base(series.title); message(body,"正在加载剧集…")
        work({ repository.detail(series.id) }, { result -> detail=result; val progress=library.progress(series.id); episodeIndex=progress?.let { p -> result.episodes.indexOf(p.episodeId).takeIf { it>=0 } } ?: 0; group=episodeIndex/20; showDetail(false) }, { problem -> error(body,problem) { openDetail(series) } })
    }
    private fun showDetail(focusEpisode: Boolean) {
        generation++; screen="detail"; val data=detail ?: return showCatalog()
        val body=base("剧集详情")
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row(); hero.gravity=Gravity.TOP; hero.setPadding(0,dp(12),0,dp(12))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }; hero.addView(image,lp(dp(123),dp(174)).apply { rightMargin=dp(24) }); loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,26f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}",13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        val description=data.series.description.ifBlank { "选择剧集开始观看" }
        info.addView(text(description,15f,muted).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END })
        val actions=row(); actions.setPadding(0,dp(12),0,0); info.addView(actions)
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        val resumePosition=progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0
        val play=addButton(actions,if(progress!=null && !progress.completed) "继续第 ${resumeIndex+1} 集" else "开始观看") { playEpisode(if(progress?.completed==true) (resumeIndex+1).coerceAtMost(data.episodes.lastIndex) else resumeIndex,resumePosition) }
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏") { val saved=library.toggle(data.series); favorite.text=if(saved) "已收藏" else "收藏" }
        addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介") { synopsisExpanded=!synopsisExpanded; showDetail(false) }
        content.addView(hero)
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(dp(190),dp(42)))
        addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        content.addView(groupRow)
        val episodes=mutableListOf<View>()
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(10).forEach { numbers ->
            val line=row()
            numbers.forEach { index -> val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) }; item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item }
            repeat(10-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(49)))
        }
        episodes.forEachIndexed { i,v -> v.nextFocusLeftId=if(i%10==0) v.id else episodes[i-1].id; v.nextFocusRightId=if(i%10==9 || i==episodes.lastIndex) v.id else episodes[i+1].id; if(i>=10) v.nextFocusUpId=episodes[i-10].id; v.nextFocusDownId=if(i+10<episodes.size) episodes[i+10].id else v.id }
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true) {
        val data=detail ?: return
        saveProgress(); releasePlayer(); generation++; screen="player"; episodeIndex=index.coerceIn(0,data.episodes.lastIndex); group=episodeIndex/20
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
        val view=PlayerView(this).apply { useController=false; resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT; isFocusable=false; setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS) }
        root.addView(view,FrameLayout.LayoutParams(-1,-1))
        hud=column().apply { setPadding(dp(widthDp()*.05f),dp(20),dp(widthDp()*.05f),(resources.displayMetrics.heightPixels*.05f).toInt()); setBackgroundColor(Color.argb(215,12,15,22)) }
        val title=text("${data.series.title}  ·  第 ${episodeIndex+1} 集",23f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }; hud.addView(title)
        playbackText=text("正在获取播放地址…",15f,muted).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
        progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface) }; hud.addView(progressBar,lp(-1,dp(4)))
        hud.addView(text("确认 暂停/播放    左右 快退/快进    ↓ 更多操作    返回 退出",13f,muted).apply { setPadding(0,dp(12),0,0) })
        controls=row().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
        root.addView(hud,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality
        work({ RemoteVideo(repository.http,repository.stream(requestedId,maxQuality)).prepare() }, { remote ->
            video=remote; quality=remote.info.quality
            val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,1000,2000).setTargetBufferBytes(12*1024*1024).build()
            val p=ExoPlayer.Builder(this).setLoadControl(load).build(); player=p; view.player=p
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
            p.setHandleAudioBecomingNoisy(true)
            p.addListener(object: Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if(player!==p) return
                    if(state==Player.STATE_READY) { playbackReady=true; updatePlaybackText(); showHud() }
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else { p.pause(); playbackText.text="本集已结束"; showPanel() }
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(player===p) { updatePlaybackText(); showHud() } }
                override fun onPlayerError(error: PlaybackException) { if(player===p) playerFailure(error) }
            })
            val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setUri("hongguotv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
            p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=autoplay && !pausedForLifecycle
        }, { problem -> playerFailure(problem) })
    }
    private fun playerFailure(problem: Throwable) {
        playError=true; player?.pause(); hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        playbackText.text=if(problem is PlaybackException && (problem.errorCode==PlaybackException.ERROR_CODE_DECODING_FAILED || problem.errorCode==PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)) "电视无法解码当前视频，可尝试 720P。" else "播放失败，请检查网络后重试。"
        addButton(controls,"重试") { playEpisode(episodeIndex,library.progress(detail!!.series.id)?.takeIf { it.episodeIndex==episodeIndex }?.position ?: 0) }.requestFocus()
        addButton(controls,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,player?.currentPosition ?: 0) }
        addButton(controls,"返回选集") { returnToDetail() }
        android.util.Log.w("HongguoTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""))
    }
    private fun showHud() {
        if(screen!="player") return
        hud.visibility=View.VISIBLE; main.removeCallbacks(hideHud)
        if(!panel && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    private fun updatePlaybackText() {
        if(screen!="player" || playError) return
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality"
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun togglePlayback() { val p=player ?: return; if(p.playbackState==Player.STATE_ENDED) p.seekTo(0); if(p.playWhenReady) p.pause() else { pausedForLifecycle=false; p.play() }; showHud(); updatePlaybackText(); if(panel) showPanel() }
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else 10000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun showPanel() {
        if(playError) return
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val play=addButton(controls,if(player?.playWhenReady==true) "暂停" else "播放") { togglePlayback() }
        addButton(controls,"上一集") { if(episodeIndex>0) playEpisode(episodeIndex-1) }.apply { isEnabled=episodeIndex>0; isFocusable=episodeIndex>0; alpha=if(episodeIndex>0) 1f else .4f }
        addButton(controls,"下一集") { if(episodeIndex<(detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex+1) }.apply { val enabled=episodeIndex<(detail?.episodes?.lastIndex ?: 0); isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .4f }
        addButton(controls,"选集") { returnToDetail() }
        addButton(controls,"从头播放") { player?.seekTo(0); pendingSeek=null; main.removeCallbacks(seekRunnable); hidePanel() }
        play.requestFocus()
    }
    private fun hidePanel() { if(playError) return; panel=false; controls.visibility=View.GONE; controls.clearFocus(); showHud() }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        library.save(WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis()))
    }
    private fun releasePlayer() {
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        video?.close(); video=null; player?.release(); player=null; playbackReady=false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun returnToDetail() { saveProgress(); releasePlayer(); showDetail(true) }
    private fun goBack() {
        when(screen) {
            "player" -> if(panel && !playError) hidePanel() else returnToDetail()
            "detail" -> showCatalog()
            else -> if(nav.any { it.hasFocus() }) { if(tab!=0) { switchTab(0); nav[0].requestFocus() } else finish() } else nav.getOrNull(tab)?.requestFocus()
        }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) { if(key==KeyEvent.KEYCODE_MEDIA_PLAY) player?.play() else if(key==KeyEvent.KEYCODE_MEDIA_PAUSE) player?.pause() else togglePlayback() }; return true
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU)) {
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> seek(1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU -> if(event.repeatCount==0) showPanel()
                        else -> showHud()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        when(screen) {
            "player" -> { val position=player?.currentPosition ?: 0; val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "detail" -> if(detail!=null) showDetail(false)
            else -> showCatalog()
        }
    }
    override fun onPause() { pausedForLifecycle=true; player?.pause(); saveProgress(); super.onPause() }
    override fun onStop() { super.onStop(); if(screen=="player") { generation++; saveProgress(); releasePlayer() } }
    override fun onRestart() { super.onRestart(); if(screen=="player" && player==null) { val progress=detail?.let { library.progress(it.series.id) }; playEpisode(episodeIndex,progress?.takeIf { it.episodeIndex==episodeIndex }?.position ?: 0,autoplay=false) } }
    override fun onDestroy() { generation++; saveProgress(); releasePlayer(); main.removeCallbacksAndMessages(null); io.shutdownNow(); images.shutdownNow(); Thread({
            repository.http.dispatcher.cancelAll()
            repository.http.connectionPool.evictAll()
            repository.http.dispatcher.executorService.shutdown()
        }, "hongguotv-network-cleanup").start(); super.onDestroy() }
}
