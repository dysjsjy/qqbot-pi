# 脚本说明

项目使用 SQLite 和 QQ 官方机器人。完整启动与接入步骤见 [项目 README](../README.md)。以下命令在项目根目录执行。

| 脚本 | 用途 |
|---|---|
| `start.ps1` | 检查 JDK 17 / 21、Node 22.19+、模型与官方 QQ 凭据，引导设置管理员密码并启动 |
| `admin/password-hash.ps1` | 生成 BCrypt 哈希，`-SaveToEnv` 保存到配置 |
| `release/package-source.ps1` | 打包源码，排除本机配置、运行数据、依赖和视觉证据 |
| `release/package-server.ps1` | 构建 Linux x86_64 离线镜像部署包 |
| `release/deploy.sh` | 在服务器加载离线镜像并部署 |
| `memory/raw-archive.ps1` | 停机副本inspect/migrate/verify/finalize/export-legacy；显式指定DB、state、report，可用`-Jar`免源码运行 |

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Check
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1 -Build
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\admin\password-hash.ps1 -SaveToEnv
```

首次需要先复制 `.env.qq-official.example` 并填写配置；已有 `.env` 保留原文件。运行脚本不会替你启用群采集或验证平台与模型连通性。

发布包默认版本为 0.2.0，可用 `-Version` 指定。构建产物为 `target/qqbot-pi-0.2.0-SNAPSHOT.jar`。默认输出 `tar.gz`；生成服务器 ZIP 前先生成同版本的源码 ZIP。离线包自带部署、端口和备份说明。

RAW 工具不会启动 Spring、渠道或模型。修改命令取得数据库与 state 双锁；`inspect` 和 `verify` 只读。报告放在 memory 目录外，不含正文。备份、容器运行和 V9 反向导出见 [分段归档](release/raw-archive.md)。
