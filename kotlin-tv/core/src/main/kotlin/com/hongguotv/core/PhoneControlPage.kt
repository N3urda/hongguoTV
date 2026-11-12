// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** All rendering stays on the phone. No artwork, web fonts, libraries or video on the TV. */
internal object PhoneControlPage {
    fun html(nonce: String) = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>红果 TV · 手机控制台</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#10131b;color:#f5f5f7;font:16px system-ui,sans-serif}main{max-width:700px;margin:auto;padding:20px 18px 40px}h1{font-size:23px;margin:6px 0}h2{font-size:21px}h3{font-size:17px}button,input,select,a.action{font:inherit;border-radius:10px;padding:11px 13px;border:1px solid #475063;background:#222a38;color:inherit;min-height:44px}button{cursor:pointer}button:disabled{opacity:.45}button.primary,a.action.primary{background:#e95342;border-color:#e95342}a.action{display:inline-block;text-decoration:none}a{color:#ffad9b}[hidden]{display:none!important}.row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:12px 0}input[type=search],input[type=tel],input.code{flex:1;min-width:0;width:100%}.searchbox{display:flex;gap:8px;margin:18px 0 8px}.card{background:#1b2230;border:1px solid #2b3444;border-radius:14px;padding:16px;margin:12px 0}.muted{color:#acb8ca;font-size:14px;line-height:1.6}#notice{color:#ffd0a0;margin:8px 0;line-height:1.5}#notice:empty{display:none}#rows h3{margin:0 0 8px}label{display:inline-flex;align-items:center;gap:6px}input[type=number]{width:84px}.tabs{display:grid;grid-template-columns:repeat(4,1fr);gap:6px;position:sticky;top:0;background:#10131b;padding:14px 0;z-index:2}.tabs button{border-color:transparent;background:none}.tabs button[aria-selected=true],button[aria-pressed=true]{background:#392822;border-color:#df735b;color:#ffb19d}.subtabs{display:flex;gap:8px;margin:12px 0}.subtabs button{flex:1}.chips{display:flex;gap:8px;overflow-x:auto;padding:4px 0 8px}.chips button{white-space:nowrap;font-size:14px;padding:8px 13px;flex-shrink:0}.filter h3{font-size:14px;margin:8px 0 2px;color:#acb8ca}.suggestions{display:grid;gap:4px}.suggestions button{text-align:left;background:#1b2230;border-color:transparent}summary{cursor:pointer;padding:12px 0;min-height:44px}#qrImage{width:220px;max-width:100%;background:white;padding:8px;border-radius:12px}#libraryKind{width:100%}.playback-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:16px 0}small{display:block}header .muted{margin:4px 0}.empty{padding:20px 0;color:#acb8ca}#loginPanel input{margin:5px 0}#loginNote{min-height:22px;line-height:1.5;color:#ffd0a0}
</style></head>
<body><main><header><h1>红果 TV</h1><p class="muted" id="connection">正在连接电视…</p></header>
<nav class="tabs" role="tablist" aria-label="主要功能"><button id="tab-browse" role="tab" aria-controls="panel-browse" data-tab="browse" aria-selected="true">找剧</button><button id="tab-player" role="tab" aria-controls="panel-player" data-tab="player" aria-selected="false">播放</button><button id="tab-library" role="tab" aria-controls="panel-library" data-tab="library" aria-selected="false">片单</button><button id="tab-account" role="tab" aria-controls="panel-account" data-tab="account" aria-selected="false">账户</button></nav>
<p id="notice" role="status" aria-live="polite"></p>
<section id="panel-browse" role="tabpanel" aria-labelledby="tab-browse">
<div class="searchbox"><input id="query" type="search" maxlength="80" autocomplete="off" placeholder="搜剧名、演员" aria-label="搜索剧名或演员" aria-controls="suggestions"><button class="primary" id="search">搜索</button></div>
<div id="suggestions" class="suggestions" aria-label="搜索联想" hidden></div>
<div class="subtabs"><button id="searchMode" aria-pressed="true">搜索</button><button id="discoverMode" aria-pressed="false">按标签找剧</button></div>
<div id="filters" hidden></div><div id="browseHost"></div>
</section>
<section id="panel-player" role="tabpanel" aria-labelledby="tab-player" hidden>
<h2 id="playing">电视未在剧集播放页</h2><p id="position" class="muted"></p>
<div class="playback-grid"><button data-cmd="previous">上一集</button><button class="primary" id="togglePlay">暂停</button><button data-cmd="next">下一集</button></div>
<div class="row"><button data-cmd="backward">−15 秒</button><button data-cmd="forward">+15 秒</button></div>
<div class="card"><div class="row"><label>倍速<select id="speed"><option>0.75</option><option selected>1</option><option>1.25</option><option>1.5</option><option>1.75</option><option>2</option><option>3</option></select></label><label>清晰度<select id="quality"><option>480</option><option>720</option><option selected>1080</option></select></label></div><div class="row"><label>第<input id="episode" type="number" min="1" value="1">集</label><button id="jump">播放此集</button></div></div>
<p class="muted">在「找剧」或「片单」选一部剧，即可在电视播放。</p></section>
<section id="panel-library" role="tabpanel" aria-labelledby="tab-library" hidden>
<h2>我的片单</h2><label for="libraryKind" class="muted">片单来源</label><select id="libraryKind"><option value="favorites">账户收藏</option><option value="history">账户历史</option><option value="online">预约 · 已上线</option><option value="localFavorites">本机收藏</option><option value="localHistory">本机历史</option><option value="later">稍后看</option></select>
<div id="loginGate" class="card" hidden><p>登录红果后，在这里查看账户片单。</p><button id="goLogin" class="primary">在手机登录</button></div><div id="libraryHost"></div></section>
<section id="panel-account" role="tabpanel" aria-labelledby="tab-account" hidden><h2>红果账户</h2><p id="accountLabel"></p><p id="accountStatus" class="muted"></p>
<div id="loginPanel" class="card"><h3>在手机登录，同步到电视</h3><button id="prepareApp" class="primary">红果 App 授权登录</button><p><a id="openApp" class="action primary" hidden rel="noreferrer">打开红果 App 授权</a></p><p id="appHint" class="muted">手机红果 App 需先登录。授权后返回此页，电视自动登录。</p><details id="smsDetails"><summary>手机号验证码登录</summary><p class="muted">使用红果手机号验证，成功后这台电视自动登录。</p><label for="mobile">手机号（支持 +国家码）</label><input id="mobile" type="tel" autocomplete="tel" maxlength="32" placeholder="请输入手机号"><div class="row"><button id="sendCode">获取验证码</button></div><label for="code">短信验证码</label><input id="code" class="code" inputmode="numeric" autocomplete="one-time-code" maxlength="8" placeholder="请输入验证码"><button id="verifyCode" class="primary">登录并同步到电视</button>
</details><p id="loginNote" role="status"></p><details id="qrDetails"><summary>改用红果 App 扫码授权</summary><p class="muted">用红果 App「我的 → 扫一扫」扫描此码；同一手机可尝试从相册识别。授权后返回本页，电视会自动同步。</p><button id="getQr">显示授权二维码</button><div id="qrBox" hidden><p><img id="qrImage" alt="红果登录授权二维码"></p><p id="qrNote" class="muted"></p></div></details></div>
<button id="switchAccount" hidden>换个账户登录</button><details><summary>连接设置</summary><p class="muted">同一局域网可用。电视退出应用后连接失效，需要重新扫码。</p><button id="disconnect">断开所有手机</button></details></section>
<div id="listArea"><div class="row"><button id="backList" hidden>返回原列表</button><button id="refresh">刷新列表</button><span id="page" class="muted"></span><button id="retry" hidden>重试</button></div><div id="rows"></div><div class="row" id="pagination"><button id="prev">上一页</button><button id="next">下一页</button></div></div>
<script nonce="$nonce">
(function () {
'use strict';
var tab='browse', browseMode='search', mode='search', page=1, keyword='', busy=false, commandBusy=false, stopped=false, polling=false;
var timer,listTimer,view=0,lastData=null,shownContext=null,returnList=null,related=false;
var revision=null,accountScope=null,refreshNeeded=false,accountChanged=false,retryAction=null,signedIn=false,playing=false;
var selection={},cursors=[null],groups=[],suggestTimer,suggestVersion=0,suggestPending=false;
var loginBusy=false,qrPending=false,qrDeadline=0,qrTimer,qrPolling=false,resendAt=0,countdown,loginSubmitted=false,changingAccount=false,submittedScope=null;
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
function nav(){
 by('searchMode').setAttribute('aria-pressed',String(browseMode==='search'));by('discoverMode').setAttribute('aria-pressed',String(browseMode==='discover'));
 by('filters').hidden=tab!=='browse'||browseMode!=='discover';
}
function cloudList(){return ['favorites','history','online'].indexOf(mode)>=0}
function gate(){var gated=tab==='library'&&cloudList()&&!signedIn;by('loginGate').hidden=!gated;by('listArea').hidden=gated;return gated}
function changeTab(next){
 tab=next;Array.prototype.forEach.call(document.querySelectorAll('[data-tab]'),function(b){var selected=b.getAttribute('data-tab')===tab;b.setAttribute('aria-selected',String(selected));by('panel-'+b.getAttribute('data-tab')).hidden=!selected});
 clearTimeout(listTimer);clearTimeout(qrTimer);hideSuggestions();note('');
 if(tab==='browse'||tab==='library'){
  by(tab==='browse'?'browseHost':'libraryHost').appendChild(by('listArea'));
  var nextMode=tab==='browse'?browseMode:by('libraryKind').value;
  if(mode!==nextMode){view++;mode=nextMode;page=1;related=false;returnList=null;shownContext=null;render({items:[]});}
  nav();if(!gate()&&!related){if(mode!=='search'||keyword)load(true);else emptySearch()}
 }else{by('listArea').hidden=true;if(tab==='account'){if(!signedIn&&!qrPending&&!loginBusy&&!loginSubmitted)prepareQr(false);else pollQr()}}
}
function emptySearch(){by('rows').textContent='输入剧名，或切换到「按标签找剧」。';by('rows').className='empty';by('pagination').hidden=true;by('page').textContent='';by('refresh').hidden=true}
function hideSuggestions(){clearTimeout(suggestTimer);suggestVersion++;by('suggestions').hidden=true;by('suggestions').textContent=''}
function searchFor(value){if(!value.trim())return;hideSuggestions();keyword=value.trim();by('query').value=keyword;browseMode='search';mode='search';page=1;nav();load(false)}
function suggest(){
 var value=by('query').value.trim(),token=++suggestVersion;if(!value||tab!=='browse'){hideSuggestions();return}
 if(suggestPending){suggestTimer=setTimeout(suggest,350);return}suggestPending=true;
 api({action:'suggest',query:value}).then(function(v){if(token!==suggestVersion||tab!=='browse')return;by('suggestions').textContent='';(v.items||[]).slice(0,8).forEach(function(t){var b=document.createElement('button');b.textContent=t;b.onclick=function(){searchFor(t)};by('suggestions').appendChild(b)});by('suggestions').hidden=!by('suggestions').children.length}).catch(function(){}).then(function(){suggestPending=false});
}
function renderFilters(){by('filters').textContent='';groups.forEach(function(g,index){var section=document.createElement(index<2?'div':'details');section.className='filter';var title=document.createElement(index<2?'h3':'summary');title.textContent=g.label+(selection[g.type]?' · '+((g.options||[]).find(function(o){return o.id===selection[g.type]})||{}).label:'');section.appendChild(title);var row=document.createElement('div');row.className='chips';[{id:'',label:'全部'}].concat(g.options||[]).forEach(function(o){var b=document.createElement('button');b.textContent=o.label;b.setAttribute('aria-pressed',String((selection[g.type]||'')===o.id));b.onclick=function(){if(busy)return;if(g.type==='genre')selection={};if(o.id)selection[g.type]=o.id;else delete selection[g.type];page=1;cursors=[null];load(false)};row.appendChild(b)});section.appendChild(row);by('filters').appendChild(section)})}

function favoriteLabel(item){return item.favoritePending?'收藏待同步':item.favorite?'取消账户收藏':'加入账户收藏'}
function render(data){
  lastData=data;by('rows').textContent='';by('rows').className='';by('pagination').hidden=false;by('refresh').hidden=false;
  var source=related?'related':mode;
  (data.items||[]).forEach(function(item){
    var card=document.createElement('article');card.className='card';
    var h=document.createElement('h3');h.textContent=item.title;card.appendChild(h);
    var p=document.createElement('p');p.className='muted';p.textContent=item.badge||'';card.appendChild(p);
    var actions=document.createElement('div');actions.className='row';
    if(item.playable!==false){
      actions.appendChild(button('电视播放',function(){return command('play',{id:item.id,source:source,scope:item.scope}).then(function(){changeTab('player');poll()})}));
      actions.appendChild(button('相关作品',function(){
        return api({action:'related',id:item.id}).then(function(v){
          clearTimeout(listTimer);view++;
          if(!related)returnList={data:lastData,context:shownContext};
          related=true;render(v);
        });
      }));
      var favorite=button(favoriteLabel(item),function(){
        if(!signedIn){changeTab('account');return}var add=!item.favorite;
        if(!add&&!confirm('从账户收藏中移除《'+item.title+'》？'))return;
        favorite.textContent='正在更新…';favorite.setAttribute('data-pending','true');
        return command('favorite',{id:item.id,add:add,scope:item.scope}).catch(function(e){favorite.textContent=favoriteLabel(item);favorite.setAttribute('data-pending','false');throw e});
      });
      favorite.setAttribute('data-favorite',item.id);favorite.setAttribute('data-pending',String(!!item.favoritePending));favorite.disabled=!!item.favoritePending;
      favorite.update=function(state){item.favorite=state.favorite;item.favoritePending=state.pending;favorite.textContent=favoriteLabel(item);favorite.disabled=!!state.pending||!!state.loading;favorite.setAttribute('data-pending',String(favorite.disabled))};
      actions.appendChild(favorite);
    }else if(item.reservable){
      var reserve=button(item.reservationLabel||'预约',function(){
        if(!signedIn){changeTab('account');return}reserve.textContent='预约中…';reserve.setAttribute('data-pending','true');
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
function context(){return {mode:mode,page:page,keyword:keyword,selection:Object.assign({},selection)}}
function restore(c){if(c){mode=c.mode;page=c.page;keyword=c.keyword;selection=c.selection||{};if(mode==='search'||mode==='discover')browseMode=mode;nav();renderFilters()}}
function load(refresh){
  if(busy){refreshNeeded=true;return}
  if(gate())return;
  if(mode==='search'&&!keyword){note('请输入剧名');by('query').focus();return}
  busy=true;clearTimeout(listTimer);by('retry').hidden=true;
  var current=++view, requested=context();note('正在读取…');
  api({action:mode==='search'?'search':mode==='discover'?'discover':'list',kind:mode,page:page,query:keyword,refresh:!!refresh,filters:selection,cursor:mode==='discover'?cursors[page-1]:undefined})
    .then(function(v){if(current!==view)return;related=false;returnList=null;shownContext=requested;if(mode==='discover'){if(v.groups&&v.groups.length)groups=v.groups;cursors[page]=v.cursor||null;renderFilters()}render(v);
      if(v.loading&&!document.hidden)listTimer=setTimeout(function(){if(current===view)load(false)},1500);
    }).catch(function(e){
      if(current!==view)return;restore(shownContext);retryAction=function(){restore(requested);load(refresh)};
      by('retry').hidden=false;note(e.message);
    }).then(function(){busy=false});
}
by('retry').onclick=function(){if(!busy&&retryAction)retryAction()};
by('backList').onclick=function(){if(busy||!returnList)return;var previous=returnList;related=false;returnList=null;restore(previous.context);render(previous.data);load(false)};
Array.prototype.forEach.call(document.querySelectorAll('[data-tab]'),function(b){b.onclick=function(){changeTab(b.getAttribute('data-tab'))};b.onkeydown=function(e){if(e.key==='ArrowRight'||e.key==='ArrowLeft'){var tabs=Array.prototype.slice.call(document.querySelectorAll('[data-tab]')),i=tabs.indexOf(b);tabs[(i+(e.key==='ArrowRight'?1:3))%4].focus();tabs[(i+(e.key==='ArrowRight'?1:3))%4].click();e.preventDefault()}}});
by('libraryKind').onchange=function(){changeTab('library')};
by('goLogin').onclick=function(){changeTab('account')};
by('searchMode').onclick=function(){browseMode='search';changeTab('browse')};
by('discoverMode').onclick=function(){browseMode='discover';changeTab('browse')};
by('search').onclick=function(){searchFor(by('query').value)};
by('query').onkeydown=function(e){if(e.key==='Enter')by('search').click();if(e.key==='Escape')hideSuggestions()};
by('query').oninput=function(){hideSuggestions();suggestTimer=setTimeout(suggest,350)};
by('refresh').onclick=function(){load(true)};
by('prev').onclick=function(){if(!busy&&page>1){page--;load(false)}};
by('next').onclick=function(){if(!busy){page++;load(false)}};
function send(action,extra){if(commandBusy)return;commandBusy=true;command(action,extra).then(function(){if(action==='pause'||action==='resume'){playing=action==='resume';by('togglePlay').textContent=playing?'暂停':'继续'}}).catch(function(e){note(e.message)}).then(function(){commandBusy=false})}
Array.prototype.forEach.call(document.querySelectorAll('[data-cmd]'),function(b){b.onclick=function(){send(b.getAttribute('data-cmd'))}});
by('togglePlay').onclick=function(){send(playing?'pause':'resume')};
by('speed').onchange=function(){send('speed',{value:Number(this.value)})};
by('quality').onchange=function(){send('quality',{value:Number(this.value)})};
by('jump').onclick=function(){send('episode',{value:Number(by('episode').value)})};

by('disconnect').onclick=function(){if(confirm('断开所有手机？重新连接需要在电视上重新打开控制台。'))command('disconnect').then(function(){stopped=true;clearTimeout(timer);clearTimeout(listTimer);clearTimeout(qrTimer);note('已断开，请在电视上重新连接')}).catch(function(){stopped=true;clearTimeout(timer);clearTimeout(listTimer);clearTimeout(qrTimer);note('连接已关闭')})};
function loginNote(t){by('loginNote').textContent=t||''}
function loginAction(action,extra){if(loginBusy)return Promise.resolve(null);loginBusy=true;by('sendCode').disabled=true;by('verifyCode').disabled=true;by('getQr').disabled=true;by('prepareApp').disabled=true;return api(Object.assign({action:action},extra||{})).then(function(v){loginNote(v.message);return v}).catch(function(e){loginNote(e.message);return null}).then(function(v){loginBusy=false;by('verifyCode').disabled=false;by('getQr').disabled=false;by('prepareApp').disabled=false;updateCountdown();return v})}
function updateCountdown(){clearTimeout(countdown);var left=Math.max(0,Math.ceil((resendAt-Date.now())/1000));by('sendCode').textContent=left?left+' 秒后重发':'获取验证码';by('sendCode').disabled=loginBusy||left>0;if(left&&!document.hidden)countdown=setTimeout(updateCountdown,1000)}
by('sendCode').onclick=function(){qrPending=false;clearTimeout(qrTimer);by('openApp').hidden=true;by('prepareApp').hidden=false;var mobile=by('mobile').value.trim();if(!mobile){loginNote('请输入手机号');return}loginAction('loginSend',{mobile:mobile}).then(function(v){if(v){resendAt=Date.now()+v.retrySeconds*1000;updateCountdown();by('code').focus()}})};
by('verifyCode').onclick=function(){var code=by('code').value.trim();if(!/^[0-9]{4,8}$/.test(code)){loginNote('请输入 4–8 位短信验证码');return}loginAction('loginVerify',{code:code}).then(function(v){if(v&&v.submitted){loginSubmitted=true;submittedScope=accountScope;qrPending=false;by('code').value='';by('mobile').value='';poll()}})};
function prepareQr(showImage){qrPending=false;clearTimeout(qrTimer);by('openApp').hidden=true;return loginAction('loginQr').then(function(v){if(!v)return;by('qrImage').src=v.image;by('qrBox').hidden=!showImage;qrDeadline=Date.now()+v.expiresIn*1000;qrPending=true;by('qrNote').textContent='等待红果 App 授权，返回本页后自动同步';if(v.appUrl&&v.appUrl.indexOf('dragon8662://webview?url=')===0){by('openApp').href=v.appUrl;by('openApp').hidden=false;by('prepareApp').hidden=true;by('appHint').textContent='请先确认手机红果 App 已登录，再点击授权。确认后返回此页，电视自动同步。'}else{by('appHint').textContent='暂未取得 App 授权入口，可使用验证码或扫码登录。'}pollQr()})}
by('prepareApp').onclick=function(){prepareQr(false)};
by('getQr').onclick=function(){prepareQr(true)};
by('openApp').onclick=function(e){if(Date.now()>=qrDeadline){e.preventDefault();by('openApp').hidden=true;by('prepareApp').hidden=false;loginNote('授权已过期，请重新发起');return}by('appHint').textContent='在红果 App 确认后返回此页。未能打开时，请用系统浏览器重试，或改用验证码登录。'};
function pollQr(){clearTimeout(qrTimer);if(!qrPending||qrPolling||stopped||document.hidden||tab!=='account')return;qrPolling=true;api({action:'loginPoll'}).then(function(v){if(v.pending&&Date.now()>=qrDeadline){qrPending=false;by('openApp').hidden=true;by('prepareApp').hidden=false;loginNote('授权已过期，请重新获取');return}if(v.submitted){qrPending=false;by('openApp').hidden=true;loginSubmitted=true;submittedScope=accountScope;loginNote(v.message);by('qrBox').hidden=true;by('qrImage').removeAttribute('src');poll()}}).catch(function(e){qrPending=false;by('openApp').hidden=true;by('prepareApp').hidden=false;by('qrNote').textContent=e.message;loginNote(e.message)}).then(function(){qrPolling=false;if(qrPending)qrTimer=setTimeout(pollQr,2500)})}
by('switchAccount').onclick=function(){changingAccount=true;prepareQr(false);by('loginPanel').hidden=false;by('switchAccount').hidden=true;loginNote('登录后将替换这台电视的当前账户')};
function time(ms){var n=Math.floor(Math.max(0,ms)/1000);return Math.floor(n/60)+':'+('0'+n%60).slice(-2)}
function poll(){
  if(stopped||document.hidden||polling)return;polling=true;
  api({action:'status'}).then(function(s){
    if(s.reservation)Array.prototype.forEach.call(document.querySelectorAll('[data-reserve]'),function(b){if(b.getAttribute('data-reserve')===s.reservation.id){b.textContent=s.reservation.label;b.disabled=['已预约','已提交','预约中…'].indexOf(s.reservation.label)>=0;b.setAttribute('data-pending',String(b.disabled))}});
    if(s.favorite)Array.prototype.forEach.call(document.querySelectorAll('[data-favorite]'),function(b){if(b.getAttribute('data-favorite')===s.favorite.id)b.update(s.favorite)});
    if(s.scope!==undefined){if(accountScope!==null&&accountScope!==s.scope){accountChanged=true;view++;page=1;related=false;returnList=null;shownContext=null;render({items:[]});note('账户已切换，正在更新片单…')}accountScope=s.scope}
    if(s.catalogRevision!==undefined){if(revision!==null&&revision!==s.catalogRevision)refreshNeeded=true;revision=s.catalogRevision}
    if((tab==='browse'||tab==='library')&&!busy&&!related&&(refreshNeeded||accountChanged)&&(mode!=='search'||keyword)){refreshNeeded=false;accountChanged=false;load(false)}
    by('playing').textContent=s.title||'电视未在剧集播放页';
    by('position').textContent=s.active?('第 '+s.episode+' / '+s.episodes+' 集 · '+time(s.position)+' / '+time(s.duration)+' · '+(s.playing?'播放中':'已暂停 / 准备中')):'选择剧目后点击「电视播放」';
    signedIn=!!s.signedIn;playing=!!s.playing;by('togglePlay').textContent=playing?'暂停':'继续';by('connection').textContent=s.active?'电视正在播放 · '+s.title:'已连接电视';by('accountLabel').textContent=s.account||'未登录';by('accountStatus').textContent=s.accountStatus===s.account?'':s.accountStatus||'';if(loginSubmitted&&signedIn&&s.scope!==submittedScope){loginSubmitted=false;changingAccount=false;by('loginPanel').hidden=true;by('switchAccount').hidden=false;loginNote('');note('已登录并同步到电视')}else if(signedIn&&!changingAccount&&!loginSubmitted){by('loginPanel').hidden=true;by('switchAccount').hidden=false}if(!signedIn){by('loginPanel').hidden=false;by('switchAccount').hidden=true}if(tab==='library')gate();
    if(!commandBusy){by('speed').value=String(s.speed);by('quality').value=String(s.quality)}
  }).catch(function(){by('connection').textContent='连接中断，请检查电视或重新扫码'}).then(function(){polling=false;if(!stopped&&!document.hidden)timer=setTimeout(poll,3000)});
}
document.addEventListener('visibilitychange',function(){clearTimeout(timer);clearTimeout(listTimer);clearTimeout(qrTimer);clearTimeout(countdown);hideSuggestions();if(!document.hidden){poll();pollQr();updateCountdown();if((tab==='browse'||tab==='library')&&mode!=='search'&&!related)load(false)}});by('browseHost').appendChild(by('listArea'));emptySearch();nav();poll();
})();
</script></body></html>"""
}
