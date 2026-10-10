# 航空客服多 Agent 示例：Spring AI Java 实现参考

## 1. 文档用途与依据

这份文档用于指导已有 Spring AI Java Agent 客服项目完善多 Agent 编排、业务工具、会话状态和可观测界面。重点是复用业务机制，而不是逐行翻译 TypeScript 或 Python。

阅读层次：

- **第 2–9 节：基础版**，保留 OpenAI Agents JS 三 Agent CLI 示例的语义。
- **第 10–18 节：增强版**，基于当前 `openai-cs-agents-demo` 的六 Agent 后端及前端源码，给出 Java 项目的完善规格。
- **第 19–22 节：服务体验增强**，参考 Claude 客服 demo，补齐可追溯政策来源、咨询标签、后续问题与人工服务申请。
- 完善已有客服项目时，以增强版为目标；第 8 节任务说明仅适用于最小复刻，不应继续限制增强版为三个角色、两个工具。

主要依据：

- [index.ts](../openai-agents-js/examples/customer-service/index.ts)：三个 Agent、两个业务工具、交接关系和 CLI 会话循环。
- [README.md](../openai-agents-js/examples/customer-service/README.md)：基础示例定位与运行说明。
- [result.ts](../openai-agents-js/packages/agents-core/src/result.ts)：`history`、`output`、`newItems` 和 `lastAgent` 的 SDK 语义。
- [handoff.ts](../openai-agents-js/packages/agents-core/src/handoff.ts)：交接通过工具表达，默认向目标 Agent 提供完整对话历史。
- [handoffPrompt.ts](../openai-agents-js/packages/agents-core/src/extensions/handoffPrompt.ts)：多 Agent 系统提示词前缀。
- [server.py](python-backend/server.py)、[main.py](python-backend/main.py)：增强版会话、流式响应、事件与 HTTP 入口。
- [agents.py](python-backend/airline/agents.py)、[tools.py](python-backend/airline/tools.py)：六 Agent 的实际工具权限与交接图。
- [context.py](python-backend/airline/context.py)、[demo_data.py](python-backend/airline/demo_data.py)、[guardrails.py](python-backend/airline/guardrails.py)：业务状态、演示航程与输入检查。
- [page.tsx](ui/app/page.tsx)、[chatkit-panel.tsx](ui/components/chatkit-panel.tsx)、[types.ts](ui/lib/types.ts)：当前界面接线与状态数据契约。

跨仓库相对链接要求两个 checkout 同处一个父目录。将本文复制到 Java 项目后，正文可独立使用，源码链接需要相应调整。增强版结论来自当前工作区源码静态核对，不代表已经执行真实模型、航空后台或 Java 集成测试。

本文区分三类内容：

1. **原代码行为**：示例实际实现的功能。
2. **迁移建议**：Java 项目为保持同类行为可以采用的设计，并非原代码已有功能。
3. **生产扩展**：示例没有实现，不应在复刻阶段冒充已完成的能力。

第1–18节保留版本无关的迁移设计，`ChatClient`、`@Tool`、`ToolContext`、`ChatMemory` 等是候选映射。当前 [Java 项目](java-customer-service/README.md) 已使用 Spring Boot 4.1.1、Spring AI 2.0.1 与官方 DeepSeek starter，验证 `DeepSeekChatModel`、host-controlled ToolCallback schema 和工具历史往返；不要把候选映射理解为本项目已使用的全部 API。

## 2. 一句话理解系统

这是一个“分诊 Agent 将航空客服会话交给专业 Agent，专业 Agent 调用业务工具，遇到职责外问题再交回分诊 Agent”的多轮对话系统。

它不是三个 Agent 并行回答，也不是每条用户消息都重新交给分诊 Agent，更不是已经接入航空公司后台的订票系统。

### 2.1 Agent 交接图

```text
                         用户
                          |
                   会话入口 / Runner
                          |
                    Triage Agent
                     /        \
                    v          v
              FAQ Agent    Seat Booking Agent
                  |                 |
          faq_lookup_tool       update_seat
                  |                 |
                  +-----> Triage <---+
```

允许的交接边只有：

- Triage → FAQ。
- Triage → Seat Booking。
- FAQ → Triage。
- Seat Booking → Triage。

FAQ 与 Seat Booking 没有直接交接边。跨专业问题应先回到 Triage，再由 Triage 选择另一个专业 Agent。

### 2.2 最值得借鉴的五点

1. **按业务职责拆分 Agent**：提示词、工具权限和交接目标随职责变化。
2. **让工具提供事实和执行动作**：FAQ 不靠模型自行编造，座位修改通过明确的函数执行。
3. **保留当前 Agent**：多轮收集信息时，不要每轮重新分诊。
4. **分开保存对话历史与业务 context**：历史帮助模型理解对话，context 保存服务端结构化状态。
5. **由宿主程序执行交接**：模型提出交接请求，Runner 切换 Agent；一句“转交成功”不等于完成交接。

## 3. 原代码中的业务模型

### 3.1 业务 context

`AirlineAgentContext` 的四个字段均可缺省：

| 字段                 | 含义       | 示例中的实际使用         |
| -------------------- | ---------- | ------------------------ |
| `passengerName`      | 乘客姓名   | 声明但没有赋值或读取     |
| `confirmationNumber` | 预订确认号 | `update_seat` 执行时写入 |
| `seatNumber`         | 座位号     | `update_seat` 执行时写入 |
| `flightNumber`       | 航班号     | 声明但没有赋值或读取     |

初始 context 是空对象，在一次 CLI 会话内复用。它是程序持有的状态，不是数据库，也不是模型提示词中的自动变量。

本示例没有将整个 context 显式注入模型提示词。模型从对话和工具结果获得信息；工具从 `RunContext` 获得业务 context。迁移时如果需要让模型读取某些 context 字段，应由 Java 宿主程序明确、安全地提供，而不是假设保存状态就等于模型已经知道状态。

### 3.2 两个业务工具

#### 工具一：`faq_lookup_tool`

- 描述：`Lookup frequently asked questions.`
- 输入：对象，包含必填字符串 `question`。
- 输出：一段字符串。
- 性质：本地硬编码查询，不调用搜索服务，不使用向量数据库，也不是 RAG。

匹配顺序如下：

| 优先级 | 匹配条件 | 固定结果 |
| --- | --- | --- |
| 1 | 转小写后的问题包含 `bag` 或 `baggage` | 可带一个包，重量小于 50 磅，尺寸 22 × 14 × 9 英寸 |
| 2 | 归一化后的问题包含 `wifi` | 飞机提供免费 Wi-Fi，网络名称 `Airline-Wifi` |
| 3 | 转小写后的问题包含 `seats` 或 `plane` | 120 个座位；商务舱 22 个，经济舱 98 个；第 4、16 排为出口排；第 5–8 排为 Economy Plus，腿部空间更大 |
| 4 | 以上均不匹配 | `I'm sorry, I don't know the answer to that question.` |

Wi-Fi 的归一化规则：移除空白字符、Unicode 横线标点和下划线。因此 `wifi`、`Wi-Fi`、`wi fi`、`wi_fi` 等可以命中。Java 复刻时应注意 Unicode 字符类别与正则语义，不要只删除 ASCII 连字符。

这些是子串匹配，不是精确意图分类。例如，同时包含 `bag` 和 `wifi` 的问题会先得到行李答案；中文问题不保证命中。上述航空政策仅是演示数据，不能当成真实航空公司政策。

#### 工具二：`update_seat`

- 描述：`Update a seat on a flight`。
- 输入：对象，包含必填字符串 `confirmationNumber` 和 `seatNumber`。
- 输出：`Seat updated to {seatNumber} for confirmation {confirmationNumber}`。
- 副作用：存在业务 context 时，将这两个输入字段写入 context。

原代码的重要限制：

- 没有调用航空公司 API，也没有更新数据库。
- 没有验证预订是否属于当前用户。
- 没有查询座位是否存在或可用。
- 没有使用 `flightNumber`。
- 没有支付、票价、审批或幂等逻辑。
- 字符串 schema 并未要求非空，也没有确认号和座位号格式校验。
- 如果执行时没有传入 context，工具仍然返回相同的成功字符串，但不会保存状态。

Java 演示实现可以保留 mock 行为，但应始终在正常会话调用路径提供 context。真实业务实现必须在后端确认操作成功后才能返回成功结果，不应照搬 mock 的成功语义。

## 4. 三个 Agent 的职责与提示词

### 4.1 公共多 Agent 提示词前缀

