# deerflow-java 设计文档（Spec）

**Spring AI Agent 运行时 —— 对标 DeerFlow 的 Java 实现**

- 日期：2026-10-05
- 状态：已批准（四段设计逐段通过；关键决策 D1-D8 均已确认）
- 目标读者：项目作者（学习 / 求职）、面试官、后续开发者

---

## 1. 背景与目标

### 1.1 背景

作者为 Java 开发者，简历投递方向为 **Spring AI**。DeerFlow 是基于 LangGraph 的 Python 超级 Agent 项目（后端约 11.5 万行 Python），本设计以 DeerFlow 为**对标对象与设计来源**，用 Spring AI 2.0 在 Java 生态重建一个可讲清架构深度的 Agent 运行时，作为简历项目与学习载体。

核心洞察（来自源码调研）：

- DeerFlow 的 ReAct 循环本身来自 LangGraph 标准 `create_agent`，复杂度全在 ~40 个中间件与提示词工程中。
- Spring AI 2.0 已把工具调用循环重构进 **Advisor 链**（`ToolCallingAdvisor`），这正是 LangGraph middleware 的 Java 对等物，概念一一对应。
- 最大架构缺口：LangGraph checkpointer 无对等物，用「messages 表 + run 记录重建上下文」替代。

### 1.2 目标

1. **2-3 周内产出 MVP 并开始投递**，此后按里程碑持续扩展。
2. 编程模型与 Spring AI 岗位考察点同频（ChatClient、Advisor 链、Tool、MCP、流式、多模型）。
3. 文档为一等交付物：README（对外）、architecture.md（架构/学习）、resume.md（简历素材）。
4. MVP 验收：演示「让 Agent 写一个 Python 脚本→执行→返回结果」，全程流式可见、可取消、历史可回溯；他人 5 分钟内可本地跑起来。

### 1.3 非目标（Non-goals，写入 README）

多用户/鉴权、IM 渠道（飞书/Slack 等）、定时任务调度、K8s provisioner、多实例水平扩展、全量对标 DeerFlow（只对齐概念，不追功能面）。

---

## 2. 关键决策记录

| # | 决策 | 理由 |
|---|---|---|
| D1 | 分阶段推进：MVP（2-3 周）→ 投递 → 持续扩展 | 快速产出简历材料；后续迭代有参照 |
| D2 | 基线：Spring AI 2.0.0 GA + Spring Boot 4.1.1 + Java 21 + Maven（wrapper） | 最新稳定线，简历叙事新；工具循环已是 Advisor 链架构 |
| D3 | 默认模型 DeepSeek（OpenAI 兼容协议），配置驱动多 provider | 便宜、中文强、国内直连；Spring AI 原生多后端 |
| D4 | 前端为原生 HTML/JS 单页（零构建、Boot 静态托管） | 演示与截图用；精力留给后端；直连自定义 SSE 协议 |
| D5 | 存储 MySQL 8（Docker Compose）+ Spring Data JPA | 国内岗位最通用；顺便练 Docker；JPA 多库切换友好 |
| D6 | 沙箱：MVP 本机受限执行（工作区隔离+超时+黑名单），二期升级 Docker 容器沙箱 | MVP 速度优先；安全局限在 README 明示 |
| D7 | 架构方案 A：Spring AI 原生承载（ChatClient + Advisor 链），自研集中在 DeerFlow 独有概念（SSE 协议、沙箱、持久化） | 与面试考察点同频、代码量最小；自研循环（方案 B）留作二期对比实验 |
| D8 | 项目名 `deerflow-java`，目录 `D:\JAVA_Projects\deerflow-java`，计划公开 GitHub 仓库 | 对标叙事明确 |

---

## 3. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 语言/运行时 | Java 21 LTS | 虚拟线程承载 SSE 长连接 |
| 框架 | Spring Boot 4.1.1 | 与 Spring AI 2.0 配套（Boot 4 / Framework 7 / Jakarta EE 11） |
| AI | Spring AI 2.0.0 GA（BOM 管理） | ChatClient + ToolCallingAdvisor 链 |
| 模型接入 | `spring-ai-starter-model-openai` 指向 DeepSeek base-url | yaml 定义模型列表，配置驱动可扩 |
| 持久化 | MySQL 8（Docker Compose）+ Spring Data JPA | MVP 用 `ddl-auto`，二期引入 Flyway |
| 流式 | Spring MVC `SseEmitter` + 虚拟线程 | 简单可控，不用 WebFlux |
| 前端 | 原生 HTML/JS/CSS，`src/main/resources/static/` | 零构建、无 npm |
| 构建 | Maven Wrapper（`mvnw`，由 start.spring.io 骨架自带） | 本机 PATH 无 mvn |

