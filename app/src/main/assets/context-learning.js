'use strict';
(() => {
  const STORAGE='wordweave.contextLearning.v1';
  window.ContextLearning={create(api){
    const esc=api.esc,$=id=>document.getElementById(id);let state=null,busy=false,lookup=0,epoch=0,open=false,wordView=null;
    const overlay=document.createElement('section');overlay.id='contextStudy';overlay.className='study context-study';
    overlay.innerHTML='<div class="study-wrap"><div class="context-header"><button id="contextExit" class="context-close" aria-label="暂停语境学习">×</button><div class="context-header-copy"><h2>在语境中理解单词</h2><p id="contextSubtitle">读句子，点词看讲解</p></div><span class="pill gray" id="contextProgress"></span></div><div id="contextContent"></div></div>';
    const drawer=document.createElement('div');drawer.id='contextWordOverlay';drawer.className='modal-overlay context-word-overlay';
    drawer.innerHTML='<div class="drawer"><div class="drawer-handle"></div><div id="contextWordContent"></div></div>';document.body.append(overlay,drawer);
    $('contextExit').onclick=close;drawer.onclick=e=>{if(e.target===drawer)closeWord();};
    function save(){try{if(state)localStorage.setItem(STORAGE,JSON.stringify({queue:state.queue,offset:state.offset,sessionId:state.session&&state.session.id,position:state.position,phase:state.phase,hidden:state.hidden||{},complete:state.complete||0}));else localStorage.removeItem(STORAGE);}catch(e){}}
    function show(){stop();open=true;api.hideLegacy();overlay.classList.add('open');render();}
    function close(){open=false;overlay.classList.remove('open');closeWord();save();stop();api.refresh();}
    function closeWord(){lookup++;drawer.classList.remove('open');wordView=null;}
    function stop(){api.request('stopSpeak').catch(()=>{});}
    function readyAi(){if(api.settings().hasApiKey)return true;api.toast('先设置 AI 服务，再生成语境与详细讲解。');close();api.settingsPage();return false;}
    function unknown(sentence,word){return (state.session.unknownWords||[]).some(n=>n.sentenceId===sentence.id&&normal(n.surface)===normal(word));}
    function normal(s){return String(s||'').toLowerCase().replace(/’/g,"'");}
    function contains(text,word){return new RegExp('(^|[^a-z])'+String(word).replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'(?=[^a-z]|$)','i').test(normal(text));}
    function targetToken(sentence,token){return (state.session.targetWords||[]).some(w=>{const forms=[w.word,...(String(w.forms||'').match(/[A-Za-z]+(?:['’-][A-Za-z]+)*/g)||[])];return forms.some(form=>contains(sentence.text,normal(form))&&contains(normal(form),normal(token)));});}
    function sentenceHTML(sentence){let html='',last=0;const regex=/[A-Za-z]+(?:['’-][A-Za-z]+)*/g;let match;
      while((match=regex.exec(sentence.text))){html+=esc(sentence.text.slice(last,match.index));const token=match[0];html+='<button type="button" class="context-token'+(targetToken(sentence,token)?' target':'')+(unknown(sentence,token)?' unknown':'')+'" data-context-token="'+esc(token)+'" aria-label="讲解 '+esc(token)+'">'+esc(token)+'</button>';last=regex.lastIndex;}
      return html+esc(sentence.text.slice(last));
    }
    function current(){return state.session.sentences[state.position];}
    function allViewed(){return state.session.sentences.every(s=>(state.session.viewed||{})[s.id]);}
    function graded(){return Object.keys(state.session.grades||{}).length;}
    function unknownList(){const seen=new Set();return (state.session.unknownWords||[]).filter(w=>{const key=normal(w.surface);if(seen.has(key))return false;seen.add(key);return true;});}
    function unknownHTML(){const words=unknownList();return words.length?'<h3 style="margin-top:23px;font-size:14px">点选过的词</h3><div class="context-unknown-list">'+words.map((w,i)=>'<button data-context-unknown="'+i+'">'+esc(w.surface)+'</button>').join('')+'</div>':'';}
    function explanationHTML(sentence){
      const notes=(sentence.wordNotes||[]).map(n=>'<div style="margin-top:9px"><strong>'+esc(n.word)+'</strong> <span class="muted tiny">'+esc(n.pos)+'</span><p>'+esc(n.meaning)+'</p><p class="muted tiny">'+esc(n.usage)+'</p></div>').join('');
      return '<div class="context-explanation" id="contextSentenceExplanation"><h3>句意</h3><p class="context-translation">'+esc(sentence.translation)+'</p>'+(sentence.explanation?'<h3>句子讲解</h3><p>'+esc(sentence.explanation)+'</p>':'')+(sentence.structure?'<h3>句子结构</h3><p>'+esc(sentence.structure)+'</p>':'')+(notes?'<h3>词语在本句中的用法</h3>'+notes:'')+(sentence.readingTip?'<h3>朗读提示</h3><p>'+esc(sentence.readingTip)+'</p>':'')+(sentence.structure?'':'<button id="contextSentenceAi" class="btn outline small" style="margin-top:16px"'+(busy?' disabled':'')+'>详细讲解这句话</button>')+'<div class="context-source">'+esc(sentence.source||'')+'</div></div>';
    }
    function render(){if(!open||!state)return;const content=$('contextContent');
      if(state.loading){content.innerHTML='<div class="context-loading"><div class="busy-line"><span class="spinner"></span>正在准备语境句…</div></div>';return;}
      if(state.error){content.innerHTML='<div class="notice error" style="margin-top:20px">'+esc(state.error)+'</div><button id="contextRetry" class="btn full" style="margin-top:15px">重新准备</button>';$('contextRetry').onclick=()=>{if(state.queue.length)loadGroup();else{state=null;start();}};return;}
      if(!state.queue.length){$('contextProgress').textContent='';content.innerHTML='<div class="context-empty"><div class="done-mark">✓</div><h2>这次的语境词已学完</h2><p>可以继续练习拼写，或到阅读页读一篇文章。</p><button id="contextToSpelling" class="btn full">继续拼写练习</button><button id="contextDone" class="btn outline full" style="margin-top:12px">返回今日</button></div>';$('contextToSpelling').onclick=()=>{reset();api.spelling();};$('contextDone').onclick=()=>{reset();api.refresh();};return;}
      if(!state.session)return;
      const session=state.session,words=session.targetWords||[];
      $('contextSubtitle').textContent=state.phase==='review'?'回想单词在句子中的意思':'点击不认识的词，先试着读懂整句';
      $('contextProgress').textContent=Math.min(state.offset+words.length,state.queue.length)+' / '+state.queue.length+' 词';
      const targets='<div class="context-targets">'+words.map(w=>'<span>'+esc(w.word)+'</span>').join('')+'</div>';
      if(state.phase==='review'){
        const grades=session.grades||{},hints=session.hints||{};
        content.innerHTML=targets+'<h3 style="margin:20px 0 6px">这一组，读懂了吗？</h3>'+words.map(w=>{const key=String(w.id),done=Object.prototype.hasOwnProperty.call(grades,key);return '<div class="card context-review-card" data-context-review="'+esc(w.id)+'"><div class="row between"><h3>'+esc(w.word)+'</h3><span class="muted tiny">'+esc(api.pos(w.pos))+'</span></div>'+(done?'<p class="muted tiny" style="margin-top:10px">'+(grades[key]===-1?'已在其他练习中更新':'已记录 · '+(grades[key]===0?'稍后再练':grades[key]===1?'提示后理解':'独立理解'))+'</p><div class="meaning-full">'+esc(api.meaning(w.meaning))+'</div>':'<div class="context-rating-buttons"><button data-context-grade="0" data-context-word="'+esc(w.id)+'"'+(busy?' disabled':'')+'>还没读懂</button><button data-context-grade="1" data-context-word="'+esc(w.id)+'"'+(busy?' disabled':'')+'>讲解后理解</button><button data-context-grade="3" data-context-word="'+esc(w.id)+'"'+(busy||hints[key]?' disabled':'')+'>独立读懂</button></div>'+(hints[key]?'<p class="context-controls-note">这个词已查看过讲解</p>':''))+'</div>';}).join('')+unknownHTML()+'<div class="context-review-actions"><button id="contextBackToSentences" class="btn outline">再读句子</button><button id="contextNextGroup" class="btn"'+(busy||graded()<words.length?' disabled':'')+'>'+(state.offset+words.length>=state.queue.length?'完成本次学习':'下一组语境')+'</button></div>';
        content.querySelectorAll('[data-context-grade]').forEach(b=>b.onclick=()=>grade(Number(b.dataset.contextWord),Number(b.dataset.contextGrade)));content.querySelectorAll('[data-context-unknown]').forEach(b=>b.onclick=async()=>{const note=unknownList()[Number(b.dataset.contextUnknown)];state.phase='sentences';await go(state.session.sentences.findIndex(s=>s.id===note.sentenceId));await openWord(note.surface);});$('contextBackToSentences').onclick=()=>{state.phase='sentences';save();render();};$('contextNextGroup').onclick=nextGroup;return;
      }
      const sentence=current(),expanded=(session.revealed||{})[sentence.id]&&!(state.hidden||{})[sentence.id];
      content.innerHTML=targets+'<div class="card"><div class="context-scene">'+esc(sentence.scene||'语境句')+' · '+(state.position+1)+' / '+session.sentences.length+'</div><div class="context-sentence" id="contextSentenceText">'+sentenceHTML(sentence)+'</div><div class="context-sentence-actions"><button id="contextSpeakSentence" class="btn secondary">朗读整句</button><button id="contextRevealSentence" class="btn outline"'+(busy?' disabled':'')+'>'+(expanded?'收起讲解':'句意与讲解')+'</button></div>'+(expanded?explanationHTML(sentence):'')+'</div><div class="context-tabs">'+session.sentences.map((s,i)=>'<button data-context-position="'+i+'" class="'+((session.viewed||{})[s.id]?'visited ':'')+(state.position===i?'active':'')+'"'+(busy?' disabled':'')+' aria-label="语境句 '+(i+1)+'">'+(i+1)+'</button>').join('')+'</div><div class="context-navigation"><button id="contextPrevious" class="btn outline"'+(busy||state.position===0?' disabled':'')+'>上一句</button><button id="contextNextSentence" class="btn"'+(busy?' disabled':'')+'>'+(state.position+1<session.sentences.length?'下一句':'回顾本组单词')+'</button></div><button id="contextReview" class="link" style="margin-top:15px"'+(busy||!allViewed()?' disabled':'')+'>回顾本组单词 →</button>'+(busy?'<div class="context-busy"><span class="spinner"></span>正在准备讲解…</div>':'')+'<div class="context-ai-tools"><button id="contextGenerate" class="btn outline full"'+(busy||graded()>0?' disabled':'')+'>'+(session.source==='ai'?'换一组不同语境':'生成更多不同语境')+'</button><p>使用你设置的 AI 服务，可能产生费用。</p></div>';
      content.querySelectorAll('[data-context-token]').forEach(b=>b.onclick=()=>openWord(b.dataset.contextToken));content.querySelectorAll('[data-context-position]').forEach(b=>b.onclick=()=>go(Number(b.dataset.contextPosition)));
      $('contextSpeakSentence').onclick=()=>api.request('speak',{text:sentence.text}).catch(e=>api.toast(e.message,true));$('contextRevealSentence').onclick=reveal;
      $('contextPrevious').onclick=()=>go(state.position-1);$('contextNextSentence').onclick=()=>{if(state.position+1<session.sentences.length)go(state.position+1);else review();};$('contextReview').onclick=review;$('contextGenerate').onclick=generate;
      if($('contextSentenceAi'))$('contextSentenceAi').onclick=sentenceAi;
    }
    async function start(){if(!api.ready()||busy)return;if(state&&state.queue.length){show();return;}epoch++;const own=epoch;state={queue:[],offset:0,position:0,phase:'sentences',loading:true,hidden:{},complete:0};show();
      try{const words=await api.request('queue',{mode:0,mixed:false,limit:Math.max(1,Number(api.settings().sessionSize)||12),newLimit:Number(api.settings().newPerDay==null?15:api.settings().newPerDay)});if(epoch!==own)return;state.queue=Array.isArray(words)?words:words.words||[];state.loading=false;await loadGroup();}catch(e){if(epoch===own){state.loading=false;state.error=e.message;render();}}
    }
    async function loadGroup(){if(!state)return;if(!state.queue.length){state.loading=false;render();return;}busy=true;state.loading=true;state.error='';render();const own=epoch;
      try{state.session=await api.request('contextCreate',{wordIds:state.queue.slice(state.offset,state.offset+6).map(w=>w.id)});if(epoch!==own)return;state.position=0;state.phase='sentences';state.hidden={};state.loading=false;save();await visit();}catch(e){if(epoch===own){state.loading=false;state.error=e.message;}}finally{if(epoch===own){busy=false;render();}}
    }
    async function visit(){const own=epoch,sessionId=state.session.id,sentenceId=current().id;const result=await api.request('contextVisit',{sessionId,sentenceId});if(epoch===own&&state.session.id===sessionId){state.session=result;save();}}
    async function go(position){if(busy||!state||position<0||position>=state.session.sentences.length)return;stop();busy=true;state.position=position;save();render();try{await visit();}catch(e){api.toast(e.message,true);}finally{busy=false;render();}}
    function review(){if(!allViewed()){api.toast('先读完这一组语境句，再回顾单词。');return;}stop();state.phase='review';save();render();}
    async function reveal(){if(busy)return;const sentence=current();state.hidden=state.hidden||{};
      if((state.session.revealed||{})[sentence.id]&&!state.hidden[sentence.id]){state.hidden[sentence.id]=true;save();render();return;}
      busy=true;render();try{state.session=await api.request('contextReveal',{sessionId:state.session.id,sentenceId:sentence.id});state.hidden[sentence.id]=false;save();}catch(e){api.toast(e.message,true);}finally{busy=false;render();}
    }
    async function generate(){if(busy||!readyAi())return;busy=true;render();const own=epoch,id=state.session.id;
      try{const result=await api.request('contextGenerate',{sessionId:id});if(epoch!==own||state.session.id!==id)return;state.session=result;state.position=0;state.hidden={};save();await visit();api.toast('新的语境句已准备好。');}catch(e){api.toast(e.message,true);}finally{busy=false;render();}
    }
    async function sentenceAi(){if(busy||!readyAi())return;busy=true;render();const own=epoch;
      try{const result=await api.request('contextSentenceAi',{sessionId:state.session.id,sentenceId:current().id});if(epoch!==own)return;state.session=result;save();}catch(e){api.toast(e.message,true);}finally{busy=false;render();}
    }
    async function openWord(surface){if(busy||!state)return;const ticket=++lookup,own=epoch,sentence=current(),id=state.session.id;drawer.classList.add('open');$('contextWordContent').innerHTML='<div class="busy-line"><span class="spinner"></span>正在查看 '+esc(surface)+'…</div><button id="contextCloseWord" class="btn outline full">返回句子</button>';$('contextCloseWord').onclick=closeWord;
      try{const result=await api.request('contextWord',{sessionId:id,sentenceId:sentence.id,surface});if(ticket!==lookup||epoch!==own)return;state.session=result.session;wordView={result,sentenceId:sentence.id,surface,sessionId:id};save();render();renderWord();}catch(e){if(ticket===lookup){$('contextWordContent').innerHTML='<div class="notice error">'+esc(e.message)+'</div><button id="contextCloseWord" class="btn outline full" style="margin-top:15px">返回句子</button>';$('contextCloseWord').onclick=closeWord;}}
    }
    function renderWord(){if(!wordView)return;const r=wordView.result,sentence=state.session.sentences.find(s=>s.id===wordView.sentenceId),contextual=r.source==='ai'||r.source==='local';
      $('contextWordContent').innerHTML='<div class="drawer-top"><div><h2 class="drawer-title">'+esc(r.surface)+'</h2>'+(r.lemma&&normal(r.lemma)!==normal(r.surface)?'<div class="muted tiny" style="margin-top:6px">原形：'+esc(r.lemma)+'</div>':'')+'<div class="ipa">'+esc(api.pos(r.pos||''))+(r.ipa?' · '+esc(r.ipa):'')+'</div></div><button id="contextSpeakWord" class="btn secondary small">听读音</button></div><div class="context-word-note"><p class="context-quoted">'+esc(sentence.text)+'</p>'+(r.meaning?'<h3>'+(contextual?'本句词义':'词典释义')+'</h3><p>'+esc(api.meaning(r.meaning))+'</p>':'<p class="muted" style="margin-top:16px">本机词库暂未收录这个词，可结合本句获取讲解。</p>')+(r.usage?'<h3>'+(contextual?'本句用法':'常用用法')+'</h3><p>'+esc(r.usage)+'</p>':'')+(r.formNote?'<h3>词形说明</h3><p>'+esc(r.formNote)+'</p>':'')+'</div>'+(r.dictionaryCandidates||[]).slice(contextual?0:1).map(w=>'<details class="context-dictionary-item"><summary>'+esc(w.word)+' · '+esc(api.pos(w.pos))+' · 更多释义</summary><div class="meaning-full">'+esc(api.meaning(w.meaning))+'</div></details>').join('')+'<button id="contextWordAi" class="btn outline full" style="margin-top:20px"'+(busy?' disabled':'')+'>'+(r.source==='ai'?'已结合本句讲解':'结合这句话讲解')+'</button><button id="contextCloseWord" class="btn full" style="margin-top:10px">返回句子</button>';
      $('contextSpeakWord').onclick=()=>api.request('speak',{text:r.surface}).catch(e=>api.toast(e.message,true));$('contextCloseWord').onclick=closeWord;$('contextWordAi').onclick=wordAi;
    }
    async function wordAi(){if(busy||!wordView||wordView.result.source==='ai'||!readyAi())return;busy=true;const view=wordView,ticket=lookup,own=epoch;renderWord();
      try{const result=await api.request('contextWordAi',{sessionId:view.sessionId,sentenceId:view.sentenceId,surface:view.surface});if(epoch!==own||ticket!==lookup)return;state.session=result.session;wordView.result=result;save();renderWord();render();}catch(e){api.toast(e.message,true);}finally{busy=false;if(wordView&&ticket===lookup)renderWord();render();}
    }
    async function grade(wordId,value){if(busy)return;busy=true;render();const own=epoch;
      try{const result=await api.request('contextGrade',{sessionId:state.session.id,wordId,grade:value});if(epoch!==own)return;state.session=result.session;save();api.refresh();}catch(e){api.toast(e.message,true);}finally{busy=false;render();}
    }
    async function nextGroup(){if(busy||graded()<state.session.targetWords.length)return;state.complete+=state.session.targetWords.length;state.offset+=state.session.targetWords.length;
      if(state.offset>=state.queue.length){state.queue=[];state.session=null;state.loading=false;save();render();return;}await loadGroup();
    }
    async function restore(){let saved;try{saved=JSON.parse(localStorage.getItem(STORAGE));}catch(e){}if(!saved||!Array.isArray(saved.queue)||!saved.queue.length||!saved.sessionId||saved.offset>=saved.queue.length)return false;
      if(!confirm('继续上次的语境学习？')){localStorage.removeItem(STORAGE);return false;}state=Object.assign({hidden:{},phase:'sentences',position:0,complete:0},saved);epoch++;state.loading=true;show();
      try{state.session=await api.request('contextGet',{sessionId:saved.sessionId});state.position=Math.max(0,Math.min(Number(state.position)||0,state.session.sentences.length-1));state.loading=false;save();await visit();render();return true;}catch(e){reset();api.toast(e.message,true);return false;}
    }
    function reset(){epoch++;lookup++;state=null;busy=false;open=false;wordView=null;overlay.classList.remove('open');drawer.classList.remove('open');save();stop();}
    return {start,restore,save,reset,back(){if(drawer.classList.contains('open')){closeWord();return true;}if(open){close();return true;}return false;}};
  }};
})();
