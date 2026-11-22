/**
 * [INPUT]: 依赖统一 API、留存概览、记忆设置、用量和上下文组件。
 * [OUTPUT]: 提供归档工作区和仅群聊可见的实验画像标签页。
 * [POS]: 同会话保留标签页草稿；归档列表仅接受当前页请求。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useCallback,useEffect,useRef,useState} from 'react';
import {Search,Trash2,RefreshCw} from 'lucide-react';
import {api} from './api';
import {MarkdownEditor} from './MemorySettings';
import {MemoryContextPanel,MemoryPolicyPanel,MemoryUsagePanel} from './MemoryTelemetry';
import MemberProfilesPanel from './components/MemberProfilesPanel';
import type {Actions,Group,MemoryRetention} from './types';

type Props=Actions&{group:Pick<Group,'id'|'name'|'chat_type'>;ragEnabled:boolean};
type Document={id:string;kind:string;state:string;source_count:number;attempt:number;error_code?:string;created_at:string;content?:string;sources?:{message_id:string}[];storage?:'MARKDOWN'|'SEGMENT_V1';open?:boolean;nextCursor?:string|null;totalSources?:number};
type ArchivePolicy={counts:{documents:number;pending:number;failed:number};mode:string;uncompressed:number;rawArchive?:{status:string;pendingMessages:number;archivedMessages:number;gcPending:number;gcFailed:number;errorCode?:string}};
type ArchivePage={offset:number;policy:ArchivePolicy;documents:Document[]};
type MemoryTab='archive'|'profiles';
const pageSize=50;
const states:Record<string,string>={QUEUED:'等待处理',RUNNING:'处理中',READY:'已保存',FAILED:'处理失败',CANCELLED:'已取消'};

export default function Memory(props:Props){
 const [tab,setTab]=useState<MemoryTab>('archive'),[profilesOpened,setProfilesOpened]=useState(false);
 const archive=useRef<HTMLButtonElement>(null),profiles=useRef<HTMLButtonElement>(null);
 const selectTab=(next:MemoryTab)=>{if(next==='profiles')setProfilesOpened(true);setTab(next);};
 if(props.group.chat_type==='PRIVATE')return <MemoryWorkspace {...props}/>;
 return <div className="memory-workspace">
  <div className="memory-tabs" role="tablist" aria-label="群记忆工作区" onKeyDown={event=>{
   if(!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;
   event.preventDefault();const chooseArchive=event.key==='Home'||(event.key!=='End'&&tab==='profiles');
   selectTab(chooseArchive?'archive':'profiles');(chooseArchive?archive:profiles).current?.focus();
  }}>
   <button ref={archive} id="memory-archive-tab" role="tab" type="button" aria-controls="memory-archive-panel" aria-selected={tab==='archive'} tabIndex={tab==='archive'?0:-1} onClick={()=>selectTab('archive')}>记忆与归档</button>
   <button ref={profiles} id="memory-profiles-tab" role="tab" type="button" aria-controls="memory-profiles-panel" aria-selected={tab==='profiles'} tabIndex={tab==='profiles'?0:-1} onClick={()=>selectTab('profiles')}>成员画像（实验）</button>
  </div>
  <div id="memory-archive-panel" role="tabpanel" aria-labelledby="memory-archive-tab" tabIndex={0} hidden={tab!=='archive'}><MemoryWorkspace {...props}/></div>
  <div id="memory-profiles-panel" role="tabpanel" aria-labelledby="memory-profiles-tab" tabIndex={0} hidden={tab!=='profiles'}>{profilesOpened&&<MemberProfilesPanel groupId={props.group.id} busy={props.busy}/>}</div>
 </div>;
}

function MemoryWorkspace({group,ragEnabled,busy}:Props){
 const[query,setQuery]=useState(''),[results,setResults]=useState<any[]>([]),[revision,setRevision]=useState(0),[detail,setDetail]=useState<Document|null>(null),[error,setError]=useState(''),[notice,setNotice]=useState(''),[searched,setSearched]=useState(false),[working,setWorking]=useState(false);
 const scope=useRef(0),detailId=useRef(0),retentionId=useRef(0);
 const [retention,setRetention]=useState<MemoryRetention|null>(null),[retentionError,setRetentionError]=useState(false);
 const [archivePage,setArchivePage]=useState<ArchivePage|null>(null),[archiveError,setArchiveError]=useState('');
 const targetOffset=useRef(0),archiveRequest=useRef<{controller:AbortController;done:Promise<void>}|null>(null);
 const loadRetention=async()=>{
  const current=scope.current,request=++retentionId.current;
  try{const value=await api<MemoryRetention>(`/groups/${group.id}/memory/retention`);if(scope.current===current&&retentionId.current===request){const valid=typeof value?.hotWindowDays==='number';setRetention(valid?value:null);setRetentionError(!valid);}}
  catch{if(scope.current===current&&retentionId.current===request){setRetention(null);setRetentionError(true);}}
 };
 const load=useCallback((offset=targetOffset.current,reset=false)=>{
  const replacing=reset||offset!==targetOffset.current;
  if(!replacing&&archiveRequest.current)return archiveRequest.current.done;
  if(replacing){archiveRequest.current?.controller.abort();targetOffset.current=offset;setArchivePage(null);}
  const current=scope.current,controller=new AbortController(),{signal}=controller;
  const isCurrent=()=>scope.current===current&&!signal.aborted;
  setArchiveError('');
  const done=Promise.all([
   api<ArchivePolicy>(`/groups/${group.id}/memory/policy`,{signal}),
   api<Document[]>(`/groups/${group.id}/memory/documents?limit=${pageSize}&offset=${offset}`,{signal}),
  ]).then(([policy,documents])=>{if(isCurrent())setArchivePage({offset,policy,documents});})
   .catch(failure=>{if(isCurrent()){setArchivePage(null);setArchiveError(failure instanceof Error?failure.message:'文档加载失败');}controller.abort();})
   .finally(()=>{if(archiveRequest.current?.controller===controller)archiveRequest.current=null;});
  archiveRequest.current={controller,done};
  return done;
 },[group.id]);
 const act:Actions['act']=async(work,success='操作已完成')=>{
  const current=scope.current;setWorking(true);setError('');setNotice('');
  try{await work();if(scope.current===current)setNotice(success);}
  catch(failure){if(scope.current===current)setError(failure instanceof Error?failure.message:'操作失败');}
  finally{if(scope.current===current)setWorking(false);}
 };
 const viewDocument=(id:string)=>{
  const current=scope.current,request=++detailId.current;
  return act(async()=>{const value=await api<Document>(`/groups/${group.id}/memory/documents/${id}`);if(scope.current===current&&detailId.current===request)setDetail(value);},'');
 };
 const moreDocument=()=>{
  if(!detail?.nextCursor)return;const previous=detail,current=scope.current,request=++detailId.current;
  return act(async()=>{try{const value=await api<Document>(`/groups/${group.id}/memory/documents/${previous.id}?cursor=${encodeURIComponent(previous.nextCursor!)}`);if(scope.current===current&&detailId.current===request)setDetail({...value,content:(previous.content??'')+'\n'+(value.content??''),sources:[...(previous.sources??[]),...(value.sources??[])]});}catch(failure){if(failure instanceof Error&&failure.message.includes('RAW_PAGE_EXPIRED'))throw new Error('这批记录已过期或发生变化，请收起文档后重新打开。');throw failure;}},'');
 };
 useEffect(()=>{
  scope.current++;setQuery('');setResults([]);setDetail(null);setSearched(false);setError('');setNotice('');setWorking(false);
  setRetention(null);setRetentionError(false);targetOffset.current=0;setArchivePage(null);void loadRetention();
  return()=>{scope.current++;detailId.current++;retentionId.current++;};
 },[group.id]);
 useEffect(()=>{
  const refresh=()=>void load();
  void refresh();const timer=setInterval(refresh,5000);
  return()=>{archiveRequest.current?.controller.abort();archiveRequest.current=null;clearInterval(timer);};
 },[load]);
 const disabled=busy||working;
 const policy=archivePage?.policy,documents=archivePage?.documents??[];
 const documentOffset=archivePage?.offset??0;
 const canGoBack=archivePage!==null&&documentOffset>0;
 const canGoForward=archivePage!==null&&documentOffset+pageSize<archivePage.policy.counts.documents;
 const changePage=(offset:number)=>{if(archivePage?.offset===targetOffset.current)void load(offset);};
 return <div className="memory-workspace">
  {error&&<p role="alert" className="banner error">{error}</p>}{notice&&<p role="status" className="banner success">{notice}</p>}
  <section className="panel pad" aria-label="数据留存与热冷状态">
   <h2>数据留存</h2>
   {retention?<><p>热工作窗 {retention.hotWindowDays} 天 · 冷留存{retention.archiveRetentionDays===0?'永久':` ${retention.archiveRetentionDays} 天`}</p>
    <div className="mini-stats"><span>已留存 <b>{retention.totalMessages}</b></span><span>热窗内 <b>{retention.hotMessages}</b></span><span>归档段 <b>{retention.rawSegments}</b></span><span>分段文件 <b>{(retention.rawDiskBytes/1048576).toFixed(2)} MiB</b></span></div>
    <p className="muted">最早记录：{retention.earliestOccurredAt?new Intl.DateTimeFormat('zh-CN',{dateStyle:'medium',timeZone:'Asia/Shanghai'}).format(new Date(retention.earliestOccurredAt)):'暂无'}。在线搜索和对话使用热窗；冷记录可在文档中翻阅。</p>
    <p className="muted">{retention.archiveRetentionDays===0?'仅手动清除记忆或删除会话会清空历史。':'冷记录到期后自动回收。手动清除记忆或删除会话会清空历史。'}</p></>:<p className="muted">{retentionError?'留存概览暂不可用。':'正在读取留存概览…'}</p>}
  </section>
  <MemoryPolicyPanel groupId={group.id} busy={disabled} onSaved={()=>void load()}/>
  <section className="panel pad">
   <div className="mini-stats"><span>文档 <b>{policy?.counts.documents??'—'}</b></span><span>待处理 <b>{policy?.counts.pending??'—'}</b></span><span>处理失败 <b>{policy?.counts.failed??'—'}</b></span>{policy?.mode==='SUMMARY'&&<span>未概括消息 <b>{policy.uncompressed??0}</b></span>}</div>
   <p className="muted">{ragEnabled?'已启用向量检索增强，Markdown 搜索始终可用。':'未启用向量增强，Markdown 记忆、关键词搜索和 /memory 命令均可使用。'}</p>
   {policy?.rawArchive&&<div><p className="muted">原始记录：{({ACTIVE:'正常归档',PAUSED:'已暂停',NEEDS_MIGRATION:'等待离线迁移',FAILED:'归档失败'} as Record<string,string>)[policy.rawArchive.status]??policy.rawArchive.status} · 待归档 {policy.rawArchive.pendingMessages} 条 · 已归档 {policy.rawArchive.archivedMessages} 条 · 待回收 {policy.rawArchive.gcPending} 项</p>{policy.rawArchive.status==='NEEDS_MIGRATION'&&<p role="status">旧记录可继续查看，请停机后使用归档迁移工具合并历史文件。</p>}{policy.rawArchive.gcFailed>0&&<p role="alert">有 {policy.rawArchive.gcFailed} 项文件回收失败，系统会自动重试。已清除的内容不可再读取。</p>}{policy.rawArchive.errorCode&&<p role="alert">{policy.rawArchive.errorCode} {policy.rawArchive.errorCode==='RAW_ARCHIVE_CORRUPT'?'请离线校验或恢复配套备份。':<button className="text-button" disabled={disabled} onClick={()=>act(async()=>{await api(`/groups/${group.id}/memory/raw-archive/retry`,{method:'POST'});await load();},'已安排重试')}>重试原始归档</button>}</p>}</div>}
  </section>
  <MemoryUsagePanel groupId={group.id}/><MemoryContextPanel key={revision} groupId={group.id}/>
  <MarkdownEditor groupId={group.id} busy={disabled} act={act} revision={revision}/>
  <section className="panel pad"><h2>搜索群记忆</h2><p>搜索长期记忆、原始记录和自动概括，结果附带文档来源。中文关键词或英文词组均可。</p>
   <form className="search-form" onSubmit={event=>{event.preventDefault();const current=scope.current;act(async()=>{const found=await api<any[]>(`/groups/${group.id}/memory/search`,{method:'POST',body:JSON.stringify({question:query})});if(scope.current===current){setResults(found);setSearched(true);}},'检索完成');}}><Search size={19}/><input required maxLength={500} value={query} onChange={event=>setQuery(event.target.value)} placeholder="例如：上次讨论的发布安排是什么？"/><button className="primary" disabled={disabled}>检索</button></form>
   {results.map((source,index)=><article className="memory-result" key={source.id+'-'+index}><div><span className="badge blue">S{index+1}</span><span>{source.name}</span><small>匹配度 {Math.round(source.score*100)}%</small></div><pre>{source.body}</pre><small>来源：{source.id}</small>{/^(原始记录|自动概括) · /.test(source.name)&&<button className="text-button" disabled={disabled} onClick={()=>viewDocument(source.id)}>查看来源文档</button>}</article>)}
   {results.length===0&&<p className="muted">{searched?'未找到匹配内容。试试更具体的关键词；新消息归档可能需要几秒。':'输入关键词开始搜索，结果仅来自当前群。'}</p>}
  </section>
  <section className="panel"><div className="panel-top"><div><h2>记忆文档与任务</h2><p>原始记录与自动概括按时间分页。搜索只覆盖热窗口和人工记忆。</p></div><button className="secondary" disabled={disabled} onClick={()=>{void load();void loadRetention();}}><RefreshCw size={15}/>刷新文档</button></div>
   {archiveError?<p role="alert" className="banner error">{archiveError}（请刷新文档后重试。）</p>:!archivePage&&<p role="status" className="muted empty">正在读取文档…</p>}
   <div className="table-wrap"><table><thead><tr><th>文档类型</th><th>消息数</th><th>状态</th><th>尝试次数</th><th>操作</th></tr></thead><tbody>{documents.map(document=><tr key={document.id}><td>{document.kind==='RAW'?'原始记录':'自动概括'}</td><td>{document.source_count}</td><td>{states[document.state]??document.state}</td><td>{document.attempt}</td><td><button className="text-button" disabled={disabled} onClick={()=>viewDocument(document.id)}>查看文档</button>{document.state==='FAILED'&&<button className="text-button" disabled={disabled} onClick={()=>{const current=scope.current;act(async()=>{await api(`/groups/${group.id}/memory/documents/${document.id}/retry`,{method:'POST'});if(scope.current===current){setDetail(null);await load();}},'已重新提交记忆任务');}}>重试记忆任务</button>}</td></tr>)}</tbody></table>{archivePage&&documents.length===0&&<p className="muted empty">收到文本消息后自动归档；自动概括按所选策略触发。</p>}</div>
   <div className="panel-top"><button className="secondary" disabled={disabled||!canGoBack} onClick={()=>changePage(documentOffset-pageSize)}>较新文档</button><button className="secondary" disabled={disabled||!canGoForward} onClick={()=>changePage(documentOffset+pageSize)}>较早文档</button></div>
   {detail&&<div className="report"><div className="panel-top"><h3>{detail.kind==='RAW'?'原始记录':'自动概括'} · {states[detail.state]??detail.state}{detail.open?' · 持续追加':''}</h3><button className="secondary" onClick={()=>{detailId.current++;setDetail(null);}}>收起文档</button></div><p className="muted">{detail.storage==='SEGMENT_V1'?'原始消息记录 · '+detail.id:detail.id+'.md'} {detail.error_code??''}</p><pre>{detail.content||'任务尚未生成文档。'}</pre>{detail.nextCursor&&<button className="secondary" disabled={disabled} onClick={moreDocument}>加载更多（已加载 {detail.sources?.length??0} / {detail.totalSources??detail.source_count} 条）</button>}<details><summary>原消息来源（{detail.sources?.length??0} 条）</summary><pre>{detail.sources?.map(source=>source.message_id).join('\n')}</pre></details></div>}
  </section>
  <button className="danger" disabled={disabled} onClick={()=>{const name=prompt('将清除本群长期记忆、原始文件、概括、旧报告及临时对话。输入群名称确认：'+group.name);const current=scope.current;if(name!==null)act(async()=>{await api(`/groups/${group.id}/memory`,{method:'DELETE',body:JSON.stringify({confirmName:name})});if(scope.current===current){setResults([]);setDetail(null);detailId.current++;setRevision(value=>value+1);await Promise.all([load(0,true),loadRetention()]);}},'本群记忆已清除');}}><Trash2 size={15}/>清除本群记忆</button>
 </div>;
}
