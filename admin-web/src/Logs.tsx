/**
 * [INPUT]: 依赖统一 API 与浏览器下载能力。
 * [OUTPUT]: 提供运行日志筛选、轮询、导出和操作审计。
 * [POS]: 全局诊断页面；轮询保持筛选焦点。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useState} from 'react';
import {Download,RefreshCw} from 'lucide-react';
import {api} from './api';

type Entry={id:number;time:string;level:string;source:string;message:string};
type Audit={id:string;created_at:string;action:string;target:string;details?:{source:string;actor:string;senderId?:string;before?:unknown;after?:unknown}};
const actions:Record<string,string>={GROUP_CREATE:'新增群聊',GROUP_UPSERT:'登记群聊',GROUP_UPDATE:'更新群配置',GROUP_DELETE:'删除群聊',MEMORY_CLEAR:'清除群记忆',MEMORY_RETRY:'重试索引',SCHEDULE_UPDATE:'更新定时任务',GROUP_ADMIN_GRANT:'授予群管理员',GROUP_ADMIN_REVOKE:'撤销群管理员',REPLY_POLICY_UPDATE:'更新群回复策略',ACTIVITY_SCHEDULE_UPDATE:'更新每日活跃榜'};
function AuditAction({entry}:{entry:Audit}){
 const details=entry.details;
 return <>{actions[entry.action]??entry.action}{details&&<details className="audit-change"><summary>{details.source==='COMMAND'?'群内指令':'管理台'} · {details.actor}</summary>{details.senderId&&<span>成员 ID：{details.senderId}</span>}{details.before!=null&&<pre>{JSON.stringify({修改前:details.before,修改后:details.after},null,2)}</pre>}</details>}</>;
}
export default function Logs(){
 const[tab,setTab]=useState('runtime'),[entries,setEntries]=useState<Entry[]>([]),[audit,setAudit]=useState<Audit[]>([]),[level,setLevel]=useState(''),[query,setQuery]=useState(''),[auto,setAuto]=useState(true),[error,setError]=useState(''),[offset,setOffset]=useState(0);
 const load=async()=>{try{
  if(tab==='runtime'){const data=await api<{items:Entry[]}>('/logs?limit=200&level='+encodeURIComponent(level)+'&query='+encodeURIComponent(query));setEntries(data.items??[]);}
  else setAudit(await api<Audit[]>('/audit?limit=50&offset='+offset));
  setError('');
 }catch(e){setError((e as Error).message);}};
 useEffect(()=>{const initial=setTimeout(load,200);const timer=auto?setInterval(load,3000):undefined;return()=>{clearTimeout(initial);if(timer)clearInterval(timer);};},[tab,level,query,auto,offset]);
 const download=()=>{
  const text=entries.map(e=>`${e.time} ${e.level} ${e.source} ${e.message}`).join('\n');
  const url=URL.createObjectURL(new Blob([text],{type:'text/plain;charset=utf-8'}));
  const link=document.createElement('a');link.href=url;link.download='qqbot-runtime.log';link.click();URL.revokeObjectURL(url);
 };
 return <section className="panel"><div className="panel-top"><div><h2>运行日志与操作审计</h2><p>连接、事件、模型与发送结果会记录在这里。运行日志保留最近 1000 条，重启清空；操作审计保存 90 天。</p></div><button className="secondary" onClick={load}><RefreshCw size={15}/>刷新日志</button></div>
  <div className="log-tabs"><button aria-pressed={tab==='runtime'} className={tab==='runtime'?'primary':'secondary'} onClick={()=>setTab('runtime')}>运行日志</button><button aria-pressed={tab==='audit'} className={tab==='audit'?'primary':'secondary'} onClick={()=>setTab('audit')}>操作审计</button><label className="check"><input type="checkbox" checked={auto} onChange={e=>setAuto(e.target.checked)}/>每 3 秒自动刷新</label></div>
  {error&&<div role="alert" className="banner error">{error}</div>}
  {tab==='runtime'?<><div className="filters"><input aria-label="搜索日志" maxLength={200} placeholder="搜索错误码、组件或任务 ID" value={query} onChange={e=>setQuery(e.target.value)}/><select aria-label="日志级别" value={level} onChange={e=>setLevel(e.target.value)}><option value="">全部级别</option>{['INFO','WARN','ERROR','DEBUG'].map(l=><option key={l}>{l}</option>)}</select><button className="secondary" disabled={!entries.length} onClick={download}><Download size={15}/>导出当前日志</button><span>最近 {entries.length} 条匹配结果</span></div><div className="log-list">{entries.map(e=><article className={'log-entry '+e.level.toLowerCase()} key={e.id}><time>{new Date(e.time).toLocaleString('zh-CN')}</time><span className={'badge '+(e.level==='ERROR'?'red':e.level==='WARN'?'gray':'blue')}>{e.level}</span><code>{e.source.replace('dev.qqbot.','')}</code><pre>{e.message}</pre></article>)}{!entries.length&&<div className="empty"><h3>暂无匹配日志</h3><p>调整筛选条件，或发送新的群消息后查看。</p></div>}</div><p className="log-note">不展示聊天正文、模型 prompt、密钥或原始异常体。需要长期保存时可导出当前结果，或由部署终端重定向应用控制台日志。</p></>:<><div className="table-wrap"><table><thead><tr><th>时间</th><th>操作</th><th>目标 ID</th></tr></thead><tbody>{audit.map(e=><tr key={e.id}><td>{new Date(e.created_at).toLocaleString('zh-CN')}</td><td><AuditAction entry={e}/></td><td>{e.target}</td></tr>)}</tbody></table>{!audit.length&&<div className="empty"><h3>暂无操作审计</h3></div>}</div><div className="panel-top"><button className="secondary" disabled={!offset} onClick={()=>setOffset(Math.max(0,offset-50))}>上一页</button><span>第 {offset/50+1} 页</span><button className="secondary" disabled={audit.length<50} onClick={()=>setOffset(offset+50)}>下一页</button></div></>}
 </section>;
}
