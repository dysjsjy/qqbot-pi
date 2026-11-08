# qqbot-pi

Java 21 / Spring Boot + pi RPC 的群聊机器人 MVP，包含 QQ OneBot 11 接入、微信网关桥接接口、PostgreSQL/pgvector 持久记忆、昨日日报和 React 管理台。

当前是**可构建、带自动化验证的实现版本**。QQ/微信群、真实模型效果及真实网关还需要凭据与实际群验证；不能把本地模拟结果作为双平台上线验收。验收进展见 [实施记录](plan/1107/evidence/implementation.md)，需求与24项标准见 [计划](plan/1107/README.md)。

## 本地运行

需要 JDK 21、Node 22.19+、PostgreSQL 17 + pgvector 0.8.x。Maven Wrapper 固定 3.9.14，pi 固定 `@earendil-works/pi-coding-agent@1.5.0`。

```powershell
cd runtime/pi
npm ci --ignore-scripts
cd ../../admin-web
npm ci
npm run build
cd ..
./mvnw.cmd verify -Pfrontend
./scripts/password-hash.ps1
```

复制 `.env.example` 为 `.env`，填写 BCrypt 密码哈希、模型与 embedding 参数。原生 Java 启动读取**进程环境变量**，不会自动加载 `.env`；Compose 自动读取 `.env`。原生启动示例：

```powershell
$env:DATABASE_URL='jdbc:postgresql://localhost:5432/qqbot'
$env:DATABASE_USER='qqbot'
$env:DATABASE_PASSWORD='your-password'
$env:ADMIN_PASSWORD_HASH='your-bcrypt-hash'
$env:PI_PROVIDER='your-provider'
$env:PI_MODEL='your-model'
$env:PI_API_KEY='your-key'
$env:EMBEDDING_URL='https://provider/v1/embeddings'
$env:EMBEDDING_MODEL='fixed-model'
$env:EMBEDDING_DIMENSIONS='1536'
$env:EMBEDDING_API_KEY='your-key'
$env:COOKIE_SECURE='false'
java -jar target/qqbot-pi-0.1.0-SNAPSHOT.jar
```

打开 http://localhost:8080 。开发界面可在 `admin-web` 执行 `npm run dev`，Vite 将 `/api` 代理到 8080。

模型通过 Java 拉起 pi 的 JSONL RPC 子进程调用，禁用工具、扩展、MCP、skills、上下文文件和自动重试。`PI_BASE_URL` 可配置受信任的 OpenAI 兼容 `/v1` 端点；不填时使用 pi 内置 provider。embedding 独立配置，要求返回 OpenAI 兼容 `data[index,embedding]`；维数与模型身份固定，变更需明确重建数据库索引。

## Docker Compose

填好 `.env` 后执行 `docker compose up --build -d`。管理员哈希在 `.env` 中使用单引号，避免 `$` 被展开。容器运行 Java 21 + Node 22，数据库持久化卷 `database`、会话卷 `state`；默认仅监听本机 8080。使用 HTTPS 反向代理时将 `COOKIE_SECURE=true`。

网关独立部署，容器内访问宿主机使用 `host.docker.internal`。Compose 镜像构建与实际启动仍需在有 Docker Engine 的环境验证。

## 配置群和接入平台

在管理台添加稳定的机器人账号ID、群ID、群名称、管理员userId；最多启用5群。启用采集和回复是两个独立开关。未启用的群不保存正文；普通群文本保存并索引，命令或 @ 机器人才触发回复。

- `/help`：命令说明，不调用模型。
- `/new`：取消自己的运行和队列，重置临时会话，保留群记忆。
- `/cancel`：取消自己的当前生成。
- `/memory 问题`：检索本群记录，资料不足时明确说明。
- `/summary yesterday`：仅群管理员可触发，与网页、定时触发共享同一日期运行。

QQ 在 NapCat / OneBot 11 网关启用 HTTP API，事件以 **array message 格式** POST 到 `/callbacks/qq/onebot`。配置 `QQ_ACCOUNT_ID`、`QQ_BASE_URL`、API `QQ_TOKEN`，回调设置 `QQ_WEBHOOK_SECRET` 并由网关携带 `X-Signature: sha1=...`；未设置 secret 时必须携带 `Authorization: Bearer QQ_TOKEN`。主动消息走 `/send_group_msg`，要求明确 `retcode=0/status=ok/data.message_id` 回执。文本消息保留原文，@解析使用消息段中的稳定账号ID。

微信目前提供 [规范化桥接契约](docs/gateway-contract.md)，需要实际网关实现群文本采集、稳定ID、主动发送和登录状态通知。尚未绑定或验证某个微信产品，不把服务号能力视为微信群能力。

## 行为与边界

RAG 按群、账号和 memory epoch 隔离，临时会话另按成员隔离；向量精确检索 Top-5，并验证答案来源编号。日报按 Asia/Shanghai 的昨日 `[00:00,24:00)` 读取全部已采集文本，首次运行冻结来源清单；迟到消息可进群记忆，不改变已冻结日报。分块分析后合并，最多1000条/100000码点、40次调用、30分钟，超限明确失败。

发送经数据库 Outbox，明确未接收的失败最多3次；网络超时等不确定结果进入 `UNKNOWN`，不自动重发，也不再发送后续分段。完整日报在管理端可查看，群内最多3段、每段500码点。服务重启将运行中任务标中断，将发送中消息标未知。清除群记忆会使旧任务、报告和会话立即失效。

原文/向量/报告保留30天，临时会话7天，操作审计90天；每小时清理。仅支持单实例，启动用 PostgreSQL 会话 advisory lock 阻止重复实例。

## 验证

普通 `mvnw verify` 运行协议、真实 pi 本地模型接口、鉴权与 HTTP 适配测试。pi 真实协议测试需先在 `runtime/pi` 执行 `npm ci`，否则报告 SKIPPED。数据库测试需设置 `TEST_DATABASE_URL`；未设置时明确跳过。可连接专用 PostgreSQL/pgvector 测试数据库（测试会清空业务表，请勿指向实际数据）。

无 Docker 时的开发测试数据库：

```powershell
cd test-support
npm ci
node database.mjs
# 另一个终端，从项目根执行
$env:TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/postgres?preferQueryMode=simple&socketTimeout=15'
$env:TEST_DATABASE_PREMIGRATED='true'
./mvnw.cmd verify
```

PGlite 执行实际 PostgreSQL SQL 和 pgvector，但单连接/锁行为不等于 PostgreSQL 服务；该路径由 Node 执行迁移 SQL，不能据此宣称 Flyway 多连接迁移或生产数据库验证完成。浏览器测试和备份说明见 [运维](docs/operations.md)。
