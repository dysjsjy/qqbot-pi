# release/
> L2 | Parent: ../AGENTS.md

- `package-source.ps1`：归档明确列出的发布源码，生成文件校验和。
- `package-server.ps1`：构建 Linux amd64 镜像并生成离线部署包。
- `SOURCE-README.md`：源码包的构建与部署说明模板。
- `SERVER-README.md`：离线镜像包的导入、配置与升级说明模板。
- `compose.server.yaml`：离线部署的服务、端口和数据卷。
- `deploy.sh`：验证校验和、导入镜像并启动 Compose 服务。
- `server.env.example`：离线部署配置模板，不含凭据。
- `raw-archive.md`：迁移、恢复与归档导出说明，随离线镜像包分发。

[PROTOCOL]: Update this header on change, then check AGENTS.md