---

## 4. 架构与模块

### 4.1 目录结构

```
deerflow-java/
├── docker-compose.yml          # MySQL 8 一键启动
├── pom.xml
├── .gitignore                  # target/、workspace/、.env、application-local.yml
├── src/main/java/com/deerflow/ # 基包（可改）
│   ├── config/       # AiConfig、AppProperties（模型列表、沙箱参数、限额）
│   ├── agent/        # AgentService（run 编排）+ advisors/ + prompt/
│   ├── tool/         # 沙箱类工具、web 工具、ToolExecutionDecorator
│   ├── sandbox/      # WorkspaceManager（会话工作区、路径防穿越、命令限制）
│   ├── chat/         # REST 控制器（sessions / runs SSE / models）
│   ├── event/        # AgentEvent 定义 + SSE 发布器
│   └── persistence/  # 实体 + Repository（sessions / messages / runs）
├── src/main/resources/
│   ├── static/       # index.html + app.js + style.css
│   └── application.yml
├── workspace/                  # 每会话工作目录（gitignored）
└── docs/                       # architecture.md、resume.md、superpowers/specs/
```

### 4.2 一次 run 的运行链路

```
前端 POST /api/sessions/{id}/runs  (SSE)
  → AgentService 建 run 记录(RUNNING) → 虚拟线程启动
  → 装配历史消息 → ChatClient.prompt() + 3 Advisor + 工具集 .stream()
      ├─ 模型输出文本 → text_delta 事件 → 前端逐字渲染
      ├─ 模型请求工具 → 装饰器发 tool_start → 沙箱执行 → tool_result 回填 → 循环
      └─ 用量统计 → UsageTrackingAdvisor 落库 runs 表
  → run_end 事件 → 消息与状态持久化
```

### 4.3 与 DeerFlow 的对标映射

| DeerFlow（Python/LangGraph） | deerflow-java（Spring AI） |
|---|---|
| `create_agent` ReAct 循环 | ChatClient + ToolCallingAdvisor（2.0 原生） |
| ~40 个中间件（6 钩子点） | Advisor 链（模型级）+ ToolCallback 装饰器（工具级），MVP 4 个，二期同插槽扩展 |
| ThreadState + checkpointer | messages 表 + runs 记录重建上下文 |
| Sandbox 8 方法抽象 + local 实现 | WorkspaceManager + 受限本机执行 |
| SummarizationMiddleware | TokenBudgetAdvisor（简化版）→ 二期 LLM 摘要 |
| DeerMem 长期记忆 | 二期：LLM 提取 + memories 表 + 检索注入 Advisor |
| Skills（SKILL.md） | 二期：spring-ai-agent-utils（SKILL.md 格式与 DeerFlow 完全一致，技能包可直接复用） |
| MCP 集成 | 二期/三期：spring-ai-starter-mcp-client |
| SSE runtime | 自定义轻量事件协议（见 5.4） |

---

## 5. 核心机制

### 5.1 Advisor 链与工具装饰器（MVP 版「中间件」）

| 组件 | 类型 | 职责 | 对标 DeerFlow |
|---|---|---|---|
| ContextAssemblyAdvisor | StreamAdvisor | 每次模型调用前注入动态上下文（当前时间、工作区路径、可用技能摘要） | DynamicContextMiddleware |
| TokenBudgetAdvisor | StreamAdvisor | 调用前估算 token（字符启发式）；超预算从最旧消息截断（保留 system + 最近 N 轮） | SummarizationMiddleware 简化版 |
| UsageTrackingAdvisor | StreamAdvisor | 汇总每轮 usage → 写 runs 表 + 推 usage 事件 | token 统计类中间件 |
| ToolExecutionDecorator | ToolCallback 装饰器 | 包裹每个工具：发 tool_start/tool_result 事件；异常捕获转错误文本回填模型；工具轮次计数 | ToolErrorHandling + ToolProgress 中间件 |

