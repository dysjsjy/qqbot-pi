/**
 * [INPUT]: 从 links.ts 读取公开链接配置。
 * [OUTPUT]: 提供产品文案、发布说明路径和合成预览。
 * [POS]: 官网各分区共用的静态内容。不请求服务。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {resolveLinks} from './links';
export type DocKey = 'startup' | 'qq' | 'memory' | 'admin' | 'operations' | 'architecture' | 'upgrade' | 'plugins';
export type DocEntry = {key:DocKey; title:string; description:string; path:string};
export type PreviewKey = 'memory' | 'summary' | 'trace';
export type FeatureEntry = {id:string; title:string; description:string; doc:DocKey};
export type FaqEntry = {id:string; question:string; answer:string; doc?:DocKey};
export const links=resolveLinks(import.meta.env);
export const brand={title:'xiu-bot — 可自行部署的 QQ 群聊 AI 助手',description:'可自行部署的 QQ 群聊 AI 助手，提供 Markdown 记忆、昨日日报与可视化管理台。支持 QQ 官方机器人接入，微信可通过独立网关桥接。'};
export const docs:DocEntry[]=[
 {key:'startup',title:'从这里开始',description:'环境、配置、构建与第一次回复。',path:'README.md#本地运行'},
 {key:'qq',title:'连接官方 QQ',description:'应用凭据、事件订阅和平台权限。',path:'README.md#官方-qq-接入'},
 {key:'memory',title:'理解记忆',description:'Markdown、检索、上下文和摘要策略。',path:'README.md#存储与记忆'},
 {key:'admin',title:'使用管理台',description:'群配置、记忆编辑与日常操作。',path:'README.md#管理台'},
 {key:'operations',title:'维护你的实例',description:'备份、恢复、状态和部署。',path:'README.md#运维与升级'},
 {key:'architecture',title:'走进代码',description:'项目目录、模块职责与工作边界。',path:'AGENTS.md'},
 {key:'upgrade',title:'升级到 v0.2',description:'保留旧数据，核对配置与迁移限制。',path:'README.md#升级现有实例'},
 {key:'plugins',title:'了解扩展边界',description:'微信桥接契约与投递扩展点。',path:'README.md#网关与扩展'},
];
export const features:FeatureEntry[]=[
 {id:'conversation',title:'对话，接在日常里',description:'通过官方 QQ 机器人在群里交流，也支持官方私聊。微信群可通过独立网关桥接。',doc:'qq'},
 {id:'memory',title:'有用的内容，留下来',description:'Markdown 是默认基础记忆。人工背景、原始记录与摘要各有位置，群与私聊按范围隔离。',doc:'memory'},
 {id:'summary',title:'昨天的讨论，有脉络',description:'按北京时间整理昨日已采集的群消息，在管理台阅读完整日报；主动群推送取决于平台权限。',doc:'startup'},
 {id:'reply',title:'回复节奏，由你决定',description:'为群设置仅 @、概率或能量回复策略，按场景调整采集与回复开关。',doc:'admin'},
 {id:'console',title:'一个管理台，照看日常',description:'管理群与官方私聊、编辑记忆、查找来源、查看日报与运行日志。数据保存在自己的实例。',doc:'admin'},
 {id:'trace',title:'每次生成，都有迹可循',description:'查看入口、上下文、模型与投递链路，结合用量和费用记录排查问题；未知金额不按零计算。',doc:'architecture'},
];
export const faqs:FaqEntry[]=[
 {id:'qq',question:'需要登录我的个人 QQ 吗？',answer:'不需要。核心只接入 QQ 官方机器人，使用开放平台的 AppID / AppSecret，通过 WebSocket 或 HTTPS 回调接收事件。支持群聊及官方私聊，不提供个人 QQ 登录。',doc:'qq'},
 {id:'weixin',question:'微信也可以直接扫码使用吗？',answer:'不可以。项目保留微信群消息桥接契约，需要自行准备具备授权能力的独立网关；不提供微信登录、私有协议或微信私聊支持。',doc:'architecture'},
 {id:'database',question:'需要独立数据库吗？旧版本如何升级？',answer:'核心使用 SQLite 本地文件，无需独立数据库服务或数据库密码。升级前停机并备份数据库和状态目录；旧 PostgreSQL 数据没有自动迁移，旧个人账号身份也不会转换为官方应用身份。',doc:'upgrade'},
 {id:'embedding',question:'没有 embedding 服务，还能用记忆吗？',answer:'可以。关闭 RAG 时仍可保存 Markdown、归档、词法检索并使用 /memory。只有可选的向量增强需要 embedding；上下文预算与摘要策略可以按需配置。',doc:'memory'},
 {id:'data',question:'数据存在哪里？会全部留在本机吗？',answer:'SQLite 与 Markdown 保存在自己的实例，Docker 使用 state 卷。模型与可选 embedding 可能调用外部服务，自行部署不代表所有内容都只在本机处理。请按文档定期备份并管理数据保留期。',doc:'operations'},
 {id:'model',question:'模型如何配置？部署后就能回答吗？',answer:'需要填写模型服务商、模型 ID 和密钥，兼容接口还需正确的基础地址。先验证健康状态和 /help，再测试模型回答；官网示例不会连接真实模型。',doc:'startup'},
 {id:'address',question:'为什么管理台地址需要自己填写？',answer:'官网是独立静态站，不是公共机器人服务。填写你部署的实例地址；它仅保存在当前浏览器，管理员密码只在你自己的管理台输入。',doc:'admin'},
 {id:'plugins',question:'其他数据库、个人 QQ 可以用插件扩展吗？',answer:'这是未来独立插件的方向，当前没有交付这些插件。现有扩展点仅支持通过 Spring bean 注册投递适配器，不代表完整新渠道支持；尚无插件市场、热加载或稳定的存储插件 SPI。',doc:'plugins'},
];
export function docEntry(key:DocKey){return docs.find(doc=>doc.key===key)!;}
// Synthetic examples only; no service calls or real identities.
export const preview={
 memory:{background:'小组约定：每周五整理一次进展，结论先写，待办随后。',question:'这次发布前，还有哪些事情要确认？',answer:'发布前需要确认演示流程和文档检查。可以先过一遍演示，再按检查单补齐说明。',sources:['原始记录 · 演示流程确认','人工记忆 · 小组协作约定']},
 summary:{title:'昨日小组日报',topics:['演示流程已完成第一轮走查。','文档检查单补充了配置与恢复步骤。'],action:'发布前，再验证一次完整的新用户启动流程。'},
 trace:[{name:'消息入口',detail:'接收与校验',time:'12 ms',width:12},{name:'上下文',detail:'历史与记忆检索',time:'46 ms',width:24},{name:'模型生成',detail:'生成回答',time:'1,240 ms',width:86},{name:'平台投递',detail:'收到发送回执',time:'82 ms',width:34}],
};
