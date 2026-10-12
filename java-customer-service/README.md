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
| 路由Agent | 航程加载 | 航班、预订、座位、FAQ、补偿 |
| 航班信息 | 状态、替代航班 | 预订、路由Agent |
| 预订与取消 | 候选、改签、取消 | 座位、补偿、路由Agent |
| 座位与特殊服务 | 标准选座、前排特殊座位、打开图 | 补偿、路由Agent |
| FAQ | 政策查询 | 路由Agent |
| 补偿 | 政策查询、补偿申请 | FAQ、路由Agent |

配置见 [AgentCatalog.java](src/main/java/com/example/airline/AgentCatalog.java)。无 Baggage Agent、真实行李挂失、支付或实际退款服务。

交接工具为每个角色生成独立的 `target` 枚举，提示词同时说明精确角色 ID。服务端仍强制校验白名单；模型请求非法目标时记录来源及允许目标并明确终止，不自动扩大权限。

[AgentRunner.java](src/main/java/com/example/airline/AgentRunner.java) 持有执行控制权，每次模型调用重新选择 prompt 和工具；业务回复使用 Spring AI `ChatModel.stream`，输入检查使用 `call`，不启用自动工具执行 advisor。交接只允许白名单目标，不允许与业务工具混用。历史保留 `AssistantMessage.ToolCall` 与匹配 ID 的 `ToolResponseMessage`；下一轮继续上一轮 active Agent，不重复路由。

### 可观测页面

- 页面品牌为“微语航空客服”，使用面向客户的中文服务文案，不展示框架版本、工具标识和内部状态版本。
- 中文客户聊天、六角色高亮、演示业务状态和输入检查。
- SSE 实时展示 run、handoff、tool_call、tool_output、operation_result 等。
- 业务事件推送**有版本号的完整快照**；模型公开回复通过 `reply_delta` 增量事件实时显示，带会话、轮次、步骤和版本标识。前端按动画帧合并渲染，不为每个片段重建全部对话。
- 重连发送最新快照，包含当前已生成的回复；最终快照以持久化回复替换草稿，不重复追加。失败时移除未完成草稿，展示明确错误。不同模型步骤的草稿不会混在一起。
- Markdown 使用本地打包的 Marked（GFM 表格、嵌套列表、代码块、引用、链接等）与 DOMPurify 白名单清理；不依赖 CDN，不执行模型提供的 HTML 脚本或事件属性。用户消息仍以纯文本显示。
- 浏览器刷新或关闭后，断开的异步响应不再写 JSON 错误正文；失效监听器会移除，I/O 失败后的流关闭由容器处理。建立流之前的 API 错误仍返回 JSON。
- 136 座互动座位图，布局与 FAQ 共用 [AirlineData.java](src/main/java/com/example/airline/AirlineData.java)。座位图工具成功后，回复携带 `OPEN_SEAT_MAP` 操作，聊天气泡显示“查看座位图”按钮；点击打开右侧 drawer，不再在页面下方固定展示。抽屉内可刷新、选座并确认申请，关闭后可从原消息重新打开。历史消息保留操作入口；切换会话关闭抽屉，航程状态变化后重新获取当前座位图。生成回复或人工服务暂停时可以查看，但不能提交选座或确认。
- 查看待确认操作、提交确认、刷新业务结果。
- 浏览器同会话标签页可以恢复；服务重启或过期后明确创建新会话。

这是独立 API，不兼容 ChatKit thread/item 协议。公开回复在模型生成时流式显示，不是完成后模拟打字。输入检查、工具执行和客服交接仍需等待相应步骤完成；工具参数及隐藏推理不推送到对话。写操作仍需页面确认。

前端运行依赖及许可证已随静态资源打包，普通 Maven 构建不要求安装 Node.js。更新浏览器依赖及运行前端回归测试：

```bash
npm ci
npm run vendor
npm test
```

修改 Java 后应重新构建并重启运行实例；不要只复制前端资源到旧服务来验证流式链路。

### 写操作确认、幂等与失败

