/**
 * [INPUT]: 依赖统一 API、浏览器会话与工作区状态。
 * [OUTPUT]: 提供会话、群选择、状态刷新和操作反馈。
 * [POS]: 控制台唯一共享状态源，App 编排页面。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useCallback,useEffect,useState} from 'react';
import {api,type WorkspaceStatus} from './api';
import type {Group,QqGateway} from './types';

export function useConsole(){
 const[authenticated,setAuthenticated]=useState(false),[checking,setChecking]=useState(true);
 const[username,setUsername]=useState('admin'),[status,setStatus]=useState<WorkspaceStatus|null>(null);
 const[groups,setGroups]=useState<Group[]>([]),[selected,setSelected]=useState('');
 const[error,setError]=useState(''),[notice,setNotice]=useState(''),[busy,setBusy]=useState(false);
 const[ragEnabled,setRagEnabled]=useState(true),[qq,setQq]=useState<QqGateway>({accountId:'',allowActiveMessages:false});
 const fail=useCallback((failure:unknown)=>setError(failure instanceof Error?failure.message:'操作失败'),[]);
 const load=useCallback(async()=>{
  try{
   const[data,status]=await Promise.all([api<Group[]>('/groups'),api<WorkspaceStatus>('/status')]);
   setStatus(status);
   setRagEnabled(status.ragEnabled!==false);
   if(status.gateways?.QQ)setQq(status.gateways.QQ);
   setGroups(data);setSelected(previous=>data.some(group=>group.id===previous)?previous:data[0]?.id??'');setError('');
  }catch(failure){fail(failure);}
 },[fail]);
 const act=useCallback(async(work:()=>Promise<unknown>,success='操作已完成')=>{
  setBusy(true);setError('');setNotice('');
  try{await work();setNotice(success);}catch(failure){fail(failure);}finally{setBusy(false);}
 },[fail]);
 const onLogin=async()=>{const user=await api<{username:string}>('/me');setUsername(user.username);setAuthenticated(true);setError('');await load();};
 const logout=()=>act(async()=>{await api('/logout',{method:'POST'});setAuthenticated(false);setGroups([]);setStatus(null);},'');
 useEffect(()=>{
  let stopped=false;
  api<{username:string}>('/me').then(user=>{if(!stopped){setUsername(user.username);setAuthenticated(true);void load();}}).catch(()=>{}).finally(()=>{if(!stopped)setChecking(false);});
  const expired=()=>{setAuthenticated(false);setGroups([]);setStatus(null);};
  window.addEventListener('session-expired',expired);
  return()=>{stopped=true;window.removeEventListener('session-expired',expired);};
 },[load]);
 useEffect(()=>{
  if(!notice)return;
  const timer=setTimeout(()=>setNotice(''),4000);
  return()=>clearTimeout(timer);
 },[notice]);
 return{authenticated,checking,username,status,groups,selected,setSelected,error,clearError:()=>setError(''),notice,busy,ragEnabled,qq,load,act,onLogin,logout};
}
