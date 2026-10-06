# M0 Spike 结论

## Task 3：DeepSeek 流式（date: 2026-10-05）
- 应用启动：正常（MySQL 3307 连接 OK）。profile `local` 生效；HikariPool-1 连接成功；Tomcat 8080；`Started DeerflowJavaApplication in 9.313 seconds`；启动后 `/actuator/health` 返回 `{"groups":["liveness","readiness"],"status":"UP"}`（首次就绪约 18s，含 mvn 编译）。
- SSE 端点：可用。`GET /api/demo/stream?q=...` 返回 HTTP 200，`Content-Type: text/event-stream`，`Transfer-Encoding: chunked`。
- 流式格式观察：
  - **data 前缀**：每个 Spring AI Flux 元素（模型增量）编码为一个 SSE 事件 `data:<内容>\n\n`；`data:` 后**没有空格**；无 `event:`/`id:` 字段。内容中的换行按 SSE 规范拆成同一事件内的多行 `data:`（字节级验证：chunk `"?\n\n"` → `data:?\ndata:\ndata:\n\n`；按 SSE 规范 join 可无损还原）。
  - **分块粒度**：token 级增量，最小 1~2 字符（如 `Deep`/`Se`/`ek`/`，`/`一个`）。中文实测（q=用一句话介绍你自己）：约 4.5s 内 40+ 个事件，块间隔约 60~80ms，共 342 字节，回答为流畅中文。
  - **首 chunk 延迟（TTFB）**：中文实测 0.42s；另一次英文实测 2.06s —— 随 DeepSeek 首 token 时间波动，非固定值。
  - **结束方式**：最后一个 chunk 后服务器正常关闭连接（curl total 0.62s / 2.36s，远早于 60s max-time）；未观察到错误帧。末尾可能出现空内容的裸 `data:` 事件，下游需容忍空 chunk。
- DeepSeek 兼容性：通过。`spring-ai-starter-model-openai` 2.0.1 + `base-url: https://api.deepseek.com` + `model: deepseek-chat` 走 OpenAI 兼容协议，中文输入输出 UTF-8 正常，无 401/402。
- 偏差修正记录：
  - 直接用 git-bash 单行 curl 传中文 query（`--data-urlencode` 传原始中文）会 400：Windows 下 curl 收到的 argv 已按本地代码页（GBK）转换，percent-encode 后为非法 UTF-8 字节，Tomcat 解码抛 `MalformedInputException`（`InvalidParameterException: Character decoding failed. Parameter [q] ... has been ignored`），请求在进 Controller 前即被拒。**属本地 curl/Windows 代码页问题，非应用缺陷**；改用 URL 中预编码 UTF-8（`%E7%94%A8...`）后全部正常。后续实测/联调：中文 query 一律预编码，或改用浏览器/前端发起。
  - `TaskStop` 停 `mvnw spring-boot:run` 后台任务后，fork 出的 java 子进程仍占 8080（PID 存活），需 `netstat -ano | grep ":8080"` 定位后 `taskkill //F //PID <pid>` 清理。

## Task 4：工具循环与装饰器（date: 2026-10-05）
- **核心结论（R1）：自研 ToolCallback 装饰器（`ToolExecutionDecorator`）在 Spring AI 2.0 工具循环中被框架正常调用 ✓**。实测两次（q=「现在几点了？必须调用工具查询」/「请调用工具告诉我服务器当前时间」），应用日志均出现 `[SPIKE] tool_start name=now args={}` 与 `[SPIKE] tool_result name=now ok=true ms=51/2 out="2026-10-05T23:37:33.69..."`。装饰器透传 `call(String, ToolContext)` 即可拦截全部工具执行 —— M1「装饰器发事件 + 从装饰器数据重建持久化」的默认策略成立，不依赖框架暴露中间态。
- 多轮 tool call：**正常** ✓。模型调用工具 → 拿到结果 → 继续生成最终回答（回答中的时间与工具返回值一致，如 `23:37:33` / `23:45:11`）；两次 curl 均 HTTP 200、正常关闭连接、无错误帧。
- **外层 stream 暴露的内容：不暴露工具轮中间态**。`.stream().content()` 的首条 SSE event 已是最终回答的首 token（实测首条分别以「现在是」「服务器」开头），工具轮（AssistantMessage(toolCalls)/ToolResponseMessage）期间客户端收不到任何 `data:` event —— 工具是框架在流内部同步执行的，调用方无法从 content 流感知。**结论：事件发射/持久化不能依赖外层流，装饰器方案是必需而非可选。**
- 线程上下文：工具 `call` 执行在 Reactor `boundedElastic-*` 线程（实测 `boundedElastic-5`/`boundedElastic-118`），非 Tomcat 请求线程 —— M1 事件发射/上下文传递需按响应式线程模型设计。
- 工具调用细节：无参工具模型传来 `args={}`（空 JSON 对象）；返回值为 JSON 引号包裹的字符串（`DefaultToolCallResultConverter` 默认行为，`out="..."`）。
- **实际 API 偏差（计划代码 → 2.0.1 实际）**：
  - 计划：`org.springframework.ai.tool.definition.ToolDefinition.builder(method)` —— **不存在**。2.0.1 中 `ToolDefinition` 接口仅有**无参** `builder()`（`DefaultToolDefinition.Builder` 需显式 `name/description/inputSchema` 三项非空）。
  - 实际采用：`org.springframework.ai.tool.support.ToolDefinitions.builder(method)`（类名从 ToolDefinition→ToolDefinitions），返回 `DefaultToolDefinition.Builder`，自动派生 name（`ToolUtils.getToolName`）、description（可 `.description("获取当前服务器时间")` 覆盖）、inputSchema（`JsonSchemaGenerator.generateForMethodInput`）—— 仅此一处理解为偏差，其余计划代码零偏差。
  - `MethodToolCallback.builder().toolDefinition(..).toolMethod(..).toolObject(..).build()` 与计划完全一致；`ToolMetadata`/`ToolCallResultConverter` 不设置时构造函数自动取默认值（`DEFAULT_TOOL_METADATA`/`DEFAULT_RESULT_CONVERTER`）。
  - 备选路径 `org.springframework.ai.support.ToolCallbacks.from(Object...)` 确认存在（javap 验证），本次未采用。
