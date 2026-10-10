# Spring AI DeepSeek 航空客服演示

可单独复制、构建和运行的 Java 项目，无需启动原 Python 后端或 Next.js 前端。

## 技术栈与运行

- Java 21+，Maven 3.9+
- Spring Boot **4.1.1**
- Spring AI BOM / DeepSeek starter **2.0.1**
- 原生 Spring AI `DeepSeekChatModel`，默认 `deepseek-v4-flash`
- Spring MVC REST + SSE、浏览器原生 HTML/CSS/JavaScript

```bash
cd java-customer-service
export DEEPSEEK_API_KEY='你的 DeepSeek API Key'
# 可选：使用账户当前支持的其他 DeepSeek 模型
# export DEEPSEEK_MODEL='deepseek-v4-pro'
mvn spring-boot:run
```

访问 <http://127.0.0.1:8080>，选择正常或延误场景创建会话。Key 仅通过服务端环境变量读取，不放进页面、代码或 Git；没有有效 Key 时不能执行真实推理。项目不自动读取 `.env`。

```bash
mvn test
mvn package
java -jar target/airline-customer-service-0.0.1-SNAPSHOT.jar
```

测试使用可脚本化模型、MockMvc 和本机模拟 DeepSeek HTTP 服务，不使用真实 Key，不产生 API 费用。`DeepSeekProtocolTest` 通过真正的 Spring AI DeepSeek 适配器验证 JSON schema、handoff 和工具历史往返，不只测试自定义接口。

可配置环境变量：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 空，需自行配置 | DeepSeek 服务端凭据 |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | DeepSeek API 地址 |
| `DEEPSEEK_MODEL` | `deepseek-v4-flash` | 模型标识，需支持工具调用 |
| `SERVER_ADDRESS` | `127.0.0.1` | 演示默认只监听本机 |
| `SERVER_PORT` | `8080` | HTTP 端口 |

默认模型与 Spring AI 2.0.1 的 `DeepSeekApi.DEFAULT_CHAT_MODEL` 对齐；该版本将 `deepseek-chat` 和 `deepseek-reasoner` 标记为旧名称。此客服实现显式使用非 thinking 模式，以避免 thinking 默认行为忽略 temperature；需要推理模式时应单独验证供应商参数及工具历史要求。

执行预算、模型超时、会话 TTL 和会话容量配置见 [application.yml](src/main/resources/application.yml)。每轮最多 16 次业务模型调用、24 次工具请求、8 次交接；总预算 120 秒，单次模型调用最多 40 秒。输入检查使用共享时间预算，超时或无效输出一律不执行业务工具。模型同时最多 8 个在途调用；超时取消不保证底层 HTTP 请求立即断开，但未返回的模型结果不会触发业务写操作。

## 功能

### 六 Agent

| 角色 | 工具 | 可以交接给 |
| --- | --- | --- |
| 分诊 | 航程加载 | 航班、预订、座位、FAQ、补偿 |
| 航班信息 | 状态、替代航班 | 预订、分诊 |
| 预订与取消 | 候选、改签、取消 | 座位、补偿、分诊 |
| 座位与特殊服务 | 标准选座、前排特殊座位、打开图 | 补偿、分诊 |
| FAQ | 政策查询 | 分诊 |
| 补偿 | 政策查询、补偿申请 | FAQ、分诊 |

配置见 [AgentCatalog.java](src/main/java/com/example/airline/AgentCatalog.java)。无 Baggage Agent、真实行李挂失、支付或实际退款服务。

[AgentRunner.java](src/main/java/com/example/airline/AgentRunner.java) 持有执行控制权，每次模型调用重新选择 prompt 和工具；直接调用 Spring AI `ChatModel.call`，不启用自动工具执行 advisor。交接只允许白名单目标，不允许与业务工具混用。历史保留 `AssistantMessage.ToolCall` 与匹配 ID 的 `ToolResponseMessage`；下一轮继续上一轮 active Agent，不重复分诊。

### 可观测页面

- 中文客户聊天、六角色高亮、演示业务状态和输入检查。
- SSE 实时展示 run、handoff、tool_call、tool_output、operation_result 等。
- 每次 SSE 推送**有版本号的完整快照**，前端替换而不是追加；重连发送最新快照，不承诺逐条事件重放。
- 136 座互动座位图，布局与 FAQ 共用 [AirlineData.java](src/main/java/com/example/airline/AirlineData.java)。
- 查看待确认操作、提交确认、刷新业务结果。
- 浏览器同会话标签页可以恢复；服务重启或过期后明确创建新会话。

这是独立 API，不兼容 ChatKit thread/item 协议。流式部分是运行状态和事件，最终客服回复在模型整轮完成后显示，**不是 token 级流式回复**。

### 写操作确认、幂等与失败

所有改签、取消、选座、特殊座位和补偿先返回 `NEEDS_CONFIRMATION`，页面展示具体参数。客户确认后才写入演示状态；模型无法自行提交确认。“OK”或其他聊天文本不代表授权。

- 同一 `operationId` 重复确认返回原结果，不重复写入历史或业务状态。
- 同一聊天 `requestId` + 消息重试返回原回复；不同消息复用 ID 返回冲突。
- 候选过滤为空不回退全部；未知航班不当作准点；无匹配不进行占位订票。
- 取消保存 `CANCELLED`；后续选座等写入被拒绝。
- 操作五分钟过期；航程/座位状态变更后，旧操作版本明确拒绝。
- 确认后可再发消息继续流程。一次确认不会自动触发下一轮付费模型推理。
- 部分步骤已完成、后续失败时保留完成结果，不会“整轮自动重试”。

## 推荐体验流程

