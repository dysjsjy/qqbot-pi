# xiu-bot-front/
> L2 | Parent: ../AGENTS.md

- `src/`：静态官网、合成预览与部署指南。
- `public/`：折页 Z、纸面分享封面和第三方许可。
- `README.md`：开发、公开链接与管理台入口配置。
- `index.html`：官网文档与分享元信息。
- `package.json`：固定 React、Motion 与 Vite 依赖。
- `package-lock.json`：npm 依赖锁。
- `bun.lock`：Bun 依赖锁。
- `vite.config.ts`：构建配置与公开链接校验。
- `tsconfig.json`：TypeScript 编译边界。
- `Dockerfile`：Nginx 静态站镜像。
- `.env.example`：公开构建参数模板。
- `.dockerignore`：镜像构建上下文排除。
- `.gitignore`：官网本地产物排除。

独立静态站不请求管理 API。
产品预览持续标注合成数据。
导航、标签栏共用 `public/favicon.svg`。图形与管理台折页 Z 一致。

[PROTOCOL]: Update this header on change, then check AGENTS.md
