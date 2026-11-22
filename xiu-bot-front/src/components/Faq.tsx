import {Plus} from 'lucide-react';
import {faqs} from '../content/site';
import {DocReference} from './ResourceLinks';
export default function Faq(){return <section id="faq" className="section wrap faq-section" aria-labelledby="faq-title"><div><p className="section-label">05 / 开始之前</p><h2 id="faq-title">你可能还想知道。</h2><p className="section-intro">先了解边界，<br/>再搭建适合自己的实例。</p></div><div className="faq-list">{faqs.map(faq=><details key={faq.id}><summary>{faq.question}<Plus size={18} aria-hidden="true"/></summary><div className="faq-answer"><p>{faq.answer}</p>{faq.doc&&<DocReference doc={faq.doc}/>}</div></details>)}</div></section>;}