三个 Agent 的提示词都包含 `RECOMMENDED_PROMPT_PREFIX`。其核心含义：

- 当前 Agent 属于一个多 Agent 系统。
- 每个 Agent 拥有自己的指令和工具。
- Agent 可以通过交接函数把会话交给其他 Agent。
- 交接函数通常形如 `transfer_to_<agent_name>`。
- 交接由系统在后台执行，不应在面向客户的自然语言回答中刻意强调交接过程。

CLI 另外打印交接诊断信息，这不等于提示词要求 Agent 对客户宣布交接。

Java 实现应保留这些语义，但把“Agents SDK”等框架名称换成目标项目的实际编排系统；不要让模型误以为不存在的 SDK 自动帮它完成交接。

### 4.2 FAQ Agent

| 配置项                  | 内容                   |
| ----------------------- | ---------------------- |
| 名称                    | `FAQ Agent`            |
| 对其他 Agent 的能力描述 | 能回答航空公司相关问题 |
| 业务工具                | 只有 `faq_lookup_tool` |
| 允许交接目标            | 只有 Triage            |

提示词要求：

1. 找到客户最近提出的问题。
2. 使用 FAQ 查询工具回答，不能依赖自身知识。
3. 如果不能回答，交回 Triage。

这里“不能回答就交回”的判断由模型遵循提示词完成，工具返回的仍然是普通字符串。原代码没有用 `found=false` 之类的结构化结果触发确定性路由。

### 4.3 Seat Booking Agent

| 配置项                  | 内容                 |
| ----------------------- | -------------------- |
| 名称                    | `Seat Booking Agent` |
| 对其他 Agent 的能力描述 | 能修改航班座位       |
| 业务工具                | 只有 `update_seat`   |
| 允许交接目标            | 只有 Triage          |

提示词要求：

1. 询问客户的预订确认号。
2. 询问客户希望选择的座位号。
3. 调用座位修改工具执行操作。
4. 客户提出与上述流程无关的问题时，交回 Triage。

这是提示词驱动的信息收集流程，不是代码中定义的强制状态机。原代码没有显式“等待确认号”“等待座位号”状态，也没有确定性校验保证模型一定按顺序提问。

Java 可以借鉴为“只补问当前缺失的信息”，避免用户一次提供两个字段后仍重复提问。但这属于迁移时对用户体验的明确选择，不应描述为原代码已保证的行为。

### 4.4 Triage Agent

| 配置项                  | 内容                         |
| ----------------------- | ---------------------------- |
| 名称                    | `Triage Agent`               |
| 对其他 Agent 的能力描述 | 将客户请求分派给适当的 Agent |
| 显式业务工具            | 无                           |
| 允许交接目标            | FAQ、Seat Booking            |

Triage 的主要任务是分诊，不是直接执行业务。SDK 将配置的 handoff 暴露为可供模型选择的交接工具；因此“没有显式业务工具”不等于“模型完全没有工具可用”。

原代码只提供概括性的分诊提示词，没有写死分类规则，也没有对未知问题实现稳定的兜底策略。

## 5. 多轮执行流程与状态所有权

### 5.1 三份不同的数据

| 数据         | 原代码变量     | 用途                             |
| ------------ | -------------- | -------------------------------- |
| 当前 Agent   | `currentAgent` | 决定下一条用户消息先由谁处理     |
| 对话历史     | `inputItems`   | 提供后续运行所需的对话与工具记录 |
| 业务 context | `context`      | 保存工具写入的结构化业务状态     |

这三份数据不能互相替代。只保存聊天文本会丢失当前 Agent；只保存 context 无法完整理解用户此前的问题；只保存 Agent 名称无法恢复工具调用记录。

### 5.2 原会话循环

```text
初始化：
  currentAgent = Triage
  context = 空业务对象
  inputItems = 空历史

对每条用户消息：
  1. 读取输入。
  2. EOF 或 trim + 小写后的 exit / quit：结束会话。
  3. 在名为 Customer service 的 trace 中执行本轮。
  4. 将用户消息追加到 inputItems。
  5. run(currentAgent, inputItems, context)。
  6. 输出本轮结果、context 和运行事件。
  7. 使用 result.history 替换 inputItems。
  8. 使用 result.lastAgent 更新 currentAgent。
  9. 等待下一条用户消息。
```

一次 `run` 可以内部完成多次模型调用、工具执行和 Agent 交接。不要把一次用户输入理解成恰好一次模型调用，也不要把 handoff 当作必须等待下一条用户消息才生效的操作。

当 Seat Booking 正在收集确认号和座位号时，下一条用户消息应继续交给 Seat Booking；原代码不会自动回到 Triage。FAQ 回答完问题后也不会自动回到 Triage，除非实际发生交接。

### 5.3 结果对象不要混用

- `result.history`：包含本次输入与新生成记录，可作为下一轮输入。使用完整 history 替换旧历史，不能把完整 history 再追加一次，否则会重复记录。
- `result.output`：本轮生成的模型输出项数组，不是一个最终答案字符串。
- `result.newItems`：带 Agent 归属的运行项，可用于输出工具、消息和交接事件。
- `result.lastAgent`：本轮结束时的当前 Agent，后续会话从这个 Agent 开始。
- SDK 还提供 `finalOutput`：用于取得最终输出；原示例未使用这个属性。

Java 面向用户的响应和内部运行诊断应分开。不要直接把所有工具记录序列化成用户回复。

### 5.4 历史传递与 context 共享

示例没有自定义 handoff 输入过滤器，目标 Agent 默认接收完整会话历史，因此无需让用户重新说明刚才的问题。

工具共享同一会话的业务 context；交接不创建一份全新的业务对象。Java 实现应保持等价的会话级状态传递，但不能把所有用户放进一个共享可变 context。

原示例只有一个进程内 CLI 会话，没有 session ID、持久化、并发请求控制或重启恢复。

## 6. Spring AI 的迁移设计

本节是建议，不是 Spring AI 已自动提供的完整多 Agent handoff 能力。

### 6.1 概念映射

| OpenAI Agents JS 概念 | Java / Spring AI 候选实现 | 迁移注意事项 |
| --- | --- | --- |
| `Agent` | 三份 Agent 配置，复用现有 `ChatClient` 或项目封装 | 分离提示词、业务工具和可交接目标，不必创建三套模型基础设施 |
| `instructions` | Agent 专属 system prompt | 不要在切换 Agent 后继续沿用旧角色的 system prompt |
| `handoffDescription` | 路由工具中对目标 Agent 的能力描述 | 用于帮助模型选择，不是面向用户的固定回复 |
| `tool(...)` | `@Tool` 或项目现有 tool callback 注册机制 | 先验证版本；参数 schema、工具名称和工具权限应保持清楚 |
| `RunContext` | 服务端会话 context，必要时通过 `ToolContext` 传递 | context 不应作为要求模型生成的工具参数 |
| `handoffs` | 宿主编排器处理的交接指令 / 路由工具 | 只有模型输出交接文本，无法完成真正切换 |
| `run(...)` | 会话编排服务的一次完整 turn | 管理模型调用、工具结果、交接和终止条件 |
| `history` | 项目已有消息历史机制，或经验证的 `ChatMemory` 方案 | 不能假设 memory 自动包含全部工具调用与结果 |
| `lastAgent` | 会话中的 `activeAgentId` | 下一轮继续使用，不要每轮重置为 Triage |
| `withTrace`、`newItems` | 项目已有可观测性和内部事件机制 | 区分用户消息与诊断事件，避免泄漏敏感信息 |

Spring AI 工具自动执行和多 Agent 交接编排是两件事。若自动工具执行机制会在工具返回后继续让源 Agent 调用模型，不能仅在一个普通工具里修改 `activeAgentId` 就声称交接完成；必须确保当前执行链退出或交还编排器，随后使用目标 Agent 的提示词和工具继续运行。

### 6.2 最小职责划分

建议优先复用 Java 项目已有组件，只补充缺失职责：

- **会话入口**：接收会话标识和用户消息，返回用户答案。
- **Agent 配置**：定义三个角色及其工具和交接目标。
- **会话编排器**：执行一轮对话，处理交接，保存最终 active Agent。
- **业务工具**：实现 FAQ 查找和 mock 座位更新。
- **会话状态**：保存历史、active Agent 和业务 context。

这些是逻辑职责，不要求为每个职责创建一个独立类。不要为了这个小示例引入额外多 Agent 框架、事件总线或复杂工作流引擎。

### 6.3 推荐的编排语义

