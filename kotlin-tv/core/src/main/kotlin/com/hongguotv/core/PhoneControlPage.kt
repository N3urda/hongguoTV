// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** All rendering stays on the phone. No artwork, web fonts, libraries or video on the TV. */
internal object PhoneControlPage {
    fun html(nonce: String) = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 手机控制台</title>
<style>body{margin:0;background:#10131b;color:#f5f5f7;font:16px system-ui,sans-serif}main{max-width:700px;margin:auto;padding:20px}h1{font-size:25px}h2{font-size:19px}button,input,select{font:inherit;border-radius:9px;padding:11px;border:1px solid #475063;background:#222a38;color:inherit}button{cursor:pointer}button:disabled{opacity:.45}button.primary{background:#e95342;border-color:#e95342}.row{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}input[type=search]{flex:1;min-width:160px}.card{background:#1b2230;border-radius:12px;padding:15px;margin:12px 0}.muted{color:#b9c2d3;font-size:14px}#notice{min-height:24px;color:#ffd0a0}#rows h3{margin:0 0 8px;font-size:17px}label{display:inline-flex;align-items:center;gap:5px}input[type=number]{width:90px}nav button[aria-pressed=true]{border-color:#ff8066;color:#ffad9b}</style></head>
<body><main><h1>红果 TV · 手机控制台</h1><p class="muted">同一局域网内可用；浏览列表不打断电视播放。电视退出应用后连接失效。</p>
<section class="card"><h2 id="playing">正在连接电视…</h2><p id="position" class="muted"></p><div class="row"><button data-cmd="previous">上一集</button><button data-cmd="pause">暂停</button><button data-cmd="resume">继续</button><button data-cmd="next">下一集</button></div><div class="row"><button data-cmd="backward">−15 秒</button><button data-cmd="forward">+15 秒</button><label>倍速<select id="speed"><option>0.75</option><option selected>1</option><option>1.25</option><option>1.5</option><option>1.75</option><option>2</option><option>3</option></select></label><label>清晰度<select id="quality"><option>480</option><option>720</option><option selected>1080</option></select></label></div><div class="row"><label>第<input id="episode" type="number" min="1" value="1">集</label><button id="jump">播放此集</button><button id="account">电视账户管理</button></div></section>
<section class="card"><h2>方向遥控</h2><div class="row"><button data-key="up">上</button><button data-key="down">下</button><button data-key="left">左</button><button data-key="right">右</button><button data-key="ok">确定</button><button data-key="back">返回</button></div></section><p id="accountLabel" class="muted"></p><nav class="row" id="tabs"></nav><div class="row"><input id="query" type="search" maxlength="80" placeholder="输入剧名"><button class="primary" id="search">搜索</button></div><label><input type="checkbox" id="sync">搜索结果同时显示到电视（会离开当前播放）</label><div class="row"><button id="refresh">刷新当前列表</button><span id="page" class="muted"></span></div><button id="backList" hidden>返回原列表</button><button id="retry" hidden>重试</button><p id="notice" role="status" aria-live="polite"></p><div id="rows"></div><div class="row"><button id="prev">上一页</button><button id="next">下一页</button></div><p class="muted">账户预约每类最多显示当前已读取的 20 部。未上线作品不能播放；点击预约后会自动核对状态。</p><button id="disconnect">断开所有手机</button></main>
<script nonce="$nonce">
(function () {
'use strict';
var mode='search', page=1, keyword='', busy=false, commandBusy=false, stopped=false, polling=false;
var timer, listTimer, view=0, lastData=null, shownContext=null, returnList=null, related=false;
var revision=null, accountScope=null, refreshNeeded=false, accountChanged=false, retryAction=null;
var by=function(id){return document.getElementById(id)};
function note(text){by('notice').textContent=text||''}
function api(data){
  var controller=typeof AbortController==='undefined'?null:new AbortController();
  var timeout=controller?setTimeout(function(){controller.abort()},25000):null;
  return fetch(location.pathname+'/api',{method:'POST',headers:{'Content-Type':'application/json','X-Control-Request':'1'},body:JSON.stringify(data),cache:'no-store',signal:controller?controller.signal:undefined})
    .then(function(r){return r.json().then(function(v){if(!r.ok||v.error)throw Error(v.error||'请求未完成');return v})})
    .then(function(v){clearTimeout(timeout);return v},function(e){clearTimeout(timeout);throw Error(e.name==='AbortError'?'连接超时，请重试':e.message)});
}
function button(text,action){
  var b=document.createElement('button');b.textContent=text;
  b.onclick=function(){if(busy)return;busy=true;b.disabled=true;
    Promise.resolve().then(action).catch(function(e){note(e.message)}).then(function(){
      busy=false;b.disabled=b.getAttribute('data-pending')==='true';
    });
  };return b;
}
function command(action,extra){return api(Object.assign({action:action},extra||{})).then(function(v){note(v.message);return v})}
function nav(){Array.prototype.forEach.call(by('tabs').children,function(b){b.setAttribute('aria-pressed',b.getAttribute('data-mode')===mode?'true':'false')})}
function favoriteLabel(item){return item.favoritePending?'收藏待同步':item.favorite?'取消账户收藏':'加入账户收藏'}
function render(data){
  lastData=data;by('rows').textContent='';
  var source=related?'related':mode;
  (data.items||[]).forEach(function(item){
    var card=document.createElement('article');card.className='card';
    var h=document.createElement('h3');h.textContent=item.title;card.appendChild(h);
    var p=document.createElement('p');p.className='muted';p.textContent=item.badge||'';card.appendChild(p);
    var actions=document.createElement('div');actions.className='row';
    if(item.playable!==false){
      actions.appendChild(button('电视播放 / 续播',function(){return command('play',{id:item.id,source:source,scope:item.scope})}));
      actions.appendChild(button('相关作品 / 预约',function(){
        return api({action:'related',id:item.id}).then(function(v){
          clearTimeout(listTimer);view++;
          if(!related)returnList={data:lastData,context:shownContext};
          related=true;render(v);
        });
      }));
      var favorite=button(favoriteLabel(item),function(){
        var add=!item.favorite;
        if(!add&&!confirm('从账户收藏中移除《'+item.title+'》？'))return;
        favorite.textContent='正在更新…';favorite.setAttribute('data-pending','true');
        return command('favorite',{id:item.id,add:add,scope:item.scope}).catch(function(e){favorite.textContent=favoriteLabel(item);favorite.setAttribute('data-pending','false');throw e});
      });
      favorite.setAttribute('data-favorite',item.id);favorite.setAttribute('data-pending',String(!!item.favoritePending));favorite.disabled=!!item.favoritePending;
      favorite.update=function(state){item.favorite=state.favorite;item.favoritePending=state.pending;favorite.textContent=favoriteLabel(item);favorite.disabled=!!state.pending||!!state.loading;favorite.setAttribute('data-pending',String(favorite.disabled))};
      actions.appendChild(favorite);
    }else if(item.reservable){
      var reserve=button(item.reservationLabel||'预约',function(){
        reserve.textContent='预约中…';reserve.setAttribute('data-pending','true');
        return command('reserve',{id:item.id,scope:item.scope}).catch(function(e){reserve.textContent='预约';reserve.setAttribute('data-pending','false');throw e});
      });
      reserve.setAttribute('data-reserve',item.id);reserve.disabled=['已预约','已提交','预约中…'].indexOf(item.reservationLabel)>=0;
      reserve.setAttribute('data-pending',String(reserve.disabled));actions.appendChild(reserve);
    }
    card.appendChild(actions);by('rows').appendChild(card);
  });
  by('backList').hidden=!related;by('prev').disabled=related||page<=1;by('next').disabled=related||!data.hasMore;
  by('page').textContent=related?'相关作品':'第 '+page+' 页';
  note(data.message||((data.items||[]).length?'':'暂无记录'));
}
function context(){return {mode:mode,page:page,keyword:keyword}}
function restore(c){if(c){mode=c.mode;page=c.page;keyword=c.keyword;nav()}}
function load(refresh){
  if(busy)return;
  if(mode==='search'&&!keyword){note('请输入剧名');by('query').focus();return}
  busy=true;clearTimeout(listTimer);by('retry').hidden=true;
  var current=++view, requested=context();note('正在读取…');
  api({action:mode==='search'?'search':'list',kind:mode,page:page,query:keyword,refresh:!!refresh,sync:mode==='search'&&by('sync').checked})
    .then(function(v){if(current!==view)return;related=false;returnList=null;shownContext=requested;render(v);
      if(v.loading&&!document.hidden)listTimer=setTimeout(function(){if(current===view)load(false)},1500);
    }).catch(function(e){
      restore(shownContext);retryAction=function(){restore(requested);load(refresh)};
      by('retry').hidden=false;note(e.message);
    }).then(function(){busy=false});
}
by('retry').onclick=function(){if(!busy&&retryAction)retryAction()};
by('backList').onclick=function(){if(busy||!returnList)return;var previous=returnList;related=false;returnList=null;restore(previous.context);render(previous.data);load(false)};
[['search','找剧'],['favorites','账户收藏'],['history','账户历史'],['localFavorites','本机收藏'],['localHistory','本机历史'],['later','稍后看'],['online','预约·已上线'],['upcoming','预约·未上线']].forEach(function(t){
  var b=document.createElement('button');b.textContent=t[1];b.setAttribute('data-mode',t[0]);
  b.onclick=function(){if(busy)return;mode=t[0];page=1;nav();
    if(mode!=='search'||keyword)load(true);else{related=false;returnList=null;shownContext=null;render({items:[]});note('输入剧名开始搜索')}
  };by('tabs').appendChild(b);
});nav();
by('search').onclick=function(){if(busy)return;var value=by('query').value.trim();if(!value){note('请输入剧名');return}keyword=value;mode='search';page=1;nav();load(false)};
by('query').onkeydown=function(e){if(e.key==='Enter')by('search').click()};
by('refresh').onclick=function(){load(true)};
by('prev').onclick=function(){if(!busy&&page>1){page--;load(false)}};
by('next').onclick=function(){if(!busy){page++;load(false)}};
function send(action,extra){if(commandBusy)return;commandBusy=true;command(action,extra).catch(function(e){note(e.message)}).then(function(){commandBusy=false})}
Array.prototype.forEach.call(document.querySelectorAll('[data-cmd]'),function(b){b.onclick=function(){send(b.getAttribute('data-cmd'))}});
Array.prototype.forEach.call(document.querySelectorAll('[data-key]'),function(b){b.onclick=function(){send('key',{value:b.getAttribute('data-key')})}});
by('speed').onchange=function(){send('speed',{value:Number(this.value)})};
by('quality').onchange=function(){send('quality',{value:Number(this.value)})};
by('jump').onclick=function(){send('episode',{value:Number(by('episode').value)})};
by('account').onclick=function(){send('account')};
by('disconnect').onclick=function(){if(confirm('断开所有手机？重新连接需要在电视上重新打开控制台。'))command('disconnect').then(function(){stopped=true;clearTimeout(timer);clearTimeout(listTimer);note('已断开，请在电视上重新连接')}).catch(function(){stopped=true;clearTimeout(timer);clearTimeout(listTimer);note('连接已关闭')})};
function time(ms){var n=Math.floor(Math.max(0,ms)/1000);return Math.floor(n/60)+':'+('0'+n%60).slice(-2)}
function poll(){
  if(stopped||document.hidden||polling)return;polling=true;
  api({action:'status'}).then(function(s){
    if(s.reservation)Array.prototype.forEach.call(document.querySelectorAll('[data-reserve]'),function(b){if(b.getAttribute('data-reserve')===s.reservation.id){b.textContent=s.reservation.label;b.disabled=['已预约','已提交','预约中…'].indexOf(s.reservation.label)>=0;b.setAttribute('data-pending',String(b.disabled))}});
    if(s.favorite)Array.prototype.forEach.call(document.querySelectorAll('[data-favorite]'),function(b){if(b.getAttribute('data-favorite')===s.favorite.id)b.update(s.favorite)});
    if(s.scope!==undefined){if(accountScope!==null&&accountScope!==s.scope){accountChanged=true;view++;page=1;related=false;returnList=null;shownContext=null;render({items:[]});note('账户已切换，正在更新片单…')}accountScope=s.scope}
    if(s.catalogRevision!==undefined){if(revision!==null&&revision!==s.catalogRevision)refreshNeeded=true;revision=s.catalogRevision}
    if(!busy&&!related&&((refreshNeeded&&mode!=='search')||(accountChanged&&(mode!=='search'||keyword)))){refreshNeeded=false;accountChanged=false;load(false)}
    by('playing').textContent=s.title||'电视未在剧集播放页';
    by('position').textContent=s.active?('第 '+s.episode+' / '+s.episodes+' 集 · '+time(s.position)+' / '+time(s.duration)+' · '+(s.playing?'播放中':'已暂停 / 准备中')):'选择剧目后点击「电视播放」';
    by('accountLabel').textContent='账户：'+s.account+(s.message?' · '+s.message:'');
    if(!commandBusy){by('speed').value=String(s.speed);by('quality').value=String(s.quality)}
  }).catch(function(){by('playing').textContent='连接中断，请检查电视或重新扫码'}).then(function(){polling=false;if(!stopped&&!document.hidden)timer=setTimeout(poll,3000)});
}
document.addEventListener('visibilitychange',function(){clearTimeout(timer);clearTimeout(listTimer);if(!document.hidden){poll();if(mode!=='search'&&!related)load(false)}});poll();
})();
</script></body></html>"""
}
