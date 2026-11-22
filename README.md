# qqbot-pi

Java 17 / 21 + Spring Boot + pi RPC 群聊 AI 助手。当前版本 **0.2.0-SNAPSHOT** 使用 **SQLite** 和 **QQ 官方机器人**，提供 Markdown 记忆、可选本地向量增强、昨日日报、React 管理台、生成用量和链路分析。

首次部署可选 [Windows 本地运行](#本地运行) 或 [Docker 部署](#docker-部署)。已有实例请先阅读 [升级说明](#升级现有实例)。

## 本地运行

需要 JDK **17 或 21**、Node.js **22.19+**。Java 编译目标固定 17，Maven Wrapper 固定 3.9.14，pi 固定 `@earendil-works/pi-coding-agent@1.5.0`。

新安装在仓库根创建配置，已有 `.env` 保留原文件：

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.qq-official.example -Destination .env
}
```

填写模型 `PI_PROVIDER`、`PI_MODEL`、`PI_API_KEY`，以及 [QQ 开放平台](https://q.qq.com/) 的 `QQ_APP_ID`、`QQ_APP_SECRET`。第三方 OpenAI 兼容接口另设 `PI_BASE_URL`，填基础路径（例如 `https://api.deepseek.com/v1`），不附加 `/chat/completions`；接口须支持流式响应。官方模板启用 QQ、默认本地 WebSocket、端口 8082。若只验证管理台可使用 `.env.example`，保持 `QQ_ENABLED=false`，默认端口 8080。

先停止旧实例，再构建并启动：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Build
```

脚本安装运行时和管理台依赖、执行构建验证；管理员密码未配置时引导设置至少 12 位密码，将 BCrypt 哈希写入 `.env`。登录时使用原始密码。后续启动执行同一命令去掉 `-Build`。`-Check` 仅检查本机环境、必填配置和产物，不验证模型 / QQ 网络。单独重置密码：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\admin\password-hash.ps1 -SaveToEnv
```

重置后重启。直接运行产物：`java -jar target/qqbot-pi-0.2.0-SNAPSHOT.jar`。需要保留 `runtime/pi` 依赖，仅复制 JAR 不足以部署。

应用读取当前工作目录 `.env`。配置优先级：命令行 > JVM 属性 > 进程环境 > `.env` > 默认值。`--bot.env-file=完整路径` 只改变配置位置，数据库和 `.state` 的相对路径仍以工作目录为基准。改配置后重启。

访问 `http://127.0.0.1:8082/actuator/health`（按 `PORT` 调整），返回 `UP` 后在同一端口登录管理台。健康检查不验证 QQ 或模型连通性。

## 官方 QQ 接入

QQ 使用开放平台 AppID / AppSecret，默认 WebSocket 主动连接，无需公网回调。选择 `QQ_OFFICIAL_TRANSPORT=webhook` 时，在开放平台登记可达的 HTTPS `/callbacks/qq/official` 并订阅所需群聊、私聊事件；反向代理只需开放回调路径。

首次收到群消息会登记暂停的群。登录管理台，核对 AppID、`group_openid`，启用采集与回复，再真实 @ 发送 `/help` 和问题。最多启用 5 群，启用前不补抓历史；管理员使用成员 OpenID，不自动同步群主角色。私聊使用官方单聊能力，首次自动启用，历史按用户隔离，可在管理台暂停。平台须开通权限并将沙箱测试用户加入名单。

| 命令 | 行为 |
|---|---|
| `/help` | 查看命令，不调用模型 |
| `/new` | 取消自己的运行和队列、重置临时会话，保留群记忆 |
| `/cancel` | 取消自己的当前生成 |
| `/memory 问题` | 检索本群 Markdown、原始记录、摘要，可选向量增强 |
| `/summary yesterday` | 授权管理员触发昨日日报，与网页 / 定时任务共享日期运行 |

群命令需附真实 @，平台推送范围决定可采集消息。全量事件中机器人身份不同可配置 `QQ_OFFICIAL_BOT_OPENID`。连接与已认证事件标识见管理台“群管理”。

## 存储与记忆

SQLite 默认 `jdbc:sqlite:qqbot.db`，无需外部数据库。自定义绝对路径的父目录须存在且可写。数据库与 `.state` 一起备份；单实例运行，不删除运行中的锁文件。

Markdown 是默认基础记忆，`RAG_ENABLED=false` 时也可归档、词法检索与使用 `/memory`。每个会话的 `MEMORY.md` 保存人工背景，后台写入 `raw-log/` 或 `summaries/`。所有回复使用同一套预算上下文：按需检索，并从近期对话滤除噪声。

RAG 开启才需 embedding；本地 JSON 向量用 Java 余弦相似度检索。关闭期间不自动补建向量，更改模型 / 维数需显式重建。原文、摘要、日报与活跃榜默认长期保留；热索引与过程流水使用独立保留期。分段归档和离线操作见 [归档指南](scripts/release/raw-archive.md)。

清除群记忆删除正文 / 索引 / 上下文统计并推进 epoch，保留用量账目；删除群后账目脱离 group_id，按 90 天维护。群与私聊作用域隔离，临时会话另按成员隔离。

## 管理台

在“群管理”启用采集、设置群管理员和仅 @ / 概率 / 能量回复策略；暂停群只需关闭采集，不必删除群。保存回复策略重置能量，队列满时不扣能量。管理台还提供记忆编辑与检索、日报、运行日志、审计、用量与费用和链路分析。金额未知不补 0。实验性群成员画像默认关闭，群级建模和回复注入独立授权。

