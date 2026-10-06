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
        // 窗口不得以孤立的 ToolResponseMessage 开头（其对应的 assistant(tool_calls) 被裁掉会让 API 400），
        // 因此把边界向前推过连续的 tool 响应（T13 审查实测 DeepSeek 约束）
        while (from < others.size() && others.get(from) instanceof ToolResponseMessage) {
            from++;
        }
        if (from > 0) {
            kept.add(new UserMessage("[提示] 更早的 " + from + " 条历史消息因上下文长度限制已被省略。"));
        }
        kept.addAll(others.subList(from, others.size()));
        return kept;
    }

    static int estimateTokens(List<Message> messages) {
        int chars = messages.stream().mapToInt(TokenBudgetAdvisor::charsOf).sum();
        return chars / 2; // 中英混合粗估：约 2 字符/token
    }

    /** 计入 assistant 的 toolCalls 参数长度（write_file 等大参数的主要来源，T13 审查指出原实现漏计）。 */
    public static int charsOf(Message m) {
        if (m instanceof ToolResponseMessage tr) {
            return tr.getResponses().stream()
                    .mapToInt(r -> r.responseData() == null ? 0 : r.responseData().length())
                    .sum();
        }
        if (m instanceof AssistantMessage am) {
            int text = am.getText() == null ? 0 : am.getText().length();
            int args = am.getToolCalls() == null ? 0 : am.getToolCalls().stream()
                    .mapToInt(tc -> tc.arguments() == null ? 0 : tc.arguments().length())
                    .sum();
            return text + args;
        }
        return m.getText() == null ? 0 : m.getText().length();
    }
}
