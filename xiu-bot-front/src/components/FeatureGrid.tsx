import {AnimatedGroup} from './motion-primitives/animated-group';
import {InView} from './motion-primitives/in-view';
import {ArrowUpRight,BookOpen,ChartNoAxesCombined,MessagesSquare,SlidersHorizontal,NotebookPen,PanelsTopLeft} from 'lucide-react';
import {features,docEntry,links} from '../content/site';
const icons=[MessagesSquare,NotebookPen,BookOpen,SlidersHorizontal,PanelsTopLeft,ChartNoAxesCombined];

export default function FeatureGrid(){return <section id="features" className="section wrap" aria-labelledby="features-title">
 <div className="section-heading"><div><p className="section-label">01 / 日常所需</p><h2 id="features-title">从一句提问，<br className="mobile-break"/>到一份可查的记忆。</h2></div><p>聊天、整理、回看。<br/>让小组的上下文慢慢积累。</p></div>
 <InView><AnimatedGroup className="feature-grid">{features.map((feature,index)=>{const Icon=icons[index];const doc=docEntry(feature.doc);const url=links.doc(doc.path);return <article className="feature-card" key={feature.id}><div className="feature-top"><span className="feature-icon"><Icon size={22} aria-hidden="true"/></span><span>0{index+1}</span></div><h3>{feature.title}</h3><p>{feature.description}</p>{url?<a className="text-link" href={url} target="_blank" rel="noopener noreferrer">{doc.title}<ArrowUpRight size={15} aria-hidden="true"/></a>:<a className="text-link" href="#docs">查阅相关文档<ArrowUpRight size={15} aria-hidden="true"/></a>}</article>;})}</AnimatedGroup></InView>
 </section>;}
