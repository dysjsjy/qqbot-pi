/**
 * [INPUT]: 依赖统一 API、链路类型和格式化规则。
 * [OUTPUT]: 提供链路筛选、列表和耗时热点。
 * [POS]: 诊断页面；打开详情时保留筛选与焦点。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useRef,useState} from 'react';
import {api} from '../api';
import Empty from '../components/Empty';
import TraceDetail from './TraceDetail';
import {kindLabels,kindColors,statusLabels,statusClass,formatDuration,formatBytes,formatCount,formatTime} from '../traceFormat';
import type {Group,TraceKind,TraceSpan,TraceStat} from '../types';

export default function Traces({groups}:{groups:Group[]}){
 const trigger=useRef<HTMLButtonElement|null>(null);
 const[tab,setTab]=useState<'list'|'stats'>('list'),[rows,setRows]=useState<TraceSpan[]>([]),[stats,setStats]=useState<TraceStat[]>([]);
 const[groupId,setGroupId]=useState(''),[kind,setKind]=useState(''),[offset,setOffset]=useState(0),[hours,setHours]=useState(24);
 const[selected,setSelected]=useState<string|null>(null),[error,setError]=useState(''),[loading,setLoading]=useState(false);
 useEffect(()=>{
  let cancelled=false;setError('');setLoading(true);
  const query=new URLSearchParams({limit:'20',offset:String(offset)});
  if(groupId)query.set('groupId',groupId);if(kind)query.set('kind',kind);
  const load=async()=>{try{
   if(tab==='list'){const result=await api<TraceSpan[]>('/traces?'+query);if(!cancelled)setRows(result);}
   else{const result=await api<TraceStat[]>('/traces/stats?hours='+hours);if(!cancelled)setStats(result);}
  }catch(failure){if(!cancelled)setError((failure as Error).message);}finally{if(!cancelled)setLoading(false);}};
  void load();return()=>{cancelled=true;};
 },[tab,groupId,kind,offset,hours]);
 const maximum=Math.max(1,...stats.map(item=>item.totalUs));
 return <section className="panel">
  <div className="panel-top"><div><h2>链路分析</h2><p>每一行是一次调用的一个阶段，点开可看完整链路。</p></div></div>
  <div className="trace-tabs" role="tablist" aria-label="链路视图">{(['list','stats'] as const).map((value,index)=><button type="button" key={value} role="tab" id={'trace-tab-'+value} aria-controls="trace-panel" aria-selected={tab===value} tabIndex={tab===value?0:-1} onClick={()=>setTab(value)} onKeyDown={event=>{if(!['ArrowLeft','ArrowRight','Home','End'].includes(event.key))return;event.preventDefault();const next=event.key==='Home'?'list':event.key==='End'?'stats':index===0?'stats':'list';setTab(next);document.getElementById('trace-tab-'+next)?.focus();}}>{value==='list'?'调用记录':'耗时热点'}</button>)}</div>
  {error&&<div role="alert" className="banner error">{error}</div>}
  <div role="tabpanel" id="trace-panel" aria-labelledby={'trace-tab-'+tab} tabIndex={0}>
  {tab==='list'?<>
   <div className="filters"><select aria-label="群筛选" value={groupId} onChange={event=>{setGroupId(event.target.value);setOffset(0);}}><option value="">全部群</option>{groups.map(group=><option key={group.id} value={group.id}>{group.name}</option>)}</select>
    <select aria-label="阶段筛选" value={kind} onChange={event=>{setKind(event.target.value);setOffset(0);}}><option value="">全部阶段</option>{(Object.keys(kindLabels) as TraceKind[]).map(value=><option key={value} value={value}>{kindLabels[value]}</option>)}</select></div>
   <div className="table-wrap" aria-busy={loading}><table><thead><tr>{['开始时间','阶段','群','状态','耗时','CPU','内存分配','输入 token','输出 token','操作'].map(label=><th key={label}>{label}</th>)}</tr></thead><tbody>{rows.map(row=><tr key={row.id}>
    <td>{formatTime(row.startUs)}</td><td><span className="trace-kind"><i style={{background:kindColors[row.kind]}}/>{kindLabels[row.kind]}</span></td><td>{groups.find(group=>group.id===row.groupId)?.name??'—'}</td>
    <td><span className={'badge '+statusClass[row.status]}>{statusLabels[row.status]}</span>{row.status==='ERROR'&&<code>{row.errorCode}</code>}</td>
    <td>{formatDuration(row.durationUs)}</td><td>{formatDuration(row.cpuUs)}</td><td>{formatBytes(row.allocBytes)}</td><td>{formatCount(row.inputTokens)}</td><td>{formatCount(row.outputTokens)}</td>
    <td><button className="text-button" onClick={event=>{trigger.current=event.currentTarget;setSelected(row.runId);}}>查看链路</button></td>
   </tr>)}</tbody></table>{!rows.length&&!loading&&<Empty text="还没有链路记录。机器人回复一次后，这里会出现数据。"/>}</div>
   <div className="panel-top"><button className="secondary" disabled={offset===0||loading} onClick={()=>setOffset(Math.max(0,offset-20))}>上一页</button><span>第 {offset/20+1} 页</span><button className="secondary" disabled={rows.length<20||loading} onClick={()=>setOffset(offset+20)}>下一页</button></div>
  </>:<>
   <div className="filters"><select aria-label="时间范围" value={hours} onChange={event=>setHours(Number(event.target.value))}><option value={1}>最近 1 小时</option><option value={24}>最近 24 小时</option><option value={168}>最近 7 天</option></select></div>
   <div className="table-wrap" aria-busy={loading}><table><thead><tr>{['阶段','名称','次数','失败','累计耗时','平均','P50','P95','最大','平均 CPU','平均内存分配'].map(label=><th key={label}>{label}</th>)}</tr></thead><tbody>{stats.map(row=><tr key={row.kind+row.name}>
    <td><span className="trace-kind"><i style={{background:kindColors[row.kind]}}/>{kindLabels[row.kind]}</span></td><td><div className="trace-share"><i style={{width:row.totalUs/maximum*100+'%'}}/></div><code>{row.name}</code></td>
    <td>{formatCount(row.count)}</td><td>{formatCount(row.errors)}</td>{[row.totalUs,row.avgUs,row.p50Us,row.p95Us,row.maxUs,row.avgCpuUs].map((value,index)=><td key={index}>{formatDuration(value)}</td>)}<td>{formatBytes(row.avgAllocBytes)}</td>
   </tr>)}</tbody></table>{!stats.length&&!loading&&<Empty text="这个时间范围内没有数据。"/>}</div>
  </>}
  </div>
  {selected&&<TraceDetail key={selected} runId={selected} onClose={()=>{setSelected(null);queueMicrotask(()=>trigger.current?.isConnected&&trigger.current.focus());}}/>}
 </section>;
}
