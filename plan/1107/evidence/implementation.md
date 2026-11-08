# MVP 实施与验收记录

日期：2026-11-07。状态：**实现已落地，本地自动化验证通过；真实双平台 MVP 验收未完成**。

本文件区分“代码能力”“本地测试”“完整24项验收”。后端自动化测试的数量与计划的24项验收是不同统计，不互相替代。

## 已实现

| 模块 | 代码与行为 |
| --- | --- |
| 工程 | Java 21、Spring Boot 3.5.16、Maven Wrapper 3.9.14、Flyway SQL、PostgreSQL/pgvector；单应用按包分层 |
| pi | 官方 `@earendil-works/pi-coding-agent@1.5.0`，JSONL RPC；等待最终assistant消息和agent_settled；禁用工具/扩展/MCP/skills；最多2进程，取消/超时/检查点 |
| 会话 | 按群/账号/成员隔离；不可变原生会话检查点；通过 `--fork` 恢复到当前隔离目录，避免旧临时cwd不存在导致恢复失败 |
| 群消息 | 未@文本落库、不自动回复；幂等去重、自身过滤、严格命令、管理员ID、队列与频率限制、generation/epoch防迟到结果 |
| QQ | OneBot 11 array事件、text/at解析、HMAC-SHA1或Bearer回调认证、send_group_msg文本段、平台回执与心跳；真实NapCat版本待选定 |
| 微信 | 规范化HTTP入站、主动发送、心跳桥接契约；**未包含微信登录或某个具体网关实现** |
| RAG | OpenAI兼容embedding、模型维数锁定、持久向量、同群Top-5、阈值、引用编号验证、无依据回复、失败索引重提、清除与保留期 |
| 日报 | Asia/Shanghai昨日半开窗口、10秒扫描、唯一业务日期、首次来源快照、完整输入分块/合并、覆盖/缺口/非文本计数、显式一次重试 |
| 预算 | 日报1000条/100000码点、40次调用、30分钟；即时单工作线程、日报单工作线程、pi总共最多2进程；最多5启用群 |
| 发送 | 持久Outbox；最多3次明确未接收重试；不确定结果UNKNOWN不自动重发，停止后续分段；发送前再次核对scope/epoch/generation |
| 管理台 | React/TypeScript/Vite；会话登录/CSRF、群配置、记忆检索与确认清除、定时配置、分页运行记录、报告与UNKNOWN状态 |
| 运维 | 多阶段Dockerfile、Compose、环境模板、BCrypt初始化工具、运行/备份恢复手册；正文30天、临时上下文7天、审计90天清理 |

## 已执行的本地验证

环境：Windows、Microsoft OpenJDK 21.0.9、Node 22.22.1、npm 10.9.4。前端React19.3.1 / Vite8.3.6。SQL测试使用PGlite0.5.15（引擎报告PostgreSQL18.6）+ pgvector扩展0.0.16包；**不代表已验证目标PG17服务的锁和多连接行为**。

| 验证 | 结果 | 实际范围 |
| --- | --- | --- |
| Maven Wrapper `-Pfrontend verify` | PASS | 24个后端测试、0失败/0错误/0跳过；生成包含React静态资源的可执行JAR |
| 前端 `npm run build` | PASS | TypeScript类型检查与Vite生产构建 |
| Playwright Chromium流程 | PASS | 1条综合流程：登录、4页、来源文本安全显示、日报提交、UNKNOWN禁重试、确认清除、退出；API为模拟响应 |
| Spring Security/MockMvc | PASS | 5项：未授权401、无CSRF403、登录/会话/退出、回调认证/账号绑定、OneBot解析 |
| 完整Spring上下文+数据库API | PASS | 2项：群配置与定时配置落库、revision冲突、scope不可变、10次触发同一日报、非法时间与清除名称拒绝 |
| 数据库业务集成 | PASS | 11项：100条各重投3次/10条@、命令、向量作用域、清除epoch、取消迟到结果、昨日边界与冻结快照、调度去重、空日/1001条超限、UNKNOWN停止后续、旧generation禁止发送、分块日报与保留期 |
| HTTP适配 | PASS | 3项：OneBot纯文本及平台回执、空回执UNKNOWN、embedding按index还原并拒绝零向量 |
| pi协议 | PASS | 3项：中文/emoji、thinking/stderr过滤与检查点；超时/取消；真实1.5.0 CLI调用本地SSE模型接口并恢复原生会话 |
| Compose配置解析 | PASS | `docker compose --env-file .env.example config --no-env-resolution --quiet`，不代表镜像已构建 |

可复现命令见 [项目README](../../../README.md) 与 [运行手册](../../../docs/operations.md)。机器可读测试摘要见 [verification.json](verification.json)。Maven原始报告在项目 `target/surefire-reports`；浏览器报告在 `test-support/playwright-report`，两者为可重新生成的构建产物。

