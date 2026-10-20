import {installBridge} from './ui_bridge_fixture.mjs';
import fs from 'node:fs';
const {chromium}=await import(process.env.PLAYWRIGHT_MODULE||'playwright');
const out=process.env.UI_QA_OUTPUT||'/tmp/wordweave_context_ui';fs.mkdirSync(out,{recursive:true});
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||undefined,headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--in-process-gpu','--no-zygote','--single-process']});
const page=await browser.newPage({viewport:{width:412,height:892},isMobile:true,hasTouch:true});
const errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
function installContextBridge(){
  const original=window.Android.request;let sessions=JSON.parse(sessionStorage.getItem('contextMockSessions')||'{}'),nextId=Object.keys(sessions).length+1;
  window.__contextFail=false;window.__contextFailWord=false;
  const targets=[{id:1,word:'abandon',meaning:'v. 放弃；抛弃；遗弃',pos:'v.',forms:'abandoned',state:{stage:0,reviews:0,lessonAt:0}},{id:3,word:'concrete',meaning:'adj. 具体的；实在的；n. 混凝土',pos:'adj. / n.',state:{stage:0,reviews:5,lessonAt:1}}];
  const sentences=()=>[{id:'s1',text:'The team will abandon the risky plan and choose a concrete solution.',scene:'工作',translation:'团队将放弃冒险的计划，选择一个具体的解决办法。',explanation:'两个动作说明团队将如何处理这个计划。',structure:'The team是主语，will统领两个由and连接的谓语。',readingTip:'两个动作可分成两个意群。',targetIds:[1,3],wordNotes:[{wordId:1,word:'abandon',pos:'v.',meaning:'放弃',usage:'abandon the plan表示放弃计划。'},{wordId:3,word:'concrete',pos:'adj.',meaning:'具体的',usage:'修饰solution。'}]},{id:'s2',text:'Students abandon vague ideas when they need concrete examples in class.',scene:'校园',translation:'在课堂上需要具体例子时，学生们会放弃模糊的想法。',explanation:'when从句说明时间或情形。',structure:'Students是主语，abandon为谓语，when引导从句。',readingTip:'可在when从句前划分意群。',targetIds:[1,3],wordNotes:[]}];
  window.Android.request=(id,action,raw)=>{
    if(!action.startsWith('context')){original(id,action,raw);return;}
    const p=JSON.parse(raw),call={action,p,ok:true};window.__calls.push(call);let result,error;
    const session=p.sessionId?sessions[p.sessionId]:null;
    if(action==='contextCreate'){if(p.wordIds.join(',')!=='1,3')throw Error('default context queue did not use recognition targets');const id=nextId++;result={id,createdAt:Date.now(),revision:1,targetWords:targets,source:'local',sentences:sentences().map(s=>({...s,structure:'',wordNotes:[]})),viewed:{},hints:{},revealed:{},grades:{},unknownWords:[],wordExplanations:{}};sessions[id]=result;}
    else if(!session)error='课程不存在';
    else if(action==='contextGet')result=session;
    else if(action==='contextVisit'){session.viewed[p.sentenceId]=Date.now();result=session;}
    else if(action==='contextReveal'){session.revealed[p.sentenceId]=true;session.hints['1']=true;session.hints['3']=true;result=session;}
    else if(action==='contextWord'){
      if(p.surface==='abandon')session.hints['1']=true;if(p.surface==='concrete')session.hints['3']=true;
      if(!session.unknownWords.some(w=>w.surface===p.surface&&w.sentenceId===p.sentenceId))session.unknownWords.push({surface:p.surface,sentenceId:p.sentenceId});
      const key=p.sentenceId+'|'+p.surface;
      result={surface:p.surface,dictionaryCandidates:[],...session.wordExplanations[key],session};
      if(!result.meaning&&p.surface==='abandon')Object.assign(result,{lemma:'abandon',pos:'v.',meaning:'放弃；抛弃',usage:'常与计划或活动搭配。',source:'dictionary'});
      if(!result.meaning&&p.surface==='will')Object.assign(result,{lemma:'will',pos:'助动词',meaning:'将会；愿意',usage:'后接动词原形。',source:'dictionary'});
    }
    else if(action==='contextWordAi'){
      if(window.__contextFailWord)error='本次讲解失败，可以重试。';else{
        const note={surface:p.surface,lemma:'team',pos:'n.',meaning:'团队',usage:'在本句作主语的一部分。',formNote:'本句使用单数形式。',source:'ai'};session.wordExplanations[p.sentenceId+'|'+p.surface]=note;result={...note,dictionaryCandidates:[],session};
      }
    }
    else if(action==='contextSentenceAi'){session.sentences=sentences();session.revealed[p.sentenceId]=true;session.hints['1']=true;session.hints['3']=true;result=session;}
    else if(action==='contextGenerate'){
      if(window.__contextFail)error='语境生成失败，原来的句子仍可继续学习。';else{session.sentences=sentences();session.source='ai';session.revision++;session.viewed={};session.revealed={};result=session;}
    }
    else if(action==='contextGrade'){
      if(!session.viewed.s1||!session.viewed.s2)throw Error('grade before all contexts were viewed');
      if(!(String(p.wordId) in session.grades))session.grades[p.wordId]=session.hints[p.wordId]?Math.min(1,p.grade):p.grade;result={session,grade:session.grades[p.wordId]};
    }else error='Unknown context action '+action;
    sessionStorage.setItem('contextMockSessions',JSON.stringify(sessions));sessionStorage.setItem('mockCalls',JSON.stringify(window.__calls));
    setTimeout(()=>{if(error){call.ok=false;window.onNativeResult(id,false,JSON.stringify({message:error}));}else window.onNativeResult(id,true,JSON.stringify(result));},action==='contextGenerate'?100:15);
  };
}
await page.addInitScript({content:'('+installBridge.toString()+')();('+installContextBridge.toString()+')();'});
await page.goto(new URL('../app/src/main/assets/index.html',import.meta.url).href);
await page.waitForFunction(()=>!document.getElementById('startToday').disabled);
await page.locator('#startToday').click();await page.locator('#contextSentenceText').waitFor();
if(await page.locator('#study').isVisible())throw Error('default learning still entered word MCQs');
if(await page.locator('#contextSentenceExplanation').count())throw Error('sentence translation revealed automatically');
if(await page.locator('[data-context-token]').count()<10)throw Error('not every English word is selectable');
if(!await page.locator('.context-targets').textContent().then(t=>t.includes('abandon')&&t.includes('concrete')))throw Error('multi-word group missing');
await page.locator('#contextSpeakSentence').click();await page.waitForTimeout(40);
let calls=await page.evaluate(()=>__calls);const utterance=calls.filter(c=>c.action==='speak').at(-1).p.text;if(utterance!=='The team will abandon the risky plan and choose a concrete solution.')throw Error('sentence speech contains wrong content');
await page.locator('[data-context-token="will"]').click();await page.locator('#contextWordContent').getByText('将会；愿意',{exact:true}).waitFor();await page.locator('#contextCloseWord').click();
await page.locator('[data-context-token="team"]').click();await page.locator('#contextWordAi').waitFor();await page.evaluate(()=>window.__contextFailWord=true);await page.locator('#contextWordAi').click();await page.waitForTimeout(60);if(!await page.locator('#contextWordOverlay').isVisible())throw Error('failed AI lookup closed word selection');
await page.evaluate(()=>window.__contextFailWord=false);await page.locator('#contextWordAi').click();await page.locator('#contextWordContent').getByText('团队',{exact:true}).waitFor();await page.locator('#contextCloseWord').click();
const beforeCache=await page.evaluate(()=>__calls.filter(c=>c.action==='contextWordAi').length);await page.locator('[data-context-token="team"]').click();await page.locator('#contextWordContent').getByText('团队',{exact:true}).waitFor();if(await page.evaluate(()=>__calls.filter(c=>c.action==='contextWordAi').length)!==beforeCache)throw Error('cached explanation made another AI call');await page.locator('#contextCloseWord').click();
await page.locator('[data-context-token="abandon"]').click();await page.locator('#contextSpeakWord').click();await page.waitForTimeout(30);calls=await page.evaluate(()=>__calls);if(calls.filter(c=>c.action==='speak').at(-1).p.text!=='abandon')throw Error('selected-word speech wrong');await page.locator('#contextCloseWord').click();
await page.locator('#contextNextSentence').click();await page.waitForFunction(()=>document.getElementById('contextSentenceText').textContent.includes('Students'));
await page.reload();await page.locator('#contextSentenceText').waitFor();if(!await page.locator('#contextSentenceText').textContent().then(t=>t.includes('Students')))throw Error('resume lost sentence position');
await page.locator('#contextNextSentence').click();await page.locator('[data-context-review="1"]').waitFor();
if(!await page.locator('.context-unknown-list').textContent().then(t=>t.includes('team')&&t.includes('abandon')))throw Error('selected unfamiliar words absent from review');
if(!await page.locator('[data-context-word="1"][data-context-grade="3"]').isDisabled())throw Error('viewed word explanation counted as independent recall');
if(await page.locator('[data-context-word="3"][data-context-grade="3"]').isDisabled())throw Error('unexposed target was capped by another word lookup');
await page.locator('[data-context-word="1"][data-context-grade="1"]').click();await page.waitForTimeout(40);await page.locator('[data-context-word="3"][data-context-grade="3"]').click();await page.waitForTimeout(40);
await page.screenshot({path:out+'/per_word_review.png',fullPage:true});
const recorded=await page.evaluate(()=>Object.values(JSON.parse(sessionStorage.getItem('contextMockSessions')))[0].grades);if(recorded['1']!==1||recorded['3']!==3)throw Error('per-word context grades incorrect');
await page.reload();await page.locator('#contextNextGroup').waitFor();if(await page.locator('[data-context-grade]').count())throw Error('reload permits duplicate grades');
await page.locator('#contextNextGroup').click();await page.locator('#contextDone').click();
await page.locator('#startToday').click();await page.locator('#contextGenerate').waitFor();const text=await page.locator('#contextSentenceText').textContent();await page.evaluate(()=>window.__contextFail=true);await page.locator('#contextGenerate').click();await page.waitForTimeout(180);if(await page.locator('#contextSentenceText').textContent()!==text)throw Error('AI failure discarded local materials');
await page.evaluate(()=>window.__contextFail=false);await page.locator('#contextGenerate').click();await page.waitForTimeout(180);if(await page.locator('#contextSentenceExplanation').count())throw Error('AI translations leaked before request');
await page.locator('#contextRevealSentence').click();await page.locator('#contextSentenceExplanation').waitFor();if(!await page.locator('#contextSentenceExplanation').textContent().then(t=>t.includes('具体的解决办法')&&t.includes('两个由and连接的谓语')&&t.includes('意群')))throw Error('whole-sentence meaning, structure, or reading explanation absent');
await page.screenshot({path:out+'/sentence_explanation.png',fullPage:true});
await page.locator('#contextSpeakSentence').click();await page.waitForTimeout(30);calls=await page.evaluate(()=>__calls);if(calls.filter(c=>c.action==='speak').at(-1).p.text.includes('团队'))throw Error('speech read the translation instead of English');
await page.locator('#contextNextSentence').click();await page.waitForTimeout(40);await page.locator('#contextNextSentence').click();
if(!await page.locator('[data-context-word="3"][data-context-grade="3"]').isDisabled())throw Error('sentence translation did not cap exposed targets');
await page.locator('#contextBackToSentences').click();await page.locator('#contextExit').click();await page.locator('#startToday').click();await page.locator('#contextSentenceText').waitFor();
if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error('context UI overflows horizontally');
await page.evaluate(()=>window.nativeBack());if(await page.locator('#contextStudy').isVisible())throw Error('native back did not close context study');
if(errors.length)throw Error(errors.join(';'));
fs.writeFileSync(out+'/result.json',JSON.stringify({passed:true,browserErrors:errors,bridgeCalls:await page.evaluate(()=>__calls.length),checks:['sentence-first default','multi-word group','any-word selection including basic and out-of-bank words','sentence and word TTS','AI failure and retry','cache reuse','resume position and hints','independent per-word grades','duplicate-grade protection','AI context generation','hidden sentence translation','structure and reading tips','sentence hint caps','native back and mobile width']},null,2));
console.log('PASS: context-first default, selectable words, TTS, cached contextual explanations, AI retries, persisted progress, per-word evidence, sentence explanations, and mobile controls.');
await browser.close();
