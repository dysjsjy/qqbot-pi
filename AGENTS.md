# 群知 - QQ 与微信群聊知识工作空间

Java 17+ + Spring Boot 3.5.16 + SQLite 3.51.1.0 + pi 1.5.0 + React 19.3.1 + Vite 8.3.6

## 目录

- `admin-web/`：管理员界面。复用 `/api/admin` 会话与 CSRF。
- `xiu-bot-front/`：独立产品官网、部署指南和管理台入口。
- `src/main/java/dev/qqbot/`：群聊与私聊接入、记忆、日报和诊断。
- `src/main/resources/`：部署配置与 SQLite 迁移。
- `src/test/`：Java 协议、鉴权和隔离 SQLite 回归。不进入镜像。
- `runtime/pi/`：固定版本的 pi RPC 运行依赖。
- `scripts/`：本机启动、部署和维护脚本。
- `tool/`：本机统计工具，不参与应用运行。

## 配置

- `pom.xml`：Java 依赖、Java 17 编译目标、Spotless 格式检查和前端资源打包。
- `compose.yaml`、`compose.sqlite.yaml`：SQLite 单实例部署。
- `Dockerfile`：Java、Node 和管理台构建镜像。
- `.env.example`：部署参数示例；凭据不进入代码。
- `README.md`：面向用户的启动、接入与运维入口。

## 约定

管理台与官网采用 Laper 的浅色纸面布局与橙色主题。
两站各自部署，页面与标签栏在站内共用折页 Z 资源。
管理台以 `admin-web/src/style.css` 定义纸面色值，官网在自身 `src/style.css` 同步视觉规则。
`console-features.css` 为新增业务提供纯色工作面。
群列表、表单和正文使用不透明纯色。
渐变限于背景、介绍与装饰栏。
App 编排页面，useConsole 持有共享会话状态。
私聊按用户隔离，不启用群回复策略与群日报。
群管理员授权限定于群配置与机器人账号。活跃榜默认不群发。
回复统一使用按需检索的预算上下文，归档策略独立保存。
原文、摘要、日报与榜单默认长期保留；热索引与过程流水独立清理。
实验性群成员画像默认关闭；群级建模与聊天注入独立授权。
控制器依赖业务服务，不直接访问数据库。
工作区统计仅计入会话当前 epoch 的消息与记忆。
管理接口返回真实状态，合成数据只用于测试。
改动后检查文件契约、模块地图和本文件。

[PROTOCOL]: Update this header on change, then check AGENTS.md
