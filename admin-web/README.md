# xiu-bot 管理台

React 19 / TypeScript / Vite 的单页管理台，覆盖群管理、群记忆、回复方式、定时任务、执行记录、链路分析、运行日志。开发服务通过现有 Vite 代理访问后端；生产资源由 Java 应用提供，API 和认证契约保持现状。

## 本地构建

在本目录运行：

```powershell
npm ci
npm run dev
npm run build
npx vite preview --host 127.0.0.1 --port 4173 --strictPort
```

`build` 执行 TypeScript 检查并输出 `dist/`。`vite preview` 仅预览静态产物；独立预览不代表登录后端已经联通。Java 打包沿用根目录 Dockerfile 的 web 阶段和 Maven `frontend` profile。

## 交互与样式维护

品牌、颜色、间距、输入、按钮、面板、表格和响应式样式集中在 `src/style.css`。手机保留可见七页导航和退出入口，宽表局部滚动；按钮最小 44px。保持页面 group.id、过期响应保护、409 输入保留、群/私聊隔离、未知金额和混合币种的既有含义。

`components/Dialog.tsx` 使用原生模态 dialog 提供背景禁用、打开焦点、Tab 环绕、Escape 与关闭归还焦点。忙碌期间不能 Escape 关闭；触发按钮消失时回到页面标题。链路详情继续在页内显示，打开定位标题，关闭还原列表触发按钮及筛选。

Motion 运行时固定 `14.14.0`，与官网一致。仅移植所需的 Animated Background，当前导航为唯一受控状态；列表、日志刷新、用量数字和业务表单不做入场或布局动画。`src/ui/motion.tsx` 使用 `LazyMotion/domMin/m`，媒体查询订阅将系统减少动态效果偏好实时传入 `MotionConfig` 和组件静态路径。

原始 commit、哈希、适配点见 [SOURCE.md](src/components/motion-primitives/SOURCE.md)，MIT 原文见 [LICENSE.md](src/components/motion-primitives/LICENSE.md)。公开产物保留 `/third-party-notices.txt`，包含移植源码与新增运行时依赖许可。未引入 Tailwind、Next 或新路由。

## 验证

`npm run build` 检查 TypeScript 并生成 `dist/`。要验证登录和管理 API，请据 [启动说明](../README.md#本地运行) 启动后端；仅预览静态产物不验证 API。