```text
处理用户消息：
  从可信会话存储读取会话；新会话以 Triage 开始。
  将用户消息写入本轮历史一次。

  循环：
    使用 active Agent 的 system prompt、业务工具和交接目标调用模型。

    如果模型请求业务工具：
      用宿主提供的会话 context 执行工具。
      将调用与结果按项目采用的模型协议记录。
      继续当前 Agent 的工具调用循环。

    如果模型请求交接：
      检查目标是否属于当前 Agent 允许的交接集合。
      按协议完成交接工具结果记录。
      切换 active Agent，并使用已有历史继续运行。

    如果模型给出最终用户回复：
      保存历史、active Agent 和业务 context。
      返回用户回复，结束本轮。
```

这是语义伪代码，不是要求手写一套 Spring AI 已有的工具执行器。若项目已有可靠的自动工具调用机制，应复用它，并只在必要的交接边界增加编排。

### 6.4 Java 迁移中需要明确的边界

1. **工具权限**：FAQ 不能修改座位，Seat Booking 不能直接调用 FAQ 工具，Triage 不直接执行业务工具。
2. **可信会话状态**：Agent 选择和会话 context 由服务端维护，不让模型自行指定任意用户的 context。
3. **历史来源**：选择一个历史管理来源。不要既由 memory 自动追加，又手动重复追加同一条消息。
4. **工具协议**：保留有效的调用与结果关联，不能只保存工具返回的字符串。
5. **交接循环**：设置可配置或复用现有的每轮执行上限。未知 FAQ 可能在 FAQ 与 Triage 间往返；到达上限应显式返回无法解决的问题，不能伪造成功。
6. **失败处理**：模型或工具失败时要明确报错；真实座位修改失败时不得更新 context 为成功结果。
7. **并发**：如果 HTTP 服务允许同会话并发请求，采用项目已有的串行化或版本控制机制，避免消息和座位状态互相覆盖。CLI 原示例没有这个问题的实现。
8. **隐私**：原示例打印完整输出和 context 只是演示诊断方式。Java 服务不要默认向客户端或公共日志输出完整确认号、业务 context 和工具输入。

这些是服务化迁移边界，不表示原代码已经具备相应生产能力。

## 7. 典型对话与验收场景

下面的对话是预期场景，不是已运行的模型输出。用户回复的具体措辞可能变化，测试应优先断言工具调用、路由、历史和状态。

### 7.1 FAQ 查询

```text
用户：Is Wi-Fi available on the plane?
内部：Triage → FAQ → faq_lookup_tool
客服：说明提供免费 Wi-Fi，网络名称为 Airline-Wifi。
```

验收：答案依据工具结果；FAQ 不靠模型补充未经查询的政策。回答结束后，active Agent 仍可为 FAQ，而不是强制重置为 Triage。

### 7.2 多轮修改座位

```text
用户：I'd like to change my seat.
内部：Triage → Seat Booking
客服：询问预订确认号。
用户：DEMO123
客服：询问希望选择的座位号。
用户：12A
内部：update_seat(confirmationNumber=DEMO123, seatNumber=12A)
客服：根据工具结果确认 mock 更新。
```

验收：中间两轮继续由 Seat Booking 处理；工具执行后 context 包含 `confirmationNumber=DEMO123` 和 `seatNumber=12A`。不要把示例确认号当作真实客户数据。

### 7.3 修改座位过程中切换问题

```text
用户：I'd like to change my seat.
内部：Triage → Seat Booking
客服：询问确认号。
用户：Before that, what's the baggage allowance?
内部：Seat Booking → Triage → FAQ → faq_lookup_tool
客服：依据工具返回行李规则。
```

验收：完整历史得到保留；Seat Booking 不越权直接查询 FAQ；FAQ 不因交接丢失最后一个用户问题。

### 7.4 建议测试清单

| 类型 | 输入或场景 | 应验证的结果 |
| --- | --- | --- |
| FAQ 确定性测试 | `BAGGAGE allowance` | 返回行李固定答案 |
| FAQ 确定性测试 | `Wi-Fi`、`wi fi`、`wi_fi`、带 Unicode 横线的 Wi-Fi | 返回 Wi-Fi 固定答案 |
| FAQ 确定性测试 | 同时包含 `bag` 与 `wifi` | 行李分支优先 |
| FAQ 确定性测试 | `How many seats are on the plane?` | 返回座位布局固定答案 |
| FAQ 确定性测试 | `What meals are available?` | 返回原工具的未知答案字符串 |
| 座位工具测试 | 合法的演示确认号与座位号 | 更新当前会话 context，返回对应字符串 |
| 会话编排测试 | 两轮分别提供确认号、座位号 | active Agent 持续为 Seat Booking，历史不重复 |
| 交接测试 | Seat Booking 中提出 FAQ 问题 | 经 Triage 到 FAQ，目标使用正确提示词和工具 |
| 会话隔离测试 | 两个独立会话分别改座位 | context、历史和 active Agent 不互相污染 |
| 失败测试 | 工具失败或交接循环到达上限 | 返回明确失败，不声称座位更新成功 |

前五项及 mock 工具结果可以做普通单元测试；编排测试优先使用项目现有的可脚本化模型响应，控制交接和工具调用顺序。真实模型测试用于补充验证提示词表现，不应把“模型恰好说出同一句话”当作唯一验收条件。

特别注意：中文 FAQ、真实航班查询、退款和身份验证均不属于原示例支持范围。如果目标 Java 项目需要这些能力，应单独确认需求，而不是通过测试要求偷偷扩展复刻范围。

## 8. 可以直接交给 Java 编码 Agent 的任务说明

以下是**基础版最小复刻任务**。完善已有 Java 客服项目时，改用第 18 节的增强版任务说明。将任务说明连同本文，以及 Java 项目自身代码和依赖配置，一起交给编码 Agent：

```text
请在现有 Spring AI Java 项目中，参考本文实现或改造一个航空客服多 Agent 演示。

先检查：
- Spring AI、Spring Boot 和 Java 的实际版本。
- 已有 ChatClient、工具注册、会话历史、模型配置和异常处理机制。
- 项目是否已提供 Agent 路由、工具执行循环或会话编排。

实现目标：
1. 三个角色：Triage、FAQ、Seat Booking。
2. Triage 可以交给 FAQ 或 Seat Booking；两者只能交回 Triage。
3. FAQ 必须调用 faq_lookup_tool 获取答案，不得自行编造政策。
4. FAQ 工具复用本文描述的硬编码匹配规则及优先级，不引入 RAG。
5. Seat Booking 收集确认号和座位号，通过 update_seat 修改会话内 mock 状态。
6. 每个会话维护 active Agent、对话历史和业务 context。
7. 用户后续消息继续交给上一轮结束时的 active Agent。
8. 交接在宿主编排器中真正切换角色，并保留对话与有效工具记录。
9. 对用户返回答案，对内部记录必要的路由和工具事件。
10. 增加与项目结构一致的确定性工具测试及多轮交接测试。

实现限制：
- 优先复用现有抽象，不要另起一套不必要的 Agent 框架。
- 不要假设 Spring AI 自动提供与 OpenAI Agents SDK 相同的 handoff 语义。
- 不要把切换全局变量或输出“已转交”当作真正交接。
- 不要同时让 memory 和手动代码重复保存同一消息。
- 不要让模型生成可信会话 context 或任意会话标识。
- 不要引入数据库、支付、航空后台或向量检索，除非项目需求明确要求。
- mock 座位更新必须标注为演示，不能声称真实订票成功。
- 提示词驱动行为与程序强制行为应区分。
- 服务化后处理会话隔离、失败和交接循环上限，复用现有机制即可。
- 不要默认记录完整确认号、工具参数或业务 context。

请先说明现有项目与本文设计的对应关系，再进行最小必要改造。
对于无法从现有代码确定的 API 或业务要求，明确提出具体问题，不要杜撰。
交付时说明改动、执行过的测试及结果、仍属于 mock 的能力。
```

## 9. 复刻与生产扩展的分界

| 可以直接借鉴                     | 需要单独设计的生产能力             |
| -------------------------------- | ---------------------------------- |
| 三个 Agent 的职责与工具权限      | 客户身份认证与预订归属校验         |
| Triage ↔ 专业 Agent 的交接结构   | 真实航空后台、航班和座位可用性查询 |
| 工具提供事实和修改 mock 状态     | 事务、幂等、重试和失败后的状态恢复 |
| 历史、active Agent、context 分离 | 会话持久化、跨实例一致性与历史裁剪 |
| 每轮生成内部执行事件             | 脱敏、审计、监控和人工客服接管     |
| 英文关键词 FAQ 演示              | 中文及多语言识别、知识库与政策更新 |

