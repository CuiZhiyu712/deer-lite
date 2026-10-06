package com.deerflow.agent.advisors;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** 每次模型调用前注入动态上下文（时间/工作区/平台）。per-run 实例，order 最先。 */
public class ContextAssemblyAdvisor implements StreamAdvisor {

    public static final int ORDER = 0;

    private final String extraContext;

    public ContextAssemblyAdvisor(String sessionId, String workspacePath) {
        String shell = System.getProperty("os.name").toLowerCase().contains("win") ? "cmd.exe" : "bash";
        this.extraContext = "\n\n[运行环境] 当前时间: " + OffsetDateTime.now()
                + "；会话工作区: " + workspacePath + "；命令解释器: " + shell
                + "；会话ID: " + sessionId;
    }

    @Override
    public String getName() { return "ContextAssemblyAdvisor"; }

    @Override
    public int getOrder() { return ORDER; }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
        boolean merged = false;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage sm) {
                messages.set(i, new SystemMessage(textOf(sm) + extraContext));
                merged = true;
                break;
            }
        }
        if (!merged) {
            messages.add(0, new SystemMessage(extraContext));
        }
        ChatClientRequest mutated = request.mutate()
                .prompt(new Prompt(messages, request.prompt().getOptions()))
                .build();
        return chain.nextStream(mutated);
    }

    private static String textOf(SystemMessage sm) {
        return sm.getText() == null ? "" : sm.getText();
    }
}
