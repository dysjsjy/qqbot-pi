/**
 * [INPUT]: 依赖统一 API、群配置类型与原生对话框。
 * [OUTPUT]: 提供会话配置与各群的成员管理入口。
 * [POS]: 会话列表入口；保留官方连接事件与私聊边界。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import Dialog from './components/Dialog';
import GroupMembersDialog from './components/GroupMembersDialog';
import {useEffect,useRef,useState} from 'react';
import {Users,Database,Plus,Search,RefreshCw,Trash2,Eye,Pencil,LayoutList,LayoutGrid,BookOpen} from 'lucide-react';
import {api,type WorkspaceStatus} from './api';
import type {Group,QqGateway as Gateway,Actions} from './types';

const date=(value?:string)=>value?new Date(value).toLocaleString('zh-CN'):'尚无记录';

export default function Groups({groups,busy,act,load,qq,error,status}:{groups:Group[];load:()=>Promise<void>;qq:Gateway;error?:string;status:WorkspaceStatus|null}&Actions){
 const[view,setView]=useState<'list'|'cards'>('list');
 const trigger=useRef<HTMLElement|null>(null);
 const remember=()=>{trigger.current=document.activeElement instanceof HTMLElement?document.activeElement:null;};
 const[editing,setEditing]=useState<Group|null|undefined>(),[detail,setDetail]=useState<Group|null>(null),[deleting,setDeleting]=useState<Group|null>(null),[confirmation,setConfirmation]=useState('');
 const[members,setMembers]=useState<Group|null>(null);
 const[query,setQuery]=useState(''),[channel,setChannel]=useState(''),[enabled,setEnabled]=useState('');
 const[form,setForm]=useState({channel:'QQ',accountId:'',externalId:'',name:'',adminIds:'',enabled:true,replyEnabled:true});
 const open=(g?:Group)=>{
  setEditing(g??null);
  setForm(g?{channel:g.channel,accountId:g.account_id,externalId:g.external_id,name:g.name,adminIds:g.admin_ids,enabled:g.enabled,replyEnabled:g.reply_enabled}:{channel:'QQ',accountId:qq.accountId,externalId:'',name:'',adminIds:'',enabled:true,replyEnabled:true});
 };
 const visible=groups.filter(g=>(!channel||g.channel===channel)&&(!enabled||String(g.enabled)===enabled)&&[g.name,g.external_id,g.account_id,g.target_id||''].some(value=>value.toLowerCase().includes(query.trim().toLowerCase())));
 return <>
  <div className="stats"><div><span>已登记会话</span><strong>{String(status?.counts?.registered_groups??groups.length).padStart(2,'0')}</strong><Users/></div><div><span>已保存群文本</span><strong>{(status?.counts?.total_messages??groups.reduce((n,g)=>n+Number(g.message_count||0),0)).toLocaleString()}</strong><Database/></div><div><span>已沉淀记忆</span><strong>{(status?.counts?.indexed_memories??groups.reduce((n,g)=>n+Number(g.memory_count||0),0)).toLocaleString()}</strong><BookOpen/></div></div>
  {<><div className="banner gateway-banner">QQ 官方机器人 · AppID：{qq.accountId}。群使用 group_openid，管理员使用成员 OpenID。首次 @ 后刷新列表，在发现群中启用采集与回复，再发新消息测试。私聊默认直接回复，按用户独立保存，可在列表中暂停。</div></>}
  <section className="panel">
   <div className="panel-top"><div><h2>我的群聊与私聊 <span className="counter">{groups.length}</span></h2><p>私聊按用户自动发现并直接回复；群聊按已配置策略回复。停用采集或回复可暂停对应会话。</p></div><button className="primary" disabled={busy} onClick={()=>{remember();open();}}><Plus size={17}/>添加群聊</button></div>
   <div className="filters"><label className="filter-search"><Search size={16}/><input aria-label="搜索群聊" placeholder="搜索群名称、群 ID 或账号" value={query} onChange={e=>setQuery(e.target.value)}/></label><select aria-label="筛选平台" value={channel} onChange={e=>setChannel(e.target.value)}><option value="">全部平台</option><option value="QQ">QQ</option><option value="WEIXIN">微信</option></select><select aria-label="筛选采集状态" value={enabled} onChange={e=>setEnabled(e.target.value)}><option value="">全部采集状态</option><option value="true">采集中</option><option value="false">已暂停</option></select><span>{visible.length} 个结果</span><div className="view-switch"><button aria-label="列表视图" aria-pressed={view==='list'} onClick={()=>setView('list')}><LayoutList size={16}/></button><button aria-label="卡片视图" aria-pressed={view==='cards'} onClick={()=>setView('cards')}><LayoutGrid size={16}/></button></div></div>
   <div className={"table-wrap "+(view==='cards'?'group-cards':'')}><table><thead><tr><th>群聊 / 私聊</th><th>接入状态</th><th>采集 / 回复</th><th>记忆</th><th>操作</th></tr></thead><tbody>{visible.map(g=><tr key={g.id}>
    <td><div className="group-cell"><span className={'channel-icon '+g.channel}>{g.channel==='QQ'?'Q':'微'}</span><div><strong>{g.name}</strong><small>{g.chat_type==='PRIVATE'?'QQ 私聊':g.channel==='QQ'?'QQ':'微信'} · {g.target_id||g.external_id}</small></div></div></td>
    <td><span className={'badge '+(g.connection_state==='ONLINE'?'green':'gray')}>{g.connection_state==='ONLINE'?'最近收到消息':g.connection_state==='OFFLINE'?'已离线':'待验证'}</span></td>
    <td><span className={'badge '+(g.enabled?'blue':'gray')}>{g.enabled?'采集中':'已暂停'}</span><small>回复{g.reply_enabled?'已启用':'已关闭'}</small></td><td><strong>{g.memory_count||0}</strong><small>个文本片段</small></td>
    <td><div className="row-actions"><button className="text-button" disabled={busy} onClick={()=>{remember();void act(async()=>setDetail(await api<Group>('/groups/'+g.id)), '');}}><Eye size={14}/>详情</button>{g.chat_type!=='PRIVATE'&&<button className="text-button" disabled={busy} onClick={()=>{remember();setMembers(g);}}><Users size={14}/>活跃成员</button>}<button className="text-button" disabled={busy} onClick={()=>{remember();void act(async()=>open(await api<Group>('/groups/'+g.id)), '');}}><Pencil size={14}/>编辑</button><button className="text-button delete-button" disabled={busy} onClick={()=>{remember();setDeleting(g);setConfirmation('');}}><Trash2 size={14}/>删除</button></div></td>
   </tr>)}</tbody></table>{!visible.length&&<div className="empty"><Users size={28}/><h3>{groups.length?'没有匹配的会话':'还没有群聊或私聊'}</h3><p>{groups.length?'调整搜索或筛选条件。':'添加群聊，或收到真实事件后刷新自动发现的群聊和私聊。'}</p></div>}</div>
  </section>
  <div className="info-card"><Database size={23}/><div><h3>接入、采集与回复分别检查</h3><p>连接在线后，仍需启用目标群的采集和回复。先用 /help 验证收发，再用 @ 问题验证模型；错误原因可在运行日志中查看。</p></div></div>
  <OfficialEvents/>
  {members&&<GroupMembersDialog key={members.id} group={members} busy={busy} act={act} onClose={()=>setMembers(null)} returnFocus={trigger.current}/>}
  {editing!==undefined&&<Dialog label={editing?'编辑群聊':'添加群聊'} busy={busy} onClose={()=>setEditing(undefined)} returnFocus={trigger.current}><h2>{editing?'编辑群聊':'添加群聊'}</h2>{error&&<p role="alert" className="banner error">{error}</p>}<form onSubmit={e=>{e.preventDefault();act(async()=>{await api(editing?'/groups/'+editing.id:'/groups',{method:editing?'PATCH':'POST',body:JSON.stringify({...form,revision:editing?.revision??0})});setEditing(undefined);await load();},'群配置已保存');}}>
   <div className="form-row"><label>平台<select disabled={!!editing} value={form.channel} onChange={e=>setForm({...form,channel:e.target.value})}><option value="QQ">QQ</option><option value="WEIXIN">微信</option></select></label><label>群名称<input required maxLength={200} value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></label></div>
   <label>{form.channel==='QQ'?'机器人 AppID':'机器人账号 ID'}<input required maxLength={160} disabled={!!editing} value={form.accountId} onChange={e=>setForm({...form,accountId:e.target.value})}/></label>
   <label>{editing?.chat_type==='PRIVATE'?'私聊内部标识':form.channel==='QQ'?'平台群 ID（group_openid）':'平台群 ID'}<input required maxLength={160} disabled={!!editing} value={form.externalId} onChange={e=>setForm({...form,externalId:e.target.value})}/></label>
   {editing?.chat_type==='PRIVATE'&&<p>QQ 私聊 · 用户 ID：{editing.target_id}。与其他用户及群的记忆隔离，不使用群概率/能量回复策略，不生成群日报。</p>}
   {editing?.chat_type!=='PRIVATE'&&<label>{form.channel==='QQ'?'预配管理员成员 OpenID（逗号或空格分隔）':'预配管理员 ID（逗号或空格分隔）'}<input maxLength={2000} value={form.adminIds} onChange={e=>setForm({...form,adminIds:e.target.value})}/></label>}
   {editing&&<p>平台、账号与群 ID 固定；切换机器人账号请新增群配置。</p>}
   <label className="check"><input type="checkbox" checked={form.enabled} onChange={e=>setForm({...form,enabled:e.target.checked})}/>启用群文本采集</label><label className="check"><input type="checkbox" checked={form.replyEnabled} onChange={e=>setForm({...form,replyEnabled:e.target.checked})}/>启用机器人回复</label>
   <div className="modal-actions"><button type="button" className="secondary" disabled={busy} onClick={()=>setEditing(undefined)}>取消</button><button className="primary" disabled={busy}>保存配置</button></div>
  </form></Dialog>}
  {detail&&<Dialog label="群聊详情" onClose={()=>setDetail(null)} returnFocus={trigger.current}><h2>群聊详情</h2><dl className="detail-grid">{[['群名称',detail.name],['平台',detail.channel],['机器人账号 / AppID',detail.account_id],['平台群 ID',detail.external_id],['管理员',detail.admin_ids||'未设置'],['采集',detail.enabled?'已启用':'已暂停'],['回复',detail.reply_enabled?'已启用':'已关闭'],['已保存文本',detail.message_count],['记忆片段',detail.memory_count],['最后收到消息',date(detail.last_received_at)],['配置版本',detail.revision]].map(([label,value])=><div key={String(label)}><dt>{label}</dt><dd>{String(value??0)}</dd></div>)}</dl><div className="modal-actions"><button className="secondary" onClick={()=>setDetail(null)}>关闭</button><button className="primary" onClick={()=>{open(detail);setDetail(null);}}>编辑配置</button></div></Dialog>}
  {deleting&&<Dialog label="删除群聊" busy={busy} onClose={()=>setDeleting(null)} returnFocus={trigger.current}><h2>删除群聊</h2>{error&&<p role="alert" className="banner error">{error}</p>}<p>将删除“{deleting.name}”的配置、聊天记录、群记忆、会话、定时任务和报告。此操作不可撤销；只想停止处理时，可编辑并暂停采集。</p><p>{deleting.chat_type==='PRIVATE'?'删除后若收到该用户的新私聊消息，会建立新的已启用私聊。持续停用请取消删除并关闭采集或回复。':'删除后若收到该群的新事件，会再次发现为暂停群。'}</p><label className="delete-confirm">输入群名称确认删除<input autoFocus value={confirmation} onChange={e=>setConfirmation(e.target.value)}/></label><div className="modal-actions"><button className="secondary" disabled={busy} onClick={()=>setDeleting(null)}>取消</button><button className="danger" disabled={busy||confirmation!==deleting.name} onClick={()=>act(async()=>{await api('/groups/'+deleting.id,{method:'DELETE',body:JSON.stringify({confirmName:confirmation,revision:deleting.revision})});setDeleting(null);await load();},'群聊及关联数据已删除')}>确认删除</button></div></Dialog>}
 </>;
}

type OfficialEvent={receivedAt:string;eventType:string;groupOpenid:string;memberOpenid:string;accepted:boolean;duplicate:boolean};
type Connection={transport:string;state:string;error:string;connectedAt?:string;lastEventAt?:string;lastHeartbeatAt?:string;reconnects:number};
function OfficialEvents(){
 const[events,setEvents]=useState<OfficialEvent[]>([]),[connection,setConnection]=useState<Connection|null>(null),[error,setError]=useState('');
 const load=async()=>{try{const[e,c]=await Promise.all([api<OfficialEvent[]>('/qq/official/events'),api<Connection>('/qq/official/connection')]);setEvents(Array.isArray(e)?e:[]);setConnection(c);setError('');}catch(e){setError((e as Error).message);}};
 useEffect(()=>{load();const timer=setInterval(load,3000);return()=>clearInterval(timer);},[]);
 const states:Record<string,string>={STARTING:'正在启动',CONNECTING:'正在连接',AUTHENTICATING:'正在鉴权',ONLINE:'已连接',RECONNECTING:'等待重连',WEBHOOK:'Webhook 模式',DISABLED:'未启用',STOPPED:'已停止'};
 return <section className="panel official-panel"><div className="panel-top"><div><h2>QQ 官方连接与事件</h2><p>连接状态和最近 50 条事件每 3 秒刷新，事件标识重启后清空。</p></div><button className="secondary" onClick={load}><RefreshCw size={15}/>刷新回调</button></div>
  {connection&&<div className="connection-status"><span className={'badge '+(connection.state==='ONLINE'?'green':connection.error?'red':'gray')}>{states[connection.state]??connection.state}</span><span>{connection.transport==='webhook'?'Webhook · 需要公网 HTTPS 回调':'WebSocket · 本地主动连接，无需公网 URL'}</span><span>最近心跳：{date(connection.lastHeartbeatAt)}</span>{connection.error&&<p role="alert">{connection.error} · 查看运行日志；核对 AppID / AppSecret、群聊权限和网络访问。</p>}</div>}
  {error&&<p role="alert" className="form-error">{error}</p>}
  <div className="table-wrap"><table><thead><tr><th>接收时间 / 事件</th><th>group_openid</th><th>成员 OpenID</th><th>处理结果</th></tr></thead><tbody>{events.map((e,i)=><tr key={i}><td>{date(e.receivedAt)}<small>{e.eventType}</small></td><td>{e.groupOpenid||'—'}</td><td>{e.memberOpenid||'—'}</td><td>{e.duplicate?'重复消息':e.accepted?'已采集':'未采集 / 非群消息'}</td></tr>)}</tbody></table>{!events.length&&<p className="empty">尚无事件。WebSocket 显示已连接后，在群里真实 @ 官方机器人；首次发现群需开启采集与回复。选择 Webhook 时还需配置公网回调。</p>}</div>
 </section>;
}