最终应复用的是“职责分离 + 工具执行 + 真实交接 + 多轮状态延续”的机制，而不是把演示政策和成功字符串原样当成生产业务。

## 10. 增强版：从 CLI 示例到六 Agent 客服系统

### 10.1 已有能力与 Java 完善方向

| 维度 | 三 Agent JS 基础版 | 当前 Python 客服项目 | Java 完善方向 |
| --- | --- | --- | --- |
| 角色 | Triage、FAQ、Seat Booking | Triage、Flight Information、Booking and Cancellation、Seat and Special Services、FAQ、Refunds and Compensation | 六份独立配置，明确工具权限与交接白名单 |
| 服务入口 | CLI 循环 | FastAPI + ChatKit，按 thread 保存状态 | 复用已有 HTTP 入口和会话标识 |
| 工具 | FAQ、改座位 | 航程补全、航班状态、替代航班、改签、取消、特殊座位、补偿等 | 分离查询与写操作，返回可信结构化结果 |
| 提示词 | 固定角色指令 | 部分角色每次生成指令时读取 context | 每次模型调用读取最新状态的必要字段 |
| 界面 | 控制台输出 | Customer View + Agent View | 用户答案、诊断事件和业务状态分别输出 |
| 输入检查 | 示例未配置 | 两个模型驱动 input guardrail | 在业务执行前完成检查，显式处理检查失败 |
| 存储 | 单个 CLI 会话 | 进程内 MemoryStore 和独立 ConversationState | 统一生命周期，按部署需求持久化和串行化 |

“增强版已具备”只表示源码包含相关路径，不表示所有 UI、业务闭环和生产保障都完成。尤其不能从提示词或 README 推导未注册的 Agent 与工具。

### 10.2 六 Agent 的真实权限与交接图

以下以 [agents.py](python-backend/airline/agents.py) 注册配置为准，名称用于诊断；Java 内部建议使用稳定 ID，不把展示名称作为持久化主键。

| Agent 展示名称 | 建议 Java ID | 已注册业务工具 | 实际允许交接目标 |
| --- | --- | --- | --- |
| Triage Agent | `triage` | `get_trip_details` | flight、booking、seat、faq、refunds |
| Flight Information Agent | `flight` | `flight_status_tool`、`get_matching_flights` | booking、triage |
| Booking and Cancellation Agent | `booking` | `cancel_flight`、`get_matching_flights`、`book_new_flight` | seat、refunds、triage |
| Seat and Special Services Agent | `seat` | `update_seat`、`assign_special_service_seat`、`display_seat_map` | refunds、triage |
| FAQ Agent | `faq` | `faq_lookup_tool` | triage |
| Refunds and Compensation Agent | `refunds` | `issue_compensation`、`faq_lookup_tool` | faq、triage |

补充事实：

- 六个业务 Agent 都注册了 Relevance Guardrail 和 Jailbreak Guardrail。
- Triage → Booking、Flight → Booking 使用 `on_booking_handoff`。
- Triage → Seat、Booking → Seat 使用 `on_seat_booking_handoff`。
- 回调调用 `apply_itinerary_defaults` 补全演示状态；它们不是模型调用的普通业务工具。
- `baggage_tool` 虽然定义在工具文件中，但没有注册到上述 Agent；没有 Baggage Agent。
- Flight 提示词说“转给相关 Agent”，但没有直接到 FAQ、Refunds 的边，必须经 Triage。
- Seat、Booking、Refunds 提示词中的 Baggage 交接不受实际配置支持。
- Refunds 可以自己查 FAQ 工具。若交给 FAQ，FAQ 只能回 Triage，没有“自动回到调用方继续补偿”的注册语义。

Java 默认对齐这六个角色，不自行增加第七个行李 Agent。行李政策走 FAQ；行李挂失和追踪在未接入工具时明确说明当前不支持，或按已有人工客服流程接管。新增行李业务需单独确定工具、权限、状态、UI 和测试。

### 10.3 提示词驱动与程序保证必须分开

增强版提示词鼓励“数据齐全时连续调用工具”“每条消息最多一次交接”“完成后返回 Triage”。这些是模型指令，不是程序中的硬上限或确定性状态机。

Java 应明确：

1. 单个 Agent 的一次模型响应最多允许一个交接指令。
2. 同一用户 turn 可以经历多次交接，例如 Triage → Flight → Booking → Refunds；不能用“整个 turn 只允许一次”阻断业务链。
3. 每轮模型步数、工具调用数、交接数和总耗时由宿主设置上限，不能只靠提示词。
4. 专业 Agent 在补问缺失信息时继续保持 active；完成后的返回路由应有明确规则，不能强制每轮回 Triage。
5. 多项任务是否完成由业务结果判断，不由模型说“已完成”判断。
6. 改签自动化仅是演示行为。真实订票、取消、付费选座需要目标项目已有的确认与授权规则。

## 11. 会话状态、航程补全与动态提示词

### 11.1 状态分层

[server.py](python-backend/server.py) 的 `ConversationState` 包含：

| 字段 | 作用 | Java 对应职责 |
| --- | --- | --- |
| `input_items` | 下一轮模型输入，包括对话和工具记录 | 唯一来源的模型历史 |
| `context` | 当前会话业务对象 | 服务端业务状态 |
| `current_agent_name` | 下一轮从哪个 Agent 开始 | `activeAgentId` |
| `events` | Runner 诊断事件累计列表 | 可脱敏、分页的运行事件 |
| `guardrails` | 最近保存的一组检查记录 | 本轮检查结果，不能假装完整审计历史 |

此外，MemoryStore 单独保存 ChatKit thread、消息 item 和附件。Java 可以复用已有存储，不必照搬两份 map；但必须分清“展示给用户的聊天记录”和“下一轮需要的模型工具历史”。

建议的逻辑状态如下，字段名是设计建议，不是 Spring AI API：

```text
ConversationState
  threadId / ownerId
  activeAgentId
  modelHistory
  airlineContext
  revision
  currentRunId
  latestGuardrailChecks
  eventCursor
```

`ownerId` 从认证主体获得，`threadId` 从受控入口获得；都不作为模型可以任意填写的工具参数。生产场景下只提供会话 ID 不能替代归属校验。

### 11.2 context 的内部字段与公开投影

| Python 字段 | 含义 | 当前 `public_context` 输出 |
| --- | --- | --- |
| `passenger_name` | 乘客姓名 | 保留 |
| `confirmation_number` | 确认号 | 保留 |
| `seat_number` | 当前座位 | 保留 |
| `flight_number` | 当前主要操作航班 | 保留 |
| `account_number` | 客户账户号 | 保留 |
| `origin`、`destination` | 整体航程起终点 | 保留 |
| `special_service_note` | 特殊服务请求，可能包含医疗信息 | 保留 |
| `vouchers` | 已发放凭证 | 非空时保留 |
| `itinerary` | 航段列表 | 隐藏 |
| `baggage_claim_id` | 行李案件标识，目前未形成工具闭环 | 隐藏 |
| `compensation_case_id` | 补偿案件标识 | 隐藏 |
| `scenario` | 演示场景标识 | 隐藏 |

当前公开投影只是排除了几个字段，**不等于已脱敏**。Java 应使用显式允许列表 DTO，并区分客户视图、授权客服视图和后台审计。确认号、账户号、医疗备注、凭证不得默认进入公共日志或未经授权的状态接口。

动态提示词只注入完成当前角色任务所需字段。用户输入、服务备注和工具外部数据属于不可信内容，不能与固定 system 指令拼接后被当作新指令。状态存在并不意味着模型自动可见；每次调用需要由宿主明确提供选定字段。

### 11.3 演示航程与补全边界

[demo_data.py](python-backend/airline/demo_data.py) 定义两个固定场景：

| 场景 | 航程与确认号 | 演示结果 |
| --- | --- | --- |
| `on_time` | FLT-123，SFO → LAX，LL0EZ6，23A | 正常运行，A10，无替代航班和凭证 |
| `disrupted` | PA441，CDG → JFK；NY802，JFK → AUS；IR-D204，14C | 首段延误、错过连接；NY950 或 NY982 次日抵达 |

异常场景中 NY950 的座位是 `2A (front row)`，NY982 为 `3C`；酒店、餐食和地面交通分别是最高 $180、$60、$40 的**演示描述**。时间固定在 2024 年，没有真实时区或实时库存，不可作为当前航班与真实赔偿政策。

当前实现的细节：

