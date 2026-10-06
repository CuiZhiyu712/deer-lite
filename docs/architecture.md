# deerflow-java 架构说明

> 设计来源：`docs/superpowers/specs/2026-10-05-deerflow-java-design.md`；对标 DeerFlow（LangGraph 生态）的设计映射。

## 1. 模块

| 模块 | 职责 |
|---|---|
| `agent` | AgentService（run 编排/限额/持久化）、Advisor 链、PromptBuilder、MessageConverter |
| `tool` | 工具实现 + ToolExecutionDecorator（事件/错误恢复/限额）+ FunctionToolCallback 绑定 |
| `sandbox` | WorkspaceManager（隔离路径）、ShellRunner（超时/杀进程/黑名单/截断） |
| `event` | AgentEvent 模型 + EventSink（SSE） |
| `runtime` | RunContext（取消信号/进程引用/工具轨迹） |
| `chat` | REST API + DTO |
| `persistence` | JPA 实体与仓库（sessions/messages/runs） |

## 2. 一次 run 的时序

前端 `POST /api/sessions/{id}/runs` → AgentService 建 Run(RUNNING) → 虚拟线程执行：
装配历史（messages 表 → MessageConverter → Spring AI 消息）→ `ChatClient.prompt().stream().chatClientResponse()`：
- Advisor 链（ContextAssembly → TokenBudget → UsageTracking）包裹模型调用
- 模型请求工具 → ToolExecutionDecorator 发 tool_start → 工具执行 → 结果回填模型 → 循环（上限 40 轮）
- 文本增量 → text_delta 事件
结束后：工具轨迹写为 ASSISTANT(toolCalls)+TOOL 行（保证历史重放合法）→ 最终回复落库 → run_end 事件。

## 3. 与 DeerFlow 的映射

| DeerFlow | deerflow-java |
|---|---|
| LangGraph create_agent 循环 | ChatClient + ToolCallingAdvisor（2.0 原生） |
| ~40 中间件（6 钩子点） | Advisor 链 + ToolCallback 装饰器（同插槽可扩展） |
| ThreadState + checkpointer | messages/runs 表重建 |
| SummarizationMiddleware | TokenBudgetAdvisor（二期换 LLM 摘要） |
| Sandbox 8 方法抽象 | WorkspaceManager + ShellRunner（本机受限） |
| SSE runtime + task_* 事件 | 自定义 8 类事件协议 |

## 4. 关键设计决策

1. **不用 WebFlux**：SSE + 虚拟线程在 MVC 下足够且心智负担低。
2. **工具事件来自装饰器而非框架钩子**：ToolCallback 装饰器对框架版本不敏感，且天然获得完整入参/出参用于持久化重建。
3. **历史重放合法性**：OpenAI 兼容协议要求 TOOL 消息必须跟随带 tool_calls 的 ASSISTANT 消息，因此持久化按「一轮合并的 ASSISTANT(toolCalls) + 顺序 TOOL 行」写入。
4. **取消语义**：abort 标志在流分块与工具边界检查；当前子进程由 RunContext 持有并强杀；bash 另有 30s 上限兜底。
5. **取消契约（尽力而为）**：取消在流分块与工具边界生效；在途 non-shell 工具自然跑完；`run_end` 是唯一终态信号（其后可能先到 1 条 usage/在途 tool_result）；usage 事件为累计值。
6. **收尾防覆盖**：`executeRun` 收尾前重载会话再 touch/save，避免 detached merge 覆盖运行期间工具写入的字段（如 todosJson）。

## 5. 已知局限（→ M2/M3）

- Token 估算为字符启发式；跨会话长期记忆缺失；沙箱非真隔离；取消对模型流为「分块边界生效」。