所有改签、取消、选座、特殊座位和补偿先返回 `NEEDS_CONFIRMATION`，页面展示具体参数。客户确认后才写入演示状态；模型无法自行提交确认。“OK”或其他聊天文本不代表授权。

- 同一 `operationId` 重复确认返回原结果，不重复写入历史或业务状态。
- 同一聊天 `requestId` + 消息重试返回原回复；不同消息复用 ID 返回冲突。
- 候选过滤为空不回退全部；未知航班不当作准点；无匹配不进行占位订票。
- 取消保存 `CANCELLED`；后续选座等写入被拒绝。
- 操作五分钟过期；航程/座位状态变更后，旧操作版本明确拒绝。
- 确认后可再发消息继续流程。一次确认不会自动触发下一轮付费模型推理。
- 部分步骤已完成、后续失败时保留完成结果，不会“整轮自动重试”。

### 政策来源、咨询标签与人工申请

按 [规划文档](../SPRING_AI_REFERENCE.md) 第19–22节借鉴 Claude 客服 demo 的服务体验，保留航空业务和六 Agent：

- 本地政策库支持中文/英文复合检索、Wi-Fi 标点归一化。每条资料有稳定 ID、标题、版本和完整正文；座位资料使用演示座位图总数。
- 聊天回复关联本轮实际查询来源，侧栏保留最近15次查询，点击可打开完整政策条目。界面称“查询到的参考资料”，不代表已经验证回答的逐句引用。
- 检索是确定性关键词匹配，不是向量 RAG，不依赖 AWS；无匹配明确返回 NOT_FOUND 并建议人工，不伪造来源或可信度百分比。
- 相关性检查同时返回情绪、咨询分类及明确人工意愿，没有额外模型调用。字段缺失或非法枚举使检查失败、停止业务执行，不静默回退通过。
- 情绪和咨询分类标签仅在侧栏“咨询概况”展示，不在聊天消息气泡中显示。不显示 thinking、隐藏推理、内部工具名称或模型配置。标签只是辅助判断，不是客户心理诊断或业务授权。
- 服务端根据标签、预订状态、补偿凭证和待确认操作生成最多3个建议问题；已取消预订不推荐改签/选座，已有凭证不推荐重复补偿。
- 直接人工申请按钮无需 DeepSeek 可用：登记可查询申请号、原因、时间、最多10条公开对话摘要。重复申请返回原申请，每会话最多8单。
- 明确提出人工服务的聊天消息通过检查后也可登记；仅负面情绪不会自动登记。
- 登记申请暂停智能聊天、选座提交和操作确认，清理未执行待确认操作。已完成业务不撤回。“恢复智能服务”取消当前申请并回到路由Agent，保留历史。
- **人工功能仅为内存演示申请，不连接真人、坐席或工单平台，也不承诺响应时限。** 页面始终提示“尚未接通真人”。
- 所有来源正文、模型文本、摘要采用安全文本展示；详情 API 只允许本会话实际查到的受控资料 ID。

本轮验证：Java 21 下 `mvn -o verify` 通过41项测试，包含来源一致性、归属/CSRF、标签错误、申请幂等、互斥与暂停写入；本地模拟 DeepSeek 的打包应用完成浏览器政策详情、建议问题、申请/刷新/恢复、切换会话和136座选座确认联调。没有调用真实 DeepSeek 账户或真实航空/坐席服务，真实模型的标签与交接质量仍需配置 Key 后验证。

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
| `GET /api/threads/{id}/knowledge/{sourceId}` | 本会话查询过的完整政策条目，含 ID/标题/版本/分类/正文 |
| `POST /api/threads/{id}/human-support` | `{"reason":"需要人工协助的原因"}`；登记或返回当前 REQUESTED 申请 |
| `POST /api/threads/{id}/human-support/resume` | 取消当前申请并恢复智能服务，重复恢复返回同一 CANCELLED 单 |

