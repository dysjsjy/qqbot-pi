/**
 * [INPUT]: 依赖官网文案、合成对话和部署锚点。
 * [OUTPUT]: 提供首屏介绍、部署入口与纸面会话示例。
 * [POS]: 在静态官网说明核心能力。不读取实例数据。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {AnimatedGroup} from './motion-primitives/animated-group';
import {ArrowDown,ArrowRight,ArrowUpRight,Check,MessageCircle,Sparkles} from 'lucide-react';
import {brand,preview} from '../content/site';

export default function Hero(){return <section className="hero wrap" aria-labelledby="hero-title">
 <div className="hero-copy"><AnimatedGroup className="hero-intro"><p className="eyebrow">YOUR PRIVATE CHAT WORKSPACE</p><h1 id="hero-title">让群聊有回应，<br/>也有记忆<span>。</span></h1><p className="hero-description">{brand.description}</p></AnimatedGroup>
 <div className="hero-actions"><a className="button button-primary" href="#start">开始部署<ArrowRight size={17} aria-hidden="true"/></a><a className="button button-secondary" href="#docs">查看文档<ArrowUpRight size={16} aria-hidden="true"/></a><a className="text-link" href="#console">进入管理台<ArrowUpRight size={15} aria-hidden="true"/></a></div></div>
 <div className="hero-product"><div className="product-heading"><span><MessageCircle size={17} aria-hidden="true"/>一次对话，从已有记忆开始</span><span className="sample-label">示例数据 · 非实时管理台</span></div>
 <div className="conversation-example"><p className="conversation-context">协作小组 <span>／ 官方 QQ 群场景</span></p><div className="question">{preview.memory.question}</div><div className="answer"><span className="answer-author"><Sparkles size={16} aria-hidden="true"/>群知</span><p>{preview.memory.answer}</p><span className="source-note">参考 2 条来源 · 可在管理台查看</span></div></div>
 <a className="product-bottom" href="#preview">查看记忆、日报和链路示例<ArrowDown size={16} aria-hidden="true"/></a></div>
 <ul className="hero-facts" aria-label="项目特点"><li><Check size={15} aria-hidden="true"/>SQLite 本地存储</li><li><Check size={15} aria-hidden="true"/>Markdown 基础记忆</li><li><Check size={15} aria-hidden="true"/>RAG 按需开启</li></ul>
 </section>;}