- `create_initial_context` 返回空对象，不是一开始就生成确认号或航程。
- `get_trip_details(message)` 按英文子串 `paris`、`new york`、`austin` 选择 disrupted，否则选择 on_time。
- Triage 提示词仅在提到这些地点且缺少 context 时要求调用该工具；不是每轮自动调用。
- `apply_itinerary_defaults` 没有显式场景时使用已有场景，否则默认 **disrupted**。
- 该函数只补空字段，已有字段通常不覆盖；改场景却保留旧字段可能形成混合航程。
- Booking / Seat 的 handoff 回调可以触发默认 disrupted，而不是默认正常 FLT-123。因此 README 中某次对话的确认号不是源码保证。

Java 演示应固定 fixture 和明确的场景选择入口；不得把不相关文本默认解释为某个真实用户的预订。生产补全必须来自已认证客户的查询结果，不得随机生成确认号代替查单成功。

## 12. 工具契约：保留业务分工，补齐可信结果

### 12.1 增强版已注册工具清单

参数使用当前 Python 名称；Java 内部可以 camelCase，若兼容既有模型工具 schema 和前端协议，应在边界明确映射。

| 工具 | 模型输入，不含宿主 context | 当前行为及状态副作用 |
| --- | --- | --- |
| `get_trip_details` | `message: string` | 选择演示场景并补全航程、确认号、起终点等 |
| `faq_lookup_tool` | `question: string` | 硬编码政策字符串，无 RAG |
| `flight_status_tool` | `flight_number: string` | 写当前航班，查询固定 fixture，产生进度事件 |
| `get_matching_flights` | 可选 `origin`、`destination` | 返回候选；过滤为空时回退全部候选，可能补全 context |
| `book_new_flight` | 可选 `flight_number` | 选候选、更新航班/座位/航段；未匹配时选首项，没有候选时返回占位订票成功文本 |
| `cancel_flight` | 无 | 补全 context 后返回取消成功；没有保存取消状态或调用真实后台 |
| `update_seat` | `confirmation_number`、`new_seat` | 直接写确认号和座位，未验证占用及归属 |
| `assign_special_service_seat` | `seat_request`，默认医疗前排描述 | 包含 `front` 时选 1A，否则 2A，保存请求备注 |
| `display_seat_map` | 无 | 返回字符串 `DISPLAY_SEAT_MAP` |
| `issue_compensation` | `reason`，默认延误错过连接 | 复用或创建 CMP 案件号，从 fixture 写凭证，产生进度事件 |

查询工具在当前演示中也会补状态，不应简单宣称所有查询都没有副作用。Java 建议让身份/航程加载与查询职责更清楚，但不要为复刻擅自加入真实后台。

### 12.2 FAQ 两版行为不能混用

增强版 [tools.py](python-backend/airline/tools.py) 的优先级是：

1. `bag` / `baggage`：行李规则及丢失行李申报建议。
2. `compensation` / `delay` / `voucher`：演示延误关怀和补偿政策。
3. `seats` / `plane`：座位布局。
4. `wifi`：免费网络。
5. 未命中：未知答案。

与 JS 基础版不同，Python 只转小写，没有 Wi-Fi 横线/空格归一化；`wifi on the plane` 会先命中 `plane` 并返回布局答案。中文查询也没有关键词支持。

Java 若修复归一化、复合问题或多语言，应标记为**有意的行为增强**，为这些输入新增确定性测试，不要把基础版匹配规则冒充当前 Python 行为。真实政策查询需要版本、来源和适用条件，不能沿用演示金额及延误阈值。

### 12.3 建议的 Java 工具结果

新写操作不要只返回成功句子。可复用项目现有结果类型，至少表达以下语义：

```text
ToolResult
  status: SUCCESS | NOT_FOUND | NEEDS_CONFIRMATION | REJECTED | FAILED
  code: 稳定业务码
  userMessage: 可向客户展示的说明
  data: 该工具对应的类型化结果
  operationId: 写操作标识，可选
  retryable: 是否允许重试
```

这是 Java 目标契约，不是现有 Python 返回值。类型化结果按工具设计，不要求把所有业务塞进一个任意 map。未知航班、无匹配候选、座位占用、取消失败必须明确返回，不能继续使用“默认准点”“占位订票成功”等演示兜底。

写操作需要：

- 从宿主读取身份和可信预订，校验模型提供的确认号与当前权限匹配。
- 在需要客户确认时，绑定具体航班/座位/费用及有效期；不能把任意一句“OK”解释为所有写操作授权。
- 成功后更新业务状态；失败不写入成功状态，保留明确失败事件。
- 相同 `operationId` 重试不重复取消、改签或发凭证；超时后先查操作状态，再决定是否重试。
- 取消保存可查询的预订状态；特殊座位验证可用性与服务资格，不直接照搬“固定 1A”。
- 若一次 turn 已完成改签，随后补偿失败，只报告补偿失败，不能声称整条流程回滚，也不能重试整轮重复改签。

## 13. Spring AI 编排实现边界

### 13.1 先确定工具执行控制权

迁移设计不以伪代码代替编译验证。当前依赖见 [pom.xml](java-customer-service/pom.xml)，实际适配见 [DeepSeekGateway.java](java-customer-service/src/main/java/com/example/airline/DeepSeekGateway.java)；更换版本或供应商前仍须核对 BOM、工具调用模式、流式能力与 memory/advisor。

| 控制点 | Java 宿主需要保证 | 不可默认假设 |
| --- | --- | --- |
| Agent 配置 | 当前角色的 prompt、工具集合和交接目标同时生效 | 切换名称会自动切换 prompt |
| 工具执行 | 能观测调用、结果并在交接处停止源角色循环 | 普通 `@Tool` 修改 active ID 就完成交接 |
| 会话 context | 每轮通过经验证的 `ToolContext` 或已有封装传递 | 模型可以生成可信 context |
| 历史 | 保存完整关联的工具调用与结果 | 任意 ChatMemory 都保留所有工具记录 |
| 流式输出 | 拦截工具/交接控制信息，区分最终回复 | 模型 token stream 等于完整 Agent 事件流 |
| 检查链 | 业务工具之前完成输入检查 | 安装某个 advisor 即具备相同 guardrail 语义 |

如果现有自动工具执行支持可靠的中断/交接回宿主，优先复用；否则在可控边界使用目标版本支持的工具执行接口。不要依赖未验证的 API 名称，也不要引入第二套与既有服务竞争的历史存储。

### 13.2 一轮完整执行算法

```text
1. 验证身份、会话归属及请求；对同一 thread 串行执行或做版本控制。
2. 创建 runId，读取 active Agent / 历史 / 业务状态。
3. 保存本轮用户消息一次，执行两项输入检查。
   拒绝：保存拒绝记录并发布检查与结束事件，不执行任何业务写工具。
   检查失败：返回明确检查异常，不当作通过。
4. 在执行预算内循环：
   a. 根据当前 Agent 和最新 context 构建本次模型输入。
   b. 读取模型响应；收齐有效工具参数再执行，不能执行不完整 JSON。
   c. 若是业务工具请求：
      校验权限、参数和必要确认；按调用 ID 记录请求与结果。
      写操作成功即保存业务结果及关联事件，再继续当前角色。
   d. 若是交接请求：
      校验 source → target 白名单；记录完整工具协议结果。
      执行显式 beforeHandoff 回调；失败时不切换 Agent。
      切换 active Agent，记录 handoff，保留历史，继续目标角色。
   e. 若需补问或给出最终答案：
      保存答案、active Agent、历史与状态，发布终态，结束本轮。
5. 预算、模型、工具或连接异常：
   保存已确认的业务结果，输出明确终态，释放会话执行权。
```

一个响应包含多个工具请求时，不按任意顺序混合执行交接和写操作：对“交接 + 业务写操作”或多个交接使用明确协议校验规则，在副作用发生前拒绝歧义请求。源 Agent 的剩余工具调用不能在切换后借目标角色权限执行。

宿主可以设置 `maxModelSteps`、`maxToolCalls`、`maxHandoffs`、`timeout` 等预算；数值依据目标项目和业务链确定，不在本文杜撰默认值。检测重复路由而无状态进展，返回无法解决或人工接管，不无限 FAQ ↔ Triage 循环。

### 13.3 handoff 不是嵌套客服回答

默认保持当前项目的“转移控制权”语义。专业 Agent 调用另一个 Agent 回答子问题后继续自身任务，属于不同的“Agent-as-tool / 子任务”语义，需显式实现调用栈和返回值，不能混在 handoff 中。

