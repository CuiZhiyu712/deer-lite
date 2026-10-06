package com.deerflow.tool;

import com.deerflow.sandbox.ShellRunner;
import com.deerflow.sandbox.WorkspaceManager;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class BashTool {

    private final ShellRunner shell;
    private final WorkspaceManager workspace;

    public BashTool(ShellRunner shell, WorkspaceManager workspace) {
        this.shell = shell;
        this.workspace = workspace;
    }

    public String bash(String sessionId, String command) {
        Path cwd = workspace.sessionDir(sessionId);
        ShellRunner.Result r = shell.run(cwd, command);
        return "exit=" + r.exitCode() + (r.timedOut() ? " (超时)" : "") + "\n" + r.output();
    }
}
