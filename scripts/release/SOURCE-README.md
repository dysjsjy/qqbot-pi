# qqbot-pi __RELEASE_VERSION__ 源码部署包

这是**可编辑、可重新构建的项目源码快照**，包含当前工作区已修改和新增的源码，不局限于最后一次 Git 提交。`qqbot-pi-server-*-linux-amd64` 包含预构建镜像，没有 Java / React 项目源码；需要经常改代码时使用本源码包。

源码包仅收集发布目录，不含实施计划、历史文档或验收脚本。页面实际使用的素材、许可证、Java 回归源码、配置模板与锁文件仍会保留。`-CodeOnly` 保留为兼容参数，本分支的打包范围不变。

## 包含内容

| 路径 | 内容 |
| --- | --- |
| `src/` | Java 后端、数据库迁移、单元与集成测试 |
| `admin-web/` | 管理台 React / TypeScript 源码、样式与依赖锁文件 |
| `xiu-bot-front/` | 展示站源码、图片 / 图标、第三方组件许可证与独立 Dockerfile |
| `runtime/pi/` | Pi 运行时依赖定义与锁文件，构建时自动安装 |
| `Dockerfile`、`compose*.yaml` | 服务器构建、SQLite 部署配置 |
| `pom.xml`、`.mvn/`、`mvnw*` | Maven 定义与 Wrapper |
| `scripts/`、`.github/` | 本机启动、管理员工具、打包工具与 CI |
| `README.md`、`AGENTS.md` | 操作与部署说明、项目目录地图 |
| `.env*.example` | 无真实凭据的配置模板 |

不包含本机 `.env`、真实密钥、数据库 / 聊天数据、`.state`、`.git`、`node_modules` 或构建产物。依赖按锁文件重新安装；包内历史文档记录的是当时的验证，不代表你的服务器已完成验证。源码包没有绑定 Linux amd64 镜像，实际运行架构取决于服务器可用的基础镜像和构建环境。

## 1. 上传与解压

服务器需要 Docker Engine 与 Compose V2，能访问 Docker Hub、Maven Central、npm registry；ZIP格式解压另需unzip。使用 Docker 构建时无需安装 Java、Node 或 Maven。

```sh
sha256sum -c qqbot-pi-source-__RELEASE_VERSION__.__ARCHIVE_EXTENSION__.sha256
__EXTRACT_COMMAND__
cd qqbot-pi
sha256sum -c SOURCE-FILES.sha256
```

`SOURCE-FILES.sha256` 校验解压时的原始快照，后续正常修改源码后不必要求旧文件校验继续通过。若服务器已有 `qqbot-pi` 目录，先解压到新的空目录，不要直接覆盖原部署的 `.env` 或代码。

## 2. 首次配置与构建（SQLite）

只在没有 `.env` 时创建配置：

```sh
umask 077
test -f .env || cp .env.sqlite.example .env
chmod 600 .env
docker run --rm -it httpd:2.4-alpine htpasswd -nBC 12 admin
```

交互输入管理员密码，把输出中 `admin:` 后的完整 BCrypt 哈希用单引号包裹，填入 `.env` 的 `ADMIN_PASSWORD_HASH`；登录时使用原始密码。再填写模型配置 `PI_PROVIDER`、`PI_MODEL`、`PI_API_KEY`，第三方兼容 API 另填 `PI_BASE_URL`。

先保持 `QQ_ENABLED=false`、`WEIXIN_ENABLED=false` 验证管理台。Compose 将容器内端口固定为 8080，覆盖原生运行模板中的 `PORT`；宿主映射端口由 `BOT_PORT` 控制。

```sh
docker compose -p qqbot-pi -f compose.sqlite.yaml up -d --build
docker compose -p qqbot-pi -f compose.sqlite.yaml logs --tail 100 bot
curl -f http://127.0.0.1:8080/actuator/health
```

默认管理台在服务器 `127.0.0.1:8080`，展示站在 `127.0.0.1:8081`。可通过 SSH 隧道访问：

```sh
ssh -L 8080:127.0.0.1:8080 -L 8081:127.0.0.1:8081 用户名@服务器地址
```


## 3. 修改源码后更新

- 后端逻辑：编辑 `src/main/java/`；配置与迁移在 `src/main/resources/`。
- 管理台：编辑 `admin-web/src/`。
- 展示站：编辑 `xiu-bot-front/src/`，静态资源在 `xiu-bot-front/public/`。

改完在项目根目录执行：

```sh
docker compose -p qqbot-pi -f compose.sqlite.yaml up -d --build
docker compose -p qqbot-pi -f compose.sqlite.yaml logs --tail 100 bot
curl -f http://127.0.0.1:8080/actuator/health
```

构建会重新编译变化的内容并更新服务，缓存会复用未变化的依赖。只运行 `docker restart` 不会把新源码编译进镜像。生产镜像不包含测试；在源码目录按 `README.md` 运行 Java 回归。


## 4. 迁移现有镜像部署与保护数据

- 如果之前用离线镜像包部署，先备份 `.env` 和整个 state 数据卷，再在新源码目录保留有效 `.env`；不要用模板覆盖真实配置。
- 本文固定 Compose 项目名 `qqbot-pi`，与离线镜像包一致，SQLite 数据默认继续使用 `qqbot-pi_state` 卷。原部署若使用其他项目名或卷名，应保持原值；项目名变了会创建新的空卷，看起来像数据丢失。
- 升级前停止 bot 再备份整个 state 卷，保留 SQLite、会话及 Markdown 文件。不要执行 `down -v`，不要把本机数据库覆盖到服务器现有数据库。
- 服务器修改的源码也需要保留版本 / 备份。本包不带 `.git` 历史，可在服务器自行初始化仓库；后续覆盖源码前保留自己的修改。

## 开发机重新打源码包

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/release/package-source.ps1 -Version __RELEASE_VERSION__ -Format __ARCHIVE_EXTENSION__
```

生成到 `dist/`，不覆盖同版本的已有包。该脚本无需 Docker，排除本机运行数据和依赖缓存。