补偿流程优先让 Refunds 直接调用已授权的 `faq_lookup_tool`，查完政策继续 `issue_compensation`；无需为了查政策先交给 FAQ 再丢失补偿执行位置。

handoff 初始化在 Java 中应显式注册回调并记录为 `handoff_prepare` 或普通诊断元数据。当前 Python 事件代码通过 SDK 回调闭包推断 `on_handoff` 并生成 `tool_call`，不是稳定的框架公共契约，不应在 Java 复制反射或闭包探测。

## 14. 输入检查、拒绝与异常生命周期

### 14.1 当前 guardrail 的实际语义

[guardrails.py](python-backend/airline/guardrails.py) 用独立模型生成结构化判断：

| 检查                | 输出                       | 拒绝条件            |
| ------------------- | -------------------------- | ------------------- |
| Relevance Guardrail | `reasoning`、`is_relevant` | `is_relevant=false` |
| Jailbreak Guardrail | `reasoning`、`is_safe`     | `is_safe=false`     |

提示词要求只检查最新用户消息，并允许 `Hi`、`OK` 等正常会话片段。函数实际上把传入的 `input` 交给检查 Runner，并未在代码中先截取最新消息。因此“只检查最新消息”目前是模型遵从要求，而不是输入隔离保证。

Java 在宿主明确提取本轮用户文本，避免历史中的旧拒绝污染新一轮判断。检查模型返回无效 JSON、超时或服务不可用要走明确失败路径，不把缺失结果当通过。确认号、座位号、中文确认语等正常短消息也应列入测试。

模型检查不是身份认证、工具权限校验或写操作授权的替代品。为保证拒绝前没有订票等副作用，Java 应在业务工具执行前完成检查；是否并行检查可以复用项目机制，但两项检查都需要明确终态。

### 14.2 当前 server 的不足与目标行为

- 正常 `_record_guardrails` 找不到某个结果时默认 `passed=True`；不能视为该项确实运行通过。
- tripwire 处理仅把触发项记失败，其他项记通过；未执行或未完成项可能被误标。
- 拒绝分支产生客服消息后直接返回，没有走正常的最终状态广播。
- `MaxTurnsExceeded` 分支广播后继续进入结果处理，没有专门的用户失败答复，不能把它描述为可靠兜底。
- 若干事件转换异常被吞掉，诊断可能缺失；Java 不应复制宽泛静默 catch。

目标检查状态应至少区分 `PASSED`、`REJECTED`、`ERROR`、`NOT_RUN`。若为兼容旧界面保留 `passed`，新增状态字段并升级显示逻辑，不能将后三类映射为“已通过”。

无论成功、拒绝、预算耗尽还是异常，都执行同一终结职责：保存一致状态、发布最新检查结果、发布终态、关闭流、释放锁和监听资源。拒绝措辞按目标项目本地化；不要把所有错误伪装成“只能回答航空问题”。

## 15. HTTP、流式响应与 Runner 状态契约

### 15.1 当前 HTTP 接口

[main.py](python-backend/main.py) 提供：

| 接口 | 当前用途 | Java 迁移注意 |
| --- | --- | --- |
| `POST /chatkit` | 调用 ChatKit `process`，可能返回 JSON 或 SSE | 不是接收任意 `{message: ...}` 的普通聊天接口 |
| `GET /chatkit/bootstrap` | 创建新 thread 并返回 snapshot | 重复调用会创建新会话 |
| `GET /chatkit/state?thread_id=...` | 返回会话快照 | 当前未知 ID 会创建并返回新 thread；Java 应明确采用 404 或创建语义 |
| `GET /chatkit/state/stream?thread_id=...` | 首次快照，随后监听状态广播 | 断开后注销 listener；当前没有心跳和游标重放协议 |
| `GET /health` | 固定 `{"status":"healthy"}` | 只是存活响应，不验证模型、存储及航空 API 可用性 |

若保留现有 ChatKit 前端，必须实现其实际 thread/item 请求与流式协议，验证历史、拒绝、effect 和异常消息；Spring AI 的文本 SSE 不会自动成为 ChatKit 兼容服务。

若目标 Java 项目已有自有聊天 UI，保留其 API，只借鉴状态和事件语义。选择普通聊天接口时同时改造前端适配，不要仅把 URL 改为 `/chatkit` 就宣称兼容。

### 15.2 当前 snapshot 与事件

当前快照字段为：

```text
thread_id
current_agent
context          // public_context 输出
agents[]         // name, description, handoffs, tools, input_guardrails
events[]         // id, type, agent, content, metadata?, timestamp?
guardrails[]     // id, name, input, reasoning, passed, timestamp
```

状态广播另外带 `events_delta`，但**仍然带完整 `events`**，不是纯增量协议。`_last_event_index` 按 thread 保存，不按订阅者保存；新监听者可以先拿缓存快照，再收到当前快照。Java 不能据此声称支持精确重放或 exactly-once。

| 当前事件类型     | 内容和 metadata                                 |
| ---------------- | ----------------------------------------------- |
| `message`        | Agent 生成的文本，展示摘要最多截取 200 字符     |
| `handoff`        | source → target；`source_agent`、`target_agent` |
| `tool_call`      | 工具名称；`tool_args`                           |
| `tool_output`    | 返回摘要；`tool_result`                         |
| `context_update` | `changes`，当前只对公开投影做最终前后比较       |

内部 context 变化不一定生成公开 `context_update`。例如 compensation case ID 被隐藏，只有凭证等公开字段变化才可出现在该 diff 中。截断也不是递归脱敏：当前 `_truncate` 不会递归处理字典中的长字符串或敏感字段。

事件时间戳使用 Unix **毫秒**；client effect 中 `ts=time.time()` 使用**秒**。Java wire DTO 必须明确单位，不能直接把秒当作 JavaScript `Date` 的毫秒。

ChatKit 输出的三个 effect：

- `runner_bind_thread`：开始响应前告知实际 thread。
- `runner_state_update`：通知客户端刷新状态。
- `runner_event_delta`：携带本次转换的新事件。

工具产生的 `ProgressUpdateEvent` 由 ChatKit 单独处理，server 不将它保存到 Runner 事件。UI 类型虽然包含 `progress_update`，不能据此推导已有完整进度事件链。

### 15.3 建议的可靠事件扩展

保留现有字段需要兼容消费者；新字段是 Java 目标建议：

```json
{
  "thread_id": "thread_demo",
  "run_id": "run_demo",
  "sequence": 12,
  "id": "event_demo",
  "type": "tool_output",
  "agent": "Booking and Cancellation Agent",
  "content": "演示改签成功",
  "timestamp": 1791612000000,
  "metadata": {
    "tool_name": "book_new_flight",
    "tool_call_id": "call_demo",
    "operation_id": "operation_demo",
    "status": "SUCCESS"
  }
}
```

`run_id` 关联一轮，`tool_call_id` 精确配对调用与结果，`sequence` 在同一 thread 内单调递增，`id` 用于跨快照/增量去重。当前前端只按相邻同 Agent 的事件分组，不足以正确配对并行工具。

如果采用独立 SSE 状态流，应完成端到端接线：初始 snapshot、增量、心跳、断线重连、游标追赶及旧 thread 流注销。服务器保留窗口外的游标返回“需要全量刷新”，不要静默丢事件。消费者只对同一 thread 应用更新，按 `revision` 或 `sequence` 拒绝旧状态覆盖新状态。

监听队列必须有界；慢客户端溢出时记录并要求重新同步或关闭连接。当前 Python Queue 无显式容量，`QueueFull` 分支并不能提供实际背压保障。聊天断线和业务取消也要区分：断线不能盲目重试已经提交的写操作。

## 16. 前端完成度与交互式座位图

### 16.1 当前 UI 实际接线

当前 [page.tsx](ui/app/page.tsx)：

- bootstrap 后设置 thread、Agent 列表及 context。
- thread 变化和聊天响应结束时请求完整状态快照。
- 没有建立 `EventSource` 订阅 `/chatkit/state/stream`。
- 没有传入 ChatKitPanel 的 `onRunnerUpdate` 和 `onRunnerEventDelta` 回调。

[chatkit-panel.tsx](ui/components/chatkit-panel.tsx) 的 effect 回调只解构 `name`，却通过箭头函数的 `arguments` 读取 data，并未显式读取当前 effect 的 `data`；不能保证增量和绑定通知实际处理正确。

[agent-panel.tsx](ui/components/agent-panel.tsx) 展示 Agent、公开 context、检查和 Runner，过滤 `message`、`progress_update`。因此后端有实时广播能力，不等于当前 Agent View 已实现实时展示。

