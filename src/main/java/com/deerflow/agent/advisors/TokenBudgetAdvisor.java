package com.deerflow.agent.advisors;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上下文预算：估算超限时保留全部 system + 最近 N 条（插入省略提示）。
 * 二期由 LLM 摘要压缩替代（同插槽）。
 */
public class TokenBudgetAdvisor implements StreamAdvisor {

    public static final int ORDER = 100;

    private final int maxEstimatedTokens;
    private final int keepRecentMessages;

    public TokenBudgetAdvisor(int maxEstimatedTokens, int keepRecentMessages) {
        this.maxEstimatedTokens = maxEstimatedTokens;
        this.keepRecentMessages = keepRecentMessages;
    }

    @Override
    public String getName() { return "TokenBudgetAdvisor"; }

    @Override
    public int getOrder() { return ORDER; }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(prune(request));
    }

    ChatClientRequest prune(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        List<Message> pruned = pruneMessages(messages);
        if (pruned == messages) {
            return request;
        }
        return request.mutate()
                .prompt(new Prompt(pruned, request.prompt().getOptions()))
                .build();
    }

    /**
     * 纯函数，便于单测。public 而非包级：计划指定的测试位于 com.deerflow.agent 包，
     * 与实现包 com.deerflow.agent.advisors 不同（跨包需 public）。
     */
    public List<Message> pruneMessages(List<Message> messages) {
        if (estimateTokens(messages) <= maxEstimatedTokens) {
            return messages;
        }
        List<Message> systems = messages.stream().filter(m -> m instanceof SystemMessage).toList();
        List<Message> others = messages.stream().filter(m -> !(m instanceof SystemMessage)).toList();
        List<Message> kept = new ArrayList<>(systems);
        int from = Math.max(0, others.size() - keepRecentMessages);
        if (from > 0) {
            kept.add(new UserMessage("[提示] 更早的 " + from + " 条历史消息因上下文长度限制已被省略。"));
        }
        kept.addAll(others.subList(from, others.size()));
        return kept;
    }

    static int estimateTokens(List<Message> messages) {
        int chars = messages.stream().mapToInt(m -> textOf(m).length()).sum();
        return chars / 2; // 中英混合粗估：约 2 字符/token
    }

    static String textOf(Message m) {
        if (m instanceof ToolResponseMessage tr) {
            return tr.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .collect(Collectors.joining());
        }
        if (m instanceof AssistantMessage am && am.getText() == null) {
            return "";
        }
        return m.getText() == null ? "" : m.getText();
    }
}
