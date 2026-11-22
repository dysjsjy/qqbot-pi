import {InView} from './motion-primitives/in-view';
import {ArrowUpRight,BookOpen} from 'lucide-react';
import {docs,docEntry,links} from '../content/site';
import type {DocKey} from '../content/site';

export function DocReference({doc,label}:{doc:DocKey;label?:string}){const entry=docEntry(doc),url=links.doc(entry.path);return url?<a className="text-link" href={url} target="_blank" rel="noopener noreferrer">{label||entry.title}<ArrowUpRight size={15} aria-hidden="true"/></a>:<span className="local-reference">{label||entry.title}：<code>{entry.path}</code><span>在源码目录中查看</span></span>;}
export default function ResourceLinks(){return <section id="docs" className="section wrap" aria-labelledby="docs-title">
 <div className="section-heading"><div><p className="section-label">04 / 有据可查</p><h2 id="docs-title">从第一次启动，<br className="mobile-break"/>到每一次维护。</h2></div><p>配置、边界和实现，都在文档里。<br/>按你现在要做的事，选一个入口。</p></div>
 <InView><div className="resource-grid">{docs.map(doc=>{const url=links.doc(doc.path);return <article className="resource-card" key={doc.key} data-doc={doc.key}><BookOpen size={19} aria-hidden="true"/><h3>{doc.title}</h3><p>{doc.description}</p>{url?<a href={url} target="_blank" rel="noopener noreferrer">阅读文档<span className="sr-only">：{doc.title}</span><ArrowUpRight size={16} aria-hidden="true"/></a>:<div className="resource-local"><code>{doc.path}</code><span>在源码目录中查看</span></div>}</article>;})}</div></InView>
 {links.repository&&<a className="text-link resource-repository" href={links.repository} target="_blank" rel="noopener noreferrer">打开源码仓库<ArrowUpRight size={16} aria-hidden="true"/></a>}</section>;}
