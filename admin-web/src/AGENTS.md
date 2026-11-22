# src/
> L2 | Parent: ../AGENTS.md

- `main.tsx`：挂载 React、MotionProvider 与两层主题样式。
- `App.tsx`：编排七个页面与群聊、私聊选择。
- `useConsole.ts`：持有共享会话、工作区统计与操作反馈。
- `types.ts`：统一会话、成员、回复策略、记忆归档、日报、榜单和链路接口类型。
- `navigation.ts`：定义七页标题、说明与导航图标。
- `api.ts`：统一同源请求、CSRF、认证过期事件与 ApiError。
- `ui.tsx`：提供品牌、纸面侧栏、通知与空状态。
- `qunzhi-mark.svg`：自绘折页 Z；页面与标签栏共用图形源。
- `Login.tsx`：管理员登录与产品能力入口。
- `Groups.tsx`：会话 CRUD、活跃成员入口与官方连接事件。
- `Memory.tsx`：按目标偏移读取归档页；群聊标签页保留草稿，画像在首次打开时加载。
- `MemorySettings.tsx`：Markdown 人工记忆编辑与带版本的群回复策略。
- `MemoryTelemetry.tsx`：归档设置冲突、生成用量与上下文层统计。
- `Logs.tsx`：运行日志筛选、轮询、导出和操作审计。
- `traceFormat.ts`：统一链路时间、资源、阶段与状态显示。
- `components/`：纸面页面外壳、原生对话框和空状态。
- `pages/`：日报、活跃榜、执行记录和链路分析页面。
- `ui/`：Motion 配置与减少动态效果偏好。
- `style.css`：橙色变量、品牌、登录和纸面外壳。
- `console-features.css`：七页业务样式与小屏适配；核心区域为纯色。
- `vite-env.d.ts`：Vite 客户端和热更新的编译类型。

页面统一通过 api.ts 请求后端。
共享状态只有 useConsole 一个来源。

[PROTOCOL]: Update this header on change, then check AGENTS.md