Java 配套 UI 至少选择并完成一种状态更新方式：

- 独立状态 SSE：按第 15 节接入订阅、重连和去重。
- 聊天流内 effect：显式读取事件 data、绑定实际 thread、消费 delta，并在终态拉 snapshot 校准。

两条通道同时使用时必须按相同事件 ID 去重，避免双重追加。

### 16.2 座位图不是已完成的闭环

当前 Python `display_seat_map` 只返回 `DISPLAY_SEAT_MAP`；[seat-map.tsx](ui/components/seat-map.tsx) 定义了组件，但没有其他组件引用，server 的 `action` 也是空实现。因此不能按 Python README 推导已经支持“点击座位 → 后端修改 → 确认”；独立 Java 页面已实现这个闭环。

固定图布局为：

- 商务舱 1–4 排，每排 4 座，共 16 座。
- Economy Plus 5–8 排，每排 6 座，共 24 座。
- 经济舱 9–24 排，每排 6 座，共 96 座。
- 总计 **136 座**，与 FAQ 的 120 座、22 个商务舱座位不一致。
- 占用集合是前端硬编码，1A 被标记占用；特殊服务工具却可以直接分配 1A。

Java 建议的完整流程：

```text
display_seat_map
  → 返回结构化 ui_action 和服务端座位图数据
  → 前端渲染，与 FAQ/航机配置共用事实来源
  → 客户选择座位并提交可信会话关联的 action
  → 后端重新验证预订归属、图版本、座位可用性及必要确认
  → 执行同一座位业务服务，成功才更新 context
  → 发布工具结果/状态事件，前端关闭或刷新座位图
```

推荐用类型化 UI action，不解析模型自然语言里的魔法字符串。是否采用 ChatKit widget/action 取决于目标前端；自有 UI 可以用已有事件和 action API。前端禁用按钮不能替代后端可用性检查。

## 17. 增强版验收与实施顺序

### 17.1 按业务结果验收，不按模型措辞

下列是 Java 目标验收，不是已执行结果。提示词表现用真实模型补充测试；状态、权限、错误和协议优先使用可脚本化模型及 mock 工具确定性验证。

| 场景 | 输入或故障 | 必须断言 |
| --- | --- | --- |
| 六角色注册 | 读取 Agent 配置 | 工具及交接图符合第 10 节，无幽灵 Baggage Agent |
| 多轮补问 | 缺少确认号/目标座位，分轮提供 | active 不被重置；用户消息只入历史一次 |
| 已知状态 | 可信航程字段齐全 | 不重复索取已知字段，每次调用读取新状态 |
| 跨专业请求 | Seat 中询问政策 | Seat → Triage → FAQ，不越权用 FAQ 工具 |
| 异常航程 | Paris → New York → Austin | 加载 disrupted fixture，查询 PA441，返回 NY950/NY982 |
| 改签 | 选择 NY950 | 保存替代航段，不重复保留旧 NY802；座位和确认号一致 |
| 无匹配 | 指定不存在航班或无符合条件候选 | 明确 NOT_FOUND，不擅自选首项或成功占位航班 |
| 特殊服务 | 需要前排，目标座位已占用 | 不直接分配 1A，返回可选方案或明确拒绝 |
| 补偿 | 异常后申请餐宿支持 | Refunds 查政策并调用补偿工具，重复 operation 不重复发放 |
| 取消 | 已确认取消、随后查询预订 | 后端保存取消状态，不只生成成功句子 |
| 部分失败 | 改签成功，补偿失败 | 保留改签结果，报告补偿失败，重试不重复改签 |
| FAQ 差异 | `Wi-Fi`、`wifi on the plane`、中文、复合问题 | 记录选定行为及优先级，有意增强有明确测试 |
| 输入拒绝 | 无关请求或要求绕过规则 | 无业务写工具执行，检查与拒绝及终态都可见 |
| 检查异常 | 超时、无效结构化输出、缺少结果 | 标记 ERROR/NOT_RUN，不展示通过 |
| 预算终止 | 循环交接或工具不停止 | 显式失败终态、释放执行权，不产生成功答复 |
| 事件关联 | 同 Agent 多工具、快照与 delta 重叠 | tool_call_id 配对，ID 去重，sequence 有序 |
| 状态重连 | 断流、切 thread、旧游标 | 初始快照可用，正确补事件，旧会话不污染新会话 |
| 选座 UI | 打开图、提交选择、座位冲突 | 显示/提交/后端执行/结果反馈全链路，冲突不写成功状态 |
| 会话归属 | 用户 A 请求用户 B 的 thread | 状态、聊天、SSE、action 均拒绝，不泄漏数据 |
| 同会话并发 | 两条写请求同时到达 | 串行或版本冲突，无历史覆盖和重复写 |
| 重启与删除 | 重启、删除或过期会话 | 按选定存储语义恢复或明确失效，清理状态及监听资源 |
| 数据公开 | 成功、拒绝、错误、工具事件 | 各通道一致脱敏，不泄漏账户/医疗/凭证等受限信息 |

### 17.2 推荐实施顺序

1. **现状映射**：核对 Java 依赖、现有编排和 UI 协议，确定演示还是生产目标。
2. **六角色及状态**：完善 Agent 注册、权限、真实 handoff、唯一历史与动态 context。
3. **业务工具闭环**：先固定 fixture，再完善结构化结果、取消状态、选座验证和写操作确认。
4. **检查与终结路径**：拒绝、检查异常、预算、工具/模型错误统一保存和通知。
5. **可观测 UI**：snapshot、delta、run/tool 关联、实际订阅及完整选座 action。
6. **部署保障**：按已有需求补持久化、跨实例一致性、归属校验、幂等和人工接管。

生产扩展由目标项目需求决定，不为一个演示强制引入数据库、事件总线或工作流引擎。纯内存模式要明确重启丢失；生产持久化必须一起覆盖历史、active Agent、业务状态和操作结果，不能只保存聊天文本。

## 18. 可直接交给 Spring AI Java 编码 Agent 的增强版任务

```text
请基于本项目现有代码和 SPRING_AI_REFERENCE.md 第 10–17 节，
完善使用 Spring AI 实现的航空客服多 Agent 系统，不要逐行翻译 Python。

先读取并核对：
- Java、Spring Boot、Spring AI BOM、模型供应商及实际工具/流式 API。
- 现有 Agent 配置、ChatClient、ToolCallback/@Tool 封装、memory/advisor。
- 会话归属、历史、持久化、异常通知、前端聊天/状态/action 协议及测试。
- 当前能力与参考规格的差距，区分已实现、mock、提示词要求与未接通路径。

实现或补全：
1. 六角色：Triage、Flight Information、Booking and Cancellation、
   Seat and Special Services、FAQ、Refunds and Compensation。
   工具权限及交接白名单参考第 10 节；默认不新增 Baggage Agent。
2. 每会话保存 activeAgentId、唯一模型历史、业务 context 和运行关联信息。
   多轮补问维持当前 Agent，handoff 真正切换 prompt 和工具集合。
3. 在显式 handoff 回调中补全必要状态；演示 fixture 与真实查单严格区分。
   不随机生成预订来冒充认证查询，不默认把未知航班当作准点。
4. 完善航程查询、候选航班、改签、取消、标准/特殊选座、FAQ 和补偿。
   使用类型化结果，失败不返回成功，取消需要可查询状态。
   写操作接入已有授权/确认/幂等规则，部分成功后不得重试整轮重复提交。
5. 每次模型调用提供当前角色所需的最新可信状态，不泄漏不必要字段，
   不把用户数据拼接成固定系统指令。
6. 输入检查在业务执行前完成；区分通过、拒绝、异常和未执行。
   成功/拒绝/预算终止/失败都有保存、状态广播、终态及资源清理。
7. 用户聊天、业务状态和诊断事件分层。完善 snapshot 和真实流式接线，
   用 runId/toolCallId/sequence/eventId 关联、排序、去重和重连。
8. 完成打开座位图、选择、提交、后端验证、更新状态及结果反馈。
   座位图、FAQ 与工具共用航空布局事实来源，不解析模型魔法字符串。
9. 增加第 17 节相关确定性测试，特别覆盖权限、交接、多轮状态、
   输入拒绝前零写操作、部分失败、并发、重复请求和选座闭环。

限制与需要先确认的选择：
- 优先复用现有组件，不另起重复框架或第二份会话历史。
- 不假设 Spring AI 自动提供 Agents SDK handoff/guardrail/ChatKit 协议。
- 保留现有前端时实现其实际协议；更换协议必须同时更新消费者并测试。
- FAQ 归一化/中文支持属于有意增强，需要说明行为变化和测试。
- 真实支付、退款、行李挂失、人工接管及持久化按本项目已明确需求实施。
  未确定的业务授权、协议或版本问题先提出具体问题，不自行杜撰。
- 不将演示政策、固定日期、凭证金额和 mock 成功句子视为生产事实。

交付：
- 改动代码与直接相关文档，说明对应规格、兼容影响及仍为 mock 的能力。
- 执行最小覆盖范围的既有测试、编译/类型检查和必要前端联调。
- 提供实际运行结果；未执行真实模型/外部后台验证要明确列出。
```

