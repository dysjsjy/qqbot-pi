import {useState} from 'react';
import {ArrowUpRight} from 'lucide-react';
import {defaultAdminAddress,normalizeAdminAddress} from '../console/address';

export default function ConsoleAccess(){
 const[address,setAddress]=useState(()=>{try{return localStorage.getItem('xiu-bot-admin-url')||defaultAdminAddress();}catch{return defaultAdminAddress();}});
 const[error,setError]=useState('');
 return <section id="console" className="console section wrap" aria-labelledby="console-title"><h2 id="console-title">已部署？<span>直接打开管理台。</span></h2><p>填写你自己的管理台地址。地址保存在当前浏览器；展示站不会索取管理员密码。</p>
  <form onSubmit={event=>{
   event.preventDefault();
   const url=normalizeAdminAddress(address);
   if(!url){setError('请输入完整的 http:// 或 https:// 管理台地址');return;}
   setError('');try{localStorage.setItem('xiu-bot-admin-url',url);}catch{}
   window.location.assign(url);
  }}><label htmlFor="admin-address">管理台地址</label><div className="console-actions"><input id="admin-address" type="url" required value={address} onChange={event=>{setAddress(event.target.value);setError('');}} placeholder="http://localhost:8082"/><button className="button button-primary glass" type="submit">打开管理台 <ArrowUpRight size={17}/></button></div>{error&&<p role="alert">{error}</p>}</form>
 </section>;
}
