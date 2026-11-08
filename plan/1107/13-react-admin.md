# 13 MVP：React 管理网页

## 1. 角色与交付方式

使用 React + TypeScript + Vite，选择一个成熟组件库实现桌面浏览器优先的管理页。构建产物由 Spring Boot 静态托管，前后端同源部署。

只有一个网页管理员，凭据通过部署配置初始化，密码保存强哈希；不做注册、找回密码、多租户或 RBAC。群内 `/summary yesterday` 的权限来自群配置中的平台稳定用户 ID 白名单，与网页登录身份分开。

## 2. 最小页面

登录页之外仅四个功能页，可用 tab/路由实现，不需要独立首页大屏。

| 页面 | 必须提供 | 不做 |
| --- | --- | --- |
| 群管理 | 已知/手工登记群、启用采集与回复、群管理员 ID、连接状态、能力缺口、首次采集时间 | 群成员全量同步、账号注册和网页扫码登录 |
| 记忆查询 | 选择群、输入问题、查看相似片段/来源、索引待处理与失败数、确认清除本群记忆 | 文件上传、向量可视化、提示词工作台 |
| 定时任务 | 每群 DAILY_SUMMARY 启停、HH:mm（默认09:00）、固定时区、下次运行、手动跑昨日 | 任意 cron、任务类型插件、DAG |
| 执行记录 | 按群/日期分页，运行状态、覆盖数/缺口、报告正文/来源、发送结果和错误；明确失败时重试一次 | 通用日志检索、实时图表和任意 SQL |

未启用群默认不收正文。网关不能列群时，管理员可手工填稳定 groupId 并由后端验证/等待真实群事件确认，不能把“自动同步群列表”作为 MVP 隐含前置条件。

## 3. 最小 HTTP API

以下是内部接口设计，不表示本次已实现。

| 方法 / 路径 | 作用 |
| --- | --- |
| `POST /api/admin/login`、`POST /api/admin/logout`、`GET /api/admin/me` | 登录会话 |
| `GET /api/admin/groups`、`POST /api/admin/groups` | 列表与登记稳定群标识 |
| `PATCH /api/admin/groups/{id}` | 采集/回复开关、群管理员 ID；使用版本字段防止静默覆盖 |
| `GET /api/admin/groups/{id}/memory/status` | 索引数量和失败状态 |
| `POST /api/admin/groups/{id}/memory/search` | 本群语义检索，只返回命中与来源，不必调用生成模型 |
| `DELETE /api/admin/groups/{id}/memory` | 二次确认后清除本群数据并提升 epoch |
| `GET/PUT /api/admin/groups/{id}/schedule` | 唯一 DAILY_SUMMARY 配置 |
| `POST /api/admin/groups/{id}/summary/yesterday` | 创建/返回同一逻辑日报，返回 runId |
| `GET /api/admin/runs`、`GET /api/admin/runs/{id}` | 分页记录与详情 |
| `POST /api/admin/runs/{id}/retry` | 仅明确失败/中断任务重试；最多一次，排除 UNKNOWN |

`/retry` 若生成已成功只恢复确定失败的发送，禁止再次调用模型；接口根据后端状态机决定，前端不能任意指定状态或目标群。

## 4. 权限与交互

- Spring Security 会话 Cookie 使用 HttpOnly、生产 Secure、SameSite；写操作验证 CSRF，登录限流。浏览器不保存平台/模型密钥。
- 所有管理 API 在后端认证；不能只隐藏菜单。未知群/运行 ID 返回明确 404/403。
- 密钥只通过部署维护，页面显示“已配置/缺失/失效”；URL 不从用户文本或群记忆生成网关地址。
- 群消息和模型回复按文本/安全 Markdown 渲染，禁用原始 HTML，防止历史聊天内容执行脚本。
- 每个列表有分页，每个操作有加载/成功/错误状态；HH:mm 严格校验，时区展示为 Asia/Shanghai。
- 手动执行后显示现有/新建 runId；按钮防抖只是体验，重复执行由后端唯一键保证。
- 清除操作显示目标群及影响，要求二次确认；返回后检索和报告页面不能继续展示旧缓存。
- 运行记录用简单轮询（如每 5 秒），离开页面停止；不引入 WebSocket 实时推送系统。

## 5. MVP 验收

- AC-WEB-01：管理员登录/退出、直接 API 未授权访问、CSRF 和文本安全展示。
- AC-WEB-02：四页主要操作可完成，字段错误可见；配置保存与后端重启后保持。
- AC-WEB-03：重复点击同一日报不新增运行，正确显示报告/缺口/发送未知状态；清除有确认且数据失效。

不要求像素级设计稿、移动端专门适配或广泛浏览器矩阵。验证当前 Chromium 桌面浏览器中的完整管理闭环即可。
