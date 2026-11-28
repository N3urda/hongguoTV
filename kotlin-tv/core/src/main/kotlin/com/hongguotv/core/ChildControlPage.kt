// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** TV-served phone UI only; all permission decisions are made by the TV. */
internal object ChildControlPage {
    val markup = """
<section id="panel-child" role="tabpanel" aria-labelledby="tab-child" hidden>
<h2>允许列表</h2><p class="muted">按名称排列 · 允许整部作品及后续更新</p><div id="childHost"></div>
</section>
<section id="panel-parent" role="tabpanel" aria-labelledby="tab-parent" hidden>
<h2>家长设置</h2><p id="childLabel" role="status" aria-live="polite"></p>
<p class="muted">验证后进入搜索添加作品；电视仍保持儿童模式。允许列表在独立标签页查看。</p>
<div id="parentForm"><fieldset id="parentFields" style="border:0;padding:0;margin:0">
<label for="parentPin">家长密码</label><input id="parentPin" type="password" inputmode="numeric" maxlength="6" autocomplete="off" placeholder="6 位数字">
<div class="row"><button id="parentUnlock">进入儿童管理</button><button id="parentLock">结束家长管理</button><button id="childEnable">开启儿童模式</button><button id="childDisable">退出儿童模式（重新验证）</button></div>
<details><summary>修改密码 / 忘记密码</summary><p class="muted">修改密码需在上方输入旧密码；忘记密码需输入初次设置时保存的恢复码。</p>
<label for="childNewPin">新密码</label><input id="childNewPin" type="password" inputmode="numeric" maxlength="6" autocomplete="off">
<label for="childConfirmPin">确认新密码</label><input id="childConfirmPin" type="password" inputmode="numeric" maxlength="6" autocomplete="off">
<label for="childRecovery">恢复码</label><input id="childRecovery" type="password" maxlength="40" autocomplete="off">
<div class="row"><button id="childChangePin">修改密码</button><button id="childRecover">使用恢复码重设</button></div></details>
</fieldset></div>
<p id="childRecoveryResult" hidden></p><button id="childRecoverySaved" hidden>已保存恢复码</button>
</section>
""".trimIndent()
    val script = """
var parentToken='', childEnabled=false, parentAuthorized=false, childRevision=null, childBusy=false, lastChildState=null;
function applyChildState(s){
 if(!s)return;lastChildState=s;
 var gained=!parentAuthorized&&!!s.parent;
 var changed=childEnabled!==!!s.enabled||parentAuthorized!==!!s.parent;
 var refresh=childRevision!==s.revision;
 childEnabled=!!s.enabled;parentAuthorized=!!s.parent;childRevision=s.revision;
 if(!parentAuthorized)parentToken='';
 if(!childBusy)by('childLabel').textContent=s.broken?'配置异常，电视已限制播放':!s.configured?'请先在电视「设置 → 账户 → 儿童模式」设置密码':(childEnabled?'儿童模式已开启':'儿童模式未开启')+(parentAuthorized?' · 家长管理已验证':' · 修改允许列表需家长验证');
 by('parentForm').hidden=!s.configured||s.broken;
 by('parentLock').hidden=!parentAuthorized;by('childEnable').hidden=childEnabled;by('childEnable').disabled=!parentAuthorized;
 by('childDisable').hidden=!childEnabled;
 by('tab-account').hidden=childEnabled;
 by('tab-browse').hidden=childEnabled&&!parentAuthorized;
 by('tab-library').hidden=childEnabled&&!parentAuthorized;
 if(changed){
  view++;related=false;returnList=null;shownContext=null;lastData=null;hideSuggestions();render({items:[]});clearTimeout(listTimer);
  if(gained){browseMode='search';changeTab('browse')}
  else if(childEnabled&&!parentAuthorized)changeTab('child');
  else if(childEnabled&&tab==='account')changeTab('child');
  else changeTab(tab);
 }else if(refresh&&tab==='child'){refreshNeeded=true;if(!busy)load(false)}
 if(childEnabled){qrPending=false;clearTimeout(qrTimer);by('qrImage').removeAttribute('src');by('openApp').removeAttribute('href')}
}
function childManage(action){
 if(childBusy)return;childBusy=true;
 by('parentFields').disabled=true;by('childLabel').textContent='正在验证，请稍候…';
 var data={action:action,pin:by('parentPin').value,oldPin:by('parentPin').value,newPin:by('childNewPin').value,confirmPin:by('childConfirmPin').value,recoveryCode:by('childRecovery').value};
 api(data).then(function(v){
  if(v.parentToken)parentToken=v.parentToken;
  if(action==='parentLock'||action==='childEnable'||action==='childDisable'||v.recoveryCode)parentToken='';
  if(v.recoveryCode){by('childRecoveryResult').textContent='请保存新的恢复码（旧码已失效）：'+v.recoveryCode;by('childRecoveryResult').hidden=false;by('childRecoverySaved').hidden=false}
  return api({action:'childStatus'}).then(function(state){applyChildState(state);if(v.recoveryCode)changeTab('parent');note(v.message||'操作成功')});
 }).catch(function(e){note(e.message)}).then(function(){
  childBusy=false;by('parentFields').disabled=false;
  ['parentPin','childNewPin','childConfirmPin','childRecovery'].forEach(function(id){by(id).value=''});
  if(lastChildState)applyChildState(lastChildState);
 });
}
['parentUnlock','parentLock','childEnable','childDisable','childChangePin','childRecover'].forEach(function(id){by(id).onclick=function(){childManage(id)}});
by('childRecoverySaved').onclick=function(){by('childRecoveryResult').textContent='';by('childRecoveryResult').hidden=true;this.hidden=true};
""".trimIndent()
}
