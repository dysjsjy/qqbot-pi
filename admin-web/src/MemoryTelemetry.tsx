/**
 * [INPUT]: 依赖统一 API、ApiError 与记忆接口类型。
 * [OUTPUT]: 提供归档设置、费用和上下文层统计。
 * [POS]: 记忆工作区的状态面板；保留并发版本冲突输入。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useRef,useState} from 'react';
import {api,ApiError} from './api';
import type {MemoryContextStats,MemoryPurpose,MemorySettings,MemoryUsage,MemoryUsageTotals} from './types';

const purposes:Record<MemoryPurpose,string>={CHAT:'普通聊天',MEMORY_QUERY:'历史查询',MEMORY_SUMMARY:'记忆概括',DAILY_SUMMARY:'群日报'};
const layerNames:Record<keyof MemoryContextStats['layerTokens'],string>={system:'系统',current:'当前问题',manual:'人工记忆',history:'对话历史',evidence:'检索资料',summary:'工作摘要',overhead:'协议与模板'};
const reasonNames:Record<string,string>={LAYER_BUDGET:'层额度不足',TOTAL_BUDGET:'总额度不足',DUPLICATE_SOURCE:'重复来源',TRUNCATED:'已节选'};
const message=(failure:unknown)=>failure instanceof Error?failure.message:'请求失败，请重试';

export function MemoryPolicyPanel({groupId,busy,onSaved}:{groupId:string;busy:boolean;onSaved:()=>void}){
 const[settings,setSettings]=useState<MemorySettings|null>(null),[error,setError]=useState(''),[notice,setNotice]=useState(''),[saving,setSaving]=useState(false),[conflict,setConflict]=useState(false),[reload,setReload]=useState(0);
 const scope=useRef(0);
 useEffect(()=>{
  const current=++scope.current;const controller=new AbortController();setSettings(null);setError('');setNotice('');setConflict(false);setSaving(false);
  api<MemorySettings>(`/groups/${groupId}/memory/settings`,{signal:controller.signal}).then(value=>{if(scope.current===current)setSettings(value);}).catch(failure=>{if(scope.current===current)setError(message(failure));});
  return()=>{scope.current++;controller.abort();};
 },[groupId,reload]);
 const submit=async()=>{
  if(!settings||saving||conflict)return;
  const current=scope.current;setSaving(true);setError('');setNotice('');
  try{const saved=await api<MemorySettings>(`/groups/${groupId}/memory/settings`,{method:'PUT',body:JSON.stringify(settings)});if(scope.current===current){setSettings(saved);setNotice('记忆设置已保存');onSaved();}}
  catch(failure){if(scope.current===current){if(failure instanceof ApiError&&failure.status===409){setConflict(true);setError('设置已被其他操作修改，当前输入已保留。请点击“刷新设置”后重新编辑。');}else setError(message(failure));}}
  finally{if(scope.current===current)setSaving(false);}
 };
 return <section className="panel pad memory-policy" aria-label="记忆设置"><h2>基础记忆方式</h2><p>Markdown 是每个会话的记忆来源。回复按预算选取本人问答、近期对话和记忆资料；以下选项只控制后台归档。</p>
  {error&&<p role="alert" className="banner error">{error}</p>}{notice&&<p role="status" className="banner success">{notice}</p>}
  {!settings?<p role="status">正在加载记忆设置…</p>:<form onSubmit={event=>{event.preventDefault();void submit();}}>
   <fieldset disabled={busy||saving}><div className="memory-fields">
    <label>记忆模式<select value={settings.mode} onChange={event=>setSettings({...settings,mode:event.target.value as MemorySettings['mode']})}><option value="RAW">原样记录消息</option><option value="SUMMARY">自动概括</option></select></label>
    <label>概括策略<select value={settings.strategyVersion} onChange={event=>setSettings({...settings,strategyVersion:Number(event.target.value) as 1|2})}><option value="1">旧版：每 100 条</option><option value="2">新版：按 token 独立批次</option></select></label>
    <label>概括触发量（估算 token）<input type="number" required min="1024" max="16000" step="1" value={settings.summaryTriggerTokens} onChange={event=>setSettings({...settings,summaryTriggerTokens:Number(event.target.value)})}/></label>
    <label>概括冷却（分钟）<input type="number" required min="5" max="1440" step="1" value={settings.summaryCooldownMinutes} onChange={event=>setSettings({...settings,summaryCooldownMinutes:Number(event.target.value)})}/></label>
   </div></fieldset>
   <p className="muted">{settings.mode==='RAW'?'原样模式不调用摘要模型，聊天仍按正常规则生成回复。':settings.strategyVersion===1?'旧版概括每 100 条文本形成一批，触发量和冷却设置仅用于新版策略。':`新版概括需同时达到 ${settings.summaryTriggerTokens} 估算 token、${settings.summaryCooldownMinutes} 分钟冷却，并在最近 24 小时有成功回复；每批只输入该批原文。`}</p>
   <div className="action-row"><button className="primary" disabled={busy||saving||conflict}>保存记忆模式</button><button type="button" className="secondary" disabled={busy||saving} onClick={()=>setReload(value=>value+1)}>刷新设置</button></div>
  </form>}
  {!settings&&error&&<button className="secondary" onClick={()=>setReload(value=>value+1)}>重试设置</button>}
 </section>;
}

function amount(value:number|null,currency:string){return value===null?'未知 / 未配置':`${currency} ${value.toFixed(8)}`;}
function UsageTotals({usage}:{usage:MemoryUsageTotals}){
 return <><dl className="memory-metrics"><div><dt>尝试 / 已发送</dt><dd>{usage.calls} / {usage.dispatchedCalls}</dd></div><div><dt>已报告 / 未知用量</dt><dd>{usage.reportedCalls} / {usage.unknownCalls}</dd></div><div><dt>普通输入 / 输出</dt><dd>{usage.inputTokens} / {usage.outputTokens}</dd></div><div><dt>缓存读取 / 写入</dt><dd>{usage.cacheReadTokens} / {usage.cacheWriteTokens}</dd></div><div><dt>已报告费用</dt><dd>{amount(usage.reportedCost,usage.currency)}</dd></div><div><dt>未知调用粗估费用</dt><dd>{amount(usage.estimatedCost,usage.currency)}</dd></div></dl>
  {usage.unknownCalls>0&&<p className="muted">仅展示已知部分；另有 {usage.unknownCalls} 次已发送调用用量未知，粗估费用不等于实际费用。</p>}{usage.currency==='mixed'&&<p className="muted">存在多币种，费用不相加、不换汇。</p>}
 </>;
}

export function MemoryUsagePanel({groupId}:{groupId:string}){
 const[date,setDate]=useState(''),[reload,setReload]=useState(0),[usage,setUsage]=useState<MemoryUsage|null>(null),[error,setError]=useState('');
 useEffect(()=>{
  let stopped=false;const controller=new AbortController();setUsage(null);setError('');
  api<MemoryUsage>(`/groups/${groupId}/memory/usage${date?'?date='+encodeURIComponent(date):''}`,{signal:controller.signal}).then(value=>{if(!stopped)setUsage(value);}).catch(failure=>{if(!stopped)setError(message(failure));});
  return()=>{stopped=true;controller.abort();};
 },[groupId,date,reload]);
 return <section className="panel pad memory-usage" aria-label="记忆用量"><h2>生成用量与费用</h2><p>生成费用，不含向量服务。实际用量与粗估分别展示，价格采用每次调用开始时的快照。</p><div className="memory-date"><label>用量日期（上海时间）<input type="date" value={date} onChange={event=>setDate(event.target.value)}/></label><button className="secondary" onClick={()=>setReload(value=>value+1)}>刷新用量</button></div>
  {error?<p role="alert" className="banner error">{error}</p>:!usage?<p role="status">正在加载用量…</p>:<><p className="muted">{usage.date} · {usage.zone} · 币种 {usage.currency} · 价格版本 {usage.priceVersion}</p><UsageTotals usage={usage}/><details><summary>按用途查看</summary>{(Object.keys(purposes) as MemoryPurpose[]).map(purpose=><section className="memory-purpose" key={purpose}><h3>{purposes[purpose]}</h3><UsageTotals usage={usage.byPurpose[purpose]}/></section>)}</details></>}
 </section>;
}

export function MemoryContextPanel({groupId}:{groupId:string}){
 const[stats,setStats]=useState<MemoryContextStats|null>(null),[error,setError]=useState(''),[reload,setReload]=useState(0);
 useEffect(()=>{
  let stopped=false;const controller=new AbortController();setStats(null);setError('');
  api<MemoryContextStats>(`/groups/${groupId}/memory/context-stats`,{signal:controller.signal}).then(value=>{if(!stopped)setStats(value);}).catch(failure=>{if(!stopped)setError(failure instanceof ApiError&&failure.status===409?'本会话尚无可用的上下文统计。':message(failure));});
  return()=>{stopped=true;controller.abort();};
 },[groupId,reload]);
 return <section className="panel pad memory-context" aria-label="上下文统计"><h2>最近已装配运行</h2><p>仅显示层计数、来源数量和裁剪原因。</p><button className="secondary" onClick={()=>setReload(value=>value+1)}>刷新上下文统计</button>{error?<p role="status">{error}</p>:!stats?<p role="status">正在加载上下文统计…</p>:<><p className="muted memory-hash">运行 {stats.runId}</p><p>估算输入 {stats.estimatedInputTokens} · 最终资料来源 {stats.sourceCount}</p><dl className="memory-metrics">{(Object.keys(layerNames) as (keyof typeof layerNames)[]).map(layer=><div key={layer}><dt>{layerNames[layer]}</dt><dd>{stats.layerTokens[layer]}</dd></div>)}</dl><p>裁剪原因：{stats.reasons.length?stats.reasons.map(reason=>reasonNames[reason]??reason).join('、'):'无'}</p><p className="muted memory-hash">人工记忆 hash：{stats.manualHash}</p></>}
 </section>;
}
