# security/
> L2 | Parent: ../AGENTS.md

- `SecurityConfig.java`：管理员认证、CSRF、会话退出与 CSP。
- `PasswordHash.java`：部署时生成 BCrypt 密码哈希。
- `LoginRateLimit.java`：限制管理员登录请求速率。
- `IngressSizeLimit.java`：限制外部接入请求体大小。

管理 API 使用同源会话与 CSRF。
平台回调使用各自的网关凭据。

[PROTOCOL]: Update this header on change, then check AGENTS.md
