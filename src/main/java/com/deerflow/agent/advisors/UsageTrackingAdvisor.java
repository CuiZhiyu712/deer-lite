package com.deerflow.agent.advisors;

import com.deerflow.agent.RunUsage;
import com.deerflow.event.AgentEvent;
import com.deerflow.event.EventSink;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/** 汇总每轮 usage → RunUsage + usage 事件。per-run 实例。 */
public class UsageTrackingAdvisor implements StreamAdvisor {

    /** 必须置于工具循环（ToolCallingAdvisor，order≈-2147483348）之外：只在此处能看到框架累计后的最终 usage（T13 审查字节码实测；循环内层每轮只看到原始值）。 */
    public static final int ORDER = org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 1;

    private final RunUsage usage;
    private final EventSink sink;

    public UsageTrackingAdvisor(RunUsage usage, EventSink sink) {
        this.usage = usage;
        this.sink = sink;
    }

    @Override
    public String getName() { return "UsageTrackingAdvisor"; }

    @Override
    public int getOrder() { return ORDER; }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request).doOnNext(resp -> {
            if (resp.chatResponse() == null || resp.chatResponse().getMetadata() == null
                    || resp.chatResponse().getMetadata().getUsage() == null) {
                return;
            }
            // 字段名已实测（2.0.1 经 javap 核验，见 docs/spike-notes.md）：getPromptTokens/getCompletionTokens 返回 Integer
            Number in = resp.chatResponse().getMetadata().getUsage().getPromptTokens();
            Number out = resp.chatResponse().getMetadata().getUsage().getCompletionTokens();
            Long inL = in == null ? null : in.longValue();
            Long outL = out == null ? null : out.longValue();
            if ((inL == null || inL == 0L) && (outL == null || outL == 0L)) {
                return; // EmptyUsage：抑制 0/0，避免每 chunk 一事件与尾 chunk 覆写总额（T15 审查）
            }
            usage.set(inL, outL);
            sink.send(new AgentEvent.Usage(inL, outL));
        });
    }
}
