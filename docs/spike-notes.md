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
