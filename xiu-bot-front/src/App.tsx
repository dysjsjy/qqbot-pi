/**
 * [INPUT]: 依赖官网导航、产品内容与静态部署组件。
 * [OUTPUT]: 组合独立侧栏和纸面正文。
 * [POS]: 官网布局入口。示例与管理台实例保持隔离。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {ArrowRight} from 'lucide-react';
import SiteHeader from './components/SiteHeader';
import Hero from './components/Hero';
import FeatureGrid from './components/FeatureGrid';
import ProductPreview from './components/ProductPreview';
import DeploymentGuide from './components/DeploymentGuide';
import ResourceLinks from './components/ResourceLinks';
import Faq from './components/Faq';
import ConsoleAccess from './components/ConsoleAccess';
import SiteFooter from './components/SiteFooter';

export default function App(){return <div id="top" className="site-shell">
 <SiteHeader/>
 <div className="site-paper">
  <div className="site-ribbon"><a href="#preview"><span>你好，群知</span> 让群里的每一个好想法，都有下文。<ArrowRight size={13} aria-hidden="true"/></a></div>
  <main id="main" tabIndex={-1}>
   <Hero/>
   <section id="about" className="about wrap" aria-label="项目简介"><p className="section-label">自己的小组，自己的上下文。</p><p><strong>xiu-bot</strong> 是可自行部署的群聊 AI 助手，源码项目名为 <code>qqbot-pi</code>。用官方 QQ 应用接入日常对话，用 Markdown 保存值得留下的事；微信群可通过独立网关桥接。模型可能调用外部服务，请按自己的场景配置。</p></section>
   <FeatureGrid/><ProductPreview/><DeploymentGuide/><ResourceLinks/><Faq/><ConsoleAccess/>
  </main>
  <SiteFooter/>
 </div>
</div>;}