snapshot 和聊天条目增加 `insights`、`suggestedQuestions`，snapshot 还包含 `humanRecommended`、`knowledgeHistory`、`humanCases`，assistant 条目包含 `sourceIds`。申请历史通过归属校验后的 snapshot 查询。人工模式中的新聊天和业务写请求返回409 `HUMAN_SUPPORT_PENDING`；相同聊天请求的重放仍返回缓存结果。

运行结果状态：`COMPLETED` / `REJECTED` / `FAILED`。HTTP 200 只表示完成一次应用层处理，调用方必须读取业务状态；校验、归属、并发冲突等返回结构化 HTTP 错误 `{code,message}`。

snapshot 包含 `threadId`、`activeAgentId`、`runStatus`、`revision`、`eventCursor`、公开 `context`、聊天、事件、检查、待确认操作及 Agent 元数据。事件包含 UUID、runId、threadId、递增 sequence、Unix 毫秒 timestamp；工具事件额外带 toolCallId。最近 300 条事件会保留，不是完整审计日志。

## 代码位置

- [DeepSeekGateway.java](src/main/java/com/example/airline/DeepSeekGateway.java)：Spring AI DeepSeek、超时与并发限制。
- [InputChecks.java](src/main/java/com/example/airline/InputChecks.java)：最新消息的相关性与越狱检查，不将历史交给检查模型。
- [BusinessTools.java](src/main/java/com/example/airline/BusinessTools.java)：参数 schema、类型化结果、mock 业务、确认与幂等。
- [PolicyKnowledge.java](src/main/java/com/example/airline/PolicyKnowledge.java)：受控本地政策目录与检索。
- [ServiceInsights.java](src/main/java/com/example/airline/ServiceInsights.java)：分类/情绪校验与后续问题。
- [HumanSupport.java](src/main/java/com/example/airline/HumanSupport.java)：演示人工申请、幂等恢复和公开摘要。
- [Conversation.java](src/main/java/com/example/airline/Conversation.java)、[ConversationStore.java](src/main/java/com/example/airline/ConversationStore.java)：单会话执行锁、状态、归属、容量与 TTL。
- [EventHub.java](src/main/java/com/example/airline/EventHub.java)：版本化快照、SSE 和监听清理。
- [AirlineController.java](src/main/java/com/example/airline/AirlineController.java)：REST、CSRF、业务 action。
- [static/](src/main/resources/static/)：无需 Node.js 构建的中文页面。
- [测试](src/test/java/com/example/airline/)：工具、编排、HTTP 边界和 DeepSeek 协议。

## 演示范围与生产边界

- 真实 DeepSeek 推理，但所有航空数据均为固定 mock，日期固定在 2024 年。模拟凭证金额不是政策承诺。
- 航程按新会话场景选定；未选场景时仅根据明确航班/地点加载，不随机生成客户预订。
- 同 thread 的聊天、选座、确认和人工申请/恢复互斥；不同 thread 可同时运行。
- 状态完全在内存，重启丢失，单实例使用；TTL 清理与删除关闭 SSE。
- 每会话最多 120 条展示消息/600 条模型历史、128 条已确认操作、8 个待确认操作、4 个 SSE 连接；总会话最多 500。
- 浏览器会话隔离不是账号身份认证。禁止把此本机 demo 直接当作公网生产服务；上线需接入身份认证、TLS、持久化、共享锁、速率限制、可信航空 API 与资格/授权检查。
- 座位占用是固定演示库存，只在当前会话模拟修改，没有跨乘客真实库存锁。
- 同步 SSE 没有慢消费者独立有界队列；生产需加传输背压、写入超时和限流。此版本用监听数、生命周期、快照大小限制控制演示规模。
- 医疗备注、内部航程及案件标识不进公开 context；日志不打印模型输入或工具参数。仅使用合成数据，真实数据还需建立分级访问、脱敏与审计；不要开启模型请求 DEBUG 日志。
- 模型检查不是认证或工具授权的替代品。JSON/检查异常明确失败，不伪装为通过。
- 真实人工接管、持久化、真实退款/行李业务和逐 token 输出没有实现，不应按已完成能力验收。本轮只实现可查询的演示人工申请。
