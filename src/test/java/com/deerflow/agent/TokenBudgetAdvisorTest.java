package com.deerflow.agent;

import com.deerflow.agent.advisors.TokenBudgetAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetAdvisorTest {

    @Test
    void prunesOldestWhenOverBudget() {
        var advisor = new TokenBudgetAdvisor(50, 2); // 预算约 100 字符
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("系统提示内容"));
        for (int i = 0; i < 10; i++) {
            msgs.add(new UserMessage("第" + i + "条很长很长很长很长很长的用户消息内容ABCDEFG"));
        }
        List<Message> pruned = advisor.pruneMessages(msgs);
        assertThat(pruned).hasSize(4); // system + 省略提示 + 最近 2 条
        assertThat(pruned.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((UserMessage) pruned.get(1)).getText()).contains("省略");
    }

    @Test
    void keepsAllWhenWithinBudget() {
        var advisor = new TokenBudgetAdvisor(1_000_000, 2);
        List<Message> msgs = List.of(new SystemMessage("s"), new UserMessage("u"));
        assertThat(advisor.pruneMessages(msgs)).isSameAs(msgs);
    }

    @Test
    void neverLeavesOrphanToolResponseAtWindowStart() {
        var advisor = new TokenBudgetAdvisor(1, 1); // 极小预算 + 最近 1 条
        var toolCall = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("c1", "function", "bash", "{}");
        var assistantWithCall = org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content("").toolCalls(java.util.List.of(toolCall)).build();
        var toolMsg = org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(java.util.List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("c1", "bash", "ok")))
                .build();
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("s"));
        for (int i = 0; i < 5; i++) {
            msgs.add(new UserMessage("填充填充填充填充填充填充填充填充填充填充" + i));
        }
        msgs.add(assistantWithCall);
        msgs.add(toolMsg);
        List<Message> pruned = advisor.pruneMessages(msgs);
        assertThat(pruned).noneMatch(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance);
        assertThat(pruned.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((UserMessage) pruned.get(1)).getText()).contains("省略");
    }

    @Test
    void countsToolCallArgumentsInEstimate() {
        var toolCall = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("c1", "function", "write_file", "x".repeat(5000));
        var assistantWithCall = org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content("").toolCalls(java.util.List.of(toolCall)).build();
        assertThat(com.deerflow.agent.advisors.TokenBudgetAdvisor.charsOf(assistantWithCall)).isGreaterThanOrEqualTo(5000);
    }
}