说明：工具事件发射位置（ToolCallingAdvisor 钩子 vs ToolCallback 装饰器）为 M0 spike 验证点（见第 10 节 R1），装饰器方案为默认假设。

### 5.2 工具清单（MVP 8 个）

| 工具 | 类别 | 说明 |
|---|---|---|
| bash | 沙箱 | 受限执行（见 5.3） |
| read_file / write_file / str_replace / ls | 沙箱 | 工作区内文件操作，路径规范化 |
| web_search | 网络 | Tavily（`.env` 已有 key） |
| web_fetch | 网络 | Jina Reader 转 Markdown（已有 key） |
| write_todos | 规划 | 任务清单，实时推前端（可裁剪项） |

### 5.3 沙箱设计（本机受限执行）

- 每会话独立工作区 `workspace/{sessionId}/`；所有路径规范化后必须落在工作区内（防 `..` 穿越）。
- bash：cwd 锁定工作区；超时 30s 强杀进程树；输出截断 256KB；危险命令黑名单（`rm -rf /`、`format`、`shutdown` 等）。
- **诚实声明**：这不是真隔离（README 明示局限）；二期升级 Docker 容器沙箱（每会话一容器）。

### 5.4 SSE 事件协议（自定义）

前端用 `fetch + ReadableStream` 解析 POST SSE（`EventSource` 仅支持 GET）。

| event | data | 时机 |
|---|---|---|
| `run_start` | {runId, sessionId} | run 开始 |
| `text_delta` | {delta} | 模型文本增量 |
| `tool_start` | {id, name, argsPreview} | 工具开始执行 |
| `tool_result` | {id, status, outputPreview, durationMs} | 工具执行结束 |
| `todo_update` | {todos[]} | write_todos 更新 |
| `usage` | {inputTokens, outputTokens} | 每轮用量 |
| `run_end` | {status: done/failed/cancelled, error?} | 结束 |
| `ping` | {} | 心跳（15s，防代理断连） |

### 5.5 REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST / GET | `/api/sessions` | 创建会话 / 会话列表 |
| GET | `/api/sessions/{id}/messages` | 历史消息（可重建工具调用卡片） |
| POST | `/api/sessions/{id}/runs` | 发起 run（SSE 响应流） |
| POST | `/api/runs/{id}/cancel` | 取消（abort 标志 + 强杀当前 bash 进程） |
| GET | `/api/models` | 模型列表（yaml 配置驱动） |

### 5.6 数据模型（MySQL，4 张表）

| 表 | 关键字段 |
|---|---|
| `sessions` | id(UUID), title, model, todos_json, created_at, updated_at |
| `messages` | id, session_id, seq, role(USER/ASSISTANT/TOOL), content, tool_calls_json, tool_call_id, created_at |
| `runs` | id, session_id, status(RUNNING/DONE/FAILED/CANCELLED), input, error, input_tokens, output_tokens, started_at, ended_at |
| `memories`（二期） | id, session_id/scope, content, source, created_at |

---

## 6. 工程质量

### 6.1 错误处理分层

| 场景 | 处理策略 |
|---|---|
| 模型 API 超时/限流 | 退避重试 1 次 → 仍失败：`run_end{failed}`，前端可重发 |
| 工具执行失败 | 装饰器捕获 → 错误文本作为工具结果回填，模型自行修正；连续失败 3 次注入提示 |
| 死循环保护 | 单 run 工具轮次上限（默认 40）+ 时长上限（10 分钟）→ 优雅终止 |
| 成本保护 | 单 run token 上限（可配），超限终止并标注；usage 全量落库可审计 |
| SSE 断连 | 后台 run 继续跑完并持久化，前端重连拉历史 |
| 启动校验 | 无 API key / DB 不可达 → 启动失败并给出操作指引（如 `docker compose up -d`） |

### 6.2 安全

- API key 只存 `.env` / `application-local.yml`（gitignored），README 写明配置方式。
- 沙箱防护为演示级（路径规范化 + 命令黑名单），README 明示局限与二期 Docker 方案。
- MVP 不做鉴权（本地单用户）；如二期部署再加 Spring Security。

### 6.3 测试策略

- **单元测试**（JUnit 5）：WorkspaceManager（路径穿越/黑名单）、TokenBudget 截断、事件序列化、提示词组装。
- **集成测试**（`@SpringBootTest`）：stub ChatModel（固定脚本「文本→工具调用→文本」）驱动 AgentService，断言 SSE 事件序列与落库结果。
- **手动验收**：README 附演示脚本（让 Agent 写脚本并运行 → 核对时间线）。
- GitHub Actions 跑单元测试。

