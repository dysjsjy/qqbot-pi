# channel/
> L2 | Parent: ../AGENTS.md

- `ChannelSender.java`：按平台分派 `MessageTransport` 请求到出站适配器。
- `ConnectionMonitor.java`：记录平台账号心跳与采集缺口。
- `GatewayHttpClient.java`：发送桥接 HTTP 请求并按回执判定投递结果。
- `GroupIngressController.java`：验证桥接账号，提交微信入站事件与心跳。
- `OfficialQqClient.java`：缓存官方令牌，校验投递与网关地址，按回执决定能否重试。
- `OfficialQqHttp.java`：组装并发送 QQ 官方 HTTP 请求，在传输边界解码令牌、网关和消息回执。
- `OfficialQqConnectionController.java`：提供官方 QQ 长连接状态。
- `OfficialQqController.java`：验证官方 QQ 回调签名并提交事件。
- `OfficialQqEvents.java`：统一解码官方 QQ 回调与长连接事件，识别当前机器人的提及。
- `OfficialQqGatewayProtocol.java`：校验 Hello 与 READY 帧，编码标识、恢复和心跳请求。
- `OfficialQqSignature.java`：签名与验证官方 QQ 回调数据。
- `OfficialQqWebSocket.java`：收集有界文本帧，串行维护连接状态，入站提交后更新重放水位。
- `OutboxWorker.java`：认领和发送有效分段，分别汇总日报与活跃榜的投递回执。
- `WeixinBridgeClient.java`：向微信桥接网关发送群聊或私聊消息。

[PROTOCOL]: Update this header on change, then check AGENTS.md
