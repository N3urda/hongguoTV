// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** TV-served phone UI only; all permission decisions are made by the TV. */
internal object ChildControlPage {
    val markup = """
<details id="childPanel" class="card"><summary>儿童模式 · 家长管理</summary>
<p id="childLabel" role="status"></p>
<p class="muted">白名单允许整部作品及后续更新。手机家长管理不会解除电视的儿童模式。</p>
<div id="parentForm"><label for="parentPin">家长密码</label><input id="parentPin" type="password" inputmode="numeric" maxlength="6" autocomplete="off" placeholder="6 位数字">
<div class="row"><button id="parentUnlock">验证家长密码</button><button id="parentLock">结束家长管理</button><button id="childEnable">开启儿童模式</button><button id="childDisable">退出儿童模式（重新验证）</button></div>
<details><summary>修改密码 / 忘记密码</summary><p class="muted">修改密码需在上方输入旧密码；忘记密码需输入初次设置时保存的恢复码。</p>
<label for="childNewPin">新密码</label><input id="childNewPin" type="password" inputmode="numeric" maxlength="6" autocomplete="off">
<label for="childConfirmPin">确认新密码</label><input id="childConfirmPin" type="password" inputmode="numeric" maxlength="6" autocomplete="off">
<label for="childRecovery">恢复码</label><input id="childRecovery" type="password" maxlength="40" autocomplete="off">
<div class="row"><button id="childChangePin">修改密码</button><button id="childRecover">使用恢复码重设</button></div></details></div>
<p id="childRecoveryResult" hidden></p><button id="childRecoverySaved" hidden>已保存恢复码</button>
<div class="row"><button id="childRefresh">查看 / 刷新儿童片单</button><button id="childPrev">上一页</button><button id="childNext">下一页</button></div><div id="childRows"></div>
</details>
""".trimIndent()
    val script = """
var parentToken='', childEnabled=false, parentAuthorized=false, childRevision=null, childPage=1, childLoading=false, childBusy=false;
function applyChildState(s){
 if(!s)return;
 var changed=childEnabled!==!!s.enabled||parentAuthorized!==!!s.parent;
 var refresh=childRevision!==s.revision;
 childEnabled=!!s.enabled;parentAuthorized=!!s.parent;childRevision=s.revision;
 if(!parentAuthorized)parentToken='';
 by('childLabel').textContent=s.broken?'配置异常，电视已限制播放':!s.configured?'请先在电视「设置 → 儿童模式」设置密码':(childEnabled?'儿童模式已开启':'儿童模式未开启')+(parentAuthorized?' · 家长管理已验证':' · 修改片单需家长验证');
 by('parentForm').hidden=!s.configured||s.broken;
 by('parentLock').hidden=!parentAuthorized;by('childEnable').hidden=childEnabled;by('childEnable').disabled=!parentAuthorized;
 by('childDisable').hidden=!childEnabled;
 by('tab-account').hidden=childEnabled;
 by('tab-browse').hidden=childEnabled&&!parentAuthorized;
 by('tab-library').hidden=childEnabled&&!parentAuthorized;
 if(changed){
  view++;related=false;returnList=null;shownContext=null;lastData=null;hideSuggestions();render({items:[]});clearTimeout(listTimer);
  if(childEnabled){by('childPanel').open=true;if(!parentAuthorized||tab==='account')changeTab('player')}
 }
 if(childEnabled){qrPending=false;clearTimeout(qrTimer);by('qrImage').removeAttribute('src');by('openApp').removeAttribute('href')}
 if(refresh||changed){by('childRows').textContent='';if(by('childPanel').open)setTimeout(loadChildList,0)}
}
function loadChildList(){
 if(childLoading)return;childLoading=true;
 return api({action:'childList',page:childPage}).then(function(v){
  by('childRows').textContent='';
  (v.items||[]).forEach(function(item){
   var card=document.createElement('div');card.className='card';var title=document.createElement('h3');title.textContent=item.title;card.appendChild(title);
   card.appendChild(button('电视播放',function(){return command('play',{id:item.id,scope:item.scope,source:'child'}).then(function(){changeTab('player');poll()})}));
   if(parentAuthorized)card.appendChild(button('移出儿童片单',function(){
    if(!confirm('移除《'+item.title+'》？如果电视正在播放，将立即停止。'))return;
    return api({action:'childRemove',id:item.id}).then(function(){return loadChildList()});
   }));
   by('childRows').appendChild(card);
  });
  if(!(v.items||[]).length)by('childRows').textContent='儿童片单为空。家长验证后可在找剧或片单中添加。';
  by('childPrev').disabled=childPage<=1;by('childNext').disabled=!v.hasMore;
 }).catch(function(e){note(e.message)}).then(function(){childLoading=false});
}
function childManage(action){
 if(childBusy)return;childBusy=true;
 var data={action:action,pin:by('parentPin').value,oldPin:by('parentPin').value,newPin:by('childNewPin').value,confirmPin:by('childConfirmPin').value,recoveryCode:by('childRecovery').value};
 api(data).then(function(v){
  if(v.parentToken)parentToken=v.parentToken;
  if(action==='parentLock'||action==='childEnable'||action==='childDisable'||v.recoveryCode)parentToken='';
  if(v.recoveryCode){by('childRecoveryResult').textContent='请保存新的恢复码（旧码已失效）：'+v.recoveryCode;by('childRecoveryResult').hidden=false;by('childRecoverySaved').hidden=false;by('childPanel').open=true}
  return api({action:'childStatus'}).then(function(state){applyChildState(state);note(v.message||'操作成功')});
 }).catch(function(e){note(e.message)}).then(function(){childBusy=false;['parentPin','childNewPin','childConfirmPin','childRecovery'].forEach(function(id){by(id).value=''})});
}
['parentUnlock','parentLock','childEnable','childDisable','childChangePin','childRecover'].forEach(function(id){by(id).onclick=function(){childManage(id)}});
by('childRefresh').onclick=loadChildList;
by('childPrev').onclick=function(){if(!childLoading&&childPage>1){childPage--;loadChildList()}};
by('childNext').onclick=function(){if(!childLoading){childPage++;loadChildList()}};
by('childRecoverySaved').onclick=function(){by('childRecoveryResult').textContent='';by('childRecoveryResult').hidden=true;this.hidden=true};
by('childPanel').ontoggle=function(){if(this.open)loadChildList()};
""".trimIndent()
}
