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
}
