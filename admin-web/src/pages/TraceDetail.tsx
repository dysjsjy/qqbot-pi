/**
 * [INPUT]: 依赖链路详情类型、格式化规则与 React。
 * [OUTPUT]: 提供调用瀑布、层耗时和资源指标。
 * [POS]: 链路明细视图；不载入模型提示正文。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useRef,useState} from 'react';
import {api} from '../api';
import {kindLabels,kindColors,statusLabels,statusClass,formatDuration,formatBytes,formatCount,formatTime} from '../traceFormat';
import type {TraceDetail as Detail,TraceSpan} from '../types';

type TokenKey='estimatedInputTokens'|'inputTokens'|'outputTokens'|'cacheReadTokens'|'cacheWriteTokens'|'reasoningTokens';
function sumTokens(spans:TraceSpan[],key:TokenKey){const values=spans.map(span=>span[key]).filter((value):value is number=>value!==null);return values.length?values.reduce((total,value)=>total+value,0):null;}
function actualInput(input:number|null,cacheRead:number|null,cacheWrite:number|null){return input===null||cacheRead===null||cacheWrite===null?null:input+cacheRead+cacheWrite;}
function attribute(span:TraceSpan|undefined,key:string){const value=span?.attributes[key];return typeof value==='number'&&Number.isFinite(value)?value:null;}
function flatten(root:TraceSpan,spans:TraceSpan[]){
 const result:{span:TraceSpan;depth:number}[]=[],seen=new Set<string>();
 const visit=(span:TraceSpan,depth:number)=>{if(seen.has(span.id))return;seen.add(span.id);result.push({span,depth});spans.filter(child=>child.parentSpan===span.id).sort((left,right)=>left.startUs-right.startUs||left.seq-right.seq).forEach(child=>visit(child,depth+1));};
 visit(root,0);return result;
}
function waitAfter(previous:TraceSpan,next:TraceSpan|undefined){
 const duration=next?next.startUs-previous.startUs-previous.durationUs:0;
 return duration>0?{name:previous.kind==='INGRESS'?'排队等待':previous.kind==='AGENT'?'发件等待':'等待',duration}:null;
}
function geometry(span:TraceSpan,root:TraceSpan){
 if(root.durationUs===0)return {left:'0%',width:'100%'};
 const left=Math.max(0,Math.min(100,(span.startUs-root.startUs)/root.durationUs*100));
 return {left:left+'%',width:Math.max(0,Math.min(100-left,Math.max(.5,span.durationUs/root.durationUs*100)))+'%'};
}
function summarize(detail:Detail,roots:TraceSpan[]){
 const models=detail.spans.filter(span=>span.name==='model.call'),generations=detail.spans.filter(span=>span.name==='pi.generate');
 const childCpu=generations.map(span=>attribute(span,'childCpuUs')).filter((value):value is number=>value!==null);
 const childMemory=generations.map(span=>attribute(span,'childRssBytes')).filter((value):value is number=>value!==null);
 const estimated=sumTokens(models,'estimatedInputTokens'),actual=actualInput(sumTokens(models,'inputTokens'),sumTokens(models,'cacheReadTokens'),sumTokens(models,'cacheWriteTokens'));
 const read=sumTokens(models,'cacheReadTokens'),firstToken=attribute(generations[0],'firstTokenMs');
 const waits=roots.map((root,index)=>waitAfter(root,roots[index+1])).filter(wait=>wait!==null);
 const waitDuration=(name:string)=>{const matches=waits.filter(wait=>wait.name===name);return matches.length?formatDuration(matches.reduce((total,wait)=>total+wait.duration,0)):'—';};
 return [
  ['总耗时',formatDuration(detail.endUs-detail.startUs)],['排队等待',waitDuration('排队等待')],['发件等待',waitDuration('发件等待')],
  ['模型耗时',formatDuration(models.reduce((total,span)=>total+span.durationUs,0))],['首字延迟',firstToken===null?'—':formatDuration(firstToken*1000)],
  ['JVM CPU',formatDuration(roots.reduce((total,span)=>total+span.cpuUs,0))],['JVM 内存分配',formatBytes(roots.reduce((total,span)=>total+span.allocBytes,0))],
  ['堆占用峰值',formatBytes(Math.max(0,...detail.spans.map(span=>span.memoryBytes)))],
  ['Pi CPU',childCpu.length?formatDuration(childCpu.reduce((total,value)=>total+value,0)):'—'],['Pi 内存峰值',childMemory.length?formatBytes(Math.max(...childMemory)):'—'],
  ['估算输入',formatCount(estimated)],['实际输入',formatCount(actual)],['输出',formatCount(sumTokens(models,'outputTokens'))],
  ['缓存读取占比',actual&&read!==null?(read/actual*100).toFixed(1)+'%':'—'],['估算÷实际',actual&&estimated!==null?(estimated/actual).toFixed(2):'—'],
 ];
}
const layerLabels:Record<string,string>={system:'系统提示词',manual:'人工记忆',summary:'工作摘要',history:'历史消息',evidence:'检索片段',current:'当前问题',overhead:'固定开销'};

export default function TraceDetail({runId,onClose}:{runId:string;onClose:()=>void}){
 const heading=useRef<HTMLHeadingElement>(null);
 useEffect(()=>{heading.current?.focus();},[runId]);
 const[detail,setDetail]=useState<Detail|null>(null),[error,setError]=useState(''),[selected,setSelected]=useState<string|null>(null);
 useEffect(()=>{let cancelled=false;setDetail(null);setError('');setSelected(null);api<Detail>('/traces/'+runId).then(value=>{if(!cancelled)setDetail(value);}).catch(failure=>{if(!cancelled)setError((failure as Error).message);});return()=>{cancelled=true;};},[runId]);
 const roots=detail?.spans.filter(span=>span.parentSpan===null).sort((left,right)=>left.startUs-right.startUs)||[];
 const chosen=detail?.spans.find(span=>span.id===selected)??roots.find(span=>span.kind==='AGENT')??roots[0];
 const layerValue=detail?.spans.find(span=>span.name==='memory.assemble')?.attributes.layerTokens;
 const layers=typeof layerValue==='object'&&layerValue!==null?layerValue as Record<string,unknown>:null;
 const layerMaximum=Math.max(1,...Object.values(layers??{}).filter((value):value is number=>typeof value==='number'));
 return <div className="report trace-detail"><div className="panel-top"><h2 ref={heading} tabIndex={-1}>链路详情</h2><button className="secondary" onClick={onClose}>收起</button></div>
  {error&&<div role="alert" className="banner error">{error}</div>}
  {!detail&&!error&&<p className="muted">正在加载…</p>}
  {detail&&<>
   <p className="muted">运行 {runId.slice(0,8)} · 共 {detail.spans.length} 段 · {formatTime(detail.startUs)}</p>
   <h3>汇总</h3><dl className="memory-metrics" data-section="summary">{summarize(detail,roots).map(([name,value])=><div key={name}><dt>{name}</dt><dd>{value}</dd></div>)}</dl>
   <h3>时间构成</h3>
   <div className="trace-stack" data-section="composition">{roots.map((root,index)=>{const wait=waitAfter(root,roots[index+1]);return <span key={root.id} className="trace-stack-parts" style={{flexGrow:root.durationUs+(wait?.duration??0)}}><i style={{flexGrow:root.durationUs,minWidth:'4px',background:kindColors[root.kind]}} title={kindLabels[root.kind]+' '+formatDuration(root.durationUs)}/>{wait&&<i className="wait" style={{flexGrow:wait.duration,minWidth:'4px'}} title={wait.name+' '+formatDuration(wait.duration)}/>}</span>;})}</div>
   <ul className="trace-legend">{roots.flatMap((root,index)=>{const wait=waitAfter(root,roots[index+1]);return [<li key={root.id}><i style={{background:kindColors[root.kind]}}/>{kindLabels[root.kind]} <b>{formatDuration(root.durationUs)}</b></li>,wait&&<li key={root.id+'wait'}><i className="wait"/>{wait.name} <b>{formatDuration(wait.duration)}</b></li>];})}</ul>
   <h3>调用瀑布</h3>{roots.map((root,index)=>{const wait=waitAfter(root,roots[index+1]);return <div key={root.id}>
    <div className="trace-segment" data-segment={root.kind}>
     <div className="trace-segment-head"><span className="trace-kind"><i style={{background:kindColors[root.kind]}}/>{kindLabels[root.kind]}</span><span className={'badge '+statusClass[root.status]}>{statusLabels[root.status]}</span><b>{formatDuration(root.durationUs)}</b></div>
     <div className="trace-axis"><span>0</span><span>{formatDuration(root.durationUs/2)}</span><span>{formatDuration(root.durationUs)}</span></div>
     {flatten(root,detail.spans).map(({span,depth})=><button key={span.id} className={'trace-row'+(chosen?.id===span.id?' selected':'')} data-span={span.name} title={span.name+' · '+formatDuration(span.durationUs)+' · '+statusLabels[span.status]} aria-pressed={chosen?.id===span.id} onClick={()=>setSelected(span.id)}>
      <span className="trace-name" style={{paddingLeft:depth*14}}>{span.name}</span><span className="trace-track"><i style={{...geometry(span,root),background:kindColors[span.kind]}} className={span.status==='OK'?'':'failed'}/></span><span className="trace-value">{formatDuration(span.durationUs)}</span>
     </button>)}
    </div>{wait&&<p className="trace-gap muted">{wait.name} {formatDuration(wait.duration)}</p>}
   </div>;})}
   {chosen&&<><h3>所选阶段</h3><dl className="memory-metrics" data-section="selected">
    {[['名称',chosen.name],['状态',statusLabels[chosen.status]+(chosen.errorCode?' · '+chosen.errorCode:'')],['开始',formatDuration(chosen.startUs-detail.startUs)],['耗时',formatDuration(chosen.durationUs)],['CPU',formatDuration(chosen.cpuUs)],['CPU 占比',chosen.durationUs?(chosen.cpuUs/chosen.durationUs*100).toFixed(0)+'%':'—'],['内存分配',formatBytes(chosen.allocBytes)],['堆占用',formatBytes(chosen.memoryBytes)]].map(([name,value])=><div key={name}><dt>{name}</dt><dd>{value}</dd></div>)}
    {Object.entries(chosen.attributes).map(([name,value])=><div key={'attr:'+name}><dt>{name}</dt><dd>{typeof value==='object'?JSON.stringify(value):String(value)}</dd></div>)}
   </dl></>}
   {layers&&<><h3>上下文构成</h3><div data-section="layers">{Object.entries(layerLabels).map(([key,label])=>{const value=typeof layers[key]==='number'?layers[key] as number:null;return <div className="trace-layer" key={key}><span>{label}</span><span className="trace-share"><i style={{width:Math.max(0,(value??0)/layerMaximum*100)+'%'}}/></span><b>{formatCount(value)}</b></div>;})}</div></>}
  </>}
 </div>;
}
