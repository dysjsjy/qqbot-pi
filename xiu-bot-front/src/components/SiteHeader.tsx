/**
 * [INPUT]: 依赖折页 Z、页面锚点与可选仓库链接。
 * [OUTPUT]: 提供官网品牌、响应式导航与正文跳转。
 * [POS]: 桌面纸面侧栏；窄屏转为正文上方的导航。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useState} from 'react';
import {AnimatedBackground} from './motion-primitives/animated-background';
import {ArrowUpRight, BookOpen, Github, LayoutGrid, PanelsTopLeft, Rocket, SquareTerminal} from 'lucide-react';
import {links} from '../content/site';

const mark=`${import.meta.env.BASE_URL}favicon.svg`;
const navigation=[
 {id:'features',label:'功能介绍',icon:LayoutGrid},
 {id:'preview',label:'产品预览',icon:PanelsTopLeft},
 {id:'start',label:'部署指南',icon:Rocket},
 {id:'docs',label:'文档',icon:BookOpen},
 {id:'console',label:'管理台',icon:SquareTerminal},
];

export function Logo(){return <a className="logo" href="#top" aria-label="群知 xiu-bot 首页"><img className="logo-mark" src={mark} alt="" width="34" height="34"/>群知 <span>xiu-bot</span></a>;}

export default function SiteHeader(){
 const [active,setActive]=useState(location.hash);
 useEffect(()=>{const update=()=>setActive(location.hash);addEventListener('hashchange',update);return()=>removeEventListener('hashchange',update);},[]);
 return <>
  <a className="skip-link" href="#main">跳到正文</a>
  <header className="header">
   <Logo/>
   <p className="nav-caption">产品与部署</p>
   <nav className="nav" aria-label="主导航"><AnimatedBackground value={active}>{navigation.map(({id,label,icon:Icon})=><a key={id} data-id={`#${id}`} href={`#${id}`} aria-current={active===`#${id}`?'location':undefined}><Icon size={17} aria-hidden="true"/>{label}</a>)}</AnimatedBackground></nav>
   {links.repository&&<a className="repository-link" href={links.repository} target="_blank" rel="noopener noreferrer"><Github size={17} aria-hidden="true"/>GitHub<ArrowUpRight size={14} aria-hidden="true"/></a>}
   <div className="sidebar-note"><img src={mark} alt="" width="36" height="36"/><p>从一次讨论，<br/>到无数次启发。</p><span>GOOD IDEAS DESERVE TO STAY.</span></div>
   <p className="sidebar-caption">QQ × 微信 · 自行部署</p>
  </header>
 </>;
}