### 6.4 并发与性能

- 每 run 一个 Java 21 虚拟线程；SSE 心跳 15s；MVP 单实例（不做多 worker 广播）。

---

## 7. 前端设计（单页，~500 行）

- 布局：左侧会话列表；中间消息流（工具调用渲染为可折叠时间线卡片）；TODO 面板；输入框 + 发送/停止按钮。
- 交互：流式状态提示；失败消息可重试；历史加载可重建工具卡片。
- 技术：原生 HTML/JS/CSS；`fetch + ReadableStream` 解析 SSE；无框架无构建。

---

## 8. 文档交付物

1. `README.md`（中文为主）：一句话定位、Mermaid 架构图、5 分钟快速启动（`docker compose up -d && ./mvnw spring-boot:run`）、演示截图/GIF、Roadmap、Non-goals。
2. `docs/architecture.md`：模块图、run 时序、Advisor 设计、与 DeerFlow 中间件对标映射表、关键取舍（为什么自研会话状态、为什么不用 WebFlux）。
3. `docs/resume.md`：简历素材——一句话定位、3-4 条量化亮点、面试预案（为什么对标 DeerFlow / Advisor vs LangGraph middleware / 二期规划）。

---

## 9. 里程碑与范围

### 9.1 里程碑

| 阶段 | 内容 | 时间 |
|---|---|---|
| **M0 地基** | start.spring.io 骨架（Boot 4.1.1 + Spring AI 2.0 + JPA + MySQL）、docker-compose.yml、跑通 DeepSeek 流式、**spike：流式+工具循环+SSE 端到端**、git init + push GitHub | ~1 周 |
| **M1 MVP** | 本设计 3-7 节全部机制 + 文档三件套 + 测试 | ~1.5-2 周 |
| **投递** | 简历材料此时齐全 | — |
| **M2 二期** | 长期记忆、LLM 摘要压缩、Skills 接入（复用 DeerFlow 技能包）、HITL 澄清、多模型完善、Flyway | 持续 |
| **M3 差异化** | Docker 沙箱、Subagent 委派、MCP 接入、自研循环对比实验（面试故事） | 可选 |

### 9.2 MVP 可裁剪项（时间紧按序砍，不伤主叙事）

write_todos → 多模型列表 API。

**不可砍**：循环 + 工具 + SSE + 持久化 + 前端 + 文档三件套 + **cancel**（验收标准依赖「可取消」，且成本低：abort 标志 + 强杀进程）。

### 9.3 验收标准（M1 完成定义）

1. 演示场景全程流式可见：写 Python 脚本 → bash 执行 → 返回结果 → 时间线卡片完整。
2. run 可取消；历史消息与工具卡片可回溯。
3. `docker compose up -d && ./mvnw spring-boot:run` 后浏览器可用；README 步骤他人可复现。
4. 单元测试与集成测试通过；GitHub Actions 绿。

---

## 10. 风险与 M0 spike 验证点

| # | 风险 | 应对 | 结论（2026-10-05 M0 实测） |
|---|---|---|---|
| R1 | Spring AI 2.0 流式下工具调用的可观测点 | M0 spike 定版事件发射位置 | ✅ 关闭：装饰器被框架调用；外层流不暴露中间态 → 装饰器为唯一方案；工具执行在 Reactor boundedElastic 线程 |
| R2 | DeepSeek 经 OpenAI 兼容协议的流式工具调用 | M0 spike 实测 | ✅ 关闭：单轮/多轮 tool call 均正常 |
| R3 | POST SSE + fetch ReadableStream 端到端兼容 | M0 spike 用最小前端页验证 | ⏳ 部分：GET+Flux+curl 已验证；POST/浏览器 fetch 由 T21 验证；SseEmitter 自定义事件名格式由 T7/T17 验证 |
| R4 | `spring-ai-agent-utils`（incubating）与 2.0 兼容性 | M2 前验证 | ⏳ 挂起（M2） |
| R5 | 本机 Maven 缺失 | start.spring.io 骨架自带 `mvnw` | ✅ 关闭（另：本机 JAVA_HOME=java17，需 `JAVA_HOME=/d/JAVAs/java21` 前缀构建） |
