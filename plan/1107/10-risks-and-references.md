# 10 MVP 风险、未决项与资料依据

## 1. 必须关注的风险

| 风险 | 对闭环的影响 | MVP处理 |
| --- | --- | --- |
| 接入只提供@事件 | 无法分析全天群聊 | M0用未@文本实测，不通过就换一个现成路线 |
| 不允许主动发群 | 09:00报告无法自动送达 | 无用户新消息条件下测试；不能复用过期被动回复凭据 |
| 微信群网关未确定/不可用 | 双平台目标尚不能实现 | M0限时选型，不自研登录协议；未通过标BLOCKED，QQ和核心模块可继续 |
| 网关断线不能补历史 | 日报来源不完整 | 记录缺口和首次采集时间，标题及覆盖说明如实展示 |
| 用RAG Top-K生成日报 | 遗漏大量当天讨论 | 日报按日期读全部原文，分批有覆盖清单 |
| 临时会话与群记忆混淆 | new误删数据、跨成员/跨群串线 | 群内共享RAG、按成员隔离pi；明确generation与memoryEpoch |
| embedding故障或换模型 | 记忆丢失/向量不可比 | 原文先落库，索引有状态；MVP固定单模型版本 |
| 手动/定时/重启重复执行 | 重复成本和重复日报 | group+type+date唯一键，Outbox，UNKNOWN不自动重发 |
| pi文档与发布包不同 | 结束/取消事件判断错误 | M0固定可用包和RPC样本，只支持一种版本 |
| 群文本含指令或HTML | 越权检索、错误操作、页面脚本 | Java强制scope，工具关闭，管理员鉴权，安全文本渲染 |
| 大群超过模型预算 | 截断全文却声称完整 | MVP明确消息/字符/调用上限，超限记录而不伪装完整日报 |

## 2. 尚待实施时落定

- pi具体发布包/Node版本、生成模型和上下文上限。
- QQ官方能力是否足够；若不够，选定一个OneBot 11网关版本。
- 微信群网关产品、版本、登录方式、可用接口及费用；尚无真实可用性结论。
- embedding模型与维数、中文检索阈值；不凭空给通用距离阈值。
- 授权群、稳定管理员ID、部署主机、TLS及凭据来源。

这些属于M0的有限验证任务，不扩大为平台研发。MVP路线确定后无需同时实现所有候选协议。

## 3. 原有资料的适用范围

下面资料来自上一轮2026-11-07公开文档查阅，本轮没有安装或实测任何平台。新需求改变了接入目标，旧服务号材料只作排除/对照依据。

| 资料 | 链接 | 对本版的意义 |
| --- | --- | --- |
| pi固定文档基线 | [提交085c84e](https://github.com/earendil-works/pi/commit/085c84e98e7f4c950f2be606466fff4f75d8fe7c) | 原pi-mono当前上游命名依据；查阅源码版本字段1.5.0不等于安装验证 |
| pi RPC | [rpc.md](https://github.com/earendil-works/pi/blob/085c84e98e7f4c950f2be606466fff4f75d8fe7c/packages/coding-agent/docs/rpc.md) | Java可用子进程JSONL控制 |
| pi命令与事件 | [rpc-commands.md](https://github.com/earendil-works/pi/blob/085c84e98e7f4c950f2be606466fff4f75d8fe7c/packages/coding-agent/docs/rpc-commands.md)、[json.md](https://github.com/earendil-works/pi/blob/085c84e98e7f4c950f2be606466fff4f75d8fe7c/packages/coding-agent/docs/json.md) | prompt接受不等于完成；读取最终事件、取消 |
| pi命令行 | [cli.md](https://github.com/earendil-works/pi/blob/085c84e98e7f4c950f2be606466fff4f75d8fe7c/packages/coding-agent/docs/cli.md) | 会话与工具/资源关闭配置 |
| QQ官方消息概述 | [消息收发概述](https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html) | 存在不同群事件和主动/被动发送规则；实际账号权限仍须验证 |
| QQ回调 | [Webhook方式](https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/webhook.html) | 仅当选官方路线时适用 |
| 微信服务号推送 | [消息与事件推送](https://developers.weixin.qq.com/doc/service/guide/dev/push/index.md) | 服务号产品的一对一接入，不能作为普通微信群能力证据 |
| 服务号客服消息 | [客服消息介绍](https://developers.weixin.qq.com/doc/service/guide/product/kf/intro.md) | 窗口/额度是该产品规则，本版不将其套用为微信群协议 |

NapCat/OneBot、微信群网关、pgvector具体版本兼容与embedding供应商能力均在M0补实际资料和样本。本计划的架构建议不意味着这些第三方能力已经验证。

## 4. 实施证据清单

```text
代码提交 / Java / Spring Boot / Maven / React / Vite：
PostgreSQL / pgvector / Flyway：
Node / pi包 / lockfile / RPC样本：
QQ实际接入方式 / 版本 / 群采集与主动发送证据：
微信群网关 / 版本 / 鉴权与断线补齐能力：
生成模型 / embedding模型与维数 / 上下文预算：
授权群 / 管理员ID配置 / 采集开始时间：
24项验收报告 / 已知缺口 / 备份位置：
```

## 5. MVP收敛检查

- 四条新增需求均进入首版任务与验收，不再列为后续功能。
- 一套Java单体、一套React静态页、一个pgvector数据库、一种定时任务、一名网页管理员。
- 私聊、服务号、复杂知识平台、任意cron、工作流、多租户、多副本及完整生产SLA均后置。
- 保留真实群采集、来源可追溯、权限隔离、去重与未知发送处理；这些是闭环正确性的必要条件。
- 不把文档编写、模拟器通过或候选协议存在当作实现和真实账号验收完成。