- 空 chunk 容忍：第二次实测响应共 28 个 event，其中 **2 个裸 `data:`（空内容）出现在流中部**（非仅尾部）；第一次 0 个。结论：下游必须容忍任意位置的空 chunk（T7/T17 事件编码按此设计）。
- 失败路径：未实测（让工具抛异常需改代码）；DeepSeek 401/断流场景本次也未触发。留待 M1 正式版错误恢复逻辑与 T16 覆盖。
- 回归：纯文本问题（不带工具触发）正常 —— HTTP 200、0.8s、中文流式回答正常，日志无新增 `[SPIKE]` 行（未误触发工具）。
- **R3 边界说明**：当前验证全部基于 GET + `Flux<String>`（Spring 默认 SSE 编码，`data:` 无空格、无 `event:` 字段）。POST SSE + 浏览器 `fetch` ReadableStream 读取方式尚未验证（T21 前端接入时补）；`SseEmitter` + 自定义事件名（tool_start/tool_result 等）的线上格式由 T7/T17 验证 —— 两种编码器行为可能不同，M1 事件层需自带格式测试。
- 状态：完成（验证通过，装饰器方案确认可行）。
- Usage 字段名（2.0.1 javap 核验）：`Usage.getPromptTokens()/getCompletionTokens()`，返回 Integer（消费方注意 Long/Integer 转换）

## Task 13：Advisor API 实测（date: 2026-10-06）
- StreamAdvisor/StreamAdvisorChain 实际签名：**与计划完全一致**（javap 核验 `spring-ai-client-chat-2.0.1.jar`）。`StreamAdvisor extends Advisor extends Ordered`，唯一方法 `Flux<ChatClientResponse> adviseStream(ChatClientRequest, StreamAdvisorChain)`；chain 侧 `Flux<ChatClientResponse> nextStream(ChatClientRequest)`（另有 `getStreamAdvisors()`/`copy(StreamAdvisor)`，本期未用）。order 走 `Ordered.getOrder()`（非 advisor 自定义常量名），`Advisor` 仅额外要求 `getName()`。
- ChatClientRequest.mutate() 形态：**存在**，返回 `ChatClientRequest$Builder`，字段级方法 `prompt(Prompt)` / `context(Map<String,? extends Object>)` / `context(String,Object)` / `build()`；`ChatClientRequest`（record：`Prompt, Map<String,Object>`）与 `ChatClientResponse`（`ChatResponse, Map`）同构，均有 `copy()`。`Prompt.getOptions()` 返回 `ChatOptions`（javap 另有返回 `ModelOptions` 的桥方法，编译器自动选具体类型）；`Prompt(List<Message>, ChatOptions)` 构造器存在。消息侧：`getText()` 继承自 `AbstractMessage`（`Message`→`Content` 接口在 spring-ai-commons 包）；`ToolResponseMessage.ToolResponse.responseData()` 存在。
- **结论：三个 Advisor 计划代码在 2.0.1 下零 API 修正编译通过**，ORDER 常量（ContextAssembly 0 / TokenBudget 100 / UsageTracking 200）可直接用于 ChatClient 注册排序。
- 其它偏差与修正（非 Advisor API 层面）：
  1. `TokenBudgetAdvisor.pruneMessages` 由包级改 **public**：计划测试类在 `com.deerflow.agent` 包，实现在 `com.deerflow.agent.advisors`，跨包访问包级方法不可编译（计划注释"包级可见便于单测"与测试文件包名自相矛盾）。
  2. `PromptBuilderTest` 工作区路径断言改平台无关：Windows 下 `Path.of("/tmp/ws/s1").toString()` == `\tmp\ws\s1`（实测），断言改用同一 `Path.toString()` 表达式；`PromptBuilder` 实现未动（照常渲染平台原生路径）。
- 供 T15/T16 参考：UsageTrackingAdvisor 以 `doOnNext` 从每轮 `ChatClientResponse.chatResponse().getMetadata().getUsage()` 取值——字段链任一层可为 null，必须逐层短路（已实现）；token 字段 Integer（同 T4 记录），`Number#longValue()` 收宽安全。T13 实测结果：`TokenBudgetAdvisorTest` 2 PASS + `PromptBuilderTest` 1 PASS，全量 41 PASS。
