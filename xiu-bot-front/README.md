# xiu-bot 官网

基于 React、TypeScript 和 Vite 的独立静态介绍站。页面包含项目定位、六项能力、三种产品预览、部署指南、八项文档、八类 FAQ 和管理台入口，面向当前 SQLite、官方 QQ 机器人版本。

产品预览使用合成数据的 HTML/CSS，持续标注“示例数据 · 非实时管理台”，不连接机器人、模型或管理 API。微信仅介绍独立群消息网关契约；PostgreSQL 和个人 QQ 登录不属于核心能力，见 [网关与扩展](../README.md#网关与扩展)。

官网采用管理台的橙色纸面、宋体标题、三色栏和折页 Z。桌面用侧栏导航，窄屏用顶栏导航。主题位于 `src/style.css`；页面品牌和浏览器图标引用同一份 `public/favicon.svg`。独立构建不导入管理台产物。

## 开发与构建

需要 Node.js 22.19+，在本目录执行：

```powershell
npm ci
npm run dev
```

`npm run build` 执行 TypeScript 检查并输出 `dist/`；`npm run preview -- --host 127.0.0.1 --port 4174` 预览生产产物。默认部署在站点根路径，页面保留 `#top`、`#about`、`#features`、`#start`、`#console`，新增 `#preview`、`#docs`、`#faq`。

## 公开链接配置

首次本地配置可参考 [.env.example](.env.example) 创建 `.env.local`；已有文件按需编辑。所有 `VITE_` 值都进入公开构建产物，不能放 token、应用凭据或其他密钥。

| 变量 | 用途与缺省行为 |
|---|---|
| `VITE_REPOSITORY_URL` | GitHub 仓库主页，仅接受 `https://github.com/<owner>/<repo>`；移除尾部 `/` 和 `.git`。空值或非法值隐藏 GitHub / Issues 外链 |
| `VITE_DOCS_BASE_URL` | 仓库根目录的文档浏览前缀，例如 GitHub 的 `blob/<ref>/` 目录；页面追加 `README.md` 章节路径或 `AGENTS.md`，不推断分支。缺省显示可复制的源码内路径 |
| `VITE_SITE_URL` | 官网完整根 URL，用于构建 HTML 的 canonical、`og:url` 和绝对分享图地址；缺省不生成这些 URL，不改变 Vite 的资源 base |
| `VITE_ADMIN_URL` | 已有管理台地址的构建默认值 |
| `VITE_ADMIN_PORT` | 未配置管理台 URL 时，当前协议 / hostname 对应的管理端口；本地缺省 8082，Docker 构建缺省 8080 |

三个链接值只接受无用户名 / 密码、query 或 fragment 的 HTTPS URL。无效值与空值均按未配置处理。独立文档托管也需保留相同路径和章节。仓库地址不会自动推导文档分支或下载入口；未提供真实项目许可证时，不展示项目许可证徽章。

title、description、Open Graph 和 Twitter 元信息静态写入构建 HTML；有效站点 URL 才输出 canonical 和分享图链接。分享图为本地 `images/share-card.svg`（1200×630）。这是静态 SPA，不包含 SSR，也不承诺所有搜索引擎抓取正文或所有社交平台支持 SVG 分享卡。

## Docker 配置

根目录 `compose.yaml` 和兼容文件 `compose.sqlite.yaml` 将以下公开变量传给官网 Dockerfile 的 ARG / ENV：

| Compose 变量 | 构建变量 |
|---|---|
| `FRONT_REPOSITORY_URL` | `VITE_REPOSITORY_URL` |
| `FRONT_DOCS_BASE_URL` | `VITE_DOCS_BASE_URL` |
| `FRONT_SITE_URL` | `VITE_SITE_URL` |
| `ADMIN_PUBLIC_URL` | `VITE_ADMIN_URL` |
| `BOT_PORT`（缺省 8080） | `VITE_ADMIN_PORT` |

修改链接或端口后执行 `docker compose up -d --build front` 重建展示站；Nginx 启动时的环境变量不会自动改写已生成页面。默认展示站绑定 `127.0.0.1:8081`。完整安装、官方 QQ 配置和升级限制见 [项目 README](../README.md)。

官网只提供 Docker SQLite / Windows 本地两种运行路线，复制命令分别为 `docker compose up -d --build` 和 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Build`。兼容 Compose 文件不是第三种数据库模式。

## 管理台地址与可访问性

地址优先级保持为：浏览器已保存的 `xiu-bot-admin-url` → `VITE_ADMIN_URL` → 当前协议 / hostname 加 `VITE_ADMIN_PORT` 或 8082。构建配置不会覆盖用户已经保存的地址，可直接在页面重新填写。

提交时才校验、保存并跳转，只接受无嵌入凭据的 HTTP / HTTPS；localStorage 不可用时仍可填写和跳转。官网不会探活管理台或索取管理员密码。

预览和部署标签分别支持点击、触屏、方向键、Home / End；页面有跳到正文、可见焦点和减少动态效果支持。复制命令保留成功、拒绝和切换竞态处理。

## 验证与素材

执行 `npm run build` 和 `npm run preview -- --host 127.0.0.1 --port 4174` 检查生产产物。配置公开文档前缀时，在浏览器确认链接指向所选版本的说明文件。

页面使用系统字体和现有 Lucide 图标，不请求远程字体、图片服务或统计 SDK。原尼罗河照片已停止加载，第三方 Canvas UI Ripple 源码与许可证保留但不导入。分享图及 HTML 产品示例的来源、历史照片许可见 [CREDITS.md](public/images/CREDITS.md)，页脚亦提供素材来源入口。

## Motion Primitives 维护

运行时精确固定为 `motion@14.14.0`，与管理台一致。选择性移植 Animated Background、Animated Group、In View、Transition Panel，保留普通 CSS，未引入 Tailwind、Next 或新路由。

来源 commit、原始 SHA-256、本地适配说明和 MIT 原文见 [SOURCE.md](src/components/motion-primitives/SOURCE.md) 与 [LICENSE.md](src/components/motion-primitives/LICENSE.md)。运行产物保留 `/third-party-notices.txt`，包含上游和新增运行时依赖许可。修改适配文件时保留来源注释，不直接用上游 main 覆盖本地受控状态、键盘或无障碍处理。

`src/ui/motion.tsx` 使用 `LazyMotion` 的 `domMin` 与 `m`；背景仅测量当前控件，不引入拖拽、手势或共享布局引擎。媒体查询通过 `useSyncExternalStore` 订阅，运行中切换系统偏好即可关闭 JS 动效和 CSS 过渡。静态内容一次呈现；焦点进入立即可见；产品预览的旧面板即时移除。CTA、复制状态、错误与表单不等待动画。
