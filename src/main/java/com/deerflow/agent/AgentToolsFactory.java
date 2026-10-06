package com.deerflow.agent;

import com.deerflow.event.EventSink;
import com.deerflow.runtime.RunContext;
import com.deerflow.tool.BashTool;
import com.deerflow.tool.FileTools;
import com.deerflow.tool.TodoTool;
import com.deerflow.tool.ToolInputs;
import com.deerflow.tool.WebFetchTool;
import com.deerflow.tool.WebSearchTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/** 把底层工具绑定 sessionId/RunContext，产出对模型暴露的 ToolCallback（未套装饰器，装饰由 AgentService 统一做）。 */
@Component
public class AgentToolsFactory {

    private final FileTools fileTools;
    private final BashTool bashTool;
    private final WebSearchTool webSearchTool;
    private final WebFetchTool webFetchTool;
    private final TodoTool todoTool;

    public AgentToolsFactory(FileTools fileTools, BashTool bashTool,
                             WebSearchTool webSearchTool, WebFetchTool webFetchTool,
                             TodoTool todoTool) {
        this.fileTools = fileTools;
        this.bashTool = bashTool;
        this.webSearchTool = webSearchTool;
        this.webFetchTool = webFetchTool;
        this.todoTool = todoTool;
    }

    public List<ToolCallback> forRun(String sessionId, RunContext ctx, EventSink sink) {
        return List.of(
                FunctionToolCallback.<ToolInputs.ReadFile, String>builder("read_file",
                                in -> fileTools.readFile(sessionId, in.path()))
                        .description("读取会话工作区中的文本文件，path 为相对工作区路径")
                        .inputType(ToolInputs.ReadFile.class).build(),

                FunctionToolCallback.<ToolInputs.WriteFile, String>builder("write_file",
                                in -> fileTools.writeFile(sessionId, in.path(), in.content()))
                        .description("在会话工作区写入/覆盖文本文件")
                        .inputType(ToolInputs.WriteFile.class).build(),

                FunctionToolCallback.<ToolInputs.StrReplace, String>builder("str_replace",
                                in -> fileTools.strReplace(sessionId, in.path(), in.oldText(), in.newText()))
                        .description("把工作区文件中第一处 oldText 替换为 newText")
                        .inputType(ToolInputs.StrReplace.class).build(),

                FunctionToolCallback.<ToolInputs.Ls, String>builder("ls",
                                in -> fileTools.ls(sessionId, in.path() == null ? "." : in.path()))
                        .description("列出工作区目录内容")
                        .inputType(ToolInputs.Ls.class).build(),

                FunctionToolCallback.<ToolInputs.Bash, String>builder("bash",
                                in -> bashTool.bash(sessionId, in.command(), ctx))
                        .description("在工作区中执行一条 shell 命令（30 秒超时）。Windows 为 cmd.exe，其他平台为 bash")
                        .inputType(ToolInputs.Bash.class).build(),

                FunctionToolCallback.<ToolInputs.WebSearch, String>builder("web_search",
                                in -> webSearchTool.webSearch(in.query(), in.maxResults() == null ? 5 : in.maxResults()))
                        .description("联网搜索，返回标题/链接/摘要列表")
                        .inputType(ToolInputs.WebSearch.class).build(),

                FunctionToolCallback.<ToolInputs.WebFetch, String>builder("web_fetch",
                                in -> webFetchTool.webFetch(in.url()))
                        .description("抓取网页并转为 Markdown 文本")
                        .inputType(ToolInputs.WebFetch.class).build(),

                FunctionToolCallback.<ToolInputs.WriteTodos, String>builder("write_todos",
                                in -> todoTool.writeTodos(sessionId,
                                        in.todos().stream().map(t -> new TodoTool.TodoItem(t.content(), t.status())).toList(),
                                        sink))
                        .description("创建/更新本次任务的任务清单（多步骤任务必须先建立清单并实时更新；最多一个 in_progress）")
                        .inputType(ToolInputs.WriteTodos.class).build()
        );
    }
}
