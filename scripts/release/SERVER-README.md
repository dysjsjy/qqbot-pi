# qqbot-pi __RELEASE_VERSION__ 服务器部署包

适用：**Linux x86_64 / amd64**，Docker Engine 与 Docker Compose V2。不是 ARM64 镜像。包内已包含 bot（管理台、Java 21、Node 22、Pi 运行时）和展示站镜像，无需服务器安装 Java / Node / Maven，无需访问镜像仓库或现场构建。默认使用 SQLite，数据保存在 Docker named volume 中。

不包含任何本机 `.env`、真实账号 / 模型密钥、数据库或历史消息。QQ / 微信与模型服务本身仍需要服务器出站网络和有效凭据。

## 1. 上传、校验并导入镜像

将 `.__ARCHIVE_EXTENSION__` 和同名 `.sha256` 上传到服务器同一目录；ZIP格式解压另需unzip：

```sh
sha256sum -c qqbot-pi-server-__RELEASE_VERSION__-linux-amd64.__ARCHIVE_EXTENSION__.sha256
__EXTRACT_COMMAND__
cd qqbot-pi-server-__RELEASE_VERSION__-linux-amd64
sh deploy.sh load
```

执行 Docker 命令需要相应权限；若普通账号未加入 docker 组，使用有权限的账号操作。`load` 会先校验包内文件与镜像，再导入两个本地版本镜像。

## 2. 配置管理员与模型

```sh
umask 077
cp .env.example .env
chmod 600 .env
sh deploy.sh hash
```

交互输入至少 12 位的管理员密码，终端不会回显原文。将输出的完整 BCrypt 哈希用**单引号**包裹，填入 `.env` 的 `ADMIN_PASSWORD_HASH`；登录网页时使用原始密码。密码不作为命令行参数或包内文件传递。

编辑 `.env`，填写模型服务的 `PI_PROVIDER`、`PI_MODEL`、`PI_API_KEY`，第三方兼容 API 另填 `PI_BASE_URL`。例如 DeepSeek 可使用 `deepseek`、`deepseek-chat`、`https://api.deepseek.com/v1`，密钥使用你自己的。先保持 `QQ_ENABLED=false` 和 `WEIXIN_ENABLED=false`，启动并验证管理台。

`.env` 请限制为部署账号可读写，不要上传到公共仓库。该包强制容器内端口 8080 和 SQLite 数据路径，不需要修改 `PORT`、`DATABASE_URL` 或 `SPRING_PROFILES_ACTIVE`。

## 3. 启动与访问

```sh
sh deploy.sh up
sh deploy.sh status
sh deploy.sh logs bot
curl -f http://127.0.0.1:8080/actuator/health
```

`up` 等待两个容器健康后才成功返回。health 应返回 `{"status":"UP"}`。管理台为服务器本机 `8080`，展示站为 `8081`；默认只监听 `127.0.0.1`，不向公网暴露管理接口。

在自己电脑打开 SSH 隧道：

```sh
ssh -L 8080:127.0.0.1:8080 -L 8081:127.0.0.1:8081 用户名@服务器地址
```

然后访问 `http://localhost:8080` 登录管理台，或 `http://localhost:8081` 访问展示站。公开访问时优先配置 HTTPS 反向代理；管理台经 HTTPS 访问时设置 `COOKIE_SECURE=true`。若必须使用服务器 IP，修改 `.env` 中的 `BOT_BIND_ADDRESS` / `FRONT_BIND_ADDRESS` 为 `0.0.0.0` 并配置防火墙、云安全组，避免无必要的公开暴露。

服务器映射端口通过 `BOT_PORT` / `FRONT_PORT` 设置。预构建展示站的默认管理台端口为 8080；改变映射端口或使用域名时，在展示站的“管理台地址”表单中填写实际地址即可，不会因为修改 `.env` 而重新编译前端。

## 4. 接入 QQ 官方机器人

在 `.env` 中设置：

```dotenv
QQ_ENABLED=true
QQ_OFFICIAL_TRANSPORT=websocket
QQ_APP_ID=你的开放平台AppID
QQ_APP_SECRET=你的开放平台AppSecret
```

重新执行 `sh deploy.sh up`。WebSocket 由服务器主动连接官方平台，不要求公网回调 URL。平台需要开通群 / 单聊权限；沙箱需加入测试账号。收到群消息后在管理台启用对应群的采集 / 回复；私聊默认自动建立独立会话，无需 @。先发送 `/help` 验证收发，再发送普通文本验证模型。


## 5. 数据、升级与排错

RAW记录使用分段日志，旧文件升级需停机执行镜像内CLI。迁移时旧文件与新段暂时共存，预留额外空间；备份整个state卷，不能只复制SQLite。包内[分段归档指南](raw-archive.md)提供inspect/migrate/verify/finalize、配置、互斥与反向导出命令。回退到旧版必须使用配套备份或先导出V9，不能直接把旧JAR接到V10数据。

- SQLite、会话与 Markdown 记忆在默认 `qqbot-pi_state` 卷中；停止使用 `sh deploy.sh down`，不会删除该卷。**不要使用 `down -v` 或清理该数据卷。**
- 备份 SQLite 前先停止 bot，并备份整个 state 卷，而不是只复制数据库正文；其中还包含 Markdown 和会话文件。
- 更新时先备份，解压新包、导入镜像，把旧部署目录的 `.env` 复制到新目录，再执行 `sh deploy.sh up`。保持 Compose 项目名 `qqbot-pi` 和已有数据卷不变。首次升级会自动执行数据库迁移。
- 若已有其他数据库或同名 Compose 项目，不要直接覆盖 / 换库；本包不会迁移其他服务器上现有的数据，也不包含你本机的聊天记录。
- 诊断：`sh deploy.sh logs bot`、`sh deploy.sh status`；修改 `.env` 后执行 `sh deploy.sh up` 生效。
- 这是 SQLite 单实例部署包；ARM64 或多实例部署需要对应构建 / 配置，不能直接把本包当成这些环境的镜像。