页面证据：[群管理](admin-groups.png)、[运行报告与UNKNOWN](admin-report.png)。截图采用测试数据，不是真实群接入证明。

## 24项验收状态

下表状态针对原清单**完整场景**。NOT_RUN并非没有代码，表示仍有未执行步骤；BLOCKED表示缺少外部账号、模型或运行环境。只有原标准全部满足才标PASS。

| ID | 状态 | 已有证据 / 剩余工作 |
| --- | --- | --- |
| AC-GATE-01 | BLOCKED | pi已锁定；QQ/微信群能力、实际网关版本和真实embedding中文样本需凭据 |
| AC-BASE-01 | NOT_RUN | 本次构建/测试通过；干净机器完整启动、生产缺配置失败与目标PG17迁移尚需演练 |
| AC-PI-01 | BLOCKED | 真实CLI+本地模型接口及协议桩通过；真实模型5次问答/分段/合并未执行 |
| AC-PI-02 | NOT_RUN | 超时、取消、输出过滤已测；需补3秒宽限故障、60秒实际超时与真实恶意指令组合 |
| AC-PI-03 | NOT_RUN | 原生会话恢复已测；成员交错、强杀恢复、并发上限联合故障未全跑 |
| AC-GRP-01 | PASS | 本地数据库100条/300次投递，10条@；未授权与自身回声不保存正文 |
| AC-GRP-02 | NOT_RUN | 核心命令/正文内嵌命令/summary权限已有测试；双成员及@他人矩阵需补齐 |
| AC-GRP-03 | NOT_RUN | 作用域、旧generation、UNKNOWN及缺口机制已有；重启与不同账号组合需完整演练 |
| AC-QQ-01 | BLOCKED | OneBot代码已实现；无真实QQ账号、群和运行网关 |
| AC-WX-01 | BLOCKED | 桥接接口已实现；具体微信群网关与账号未提供，登录恢复待实测 |
| AC-RAG-01 | NOT_RUN | 实际SQL+向量写入/检索/幂等覆盖；200条、持久服务重启和embedding中断矩阵未全跑 |
| AC-RAG-02 | BLOCKED | 候选引用校验已实现；真实中文20问、5个无依据问题需模型与标注集 |
| AC-RAG-03 | NOT_RUN | 两群SQL作用域隔离已测；不同账号、提示注入与模型输入完整断言待补 |
| AC-RAG-04 | NOT_RUN | epoch清除/取消/旧结果拒绝/恢复索引已有；完整并发故障场景待补 |
| AC-JOB-01 | NOT_RUN | 昨日00:00/23:59:59/今日00:00半开边界、09:00扫描与去重已测；08:59:59推进场景待补 |
| AC-JOB-02 | NOT_RUN | 唯一键、重复触发、下次时间、快照重试已测；跨日停机与配置重启需真实服务演练 |
| AC-JOB-03 | BLOCKED | 分块清单、快照、覆盖计数与完整块处理已测；1000条5事实的真实模型质量未验证 |
| AC-JOB-04 | BLOCKED | 空日/超限/Outbox故障本地已测；真实主动群发送及30分钟故障预算待实测 |
| AC-WEB-01 | PASS | Spring认证/CSRF/退出与Chromium脚本文本安全显示通过；页面接口不返回密钥 |
| AC-WEB-02 | NOT_RUN | 四页、持久配置、非法时间、分页实现已具备；重启、跨页和全表单矩阵待补 |
| AC-WEB-03 | NOT_RUN | 10次API触发同一runId、UNKNOWN无重试、确认清除已测；进行中与全部终态组合待补 |
| AC-OPS-01 | BLOCKED | Dockerfile/Compose已写且配置解析通过；当前Docker Engine未运行，镜像实际构建启动未执行 |
| AC-OPS-02 | NOT_RUN | 保留期清理已有自动化；备份恢复流程已写，真实恢复演练未执行 |
| AC-OPS-03 | NOT_RUN | 队列、进程与索引重试上限已实现；2小时负载和p95/资源采样尚未执行 |

## 已知限制与后续顺序

1. 先提供并启动QQ OneBot网关，配置凭据、回调验签与测试群；完成20条普通文本、@问答与主动昨日日报。
2. 选定支持未@群采集和主动发群的微信网关，按桥接契约接入，验证登录失效/恢复；不自行研发微信登录协议。
3. 使用固定真实生成/embedding模型完成20问中文命中率与1000条关键事实日报。
4. 在目标Docker/PG17环境完成迁移、多连接事务、单实例锁、重启、备份恢复与2小时冒烟。

MVP仍维持单实例、单管理员、最多5群；没有增加Redis、MQ、多副本、私聊或通用工作流平台。数据库测试中的Lifecycle被替换以避开PGlite会话锁局限，不能据此宣称生产锁已验证。浏览器API模拟与Spring API测试分别执行，尚未进行真实平台到网页的完整端到端测试。