## 19. Claude 客服 demo 的实际机制与借鉴范围

本轮依据本机 [customer-support-agent](../claude-quickstarts/customer-support-agent/) 的实际实现，而非只读取功能宣传：

| 参考源码 | 实际实现 | Java 借鉴方式 |
| --- | --- | --- |
| [聊天入口](../claude-quickstarts/customer-support-agent/app/api/chat/route.ts) | 返回结构化回答、情绪、咨询分类、建议问题与人工转接建议；使用 Zod 校验 | 保留现有工具编排，单独校验服务标签，避免把控制指令混进最终聊天文本 |
| [检索工具](../claude-quickstarts/customer-support-agent/app/lib/utils.ts) | 调用 Bedrock Retrieve，默认请求3条；上下文使用所有返回文本，展示 sources 最后只保留1条 | 本地政策库检索，返回全部实际命中来源；不引入 AWS 或假称向量 RAG |
| [聊天组件](../claude-quickstarts/customer-support-agent/components/ChatArea.tsx) | 读取 JSON，展示建议问题；“Talk to a human”仅触发浏览器事件；打字动画不是 token SSE | 真正的后端申请单与状态接口，前端可查询；继续使用现有状态 SSE |
| [来源栏](../claude-quickstarts/customer-support-agent/components/RightSidebar.tsx) | 根据模型 `context_used` 展示最多15次历史、来源片段与 score | 根据真实工具检索结果记录来源历史，不依赖模型自称已使用资料 |
| [来源弹窗](../claude-quickstarts/customer-support-agent/components/FullSourceModal.tsx) | 展示传入 snippet，名称“Full Source”不表示另行读取了完整文档 | 按来源 ID 从受控 API 读取本地完整政策条目 |
| [左侧栏](../claude-quickstarts/customer-support-agent/components/LeftSidebar.tsx) | 展示 thinking、情绪及分类 | 只展示公开服务标签和事实进展，不展示隐藏推理过程 |

参考实现中的 `context_used` 是模型声明，不是引用验证；检索失败可返回空结果继续回答、header ASCII 过滤可能破坏中文来源、浏览器事件不代表真人已接通。Java 不复制这些边界，也不启用未经消毒的原始 HTML 渲染。

## 20. 本轮批准的 Java 实施规格

继续使用 Spring Boot 4.1.1、Spring AI 2.0.1 DeepSeek 和“微语航空客服”；不换成 Claude，不新建另一套前端，不接入 AWS、数据库或真实坐席平台。

### 20.1 可追溯本地政策库

- FAQ 工具改为调用本地政策目录，条目包含稳定 ID、标题、版本、业务分类、关键词及完整正文。
- 中文、英文、Wi-Fi 归一化与复合问题保留；所有命中条目都返回来源，未知政策明确 NOT_FOUND。
- 这是确定性关键词检索，不是向量搜索；不展示虚构相似度百分比。
- 航机座位政策与座位图共用布局事实，补偿金额仍明确标记演示。
- `KnowledgeLookup` 记录 runId、检索问题、时间及命中来源；每会话保留15次。
- `ChatEntry` 增加本条回复所对应的 source IDs；资料已检索不代表已证明最终回复逐句使用资料，UI 使用“查询到的参考资料”。
- 来源详情 API 只接受实际目录 ID，且该会话必须曾命中该来源；禁止模型生成任意 URL/文件路径。

### 20.2 咨询标签与后续问题

- 在现有最新消息相关性检查中同时生成 mood、categories、humanRequested，不增加一次额外模型调用。
- mood 只接受 positive、neutral、negative、curious、frustrated、confused；分类只接受 flight、booking、seat、policy、baggage、compensation、human。
- 缺字段、类型错误、未知枚举视为检查错误，不静默补默认通过。标签是可误判的辅助信息，不是授权依据或心理诊断。
- 人工意愿仅来自明确申请，单纯负面情绪只建议人工，不自动登记；直接按钮不依赖模型判断。
- 建议问题由服务端根据本轮标签、预订状态及待确认操作生成，最多3个；取消后不推荐改签/选座，补偿已发放后不推荐重复申请。
- 当前回复与历史回复携带各自标签及来源；新轮次清空当前标签与建议，避免旧结果覆盖新轮次。

### 20.3 人工服务申请闭环

- 新增 `HumanSupportCase`：申请号、REQUESTED/CANCELLED、原因、登记时间、最近对话片段摘要。
- 这是演示申请登记，不是已经接通真人、不承诺排队时长；页面明确说明。
- 客户点击申请或模型检查确认客户明确要求人工时，可登记申请；重复申请返回当前单，不重复建单。
- REQUESTED 期间暂停智能聊天和业务写入，清理未执行的待确认操作；已有成功业务结果保留。
- “恢复智能服务”将当前申请置为 CANCELLED、返回智能模式并重置到分诊；旧申请仍可查询，最多保留8单。
- 登记和恢复与聊天/选座/确认共用会话执行锁，校验 owner/CSRF，更新 snapshot 与事件。
- 未知知识或复杂失败可建议人工，不能直接假称转接成功。
- 人工摘要只取公开客户对话，不包含系统提示词、工具参数、医疗备注或内部模型历史。

## 21. API 与界面增量契约

保留已有接口与字段，在 snapshot / ChatEntry 中增加结构化服务标签、后续问题、政策来源历史及人工申请历史：

| 增量入口 | 行为 |
| --- | --- |
| `GET /api/threads/{id}/knowledge/{sourceId}` | 校验归属和实际检索记录后返回完整政策 |
| `POST /api/threads/{id}/human-support` | 带 reason，登记或返回当前人工申请；暂停自动服务 |
| `POST /api/threads/{id}/human-support/resume` | 取消当前申请、恢复智能服务，重复恢复不重复记录 |

页面增加“咨询概况”“参考资料”“接下来可以问”和“人工服务”区，保留微语品牌，不展示模型/框架版本、内部分类 ID、工具 schema 或思考过程。正文及来源使用 textContent，来源弹窗支持关闭与 Escape；历史按当前 thread 隔离，SSE重连只同步服务端快照。

## 22. 本轮验收要求

| 场景 | 结果 |
| --- | --- |
| 中英文复合政策查询 | 返回全部命中来源，来源 ID、版本、正文可验证 |
| 未知政策 | NOT_FOUND、空来源、建议人工，不假装知识命中 |
| 详情访问 | 本会话查到的来源可读；跨会话/未检索/任意路径拒绝 |
| 无效情绪/分类结果 | 检查 ERROR，本轮零工具/业务写操作 |
| 负面但未申请人工 | 只显示人工建议，不自动建单 |
| 明确人工请求 | 登记申请、暂停自动执行、无真人接通宣称 |
| 重复申请与恢复 | 单号稳定，恢复不重复追加事件/消息 |
| 已确认改签后申请人工 | 改签保留，待确认操作清理，不重复改签 |
| 人工模式中的聊天/选座/确认 | 明确拒绝，并不调用 DeepSeek 或执行写操作 |
| 恢复智能服务 | 分诊继续，历史和来源保留，无旧待确认操作 |
| 来源和建议历史 | 新轮次、刷新、重连、切换 thread 不串数据 |
| UI 内容安全 | 来源/模型文本不作为 HTML 执行，不展示隐藏思考或技术名称 |
| 构建与联调 | 确定性模型、HTTP/API、来源详情、人工流程及选座回归通过；真实云服务验证另行说明 |

本轮实现与验证结果见 [Java README](java-customer-service/README.md)：Spring Boot 4.1.1 / Spring AI 2.0.1 在 Java 21 下完成41项测试与打包，本地模拟 DeepSeek 浏览器联调覆盖政策详情、建议问题、申请刷新与恢复、会话隔离和136座选座确认。真实 DeepSeek 账户、向量检索和真实坐席平台不在本次验证范围。