### 延误改签与补偿

1. 创建“巴黎 → 纽约 → 奥斯汀”延误场景。
2. 发送“请查询我的航班状态，并提供延误后的替代航班”。
3. 申请改到 `NY950`，在待确认区确认具体操作。
4. 打开座位图，选择可用座位并再次确认。
5. 发送“请查询补偿政策并申请酒店和餐食凭证”，确认后查看演示凭证。

模型可能提问或采用不同交接顺序，程序保证权限、确认和状态边界，不保证某句话必然触发完整链路。需要稳定业务流程时，应在现有编排上增加明确任务状态，而不是依靠提示词猜测。

### 正常航程与取消

1. 创建正常场景，查询 `FLT-123`。
2. 问“Wi-Fi 和行李政策是什么？”。
3. 申请取消，确认后查看 `CANCELLED`。

中文和复合 FAQ、Wi-Fi 标点归一化、136 座统一布局、显式取消状态等是相较 Python 演示的有意增强。

## 接口

所有会话接口使用 HttpOnly、SameSite=Strict 的服务器会话 cookie。先访问 `GET /api/session` 获取 CSRF token；写请求须带 `X-CSRF-Token`。读取状态、SSE、选座、确认、删除均校验浏览器会话归属。不存在或不属于当前浏览器的 thread 返回 404，不偷偷创建替代会话。

| 方法和路径 | 请求 / 返回 |
| --- | --- |
| `GET /api/session` | 初始化浏览器，返回 `csrfToken` |
| `GET /api/health` | 存活状态，不检测模型可用性 |
| `POST /api/threads` | `{"scenario":"disrupted"}` 或 `on_time`；返回 snapshot |
| `GET /api/threads/{id}` | 当前完整 snapshot |
| `DELETE /api/threads/{id}` | 删除状态及监听器，正在执行返回 409 |
| `POST /api/threads/{id}/messages` | `{"requestId":"唯一ID","message":"客户消息"}` |
| `GET /api/threads/{id}/events` | SSE snapshot、heartbeat；15 秒心跳 |
| `GET /api/threads/{id}/seats` | `version`、航班、当前座位、136 个 seat |
| `POST /api/threads/{id}/seats` | `{"seatNumber":"15C","version":1}`；申请选座 |
| `POST /api/threads/{id}/operations/confirm` | `{"operationId":"待确认ID"}`；提交并返回结果 |

运行结果状态：`COMPLETED` / `REJECTED` / `FAILED`。HTTP 200 只表示完成一次应用层处理，调用方必须读取业务状态；校验、归属、并发冲突等返回结构化 HTTP 错误 `{code,message}`。

snapshot 包含 `threadId`、`activeAgentId`、`runStatus`、`revision`、`eventCursor`、公开 `context`、聊天、事件、检查、待确认操作及 Agent 元数据。事件包含 UUID、runId、threadId、递增 sequence、Unix 毫秒 timestamp；工具事件额外带 toolCallId。最近 300 条事件会保留，不是完整审计日志。

## 代码位置

- [DeepSeekGateway.java](src/main/java/com/example/airline/DeepSeekGateway.java)：Spring AI DeepSeek、超时与并发限制。
- [InputChecks.java](src/main/java/com/example/airline/InputChecks.java)：最新消息的相关性与越狱检查，不将历史交给检查模型。
- [BusinessTools.java](src/main/java/com/example/airline/BusinessTools.java)：参数 schema、类型化结果、mock 业务、确认与幂等。
- [Conversation.java](src/main/java/com/example/airline/Conversation.java)、[ConversationStore.java](src/main/java/com/example/airline/ConversationStore.java)：单会话执行锁、状态、归属、容量与 TTL。
- [EventHub.java](src/main/java/com/example/airline/EventHub.java)：版本化快照、SSE 和监听清理。
- [AirlineController.java](src/main/java/com/example/airline/AirlineController.java)：REST、CSRF、业务 action。
- [static/](src/main/resources/static/)：无需 Node.js 构建的中文页面。
- [测试](src/test/java/com/example/airline/)：工具、编排、HTTP 边界和 DeepSeek 协议。

## 演示范围与生产边界

- 真实 DeepSeek 推理，但所有航空数据均为固定 mock，日期固定在 2024 年。模拟凭证金额不是政策承诺。
- 航程按新会话场景选定；未选场景时仅根据明确航班/地点加载，不随机生成客户预订。
- 同 thread 的聊天、选座和确认互斥；不同 thread 可同时运行。
- 状态完全在内存，重启丢失，单实例使用；TTL 清理与删除关闭 SSE。
- 每会话最多 120 条展示消息/600 条模型历史、128 条已确认操作、8 个待确认操作、4 个 SSE 连接；总会话最多 500。
- 浏览器会话隔离不是账号身份认证。禁止把此本机 demo 直接当作公网生产服务；上线需接入身份认证、TLS、持久化、共享锁、速率限制、可信航空 API 与资格/授权检查。
- 座位占用是固定演示库存，只在当前会话模拟修改，没有跨乘客真实库存锁。
- 同步 SSE 没有慢消费者独立有界队列；生产需加传输背压、写入超时和限流。此版本用监听数、生命周期、快照大小限制控制演示规模。
- 医疗备注、内部航程及案件标识不进公开 context；日志不打印模型输入或工具参数。仅使用合成数据，真实数据还需建立分级访问、脱敏与审计；不要开启模型请求 DEBUG 日志。
- 模型检查不是认证或工具授权的替代品。JSON/检查异常明确失败，不伪装为通过。
- 人工接管、持久化、真实退款/行李业务和逐 token 输出没有实现，不应按已完成能力验收。
