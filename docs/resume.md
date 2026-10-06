# deerflow-java — 简历素材

## 一句话定位

基于 Spring AI 2.0 自研的通用 Agent 运行时（对标 DeerFlow 设计）：支持流式对话、沙箱工具执行、
多轮工具调用与 SSE 事件协议，前后端与文档完整开源。

## 简历条目（按岗位微调）

- 基于 **Spring AI 2.0（ChatClient + Advisor 链）** 设计并实现 Agent 运行时：自研 3 个 Advisor
  （动态上下文注入 / 上下文预算裁剪 / token 用量审计）与 ToolCallback 装饰器（SSE 事件、工具错误自动
  恢复、轮次限额）
- 实现 8 个内置工具（受限 bash、工作区文件四件套、Tavily 搜索、Jina 抓取、任务清单），全部经
  FunctionToolCallback 按会话绑定并做路径穿越防护；自研自定义 SSE 事件协议（8 类事件）驱动前端
  工具调用时间线，支持**中止运行并强杀子进程**
- MySQL + JPA 持久化会话/消息/运行记录，完整保存 tool_calls 结构保证历史重放合法；Java 21
  **虚拟线程**承载 SSE 长连接；设计单 run 限额（工具轮次/时长/token）与启动自检

## 面试预案

**Q1：为什么用 Java/Spring AI 而不是 Python/LangGraph？**
A：工程落地视角——团队存量是 Spring 技术栈时，Java 侧接入 LLM 的边际成本最低；我做了对标分析：
DeerFlow 的 LangGraph middleware 与 Spring AI 2.0 的 Advisor 链概念一一对应，差异最大的是
checkpointer（图状态持久化），我用 messages/runs 表重建解决，并把设计取舍写进了架构文档。

**Q2：工具调用循环怎么实现的？**
A：2.0 中 ChatClient 经 ToolCallingAdvisor 承载循环，我不重写循环，而是用 ToolCallback 装饰器
解决三件事：向 SSE 推送 tool_start/tool_result；把工具异常转成错误文本回填模型实现自恢复；
全局轮次限额。装饰器同时记录完整调用轨迹用于持久化。

**Q3：历史会话怎么保证能被模型正确接受？**
A：OpenAI 兼容协议要求 tool 消息必须紧跟带 tool_calls 的 assistant 消息。我的持久化按
「ASSISTANT(toolCalls)+顺序 TOOL 行」写入，重放时用 MessageConverter 还原为 Spring AI 的
AssistantMessage/ToolResponseMessage，并有单元测试锁定该结构。

**Q4：安全性和隔离怎么做的？**
A：MVP 是演示级的本机受限执行：会话级工作目录 + 路径规范化防穿越 + 命令黑名单 + 超时强杀 +
输出截断；我在 README 明确声明它「不是安全边界」，并把 Docker 容器沙箱列入 Roadmap。
真正上生产我会先做容器隔离再开放多人使用。

**Q5：这个项目和直接写个聊天机器人有什么区别？**
A：核心在 Agent 运行时能力而非聊天：工具循环与限额、事件协议驱动的过程可视化、可中止、
会话状态的合法重建、成本审计——这些都是"Agent 平台"问题，不是提示词问题。

## 实战坑与修复（真实发生，可深聊）

1. **FunctionToolCallback 的静默 Consumer 陷阱**：`FunctionToolCallback.<I>builder(name, fn)` 只写一个类型参数时，语句表达式 lambda 会静默绑定到 `Consumer<I>` 重载，返回值被丢弃——**模型收到的所有工具结果变成字面量 "null"**且编译无警告；必须写 `.<I, O>builder`。我用集成测试断言工具输出内容时才抓到它。→ 面试点：泛型重载二义性与"测试要断言数据内容而非仅链路通畅"。
2. **LangGraph checkpointer 缺口的工程答案**：历史重放要求 TOOL 消息必须紧跟带 tool_calls 的 ASSISTANT 消息；我设计了「ASSISTANT(toolCalls)+顺序 TOOL 行」的持久化结构 + converter 自愈（缺配对的 toolCalls 降级纯文本、孤儿 TOOL 行丢弃），并用事务保证多行写入原子，避免进程中断把会话永久毒化成 400。
3. **JPA detached 实体静默覆盖**：run 收尾的 `touch+save` 把 detached 会话整实体 merge 回写，**覆盖运行期间工具写入的 todosJson**（SSE 看起来一切正常，只有持久化静默失效）；修复为收尾前重载再 save，并补了真实 repo 链路的回归测试（先红后绿）。
4. **Advisor 顺序决定语义**：Spring AI 2.0 的 ToolCallingAdvisor 默认 order=-2147483348；我的 UsageTrackingAdvisor 最初 order=200 落在工具循环**内层**，只能看到每轮原始 usage；移到 HIGHEST_PRECEDENCE+1 后才看到框架累计的 run 总额——token 限额因此才有正确语义。

## 附：可量化点速查

- 后端 2187 行 Java（36 个源文件，不含测试）+ 1409 行测试（72 个测试全绿，`./mvnw test` 一键运行）
- 8 类 SSE 事件；8 个工具；3 个 Advisor + 1 个 ToolCallback 装饰器；单 run 三项限额（40 轮 / 600s / 20 万 token）
