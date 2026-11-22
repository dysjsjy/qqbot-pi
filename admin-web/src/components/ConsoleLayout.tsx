/**
 * [INPUT]: 依赖导航定义、工作区统计与共享品牌组件。
 * [OUTPUT]: 提供浅色侧栏、标题介绍和纯色业务工作面。
 * [POS]: 登录后页面的视觉外壳；不执行管理请求。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import type {ReactNode} from 'react';
import {ArrowUpRight,RefreshCw} from 'lucide-react';
import {navigation} from '../navigation';
import {Banner,BrandMark,Ribbon,Sidebar} from '../ui';
import type {WorkspaceStatus} from '../api';
import type {Page} from '../types';

type Props={page:Page;onPage:(page:Page)=>void;busy:boolean;refresh:()=>Promise<void>;logout:()=>Promise<unknown>;error:string;clearError:()=>void;notice:string;children:ReactNode;username:string;status:WorkspaceStatus|null};
export default function ConsoleLayout({page,onPage,busy,refresh,logout,error,clearError,notice,children,username,status}:Props){
 const current=navigation.find(item=>item.key===page)!;
 return <div className="shell"><a className="skip-link" href="#console-main">跳到正文</a>
  <Sidebar page={page} onPage={onPage} username={username} status={status} onLogout={logout}/>
  <main className="workspace-main" id="console-main" tabIndex={-1}>
   <Ribbon><span>群知工作空间</span> 留下有价值的讨论，让知识自然生长。 <ArrowUpRight size={13}/></Ribbon>
   <div className="page-bar"><span>工作空间 <span className="slash">/</span> {current.title}</span><button className="quiet-button" disabled={busy} onClick={refresh}><RefreshCw size={13}/>刷新数据</button></div>
   <section className="page-intro paper-glow heading"><p className="eyebrow">YOUR COMMUNITIES, CONNECTED.</p><h1 tabIndex={-1}>{current.heading}<span className="title-period">.</span></h1><p className="intro-copy">{current.description}</p><span className="intro-note"><BrandMark className="tiny-mark"/> 群里的知识，值得被记住</span></section>
   <div className="page-body">{error&&<Banner error onClose={clearError}>{error}</Banner>}{notice&&<Banner>{notice}</Banner>}{children}</div>
   <footer><span><b>群知</b> · 让知识留在讨论之后</span><span>{status?.preview?'界面验收 · 示例数据':'QQ / 微信'} <BrandMark className="footer-mark"/> {status?.timezone??'Asia/Shanghai'}</span></footer>
  </main>
 </div>;
}
