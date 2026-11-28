// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Local customization kept separate from upstream phone UI. */
internal object LocalUpdatePage {
    val tab = """<button id="tab-update" role="tab" aria-controls="panel-update" data-tab="update" aria-selected="false">应用更新</button>"""
    val markup = """
<section id="panel-update" role="tabpanel" aria-labelledby="tab-update" hidden>
<h2>上传应用更新</h2><p>手机与电视需连接同一局域网。选择同包名、同签名的新版 APK（不超过 200 MB）。校验通过后自动发起安装，请按电视系统提示确认。</p>
<p>已设置家长密码时，请先在家长设置验证；否则需在电视允许本次上传。微信无法选择文件时，请保存文件并用系统浏览器打开遥控页面。</p>
<input id="updateFile" type="file" accept=".apk,application/vnd.android.package-archive">
<p id="updateSelected"></p><button id="updateSend">上传并安装</button><button id="updateCancel" hidden>取消上传</button>
<p id="updateMessage" role="status" aria-live="polite"></p><progress id="updateProgress" max="100" value="0"></progress>
</section>
""".trimIndent()
    val script = """
var updateBusy=false,updateCancelled=false,updateTask='',updateTarget=0,updateWatch=null;
function updateText(text){by('updateMessage').textContent=text}
function updateDelay(ms){return new Promise(function(resolve){setTimeout(resolve,ms)})}
function updateRead(blob){return new Promise(function(resolve,reject){var r=new FileReader();r.onload=function(){resolve(r.result.split(',')[1])};r.onerror=function(){reject(Error('无法读取文件'))};r.readAsDataURL(blob)})}
function updatePoll(){
 clearTimeout(updateWatch);
 api({action:'updateStatus'}).then(function(s){
  if(s.target)updateTarget=s.target;
  if(updateTarget&&s.versionCode>=updateTarget){updateText('升级成功，当前版本 '+s.version);updateTarget=0;return}
  updateText(s.message+' · 当前版本 '+s.version);
  if(s.state==='checking'||s.state==='installing')updateWatch=setTimeout(updatePoll,2000);
 }).catch(function(){updateText('正在等待电视重新连接。若地址已变化，请重新扫码确认版本。');updateWatch=setTimeout(updatePoll,5000)});
}
by('tab-update').addEventListener('click',function(){if(!updateBusy)updatePoll()});
by('updateFile').onchange=function(){var f=this.files[0];by('updateSelected').textContent=f?f.name+' · '+(f.size/1048576).toFixed(1)+' MB':''};
by('updateCancel').onclick=function(){updateCancelled=true;this.disabled=true};
by('updateSend').onclick=async function(){
 if(updateBusy)return;
 var file=by('updateFile').files[0];if(!file||!file.name.toLowerCase().endsWith('.apk')||file.size<1||file.size>200*1024*1024){updateText('请选择 200 MB 以内的 APK');return}
 updateBusy=true;updateCancelled=false;updateTask='';clearTimeout(updateWatch);
 by('updateSend').disabled=true;by('updateFile').disabled=true;
 try{
  var auth=await api({action:'updateAuthorize'});
  if(!auth.authorized){updateText('请在电视上允许上传，然后再次点击上传并安装');return}
  var s=await api({action:'updateBegin',size:file.size});updateTask=s.task;
  by('updateCancel').hidden=false;by('updateCancel').disabled=false;
  var offset=0;
  while(offset<file.size){
   if(updateCancelled)throw Error('上传已取消');
   var data=await updateRead(file.slice(offset,offset+32768));
   var payload={action:'updateChunk',task:updateTask,offset:offset,data:data};
   try{s=await api(payload)}catch(e){await updateDelay(500);s=await api(payload)}
   offset=s.offset;var percent=Math.floor(offset*100/file.size);
   by('updateProgress').value=percent;updateText('正在上传 '+percent+'%');
  }
  if(updateCancelled)throw Error('上传已取消');
  by('updateCancel').hidden=true;updateText('正在校验安装包');
  await api({action:'updateFinish',task:updateTask});updateTask='';updatePoll();
 }catch(e){
  if(updateTask)try{await api({action:'updateCancel',task:updateTask})}catch(ignore){}
  updateText(e.message);
 }finally{updateBusy=false;by('updateSend').disabled=false;by('updateFile').disabled=false;by('updateCancel').hidden=true}
};
""".trimIndent()
}