## Docker 部署

首次安装时复制 `.env.example` 为 `.env`，填写模型配置和按需启用的 QQ 凭据。使用本机密码脚本或以下 Docker 命令交互生成 BCrypt 哈希：

```sh
docker run --rm -it httpd:2.4-alpine htpasswd -nBC 12 admin
```

把 `admin:` 后的完整哈希用单引号包裹后写入 `ADMIN_PASSWORD_HASH`。已有 `.env` 时不要覆盖。启动并检查服务：

```powershell
docker compose up --build -d
docker compose logs --tail 100 bot
```

`compose.yaml` 与兼容入口 `compose.sqlite.yaml` 都使用 SQLite，启动 bot 与展示站。默认管理台 `http://127.0.0.1:8080`，展示站 `http://127.0.0.1:8081`；宿主端口由 `BOT_PORT`、`FRONT_PORT` 控制。数据库和状态保存在 state 卷。容器固定监听 8080，官方本地模板的 `PORT=8082` 不改变容器监听。

展示站提供能力介绍、合成产品预览、两种部署指南、文档与 FAQ；它是独立静态页面，不连接管理 API。管理台入口由 `VITE_ADMIN_URL` / `VITE_ADMIN_PORT` 构建配置，Compose 传入 `ADMIN_PUBLIC_URL` / `BOT_PORT`；浏览器已保存的地址优先，可在页面改写。

可选 `FRONT_REPOSITORY_URL`、`FRONT_DOCS_BASE_URL`、`FRONT_SITE_URL` 分别映射到官网的 `VITE_REPOSITORY_URL`、`VITE_DOCS_BASE_URL`、`VITE_SITE_URL`，用于 GitHub / 文档外链和分享元信息。三个值均为公开 HTTPS URL，不能含凭据、query 或 fragment；未配置时隐藏相应外链并显示源码内文档路径，不猜仓库或分支。文档前缀指向仓库根目录浏览位置，官网追加 `README.md` 章节路径或 `AGENTS.md`。修改这些值或管理端口后执行 `docker compose up -d --build front`。详见 [官网说明](xiu-bot-front/README.md)。

## 运维与升级

默认端口仅绑定服务器本机。远程管理优先使用 SSH 隧道或 HTTPS 反向代理；通过 HTTPS 访问管理台时设 `COOKIE_SECURE=true`。修改 Compose 配置或 `.env` 后运行 `docker compose up -d` 更新容器配置，单独 `restart` 不会重新读取配置。离线镜像包和本机工具见 [脚本说明](scripts/README.md)。

备份前先停止实例，再备份数据库与匹配的 `.state`；Docker 部署备份整个 state 卷。不要运行 `docker compose down -v`。恢复时使用同一份备份中的数据库与状态目录。

### 升级现有实例

停机并备份后，保留 `.env` 中的管理员、模型、SQLite 路径和官方 QQ 应用配置。重建并启动新版本，由 Flyway 执行 SQLite 迁移。旧 PostgreSQL 数据和个人 QQ 账号数据不会自动迁移。使用旧格式的 RAW 归档时，按 [归档指南](scripts/release/raw-archive.md#升级与离线迁移) 执行离线迁移，不把旧 JAR 接到已升级的数据库。

## 网关与扩展

微信需独立网关，不包含微信登录、私有协议或私聊支持。网关向 `POST /api/ingress/weixin` 投递群消息，使用 `X-Bot-Token` 与 `WEIXIN_TOKEN` 匹配，账号与 `WEIXIN_ACCOUNT_ID` 匹配。消息字段见 [`GroupIngestRequest`](src/main/java/dev/qqbot/message/GroupIngestRequest.java)。重试保留同一群内消息 ID，`mentionsBot` 使用真实提及元数据。网关可向 `POST /api/ingress/weixin/heartbeat` 报告 `accountId` 和 `online`。

发送请求到 `WEIXIN_BASE_URL` 下的 `/api/bot/send`，使用 `Authorization: Bearer <WEIXIN_TOKEN>`，携带 `groupId`、`text` 和幂等 `requestId`。明确回执使用 `accepted`（附平台 `messageId`）、`retryable` 或 `rejected`；不确定结果不自动重发。投递适配器可实现 `ChannelAdapter` 注册为 Spring bean；这不是完整的插件系统。不支持扩展数据库、热加载或个人 QQ 登录。

## 行为边界与验证

日报按北京时间昨日实际采集文本分析，首次冻结来源；最多 1000 条 / 100000 码点、40 次模型调用、30 分钟。完整报告在管理台，群内最多 3 段、每段 500 码点。主动发送默认关闭，须具备平台权限并设置 `QQ_OFFICIAL_ALLOW_ACTIVE_MESSAGES=true`，不能绕过频控。

发送采用数据库 Outbox，不确定送达时标 `UNKNOWN`，不自动重发后续段。重启后运行任务标中断、发送中消息标未知。模型仍可能使用外部服务，自行部署不代表所有内容仅在本机处理。

安装 `runtime/pi` 依赖并构建管理台后，运行 `.\mvnw.cmd verify -Pfrontend` 验证并打包 JAR；官网单独运行 `npm run build`。后端回归使用临时 SQLite 和合成数据，不进入生产镜像。真实平台与模型需另行验证。源码结构见 [项目地图](AGENTS.md)。
