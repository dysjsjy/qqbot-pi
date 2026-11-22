/**
 * [INPUT]: 使用当前群的画像管理 API、修订号及成员版本。
 * [OUTPUT]: 提供双开关、停用时撤销授权、证据检视、人工纠偏与退出建档。
 * [POS]: 实验画像独立工作区；未打开标签页不请求画像。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useRef,useState} from 'react';
import {api,ApiError} from '../api';
import type {MemberProfileDetail,MemberProfilePage,ProfileJob,ProfilePolicy,ProfilePolicyView,ProfileState} from '../types';

const PAGE_SIZE=25;
const statuses:Record<ProfileState,string>={ACTIVE:'已建档',DORMANT:'休眠',LOCKED:'人工锁定',OPTED_OUT:'已退出',PENDING:'待建档'};
const jobTypes={EXTRACT:'增量抽取',REFLECT:'卡片重铸',REPLAY:'历史重放'};
const jobStates:Record<string,string>={QUEUED:'排队',RUNNING:'执行中',READY:'完成',FAILED:'失败',CANCELLED:'取消'};
const observationStates:Record<string,string>={CANDIDATE:'待印证',CONFIRMED:'已确认',SUPERSEDED:'已替换',EXPIRED:'已到期',DELETED:'已删除'};
const categories:Record<string,string>={IDENTITY_ROLE:'群内角色',INTEREST_TOPIC:'兴趣话题',EXPERTISE:'熟悉领域',SPEAKING_STYLE:'表达方式',SOCIAL_PREF:'互动偏好',RUNNING_GAG:'群内用语'};
const bounds=[
 ['maxProfiles','活跃画像上限',5,50],['minMessages30d','最少有效发言',5,200],['minActiveDays30d','最少活跃天数',1,30],
 ['coldStartLookbackDays','冷启动回溯天数',7,365],['updateTriggerMessages','增量发言触发数',5,100],
 ['updateCooldownHours','增量冷却小时',1,168],['dailyModelBudgetCalls','每日模型调用上限',1,100],['profileTtlDays','基础观测有效天数',14,180],
] as const;

function errorMessage(failure:unknown){
 if(failure instanceof ApiError&&failure.status===409)return '设置或作业状态已变化，请刷新后重试。';
 if(failure instanceof ApiError&&failure.status===429)return '本群今日画像调用额度已用完。';
 if(failure instanceof ApiError&&failure.status===400)return '请检查参数范围，并移除画像中的隐私、指令和不当内容。';
 return failure instanceof Error?failure.message:'画像操作失败。';
}

export default function MemberProfilesPanel({groupId,busy}:{groupId:string;busy:boolean}){
 const root=`/groups/${groupId}/experimental/member-profiles`;
 const [view,setView]=useState<ProfilePolicyView|null>(null),[draft,setDraft]=useState<ProfilePolicy|null>(null);
 const [query,setQuery]=useState(''),[filters,setFilters]=useState({q:'',state:''}),[offset,setOffset]=useState(0);
 const [page,setPage]=useState<MemberProfilePage|null>(null),[jobs,setJobs]=useState<ProfileJob[]>([]),[selected,setSelected]=useState<string|null>(null);
 const [detail,setDetail]=useState<MemberProfileDetail|null>(null),[edit,setEdit]=useState<{profileCard:string;adminNote:string;lockedByAdmin:boolean;optedOut:boolean}|null>(null);
 const [revision,setRevision]=useState(0),[detailRevision,setDetailRevision]=useState(0),[policyRevision,setPolicyRevision]=useState(0),[working,setWorking]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const detailHeading=useRef<HTMLHeadingElement>(null),trigger=useRef<HTMLButtonElement|null>(null);
 const disabled=busy||working,canRun=Boolean(view?.globalEnabled&&view.policy.enabled);
 const acceptPolicy=(value:ProfilePolicyView)=>{setView(value);setDraft(value.policy);};
 const acceptDetail=(value:MemberProfileDetail)=>{setDetail(value);const {profileCard,adminNote,lockedByAdmin,optedOut}=value.member;setEdit({profileCard,adminNote,lockedByAdmin,optedOut});};

 useEffect(()=>{
  const controller=new AbortController();
  api<ProfilePolicyView>(`${root}/policy`,{signal:controller.signal}).then(value=>{if(!controller.signal.aborted)acceptPolicy(value);})
   .catch(failure=>{if(!controller.signal.aborted)setError(errorMessage(failure));});
  return()=>controller.abort();
 },[root,policyRevision]);

 useEffect(()=>{
  const controller=new AbortController();
  const params=new URLSearchParams({...filters,offset:String(offset),limit:String(PAGE_SIZE)});
  api<MemberProfilePage>(`${root}/list?${params}`,{signal:controller.signal}).then(value=>{if(!controller.signal.aborted){setPage(value);if(offset>0&&offset>=value.total)setOffset(Math.max(0,offset-PAGE_SIZE));}})
   .catch(failure=>{if(!controller.signal.aborted)setError(errorMessage(failure));});
  return()=>controller.abort();
 },[root,filters,offset,revision]);

 useEffect(()=>{
  const controller=new AbortController();
  api<ProfileJob[]>(`${root}/jobs`,{signal:controller.signal}).then(value=>{if(!controller.signal.aborted)setJobs(value);})
   .catch(failure=>{if(!controller.signal.aborted)setError(errorMessage(failure));});
  return()=>controller.abort();
 },[root,revision]);

 useEffect(()=>{
  if(!selected)return;
  const controller=new AbortController();
  api<MemberProfileDetail>(`${root}/detail?${new URLSearchParams({senderId:selected})}`,{signal:controller.signal}).then(value=>{if(!controller.signal.aborted)acceptDetail(value);})
   .catch(failure=>{if(!controller.signal.aborted)setError(errorMessage(failure));});
  return()=>controller.abort();
 },[root,selected,detailRevision]);

 useEffect(()=>{if(selected)detailHeading.current?.focus();},[selected]);

 async function act(work:()=>Promise<unknown>,message:string,refreshDetail=false){
  setWorking(true);setError('');setNotice('');
  try{await work();setRevision(value=>value+1);if(refreshDetail)setDetailRevision(value=>value+1);setNotice(message);}
  catch(failure){setError(errorMessage(failure));}
  finally{setWorking(false);}
 }
 const update=(path:string,method:string,value?:unknown)=>api(`${root}${path}`,{method,body:value===undefined?undefined:JSON.stringify(value)});
 const savePolicy=(policy:ProfilePolicy,notice:string)=>act(async()=>{
  acceptPolicy(await api<ProfilePolicyView>(`${root}/policy`,{method:'PUT',body:JSON.stringify(policy)}));
 },notice);
 const refresh=(mode:'INCREMENTAL'|'REPLAY',senderId?:string)=>{
  if(mode==='REPLAY'&&!window.confirm('重新从留存原文计算画像？保留人工备注和退出设置。'))return;
  void act(()=>update('/refresh','POST',{mode,senderId}),'已请求后台建模。请在下方查看作业。');
 };

 return <div className="memory-workspace profile-workspace">
  <section className="panel pad">
   <h2>活跃成员画像</h2><p>实验性功能 · 默认全关 · 仅限群聊</p>
   <p className="muted">先生成并人工核验，再决定是否用于聊天。成员可真实 @ 机器人并发送 /profile off 即时退出。</p>
   {view&&!view.globalEnabled&&<p className="banner" role="status">部署未开启总开关。设置 <code>BOT_EXPERIMENTAL_MEMBER_PROFILE_ENABLED=true</code> 并重启后，才能启用建模和注入。</p>}
   {error&&<p className="banner error" role="alert">{error} <button type="button" className="text-button" disabled={disabled} onClick={()=>{setError('');setPolicyRevision(value=>value+1);setRevision(value=>value+1);setDetailRevision(value=>value+1);}}>刷新数据</button></p>}
   {notice&&<p className="banner success" role="status">{notice}</p>}
   {draft&&<form onSubmit={event=>{event.preventDefault();if(!disabled&&view?.globalEnabled)void savePolicy(draft,'画像策略已保存。');}}>
    <fieldset disabled={disabled||!view?.globalEnabled}>
     <legend>群画像策略</legend>
     <label className="check"><input type="checkbox" checked={draft.enabled} onChange={event=>setDraft({...draft,enabled:event.target.checked,injectEnabled:event.target.checked&&draft.injectEnabled})}/>启用后台活跃成员建模</label>
     <label className="check"><input type="checkbox" disabled={!draft.enabled} checked={draft.injectEnabled} onChange={event=>setDraft({...draft,injectEnabled:event.target.checked})}/>启用聊天熟人感注入</label>
     <div className="memory-fields">{bounds.map(([key,label,min,max])=><label key={key}>{label}<input type="number" required min={min} max={max} value={Number.isNaN(draft[key])?'':draft[key]} onChange={event=>setDraft({...draft,[key]:event.target.valueAsNumber})}/></label>)}
      <label>聊天风格<select value={draft.styleHintMode} onChange={event=>setDraft({...draft,styleHintMode:event.target.value as ProfilePolicy['styleHintMode']})}><option value="SUBTLE">含蓄</option><option value="WARM">亲切</option></select></label>
     </div>
     <button className="primary">保存画像策略</button>
    </fieldset>
   </form>}
   {view&&!view.globalEnabled&&(view.policy.enabled||view.policy.injectEnabled)&&<button type="button" className="secondary" disabled={disabled} onClick={()=>void savePolicy({...view.policy,enabled:false,injectEnabled:false},'群画像授权已撤销。')}>撤销已保存的群画像授权</button>}
  </section>

  <section className="panel pad">
   <h2>群成员档案</h2>
   <div className="action-row"><button className="secondary" disabled={disabled||!canRun} onClick={()=>refresh('INCREMENTAL')}>全群增量刷新</button><button className="secondary" disabled={disabled||!canRun} onClick={()=>refresh('REPLAY')}>全群历史重放</button></div>
   <form className="profile-search" onSubmit={event=>{event.preventDefault();setFilters({...filters,q:query});setOffset(0);}}>
    <label>成员搜索<input value={query} onChange={event=>setQuery(event.target.value)} placeholder="昵称或成员 ID" maxLength={200}/></label>
    <label>状态<select value={filters.state} onChange={event=>{setFilters({...filters,state:event.target.value});setOffset(0);}}><option value="">全部</option>{Object.entries(statuses).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></label>
    <button className="secondary">搜索</button>
   </form>
   {page?.members.length?<ul className="member-list" aria-label="群画像成员">{page.members.map(member=><li key={member.id}>
    <button type="button" className="text-button" disabled={disabled} aria-expanded={selected===member.senderId} onClick={event=>{if(selected===member.senderId){detailHeading.current?.focus();return;}trigger.current=event.currentTarget;setDetail(null);setEdit(null);setSelected(member.senderId);}}><strong>{member.displayName}</strong><small>{member.senderId}</small></button>
    <span>{member.confirmedCount} 项确认</span><span className="badge">{statuses[member.state]}</span>
   </li>)}</ul>:<p className="muted">{page?'没有符合条件的成员。':'正在读取成员…'}</p>}
   {page&&<div className="action-row"><button className="secondary" disabled={offset===0} onClick={()=>setOffset(offset-PAGE_SIZE)}>上一页</button><span>共 {page.total} 人</span><button className="secondary" disabled={offset+PAGE_SIZE>=page.total} onClick={()=>setOffset(offset+PAGE_SIZE)}>下一页</button></div>}
  </section>

  {selected&&<section className="panel pad profile-detail">
   <div className="action-row"><h2 ref={detailHeading} tabIndex={-1}>画像详情{detail?`：${detail.member.displayName}`:''}</h2>
    <button className="secondary" disabled={disabled} onClick={()=>{setSelected(null);setDetail(null);setEdit(null);trigger.current?.focus();}}>收起详情</button></div>
   {detail&&edit?<>
    <dl className="memory-metrics">{[
     ['近 30 天有效发言',detail.member.stats.messages30d??0],['近 90 天有效发言',detail.member.stats.messages90d??0],
     ['活跃天数',detail.member.stats.activeDays30d??0],['平均发言字符',Math.round(detail.member.stats.averageCharacters??0)],
     ['短句连发比例',`${Math.round((detail.member.stats.shortBurstRatio??0)*100)}%`],['群内昵称',detail.member.aliases.join('、')||'无'],
     ['发言时段（凌晨 / 上午 / 下午 / 晚间）',detail.member.stats.periodCounts?.join(' / ')??'暂无'],
    ].map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
    <h3>常互动成员</h3>
    {detail.interactions.length?<ul>{detail.interactions.map(item=><li key={item.senderId}>{item.senderId} · 回复 {item.replyCount} · 提及 {item.mentionCount}</li>)}</ul>:<p className="muted">暂无可核验互动。</p>}
    <form onSubmit={event=>{event.preventDefault();if(edit.optedOut&&!detail.member.optedOut&&!window.confirm('退出会立即清空该成员画像。确认继续？'))return;
     const {senderId,version}=detail.member;
     const payload=edit.optedOut?{senderId,version,optedOut:true}:{...edit,senderId,version};
     void act(()=>update('/member','PUT',payload),'画像已保存。',true);}}>
     <label>紧凑画像卡<textarea rows={3} maxLength={220} value={edit.profileCard} disabled={edit.optedOut||disabled} onChange={event=>setEdit({...edit,profileCard:event.target.value})}/></label>
     <label>人工备注<textarea rows={2} maxLength={220} value={edit.adminNote} disabled={edit.optedOut||disabled} onChange={event=>setEdit({...edit,adminNote:event.target.value})}/></label>
     <label className="check"><input type="checkbox" checked={edit.lockedByAdmin&&!edit.optedOut} disabled={edit.optedOut||disabled} onChange={event=>setEdit({...edit,lockedByAdmin:event.target.checked})}/>锁定防改写</label>
     <label className="check"><input type="checkbox" checked={edit.optedOut} disabled={disabled} onChange={event=>setEdit({...edit,optedOut:event.target.checked})}/>退出建档并清空画像</label>
     <div className="action-row"><button className="primary" disabled={disabled}>保存成员设置</button>
      <button type="button" className="danger" disabled={disabled} onClick={()=>{if(window.confirm('清空此成员的画像和观测？'))void act(()=>update(`/member?${new URLSearchParams({senderId:selected})}`,'DELETE'),'成员画像已清空。',true);}}>清空画像</button>
      <button type="button" className="secondary" disabled={disabled||!canRun||detail.member.lockedByAdmin||detail.member.optedOut} onClick={()=>refresh('INCREMENTAL',selected)}>增量刷新</button>
      <button type="button" className="secondary" disabled={disabled||!canRun||detail.member.lockedByAdmin||detail.member.optedOut} onClick={()=>refresh('REPLAY',selected)}>重放重算</button>
     </div>
    </form>
    <h3>证据观测</h3>
    {detail.observations.length?detail.observations.map(observation=><article className="profile-observation" key={observation.id}>
     <p><strong>{categories[observation.category]??observation.category}</strong> · {observationStates[observation.state]??observation.state} · {observation.evidenceCount} 条 / {observation.distinctDays} 天</p>
     {observation.statement&&<p>{observation.statement}</p>}
     {observation.sources.length>0&&<details><summary>核对原文片段（{observation.sources.length} 条）</summary>{observation.sources.map(source=><blockquote key={source.id}>{source.excerpt||'片段不可显示。'}<small>{new Date(source.occurredAt).toLocaleString('zh-CN')} · {source.id}</small></blockquote>)}</details>}
     {observation.sourceExpiredCount>0&&<p className="muted">已解绑 {observation.sourceExpiredCount} 条到期来源。</p>}
     {observation.state!=='DELETED'&&<button className="text-button" disabled={disabled} onClick={()=>{if(window.confirm('删除此观测并停止使用原卡片？'))void act(()=>update(`/observations/${observation.id}`,'DELETE'),'观测已删除。',true);}}>删除观测</button>}
    </article>):<p className="muted">暂无观测。</p>}
   </>:<p role="status">正在读取画像详情…</p>}
  </section>}

  <section className="panel pad">
   <div className="action-row"><h2>建模作业</h2><button className="secondary" disabled={disabled} onClick={()=>setRevision(value=>value+1)}>刷新作业</button></div>
   {jobs.length?<ul className="profile-jobs" aria-label="建模作业记录">{jobs.map(job=><li key={job.id}>
    <strong>{jobTypes[job.job_type]} · {jobStates[job.state]??job.state}</strong><span>{job.sender_id} · 第 {job.attempt} 次 · {job.source_count} 条来源</span>
    <span>{new Date(job.created_at).toLocaleString('zh-CN')}{job.error_code?` · ${job.error_code}`:''}</span>
    {job.state==='FAILED'&&job.attempt<3&&<button className="text-button" disabled={disabled||!canRun} onClick={()=>void act(()=>update(`/jobs/${job.id}/retry`,'POST'),'已安排重试。')}>重试作业</button>}
   </li>)}</ul>:<p className="muted">暂无建模作业。</p>}
  </section>
 </div>;
}
