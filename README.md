# 群知 · qqbot-pi

群知是可自行部署的群聊 AI 助手。它把已采集的消息变成可检索的记忆和可追溯的日报。QQ 使用开放平台官方机器人；微信群需要独立网关。应用使用 SQLite，无需另装数据库。

[Docker 部署](#docker-部署) · [Windows 本地运行](#本地运行) · [接入 QQ](#官方-qq-接入) · [备份与升级](#运维与升级)

## 核心能力

- **群聊与私聊**：支持 QQ 官方群聊和私聊，记录按群或用户隔离。群内可设仅 @、概率或能量回复策略。
- **群聊记忆**：编辑 Markdown 背景、搜索消息和摘要。不开启向量增强也能检索记忆。
- **日报与活跃榜**：日报整理北京时间昨日已采集的讨论。活跃榜独立统计，不调用模型，默认不向群内发送。
- **管理与排查**：通过网页配置群、成员权限与定时任务，查看运行日志、生成用量和调用链。

## Docker 部署

需要 Docker Engine 和 Compose V2，不需要在宿主机安装 Java 或 Node.js。已有实例请先阅读 [升级说明](#升级现有实例)。

首次部署时，在项目根目录创建 `.env`；已有文件不要覆盖：

```sh
test -f .env || cp .env.example .env
```

在 Windows PowerShell 中，可用 `if (-not (Test-Path .env)) { Copy-Item .env.example .env }`。

填写 [模型配置](#模型配置)。模板默认关闭 QQ、微信和向量检索，可以先验证管理台，再开通平台接入。

交互输入至少 12 个字符的管理员密码，生成 BCrypt 哈希：

```sh
docker run --rm -it httpd:2.4-alpine htpasswd -nBC 12 admin
```

把输出中 `admin:` 后的完整哈希用单引号包裹，写入 `.env` 的 `ADMIN_PASSWORD_HASH`。登录时输入原始密码，而不是哈希。

```sh
docker compose up -d --build
docker compose logs --tail 100 bot
curl -fsS http://127.0.0.1:8080/actuator/health
```

健康检查返回 `UP` 后，用 `ADMIN_USERNAME`（默认 `admin`）登录管理台。健康检查不验证 QQ 或模型连通性。

| 服务 | 默认地址 | 修改宿主端口 |
| --- | --- | --- |
| 机器人与管理台 | <http://127.0.0.1:8080> | `BOT_PORT` |
| 独立展示站 | <http://127.0.0.1:8081> | `FRONT_PORT` |

数据库和状态存放在 `state` 卷。容器内固定监听 8080，不随本地模板中的 `PORT` 变化。`compose.sqlite.yaml` 是兼容入口，使用相同的服务与数据库类型。

展示站只展示功能和示例数据，不连接管理 API。管理台地址可在网页内修改，也可通过 `ADMIN_PUBLIC_URL` 设置构建默认值；浏览器已保存的地址优先。仓库、文档和分享链接配置见 [官网说明](xiu-bot-front/README.md)。修改这些构建配置后，运行 `docker compose up -d --build front`。

## 本地运行

需要 JDK **17 或 21**、Node.js **22.19+**。在项目根目录打开 PowerShell。

新安装在仓库根创建配置，已有 `.env` 保留原文件：

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.qq-official.example -Destination .env
}
```

填写 [模型配置](#模型配置) 和 [QQ 应用凭据](#官方-qq-接入)。官方模板默认启用 QQ，使用 WebSocket 和端口 8082。若只验证管理台，初次复制时改用 `.env.example`，保持 `QQ_ENABLED=false`，默认端口为 8080。

先停止旧实例，再构建并启动：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Build
```

脚本安装运行时和管理台依赖、执行构建验证；管理员密码未配置时引导设置至少 12 个字符的密码，将 BCrypt 哈希写入 `.env`。登录时使用原始密码。后续启动执行同一命令去掉 `-Build`。`-Check` 仅检查本机环境、必填配置和产物，不验证模型 / QQ 网络。单独重置密码：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\admin\password-hash.ps1 -SaveToEnv
```

重置后重启。已有构建产物也可用 `java -jar target/qqbot-pi-0.2.0-SNAPSHOT.jar` 启动。此方式仍需要 Node.js 和已安装的 `runtime/pi` 依赖，不能只复制 JAR。

访问 `http://127.0.0.1:8082/actuator/health`（按 `PORT` 调整），返回 `UP` 后打开同一端口的管理台。

## 模型配置

本地和 Docker 使用同一组配置项。把值填入项目根目录的 `.env`，不要提交密钥。

| 配置 | 填写内容 |
| --- | --- |
| `PI_PROVIDER` | pi 使用的服务商标识 |
| `PI_MODEL` | 服务商提供的 API 模型 ID |
| `PI_API_KEY` | 服务商 API 密钥，不加 `Bearer` 前缀 |
| `PI_BASE_URL` | 第三方 OpenAI 兼容接口的基础地址，例如 `https://api.deepseek.com/v1`；不附加 `/chat/completions`，接口须支持流式响应 |

原生运行读取当前工作目录的 `.env`。配置优先级为命令行 > JVM 属性 > 进程环境 > `.env` > 默认值。`--bot.env-file` 只改变配置文件位置，不改变数据库、状态目录或运行时相对路径的基准目录。

## 官方 QQ 接入

1. 在 [QQ 开放平台](https://q.qq.com/) 创建机器人并开通所需群聊或私聊权限。沙箱环境需将测试账号加入名单。
2. 在 `.env` 中设置 `QQ_ENABLED=true`，填入应用的 `QQ_APP_ID` 和 `QQ_APP_SECRET`，再启动或更新服务。默认 WebSocket 由应用主动连接，不需要公网回调。
3. 将机器人添加到目标群并真实 @ 它，让系统发现群。在管理台“群管理”核对 AppID 和 `group_openid`，开启采集与回复。新发现的群默认暂停，最多启用 5 群，不补抓历史消息。
4. 在群里再次 @ 机器人发送 `/help` 检查收发，再发送普通问题检查模型生成。若没有收到事件，在“群管理”检查官方连接状态和事件记录。

使用 Webhook 时，将 `QQ_OFFICIAL_TRANSPORT` 改为 `webhook`，在开放平台登记可达的 HTTPS `/callbacks/qq/official` 并订阅所需事件。反向代理只需对平台开放回调路径。

群内命令需真实 @。平台推送范围决定可采集的消息；全量事件中的机器人身份不一致时，配置 `QQ_OFFICIAL_BOT_OPENID`。授权群管理员使用成员 OpenID，不自动同步群主角色。QQ 私聊不需要 @，首次私聊默认启用，可以在管理台暂停。

| 命令 | 行为 |
|---|---|
| `/help` | 查看命令，不调用模型 |
| `/new` | 取消自己的运行和队列、重置临时会话，保留群记忆 |
| `/cancel` | 取消自己的当前生成 |
| `/memory 问题` | 检索本群 Markdown、原始记录、摘要，可选向量增强 |
| `/bot help` | 查看群管理员命令，包括状态查询与回复策略配置 |
| `/summary yesterday` | 授权管理员触发昨日日报，与网页 / 定时任务共享日期运行 |

## 存储与记忆

SQLite 默认 `jdbc:sqlite:qqbot.db`，无需外部数据库。自定义绝对路径的父目录须存在且可写。数据库与 `.state` 一起备份；单实例运行，不删除运行中的锁文件。

Markdown 是默认基础记忆，`RAG_ENABLED=false` 时也可归档、词法检索与使用 `/memory`。每个会话的 `MEMORY.md` 保存人工背景，后台写入 `raw-log/` 或 `summaries/`。所有回复使用同一套预算上下文：按需检索，并从近期对话滤除噪声。

RAG 开启才需 embedding；本地 JSON 向量用 Java 余弦相似度检索。关闭期间不自动补建向量，更改模型 / 维数需显式重建。原文、摘要、日报与活跃榜默认长期保留；热索引与过程流水使用独立保留期。分段归档和离线操作见 [归档指南](scripts/release/raw-archive.md)。

清除群记忆删除正文 / 索引 / 上下文统计并推进 epoch，保留用量账目；删除群后账目脱离 group_id，按 90 天维护。群与私聊作用域隔离，临时会话另按成员隔离。

## 管理台

在“群管理”启用采集、设置群管理员和仅 @ / 概率 / 能量回复策略；暂停群只需关闭采集，不必删除群。保存回复策略重置能量，队列满时不扣能量。管理台还提供记忆编辑与检索、日报、运行日志、审计、用量与费用和链路分析。金额未知不补 0。实验性群成员画像默认关闭，群级建模和回复注入独立授权。

## 运维与升级

Docker 默认仅绑定服务器本机。远程管理优先使用 SSH 隧道或 HTTPS 反向代理；通过 HTTPS 访问管理台时设 `COOKIE_SECURE=true`。修改 `.env` 后，本机部署需重启应用；Docker 部署运行 `docker compose up -d` 应用配置，单独执行 `docker compose restart` 不会重新读取配置。离线镜像包和本机工具见 [脚本说明](scripts/README.md)。

备份前先停止实例，再备份数据库与匹配的 `.state`；Docker 部署备份整个 state 卷。不要运行 `docker compose down -v`。恢复时使用同一份备份中的数据库与状态目录。

### 升级现有实例

停机并备份后，保留 `.env` 中的管理员、模型、SQLite 路径和官方 QQ 应用配置。重建并启动新版本，由 Flyway 执行 SQLite 迁移。旧 PostgreSQL 数据和个人 QQ 账号数据不会自动迁移。使用旧格式的 RAW 归档时，按 [归档指南](scripts/release/raw-archive.md#升级与离线迁移) 执行离线迁移，不把旧 JAR 接到已升级的数据库。

## 网关与扩展

微信需独立网关，不包含微信登录、私有协议或私聊支持。网关向 `POST /api/ingress/weixin` 投递群消息，使用 `X-Bot-Token` 与 `WEIXIN_TOKEN` 匹配，账号与 `WEIXIN_ACCOUNT_ID` 匹配。消息字段见 [`GroupIngestRequest`](src/main/java/dev/qqbot/message/GroupIngestRequest.java)。重试保留同一群内消息 ID，`mentionsBot` 使用真实提及元数据。网关可向 `POST /api/ingress/weixin/heartbeat` 报告 `accountId` 和 `online`。

发送请求到 `WEIXIN_BASE_URL` 下的 `/api/bot/send`，使用 `Authorization: Bearer <WEIXIN_TOKEN>`，携带 `groupId`、`text` 和幂等 `requestId`。明确回执使用 `accepted`（附平台 `messageId`）、`retryable` 或 `rejected`；不确定结果不自动重发。投递适配器可实现 `ChannelAdapter` 注册为 Spring bean；这不是完整的插件系统。不支持扩展数据库、热加载或个人 QQ 登录。

## 行为边界与验证

日报按北京时间昨日实际采集文本分析，首次冻结来源；最多 1000 条 / 100000 码点、40 次模型调用、30 分钟。完整报告在管理台，群内最多 3 段、每段 500 码点。主动发送默认关闭，须具备平台权限并设置 `QQ_OFFICIAL_ALLOW_ACTIVE_MESSAGES=true`，不能绕过频控。

发送采用数据库 Outbox，不确定送达时标 `UNKNOWN`，不自动重发后续段。重启后运行任务标中断、发送中消息标未知。模型仍可能使用外部服务，自行部署不代表所有内容仅在本机处理。

## 源码构建

当前构建版本为 `0.2.0-SNAPSHOT`。后端使用 Java 17 编译目标、Spring Boot 和 pi RPC；管理台与展示站使用 React 和 Vite。无需另装 Maven，项目内置 Maven Wrapper。

仅构建和验证、不启动服务时，在项目根目录运行：

```powershell
npm.cmd --prefix runtime/pi ci --ignore-scripts
npm.cmd --prefix admin-web ci
npm.cmd --prefix admin-web run build
.\mvnw.cmd verify -Pfrontend
```

`-Pfrontend` 将管理台静态文件打包进 JAR。官网单独在 `xiu-bot-front` 运行 `npm ci` 和 `npm run build`。Java 回归使用临时 SQLite 和合成数据，不进入生产镜像；真实平台与模型需另行验证。源码结构见 [项目地图](AGENTS.md)。

### 代码统计

需要 Python 3.10 或更高版本，无需安装第三方包：

```powershell
python tool/count_code.py
python tool/count_code.py src/main
```

默认扫描项目根目录；指定目录时只统计该目录。结果按代码类型列出文件数、总行数、空行数和非空行数。非空行包含注释。依赖、锁文件、过程目录和构建产物不计入；直接指定排除目录或其子目录时返回错误。
