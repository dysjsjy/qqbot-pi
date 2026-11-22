/**
 * [INPUT]: 依赖统一 API、会话 ID 与共享操作反馈。
 * [OUTPUT]: 提供 Markdown 编辑和带版本校验的群回复策略。
 * [POS]: 记忆编辑与回复设置；不修改归档文档。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
﻿import React,{useEffect,useState} from 'react';
import {api} from './api';
import type {Actions,ReplyPolicySettings} from './types';
type EditorActions=Actions&{groupId:string};
export function MarkdownEditor({groupId,busy,act,revision}:EditorActions&{revision:number}){
 const [content,setContent]=useState(''),[loaded,setLoaded]=useState(false);
 useEffect(()=>{
  let stopped=false;const controller=new AbortController();setContent('');setLoaded(false);
  api<{content:string}>(`/groups/${groupId}/memory/markdown`,{signal:controller.signal}).then(value=>{if(!stopped){setContent(value.content);setLoaded(true);}}).catch(failure=>{if(!stopped)act(async()=>{throw failure},'');});
  return()=>{stopped=true;controller.abort();};
 },[groupId,revision]);
 return <section className="panel pad memory-editor"><h2>Markdown 长期记忆</h2><p>人工维护的 MEMORY.md，每次回复重新读取。自动概括单独保存，不会覆盖这里的内容。可直接搜索这份文件。</p><form onSubmit={e=>{e.preventDefault();if(!loaded)return;act(async()=>{await api(`/groups/${groupId}/memory/markdown`,{method:'PUT',body:JSON.stringify({content})});},'Markdown 记忆已保存');}}><label>记忆内容<textarea disabled={busy||!loaded} value={content} maxLength={32000} rows={12} onChange={e=>setContent(e.target.value)} placeholder={'# 群记忆\n\n记录本群长期有用的背景信息…'}/></label><button className="primary" disabled={busy||!loaded}>保存记忆</button></form></section>;
}
export function ReplyPolicy({groupId,busy,act}:EditorActions){
 const [mode,setMode]=useState('MENTION'),[probability,setProbability]=useState(0.1),[gain,setGain]=useState(1),[cost,setCost]=useState(10),[energy,setEnergy]=useState(0),[loaded,setLoaded]=useState(false);
 const [revision,setRevision]=useState(0),[refresh,setRefresh]=useState(0);
 useEffect(()=>{
  let cancelled=false;setLoaded(false);
  api<ReplyPolicySettings>(`/groups/${groupId}/reply-policy`).then(settings=>{
   if(cancelled)return;
   setMode(settings.mode);setProbability(settings.probability);setGain(settings.energy_gain);setCost(settings.energy_cost);setEnergy(settings.energy);setRevision(settings.revision);setLoaded(true);
  }).catch(error=>{if(!cancelled)act(async()=>{throw error},'');});
  return()=>{cancelled=true;};
 },[groupId,refresh]);
 return <section className="panel pad policy"><h2>聊天回复方式</h2><p>以下设置控制普通文本消息。能量模式每条普通消息增加能量，达到消耗值时回复并扣除能量。本群管理员可真实 @ 当前机器人后发送 <code>/bot help</code> 查看管理命令。</p>{!loaded&&<p role="status">正在加载回复设置…</p>}<form onSubmit={e=>{e.preventDefault();act(async()=>{const r=await api<ReplyPolicySettings>(`/groups/${groupId}/reply-policy`,{method:'PUT',body:JSON.stringify({mode,probability,energyGain:gain,energyCost:cost,revision})});setEnergy(r.energy);setRevision(r.revision);},'回复方式已保存');}}><label>回复模式<select disabled={busy||!loaded} value={mode} onChange={e=>setMode(e.target.value)}><option value="MENTION">仅 @ 回复</option><option value="PROBABILITY">概率回复</option><option value="ENERGY">能量回复</option></select></label>{mode==='PROBABILITY'&&<label>回复概率（0–1）<input type="number" min="0" max="1" step="any" value={probability} onChange={e=>setProbability(Number(e.target.value))}/></label>}{mode==='ENERGY'&&<><label>每条消息增加能量<input type="number" min="1" max="1000" value={gain} onChange={e=>setGain(Number(e.target.value))}/></label><label>每次回复消耗能量<input type="number" min="1" max="1000" value={cost} onChange={e=>setCost(Number(e.target.value))}/></label><p>当前能量：{energy}；设置变更时才会清零。</p></>}<div className="action-row"><button className="primary" disabled={busy||!loaded}>保存回复方式</button><button className="secondary" type="button" disabled={busy} onClick={()=>setRefresh(value=>value+1)}>重新加载回复设置</button></div></form></section>;
}
