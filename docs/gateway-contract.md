# 微信群网关桥接契约（MVP v1）

该接口是 Java 与外部网关的约定，不包含微信登录或私有协议实现。接入方必须能读取授权微信群的未@文本、识别稳定群/成员/消息ID，并主动发送到群。选定网关及其版本、账号能力、重连与历史重放限制须补入 `plan/1107/evidence`，之后执行 AC-WX-01。

## 入站

`POST /api/ingress/weixin`，`Content-Type: application/json`，`X-Bot-Token: <WEIXIN_TOKEN>`：

```json
{
  "channel": "WEIXIN",
  "accountId": "stable-bot-account",
  "groupId": "stable-group-id",
  "messageId": "stable-message-id",
  "senderId": "stable-member-id",
  "senderName": "成员昵称",
  "occurredAt": "2026-11-06T08:30:00Z",
  "text": "保留原始文本",
  "mentionsBot": false,
  "isSelf": false,
  "kind": "TEXT"
}
```

`kind` 可省略，按 TEXT 处理。纯图片/语音/文件事件使用 `kind=NON_TEXT,text=""`，只计覆盖数量，不索引、不调用生成。混合消息只分析可读取文本，不进行 OCR/ASR。

accountId 必须匹配配置；@ 必须由真实 mention 元数据判断，不根据“@昵称”字符串猜测。自身发送标记 isSelf 并可在网关先过滤。messageId 在同群内稳定，重投必须保持不变。消息事件最多128KiB，文本最多8000 UTF-16单元；其它ID/昵称长度见 Java 请求模型。超过保留窗口30天的消息不重新执行。成功响应：

```json
{"accepted":true,"duplicate":false,"messageId":"internal-uuid"}
```

重复事件 accepted=false,duplicate=true；未授权群 accepted=false，正文不保存，可能登记为未启用的发现群。返回401/403时修复认证/账号配置；暂时网络失败可重投同一ID。

## 主动发送

Java 请求 `POST <WEIXIN_BASE_URL>/api/bot/send`，`Authorization: Bearer <WEIXIN_TOKEN>`：

```json
{"groupId":"stable-group-id","text":"回复或日报文本","requestId":"outbox-uuid"}
```

网关必须把 text 作为普通文本发送，不解释为指令或消息编码。每段最多500码点。网关应以 requestId 幂等，但 Java 仍对不确定结果采取不自动重发策略。

明确接收后返回200与 `{"status":"accepted","messageId":"platform-id"}`。明确未接收、可稍后重试返回 `{"status":"retryable"}` 或HTTP429；明确拒绝返回 `{"status":"rejected"}`。禁止先发送再返回retryable。其它非明确回执、5xx、读超时、断线均视为UNKNOWN；后续分段停止，需人工查群记录核对。UNKNOWN任务不提供自动重发按钮。

## 登录状态与采集缺口

每30秒请求 `POST /api/ingress/weixin/heartbeat`，认证同入站：

```json
{"accountId":"stable-bot-account","online":true}
```

微信退出/登录失效立即发送 online=false；恢复后发true。超过90秒未收到已建立的心跳，标记UNKNOWN并记录缺口。没有心跳或网关未重放的区间无法证明完整，首次启动/服务重启会记录采集未知区间。日报展示已采集数量、时间窗口、快照和已知缺口，不能声称覆盖尚未接入时的历史。

## QQ OneBot 11

优先使用 `/callbacks/qq/onebot`，对原始请求体执行 HMAC-SHA1 验签，secret与网关配置一致。无secret模式必须提供Bearer认证。OneBot HTTP API请求用独立access token（`QQ_TOKEN`）。必须启用 array 消息格式和心跳；群消息事件的 text、at 段分别解析，主动发送使用 `send_group_msg` 文本段，避免把用户文本解释成CQ码。非群事件忽略。规范化 `/api/ingress/qq` 仅用于已有桥接层，不替代真实QQ测试。
