/**
 * [INPUT]: 依赖群范围、统一 API 与后台冻结榜单类型。
 * [OUTPUT]: 提供独立定时配置、发布选择和已生成榜单分页。
 * [POS]: 定时任务的活跃榜卡片；不提交日报任务或模型请求。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useState} from 'react';
import {ListOrdered} from 'lucide-react';
import {api} from '../api';
import type {Actions,ActivityPublishMode,ActivityRun,ActivityRunPage,ActivitySettings,Group,QqGateway} from '../types';

const PAGE_SIZE=5;
const labels:Record<ActivityRun['delivery_state'],string>={NOT_REQUESTED:'仅后台',BLOCKED:'未获准群发',QUEUED:'等待发送',DELIVERED:'已发到群',FAILED:'投递失败',UNKNOWN:'发送结果未知',CANCELLED:'已取消发送'};
const reasons:Record<string,string>={QQ_ACTIVE_MESSAGES_DISABLED:'QQ 主动发送未开启',GROUP_REPLY_DISABLED:'群回复已关闭',GATEWAY_DISABLED:'网关不可用',ACTIVITY_PUBLICATION_DISABLED:'计划已停止群发',STALE_RESULT:'发送条件已变更'};

export default function ActivitySchedule({group,qq,busy,act}:Actions&{group:Group;qq:QqGateway}){
 const root=`/groups/${group.id}/activity`;
 const [settings,setSettings]=useState<ActivitySettings|null>(null),[page,setPage]=useState<ActivityRunPage|null>(null),[offset,setOffset]=useState(0);
 const [configRefresh,setConfigRefresh]=useState(0),[runRefresh,setRunRefresh]=useState(0),[configError,setConfigError]=useState(''),[runError,setRunError]=useState('');
 useEffect(()=>{
  const controller=new AbortController();setSettings(null);setConfigError('');
  api<ActivitySettings>(`${root}/schedule`,{signal:controller.signal}).then(value=>{if(!controller.signal.aborted)setSettings(value);})
   .catch(failure=>{if(!controller.signal.aborted)setConfigError(failure.message);});
  return()=>controller.abort();
 },[root,configRefresh]);
 useEffect(()=>{
  const controller=new AbortController();setRunError('');
  api<ActivityRunPage>(`${root}/runs?limit=${PAGE_SIZE}&offset=${offset}`,{signal:controller.signal}).then(value=>{if(!controller.signal.aborted)setPage(value);})
   .catch(failure=>{if(!controller.signal.aborted)setRunError(failure.message);});
  return()=>controller.abort();
 },[root,offset,runRefresh]);
 const save=()=>{
  if(!settings)return;
  setConfigError('');
  void act(async()=>{
   try{
    setSettings(await api<ActivitySettings>(`${root}/schedule`,{method:'PUT',body:JSON.stringify({enabled:settings.enabled,localTime:settings.local_time.slice(0,5),topN:settings.top_n,publishMode:settings.publish_mode,revision:settings.revision})}));
    setRunRefresh(value=>value+1);
   }catch(failure){setConfigError((failure as Error).message);throw failure;}
  },'活跃榜任务已保存');
 };
 return <section className="panel pad schedule activity-schedule" aria-labelledby="activity-title">
  <span className="feature-icon"><ListOrdered size={25}/></span><h2 id="activity-title">每日活跃榜</h2>
  <p>按北京时间统计昨日已采集的群消息；跳过指令和已识别的机器人消息，不调用模型。</p>
  {configError&&<p className="banner error" role="alert">{configError} <button className="text-button" onClick={()=>setConfigRefresh(value=>value+1)}>重新加载配置</button></p>}
  {settings?<form onSubmit={event=>{event.preventDefault();save();}}>
   <label className="check"><input type="checkbox" disabled={busy||!group.enabled} checked={settings.enabled} onChange={event=>setSettings({...settings,enabled:event.target.checked})}/>启用每日活跃榜</label>
   {!group.enabled&&<p className="muted">先在群管理中启用采集。</p>}
   <div className="form-row">
    <label>榜单执行时间<input type="time" required disabled={busy} value={settings.local_time.slice(0,5)} onChange={event=>setSettings({...settings,local_time:event.target.value})}/></label>
    <label>榜单名额（1–20）<input type="number" min={1} max={20} required disabled={busy} value={settings.top_n||''} onChange={event=>setSettings({...settings,top_n:Number(event.target.value)})}/></label>
   </div>
   <label>榜单发布范围<select disabled={busy} value={settings.publish_mode} onChange={event=>setSettings({...settings,publish_mode:event.target.value as ActivityPublishMode})}><option value="CONSOLE_ONLY">仅后台（推荐）</option><option value="GROUP">发到当前群</option></select></label>
   {settings.publish_mode==='GROUP'&&<p className="banner">保存后将向「{group.name}」公开昵称、名次与消息数。{!group.reply_enabled?'当前群回复已关闭，仅保存后台结果。':group.channel==='QQ'&&!qq.allowActiveMessages?'QQ 主动发送未开启，仅保存后台结果。':'群发仍受平台权限和频控限制。'}</p>}
   {settings.enabled?<p className="schedule-next">下次计划：{new Date(settings.next_run_at).toLocaleString('zh-CN',{timeZone:settings.zone})}（北京时间）</p>:<p className="muted">活跃榜未开启。</p>}
   <button className="primary" disabled={busy}>保存活跃榜</button>
  </form>:!configError&&<p role="status">正在读取榜单配置…</p>}
  <div className="activity-history">
   <div className="action-row"><h3>榜单执行记录</h3><button className="secondary" onClick={()=>setRunRefresh(value=>value+1)}>刷新榜单</button></div>
   {runError&&<p role="alert" className="banner error">{runError}</p>}
   {page?<>
    {page.runs.map((run,index)=><details className="activity-run" key={run.id} open={index===0||undefined}>
     <summary><strong>{run.business_date}</strong> · {run.state==='NO_DATA'?'无已采集发言':labels[run.delivery_state]}</summary>
     <p className="muted">{run.coverage.scopeNotice}{run.coverage.partial?' · 部分时段采集有缺口':''}{run.error_code?' · '+(reasons[run.error_code]??run.error_code):''}</p>
     {run.ranking.length>0&&<ol>{run.ranking.map(row=><li key={row.rank} value={row.rank}><span>{row.nickname}</span> · {row.messageCount} 条</li>)}</ol>}
    </details>)}
    {!page.runs.length&&<p className="muted">暂无榜单。开启计划后将生成昨日榜单。</p>}
    {page.total>PAGE_SIZE&&<div className="action-row"><button className="secondary" disabled={offset===0} onClick={()=>setOffset(Math.max(0,offset-PAGE_SIZE))}>上一页</button><span>共 {page.total} 次</span><button className="secondary" disabled={offset+PAGE_SIZE>=page.total} onClick={()=>setOffset(offset+PAGE_SIZE)}>下一页</button></div>}
   </>:!runError&&<p role="status">正在读取榜单…</p>}
  </div>
 </section>;
}
