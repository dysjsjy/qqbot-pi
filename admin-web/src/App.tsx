/**
 * [INPUT]: 依赖 useConsole 会话、导航定义与各业务页面。
 * [OUTPUT]: 提供纸面工作区、会话选择和完整功能导航。
 * [POS]: 控制台编排层；保留群聊与私聊的业务边界。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useState} from 'react';
import Groups from './Groups';
import Logs from './Logs';
import Memory from './Memory';
import {ReplyPolicy} from './MemorySettings';
import ConsoleLayout from './components/ConsoleLayout';
import {Empty} from './ui';
import {Login} from './Login';
import Schedule from './pages/Schedule';
import Runs from './pages/Runs';
import Traces from './pages/Traces';
import {useConsole} from './useConsole';
import {navigation} from './navigation';
import type {Page} from './types';

export default function App(){
 const state=useConsole();
 const[page,setPage]=useState<Page>('groups');
 useEffect(()=>{
  document.title=state.authenticated?`${navigation.find(item=>item.key===page)!.title} · 群知`:'群知 · 让讨论成为共同记忆';
  window.scrollTo({top:0});
 },[state.authenticated,page]);
 if(state.checking)return <div className="loading"><span className="loading-line"/>正在打开工作空间…</div>;
 if(!state.authenticated)return <Login onLogin={state.onLogin}/>;
 const group=state.groups.find(item=>item.id===state.selected);
 const actions={busy:state.busy,act:state.act};
 const content=()=>{
  if(page==='groups')return <Groups error={state.error} groups={state.groups} load={state.load} qq={state.qq} status={state.status} {...actions}/>;
  if(page==='traces')return <Traces groups={state.groups}/>;
  if(page==='logs')return <Logs/>;
  return <>
   <div className="scope"><label htmlFor="group-scope">当前群聊 / 私聊</label><select id="group-scope" value={state.selected} onChange={event=>state.setSelected(event.target.value)}><option value="" disabled>选择一个会话</option>{state.groups.map(item=><option key={item.id} value={item.id}>{item.name} · {item.chat_type==='PRIVATE'?'私聊':item.channel==='QQ'?'QQ':'微信'}</option>)}</select><span>内容严格限定于所选会话</span></div>
   {!group?<Empty text="先在群管理中添加群聊或接收私聊消息。"/>:
    group.chat_type==='PRIVATE'&&page==='reply'?<div className="panel pad">私聊直接回复每条文本，不使用群聊的 @、概率或能量策略；需要停用时，在群管理中关闭此私聊的机器人回复。</div>:
    group.chat_type==='PRIVATE'&&page==='schedule'?<div className="panel pad">私聊不支持群日报。每日活跃榜也只适用于群聊。</div>:
    page==='reply'?<ReplyPolicy key={group.id} groupId={group.id} {...actions}/>:
    page==='memory'?<Memory key={group.id} group={group} ragEnabled={state.ragEnabled} {...actions}/>:
    page==='schedule'?<Schedule key={group.id} group={group} qq={state.qq} {...actions}/>:<Runs key={group.id} group={group} {...actions}/>}
  </>;
 };
 return <ConsoleLayout page={page} onPage={next=>{setPage(next);state.clearError();}} busy={state.busy} refresh={state.load} logout={state.logout} error={state.error} clearError={state.clearError} notice={state.notice} username={state.username} status={state.status}>{content()}</ConsoleLayout>;
}
