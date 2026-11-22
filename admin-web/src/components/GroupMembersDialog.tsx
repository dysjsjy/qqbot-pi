/**
 * [INPUT]: 依赖群与账号快照、管理 API 和原生对话框。
 * [OUTPUT]: 提供成员分页、只读画像状态和管理员授权操作。
 * [POS]: 群管理详情；成员数据只显示在触发入口对应的群范围。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useLayoutEffect,useRef,useState} from 'react';
import {api} from '../api';
import type {Actions,Group,GroupMemberDetail,GroupMemberPage} from '../types';
import Dialog from './Dialog';

const PAGE_SIZE=25;
const seen=(value:string|null)=>value?new Date(value).toLocaleString('zh-CN'):'统计期内无发言';

export default function GroupMembersDialog({group,busy,act,onClose,returnFocus}:Actions&{group:Group;onClose:()=>void;returnFocus:HTMLElement|null}){
 const [days,setDays]=useState(7),[sort,setSort]=useState('messages'),[adminOnly,setAdminOnly]=useState(false),[offset,setOffset]=useState(0);
 const [search,setSearch]=useState(''),[query,setQuery]=useState(''),[page,setPage]=useState<GroupMemberPage|null>(null),[refresh,setRefresh]=useState(0);
 const [selected,setSelected]=useState<string|null>(null),[detail,setDetail]=useState<GroupMemberDetail|null>(null),[confirm,setConfirm]=useState(false);
 const [error,setError]=useState(''),[notice,setNotice]=useState('');
 const heading=useRef<HTMLHeadingElement>(null),detailTrigger=useRef<HTMLElement|null>(null);
 const root=`/groups/${group.id}/members`;

 useEffect(()=>{
  const controller=new AbortController();
  const report=(failure:Error)=>{if(!controller.signal.aborted)setError(failure.message);};
  setError('');
  if(selected!==null){
   setDetail(null);
   api<GroupMemberDetail>(`${root}/detail?${new URLSearchParams({senderId:selected})}`,{signal:controller.signal})
    .then(value=>{if(!controller.signal.aborted)setDetail(value);}).catch(report);
  }else{
   const params=new URLSearchParams({days:String(days),q:query,sort,adminOnly:String(adminOnly),limit:String(PAGE_SIZE),offset:String(offset)});
   api<GroupMemberPage>(`${root}?${params}`,{signal:controller.signal})
    .then(value=>{if(!controller.signal.aborted){setPage(value);if(offset>0&&offset>=value.total)setOffset(Math.max(0,offset-PAGE_SIZE));}}).catch(report);
  }
  return()=>controller.abort();
 },[root,days,query,sort,adminOnly,offset,selected,refresh]);

 useLayoutEffect(()=>{
  if(selected!==null)heading.current?.focus();
  else if(detailTrigger.current){
   const previous=detailTrigger.current;
   (document.getElementById(previous.id)??heading.current)?.focus();
   detailTrigger.current=null;
  }
 },[selected]);

 const update=()=>{
  if(!detail)return;
  void act(async()=>{
   const granted=!detail.member.admin;
   setError('');setNotice('');
   try{
    const result=await api<{admin:boolean;revision:number}>(`${root}/admin`,{method:'PUT',body:JSON.stringify({senderId:detail.member.senderId,granted,revision:detail.revision})});
    setDetail({...detail,revision:result.revision,member:{...detail.member,admin:result.admin}});
    setConfirm(false);setNotice(granted?'已设为当前机器人管理员。':'已撤销当前机器人管理员。');
   }catch(failure){setError((failure as Error).message);throw failure;}
  },'');
 };

 return <Dialog label="活跃成员" busy={busy} onClose={onClose} returnFocus={returnFocus}>
  <h2 ref={heading} tabIndex={-1}>{selected===null?'活跃成员':'成员详情'}</h2>
  <p className="activity-scope">{group.name} · {group.channel==='QQ'?'QQ':'微信'}机器人 {group.account_id}</p>
  {error&&<p className="banner error" role="alert">{error} <button type="button" className="text-button" disabled={busy} onClick={()=>{setConfirm(false);setRefresh(value=>value+1);}}>重新加载</button></p>}
  {selected===null?<>
   <p>仅显示本群已采集的发言。未发言、未采集或超过保留期的成员不进入活跃列表；QQ 不一定推送全部群消息。</p>
   <form className="member-filters" onSubmit={event=>{event.preventDefault();setOffset(0);setQuery(search);}}>
    <label>搜索成员<input maxLength={200} placeholder="昵称或成员 ID" value={search} onChange={event=>setSearch(event.target.value)}/></label>
    <div className="form-row">
     <label>统计范围<select value={days} onChange={event=>{setOffset(0);setDays(Number(event.target.value));}}><option value={7}>近 7 天</option><option value={30}>近 30 天</option><option value={90}>近 90 天</option></select></label>
     <label>排序<select value={sort} onChange={event=>{setOffset(0);setSort(event.target.value);}}><option value="messages">发言数</option><option value="recent">最近发言</option></select></label>
    </div>
    <div className="action-row"><label className="check"><input type="checkbox" checked={adminOnly} onChange={event=>{setOffset(0);setAdminOnly(event.target.checked);}}/>仅看已配置管理员</label><button className="secondary">搜索</button></div>
   </form>
   {page?<>
    <ul className="member-list" aria-label="成员列表">{page.members.map(member=><li key={member.senderId}>
     <button type="button" id={`member-${encodeURIComponent(member.senderId)}`} className="text-button" onClick={event=>{
      detailTrigger.current=event.currentTarget;setDetail(null);setNotice('');setConfirm(false);setSelected(member.senderId);
     }}><strong>{member.senderName}</strong><small>{member.senderId}</small></button>
     <span>{member.messageCount} 条</span>{member.admin&&<span className="badge green">管理员</span>}
    </li>)}</ul>
    {!page.members.length&&<p className="muted">没有符合条件的成员。</p>}
    <div className="action-row"><button className="secondary" disabled={offset===0} onClick={()=>setOffset(Math.max(0,offset-PAGE_SIZE))}>上一页</button><span>共 {page.total} 人</span><button className="secondary" disabled={offset+PAGE_SIZE>=page.total} onClick={()=>setOffset(offset+PAGE_SIZE)}>下一页</button></div>
   </>:!error&&<p role="status">正在读取成员…</p>}
   <div className="modal-actions"><button className="secondary" onClick={onClose}>关闭</button></div>
  </>:<>
   {detail?<>
    <dl className="detail-grid">{[['昵称',detail.member.senderName],['成员 ID',detail.member.senderId],['近 7 天',`${detail.messages7d} 条`],['近 30 天',`${detail.messages30d} 条`],['近 90 天',`${detail.messages90d} 条`],['最后发言',seen(detail.member.lastAt)],['机器人管理员',detail.member.admin?'已配置':'未配置'],['成员画像',detail.optedOut?'已退出':detail.hasProfile?'已建档':'未建档']].map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
    {notice&&<p className="banner" role="status">{notice}</p>}
    {confirm&&<p className="banner">确认{detail.member.admin?'撤销':'授予'}「{detail.member.senderName}」在「{detail.groupName}」对机器人 {detail.accountId} 的管理员权限？不影响其他群或机器人。</p>}
   </>:!error&&<p role="status">正在读取成员详情…</p>}
   <div className="modal-actions">
    <button className="secondary" disabled={busy} onClick={()=>{setSelected(null);setDetail(null);setError('');setNotice('');setConfirm(false);}}>返回列表</button>
    {confirm&&<button className="secondary" disabled={busy} onClick={()=>setConfirm(false)}>取消确认</button>}
    {detail&&<button className={detail.member.admin?'danger':'primary'} disabled={busy} onClick={confirm?update:()=>setConfirm(true)}>{confirm?'确认'+(detail.member.admin?'撤销':'授权'):detail.member.admin?'撤销管理员':'设为管理员'}</button>}
   </div>
  </>}
 </Dialog>;
}
