/**
 * [INPUT]: 读取部署选项、复制状态与公开资源链接。
 * [OUTPUT]: 渲染本机与 Docker 的配置和启动步骤。
 * [POS]: 官网部署入口。完整操作指向发布版 README。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {AnimatedBackground} from './motion-primitives/animated-background';
import {useDeployment} from '../deployment/useDeployment';
import {deployments} from '../deployment/options';
import {DocReference} from './ResourceLinks';
import {links} from '../content/site';

export default function DeploymentGuide(){
 const{selected,select,copy,copying,copyStatus}=useDeployment();
 const{key,config,command}=selected;
 return <section id="start" className="project-start section wrap" aria-labelledby="start-title">
  <p className="section-label">03 / 开始使用</p><h2 id="start-title">把它带进，<br className="mobile-break"/>你的小组。</h2>
  <p className="section-intro">先取得项目源码，在项目根目录执行。{links.repository?<a className="text-link" href={links.repository} target="_blank" rel="noopener noreferrer">获取源码</a>:<>源码项目：<code>qqbot-pi</code>；仓库外链尚未配置。</>}已有实例请先阅读下方升级说明，保留配置与数据。</p>
  <div className="deployment-tabs" role="tablist" aria-label="部署方式"><AnimatedBackground value={key}>{deployments.map((option,index)=><button type="button" key={option.key} data-id={option.key} id={'tab-'+option.key} role="tab" aria-controls="deployment-panel" aria-selected={key===option.key} tabIndex={key===option.key?0:-1} onClick={()=>select(option.key)} onKeyDown={event=>{
   if(!['ArrowRight','ArrowLeft','Home','End'].includes(event.key))return;
   event.preventDefault();const last=deployments.length-1;
   const target=event.key==='Home'?0:event.key==='End'?last:(index+(event.key==='ArrowRight'?1:last))%deployments.length;
   select(deployments[target].key);document.getElementById('tab-'+deployments[target].key)?.focus();
  }}>{option.label}</button>)}</AnimatedBackground></div>
  <div className="deployment-panel" role="tabpanel" id="deployment-panel" aria-labelledby={'tab-'+key}>
   <ol className="deployment-steps">
    <li><h3>准备环境</h3><p>{key==='local'?'安装 JDK 17 / 21、Node.js 22.19+，在项目根目录打开 PowerShell。':'安装并启动 Docker Engine 与 Compose。镜像准备 Java、Node 和管理台，无需在宿主机另装运行时。'}核心使用 SQLite 本地文件，无需独立数据库服务。</p></li>
    <li><h3>填写配置</h3><p>首次安装才将 <code>{config}</code> 复制为 <code>.env</code>，填写模型服务商、模型 ID 和密钥。{key==='local'?'启动脚本会引导设置管理员密码，官方模板启用 QQ，需填写 AppID / AppSecret。':'模板默认关闭 QQ 和微信；启用官方 QQ 时填写 AppID / AppSecret。按 README.md 的 Docker 部署说明生成 BCrypt 哈希，写入 ADMIN_PASSWORD_HASH。'}已有 <code>.env</code> 时按需编辑，不直接覆盖。Markdown 搜索无需 embedding 服务。</p></li>
    <li><h3>启动并验证</h3><div className="command-block"><pre>{command}</pre><button type="button" disabled={copying} aria-busy={copying} onClick={copy}>{copying?'正在复制…':'复制命令'}</button></div><p role="status" className="copy-status">{copyStatus}</p><p>{key==='local'?'本地官方模板端口为 8082。':'Docker 默认管理台为 8080，展示站为 8081；BOT_PORT / FRONT_PORT 控制宿主映射，容器 PORT 固定为 8080。'}先检查健康状态并登录管理台，启用目标群采集和回复；在群中 @ 机器人验证 <code>/help</code>，再测试模型问答。</p></li>
   </ol>
   <details className="deployment-help"><summary>接入和使用时还需要知道什么？</summary><p>QQ 使用官方应用凭据，默认 WebSocket 由实例主动连接平台；选择 HTTPS 回调才需公网回调地址。微信需要独立群消息网关。首次发现的群默认暂停，主动日报推送需要平台权限；官网不替代真实平台和模型连通性验证。</p><p>SQLite 与状态目录需一起备份，Docker 持久化到 state 卷。旧数据库与个人账号身份不自动转换，升级前先停机备份并阅读升级说明。</p></details>
  </div><div className="deployment-docs"><DocReference doc="startup" label="完整启动说明"/><DocReference doc="upgrade" label="已有实例：v0.2 升级说明"/></div>
 </section>;
}
