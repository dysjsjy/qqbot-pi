/**
 * [INPUT]: 依赖统一 API、群配置与日报记录类型。
 * [OUTPUT]: 提供分页记录、清爽分段报告详情、模式徽标、来源消息折叠追踪与失败任务重试。
 * [POS]: 日报查询页面；发送未知时要求人工核实。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useState} from 'react';
import {ArrowUpRight} from 'lucide-react';
import {api} from '../api';
import Empty from '../components/Empty';
import type {Actions,Group,RunRecord} from '../types';

const labels:Record<string,string>={QUEUED:'等待执行',RUNNING:'分析中',GENERATED:'等待发送',DELIVERED:'已发送',SENT:'已发送',FAILED:'执行失败',DELIVERY_FAILED:'发送失败',EXPIRED:'已过期',INTERRUPTED:'已中断',NO_DATA:'暂无文本',CANCELLED:'已取消',DELIVERY_UNKNOWN:'发送结果未知',LIMIT_EXCEEDED:'超出分析上限'};
const styleLabels:Record<string,string>={CASUAL:'日常群聊',KNOWLEDGE:'知识答疑',WORK:'协作纪要'};
const speakerLabels:Record<string,string>={KEY_ONLY:'仅关键角色',ANONYMOUS:'隐去群友昵称',ACTIVE:'保留活跃参与者'};
const UUID_TAG=/\s*\[([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\]/g;

function parseReport(raw?:string){
 if(!raw)return {blocks:[] as string[],sources:[] as string[]};
 const seen=new Set<string>();
 for(const match of raw.matchAll(UUID_TAG))seen.add(match[1]);
 const clean=raw.replace(UUID_TAG,'').trim();
 const blocks=clean?clean.split(/\n{2,}|\n(?=[•▸])/).map(s=>s.trim()).filter(Boolean):[];
 return {blocks,sources:Array.from(seen)};
}

export default function Runs({group,busy,act}:Actions&{group:Group}){
 const[runs,setRuns]=useState<RunRecord[]>([]),[detail,setDetail]=useState<RunRecord|null>(null),[offset,setOffset]=useState(0),[pollError,setPollError]=useState('');
 const load=async()=>{try{setRuns(await api<RunRecord[]>('/runs?groupId='+group.id+'&limit=20&offset='+offset));setPollError('');}catch(failure){setPollError((failure as Error).message);}};
 useEffect(()=>{setDetail(null);load();const timer=setInterval(load,5000);return()=>clearInterval(timer);},[group.id,offset]);
 const parsed=parseReport(detail?.report);
 const cov=detail?.coverage;
 return <section className="panel"><div className="panel-top"><div><h2>分析记录</h2><p>按业务日期查看日报、采集覆盖和发送状态。</p></div></div>
  {pollError&&<div role="alert" className="banner error">{pollError}</div>}
  <div className="table-wrap"><table><thead><tr><th>业务日期</th><th>运行状态</th><th>执行次数</th><th>创建时间</th><th>操作</th></tr></thead><tbody>{runs.map(run=><tr key={run.id}><td>{String(run.business_date).slice(0,10)}</td><td><span className={'badge '+(run.state==='DELIVERED'?'green':['FAILED','DELIVERY_FAILED','DELIVERY_UNKNOWN'].includes(run.state)?'red':'blue')}>{labels[run.state]??run.state}</span></td><td>{run.attempt}</td><td>{new Date(run.created_at).toLocaleString('zh-CN')}</td><td><button className="text-button" onClick={()=>act(async()=>setDetail(await api<RunRecord>('/runs/'+run.id)),'报告已加载')}>查看报告 <ArrowUpRight size={14}/></button></td></tr>)}</tbody></table>{!runs.length&&<Empty text="首份日报生成后，会出现在这里。"/>}</div>
  <div className="panel-top"><button className="secondary" disabled={offset===0||busy} onClick={()=>setOffset(Math.max(0,offset-20))}>上一页</button><span>第 {offset/20+1} 页</span><button className="secondary" disabled={runs.length<20||busy} onClick={()=>setOffset(offset+20)}>下一页</button></div>
  {detail&&<div className="report"><div className="panel-top"><div><h2>{String(detail.business_date).slice(0,10)} · 分析报告</h2>{cov&&(cov.styleMode||cov.speakerMode)&&<div className="report-meta">{cov.styleMode&&<span className="badge blue">{styleLabels[cov.styleMode]??cov.styleMode}</span>}{cov.speakerMode&&<span className="badge gray">{speakerLabels[cov.speakerMode]??cov.speakerMode}</span>}</div>}</div><button className="secondary" onClick={()=>setDetail(null)}>收起</button></div>
   <p className="muted">状态：{labels[detail.state]??detail.state} · {detail.error_code||'无错误记录'}</p>
   {parsed.blocks.length>0?<div className="report-prose">{parsed.blocks.map((block,idx)=><p key={idx} className={idx===0&&parsed.blocks.length>1&&!block.startsWith('•')?'report-lead':'report-item'}>{block}</p>)}</div>:<pre>{detail.report||'报告尚未生成。'}</pre>}
   {parsed.sources.length>0&&<details><summary>来源消息追踪（{parsed.sources.length} 条）</summary><pre className="report-sources">{parsed.sources.join('\n')}</pre></details>}
   <details><summary>采集覆盖信息</summary><pre>{JSON.stringify(detail.coverage,null,2)}</pre></details>
   {detail.state==='DELIVERY_UNKNOWN'&&<p className="muted">请先核实群内是否收到报告，避免重复发送。</p>}
   {['FAILED','INTERRUPTED','DELIVERY_FAILED'].includes(detail.state)&&<button className="secondary" disabled={busy} onClick={()=>act(async()=>{await api('/runs/'+detail.id+'/retry',{method:'POST'});await load();setDetail(null);},detail.state==='DELIVERY_FAILED'?'已提交失败分段补发':'已提交重新分析')}>{detail.state==='DELIVERY_FAILED'?'重试发送':'重新执行分析'}</button>}
  </div>}
 </section>;
}
